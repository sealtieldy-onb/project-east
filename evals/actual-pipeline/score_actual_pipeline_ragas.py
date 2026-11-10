"""Score synthetic outputs with RAGAS using contexts returned by East's real SQL path."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import statistics
import sys
import time
from pathlib import Path
from typing import Any

import ragas
from openai import AsyncOpenAI
from ragas.embeddings import embedding_factory
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerRelevancy, ContextPrecision, ContextRecall, Faithfulness


def read_pipeline(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    metadata = next(row for row in records if row.get("record_type") == "run_metadata")
    if metadata.get("synthetic_only") is not True:
        raise ValueError("Refusing to score a trace not marked synthetic-only.")
    cases = [row for row in records if row.get("record_type") == "pipeline_case"]
    if len(cases) != metadata.get("case_count"):
        raise ValueError("Trace case count does not match run metadata.")
    for case in cases:
        contexts = case.get("retrieved_contexts", [])
        if not contexts:
            raise ValueError(f"No retrieved context recorded for {case.get('case_id')}.")
        if case.get("generation_status") == "COMPLETED" and not case.get("answer_text"):
            raise ValueError(f"Missing successful answer for {case.get('case_id')}.")
    return metadata, cases


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    os.chmod(path, 0o600)


async def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: score_actual_pipeline_ragas.py INPUT_TRACE.jsonl OUTPUT_METRICS.jsonl")
    input_path = Path(sys.argv[1]).resolve()
    output_path = Path(sys.argv[2]).resolve()
    cache_path = output_path.with_name(output_path.stem + "-cache.jsonl")
    metadata, cases = read_pipeline(input_path)

    model = os.environ.get("EAST_EVAL_MODEL", "gpt-6-luna")
    if model != metadata.get("model_id"):
        raise ValueError("Evaluator model must match the generation model recorded in the synthetic trace.")
    api_key = os.environ.get("EAST_EVAL_API_KEY")
    if not api_key:
        raise RuntimeError("EAST_EVAL_API_KEY must be set in the process environment; it is never read or printed here.")
    base_url = os.environ.get("EAST_EVAL_BASE_URL") or "https://api.openai.com/v1"
    async_client = AsyncOpenAI(api_key=api_key, base_url=base_url)
    evaluator_llm = llm_factory(model, provider="openai", client=async_client)
    evaluator_embeddings = embedding_factory(
        "openai", model="text-embedding-3-small", client=async_client, interface="modern"
    )
    metrics = {
        "faithfulness": Faithfulness(llm=evaluator_llm),
        "answer_relevancy": AnswerRelevancy(
            llm=evaluator_llm, embeddings=evaluator_embeddings, strictness=1
        ),
        "context_precision": ContextPrecision(llm=evaluator_llm),
        "context_recall": ContextRecall(llm=evaluator_llm),
    }

    input_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()
    fingerprint = hashlib.sha256(json.dumps({
        "trace_sha256": input_hash,
        "ragas": ragas.__version__,
        "model": model,
        "embedding": "text-embedding-3-small",
        "answer_relevancy_strictness": 1,
        "context_source": "actual_report_repository_retrieved_contexts",
    }, sort_keys=True).encode()).hexdigest()
    cached: dict[str, dict[str, Any]] = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            try:
                item = json.loads(line)
                if item.get("fingerprint") == fingerprint:
                    cached[item["cache_key"]] = item
            except (json.JSONDecodeError, KeyError):
                continue

    retry_errors = os.environ.get("EAST_EVAL_RAGAS_RETRY_ERRORS") == "1"
    semaphore = asyncio.Semaphore(3)
    cache_lock = asyncio.Lock()

    async def score(metric_name: str, case: dict[str, Any], kwargs: dict[str, Any]) -> dict[str, Any]:
        case_id = case["case_id"]
        cache_key = f"{fingerprint}|{case_id}|{metric_name}"
        previous = cached.get(cache_key)
        if previous and (previous.get("status") == "scored" or not retry_errors):
            return {k: previous[k] for k in ("case_id", "metric", "score", "latency_ms", "status", "error_type") if k in previous}
        async with semaphore:
            started = time.perf_counter()
            try:
                result = await metrics[metric_name].ascore(**kwargs)
                row = {
                    "case_id": case_id,
                    "metric": metric_name,
                    "score": float(result.value),
                    "latency_ms": round((time.perf_counter() - started) * 1000),
                    "status": "scored",
                }
            except Exception as exc:  # Keep trace artifacts free of prompt, output, and provider error bodies.
                row = {
                    "case_id": case_id,
                    "metric": metric_name,
                    "score": None,
                    "latency_ms": round((time.perf_counter() - started) * 1000),
                    "status": "error",
                    "error_type": type(exc).__name__,
                }
            saved = {"fingerprint": fingerprint, "cache_key": cache_key, **row}
            async with cache_lock:
                with cache_path.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps(saved, ensure_ascii=False) + "\n")
                    stream.flush()
                os.chmod(cache_path, 0o600)
                cached[cache_key] = saved
            return row

    tasks = []
    generation_error_rows = []
    for case in cases:
        contexts = [item["context_text"] for item in case["retrieved_contexts"]]
        shared_kwargs = {
            "user_input": case["question"],
            "retrieved_contexts": contexts,
            "reference": case["reference_answer"],
        }
        for metric_name in ("context_precision", "context_recall"):
            tasks.append(score(metric_name, case, shared_kwargs))
        if case.get("generation_status") == "COMPLETED":
            answer_kwargs = {
                "user_input": case["question"],
                "response": case["answer_text"],
            }
            tasks.append(score("faithfulness", case, {**answer_kwargs, "retrieved_contexts": contexts}))
            tasks.append(score("answer_relevancy", case, answer_kwargs))
        else:
            for metric_name in ("faithfulness", "answer_relevancy"):
                generation_error_rows.append({
                    "case_id": case["case_id"],
                    "metric": metric_name,
                    "score": None,
                    "status": "generation_error",
                    "error_type": case.get("failure", "unknown"),
                })

    scored_rows = await asyncio.gather(*tasks)
    all_rows = scored_rows + generation_error_rows
    order = {case["case_id"]: i for i, case in enumerate(cases)}
    all_rows.sort(key=lambda row: (order[row["case_id"]], row["metric"]))
    output_rows = [{
        "record_type": "metadata",
        "synthetic_only": True,
        "source_trace_sha256": input_hash,
        "case_count": len(cases),
        "completed_answers": sum(case.get("generation_status") == "COMPLETED" for case in cases),
        "ragas_version": ragas.__version__,
        "evaluator_model": model,
        "embedding_model": "text-embedding-3-small",
        "answer_relevancy_strictness": 1,
        "context_source": "actual_report_repository_retrieved_contexts",
        "fingerprint": fingerprint,
    }] + [{"record_type": "metric", **row} for row in all_rows]
    write_jsonl(output_path, output_rows)

    summary: dict[str, Any] = {
        "synthetic_only": True,
        "source_trace": str(input_path),
        "metrics_file": str(output_path),
        "ragas_version": ragas.__version__,
        "evaluator_model": model,
        "embedding_model": "text-embedding-3-small",
        "answer_relevancy_strictness": 1,
        "generation_cases": len(cases),
        "completed_answers": sum(case.get("generation_status") == "COMPLETED" for case in cases),
        "metric_rows": len(all_rows),
        "scored_metric_rows": sum(row["status"] == "scored" for row in all_rows),
        "metric_errors": sum(row["status"] == "error" for row in all_rows),
        "generation_error_rows": sum(row["status"] == "generation_error" for row in all_rows),
        "by_metric": {},
        "limitations": [
            "RAGAS answer metrics use the fixture question, while East generates a date-scoped report rather than answering that question directly.",
            "The same GPT-6 Luna model generated answers and judged them; independent human review remains outstanding.",
            "RAGAS evaluates generic semantic relevance and grounding, not East's full business classification rubric.",
        ],
    }
    for name in metrics:
        rows = [row for row in all_rows if row["metric"] == name]
        values = [row["score"] for row in rows if row["status"] == "scored" and row["score"] is not None]
        summary["by_metric"][name] = {
            "n": len(values),
            "mean": statistics.mean(values) if values else None,
            "errors": sum(row["status"] == "error" for row in rows),
            "generation_errors": sum(row["status"] == "generation_error" for row in rows),
        }
    summary_path = output_path.with_name(output_path.stem + "-summary.json")
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(summary_path, 0o600)
    print(json.dumps({
        "metric_rows": summary["metric_rows"],
        "scored_metric_rows": summary["scored_metric_rows"],
        "metric_errors": summary["metric_errors"],
        "generation_error_rows": summary["generation_error_rows"],
        "by_metric": summary["by_metric"],
        "summary_path": str(summary_path),
    }))
    await async_client.close()


if __name__ == "__main__":
    asyncio.run(main())
