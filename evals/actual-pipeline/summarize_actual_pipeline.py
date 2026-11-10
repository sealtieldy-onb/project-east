"""Summarize structural, retrieval, latency, token, cost, and RAGAS evidence."""

from __future__ import annotations

import json
import math
import os
import statistics
import sys
from pathlib import Path
from typing import Any


INPUT_RATE = 0.10
CACHED_INPUT_RATE = 0.01
OUTPUT_RATE = 0.50


def ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def date_only(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)[:10]


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("Usage: summarize_actual_pipeline.py PIPELINE.jsonl RAGAS_SUMMARY.json OUTPUT.json")
    trace_path, ragas_path, output_path = map(lambda item: Path(item).resolve(), sys.argv[1:])
    trace_rows = load_jsonl(trace_path)
    metadata = next(row for row in trace_rows if row.get("record_type") == "run_metadata")
    if metadata.get("synthetic_only") is not True:
        raise ValueError("Refusing to summarize a trace not marked synthetic-only.")
    fixture_path = trace_path.parents[2] / "sample-emails.json"
    fixture = {row["id"]: row for row in json.loads(fixture_path.read_text(encoding="utf-8"))["cases"]}
    cases = [row for row in trace_rows if row.get("record_type") == "pipeline_case"]
    summary_record = next(row for row in trace_rows if row.get("record_type") == "run_summary")
    scope = next(row for row in trace_rows if row.get("record_type") == "retrieval_scope_probe")
    benchmark = next(row for row in trace_rows if row.get("record_type") == "saved_report_reuse_benchmark")
    ragas = json.loads(ragas_path.read_text(encoding="utf-8"))

    totals = {
        "tp": 0, "fp": 0, "fn": 0,
        "citation_tp": 0, "citation_predicted": 0, "citation_expected": 0,
        "valid_citations": 0, "citation_count": 0,
        "transaction_exact": 0, "transaction_fields": 0,
        "urgency_exact": 0, "urgency_fields": 0,
        "deadline_exact": 0, "deadline_fields": 0,
        "strict_passes": 0,
    }
    failures = []
    for row in cases:
        expected = fixture[row["case_id"]]["expected"]
        if row.get("generation_status") != "COMPLETED":
            totals["fn"] += len(expected)
            totals["citation_expected"] += len({source for item in expected for source in item.get("sourceIds", [])})
            provider = row.get("provider_trace", {})
            failures.append({
                "case_id": row["case_id"],
                "failure_type": row.get("failure"),
                "provider_reason": provider.get("failure_reason"),
            })
            continue

        actual = row["answer"]["insights"]
        used: set[int] = set()
        matches = []
        for wanted in expected:
            match = next((
                index for index, prediction in enumerate(actual)
                if index not in used
                and prediction["kind"] == wanted["kind"]
                and set(prediction.get("sourceIds", [])) & set(wanted.get("sourceIds", []))
            ), None)
            if match is not None:
                used.add(match)
                matches.append((wanted, actual[match]))

        tp = len(matches)
        fp = len(actual) - tp
        fn = len(expected) - tp
        expected_ids = {source for item in expected for source in item.get("sourceIds", [])}
        predicted_ids = {source for item in actual for source in item.get("sourceIds", [])}
        citation_tp = len(expected_ids & predicted_ids)
        transaction_pairs = [(wanted, prediction) for wanted, prediction in matches if wanted["kind"] == "TRANSACTION"]
        transaction_exact = sum(
            wanted.get(field) == prediction.get(field)
            for wanted, prediction in transaction_pairs
            for field in ("transactionType", "amount", "currency", "transactionDate")
        )
        action_pairs = [
            (wanted, prediction) for wanted, prediction in matches
            if wanted["kind"] in {"URGENT_ACTION", "AWAITING_REPLY"}
        ]
        urgency_exact = sum(wanted.get("urgency") == prediction.get("urgency") for wanted, prediction in action_pairs)
        deadline_exact = sum(
            date_only(wanted.get("deadline")) == date_only(prediction.get("deadline"))
            for wanted, prediction in action_pairs
        )
        known_ids = {f"m{index}" for index in range(1, len(fixture[row["case_id"]]["messages"]) + 1)}
        citation_count = sum(len(item.get("sourceIds", [])) for item in actual)
        valid_citations = sum(
            source in known_ids for item in actual for source in item.get("sourceIds", [])
        )
        strict = (
            fp == 0 and fn == 0
            and citation_tp == len(predicted_ids) == len(expected_ids)
            and transaction_exact == len(transaction_pairs) * 4
            and urgency_exact == len(action_pairs)
            and deadline_exact == len(action_pairs)
        )
        values = {
            "tp": tp, "fp": fp, "fn": fn,
            "citation_tp": citation_tp,
            "citation_predicted": len(predicted_ids),
            "citation_expected": len(expected_ids),
            "valid_citations": valid_citations,
            "citation_count": citation_count,
            "transaction_exact": transaction_exact,
            "transaction_fields": len(transaction_pairs) * 4,
            "urgency_exact": urgency_exact,
            "urgency_fields": len(action_pairs),
            "deadline_exact": deadline_exact,
            "deadline_fields": len(action_pairs),
            "strict_passes": int(strict),
        }
        for key, value in values.items():
            totals[key] += value

    inputs = []
    cached = []
    outputs = []
    generation_latencies = []
    for row in cases:
        transport = row.get("provider_trace", {}).get("transport", {})
        if isinstance(transport.get("input_tokens"), int):
            inputs.append(transport["input_tokens"])
        if isinstance(transport.get("cached_input_tokens"), int):
            cached.append(transport["cached_input_tokens"])
        if isinstance(transport.get("output_tokens"), int):
            outputs.append(transport["output_tokens"])
        if row.get("generation_status") == "COMPLETED":
            generation_latencies.append(row["backend_total_ms"])
    input_tokens, cached_tokens, output_tokens = sum(inputs), sum(cached), sum(outputs)
    estimated_cost = (
        (input_tokens - cached_tokens) * INPUT_RATE
        + cached_tokens * CACHED_INPUT_RATE
        + output_tokens * OUTPUT_RATE
    ) / 1_000_000

    measured = {
        "attempted_cases": len(cases),
        "completed_cases": summary_record["completed"],
        "failed_cases": summary_record["failed"],
        "strict_case_passes": totals["strict_passes"],
        "strict_case_pass_rate": ratio(totals["strict_passes"], len(cases)),
        "insight_precision": ratio(totals["tp"], totals["tp"] + totals["fp"]),
        "insight_recall": ratio(totals["tp"], totals["tp"] + totals["fn"]),
        "citation_precision": ratio(totals["citation_tp"], totals["citation_predicted"]),
        "citation_recall": ratio(totals["citation_tp"], totals["citation_expected"]),
        "valid_source_id_rate": ratio(totals["valid_citations"], totals["citation_count"]),
        "transaction_field_exact": ratio(totals["transaction_exact"], totals["transaction_fields"]),
        "urgency_exact": ratio(totals["urgency_exact"], totals["urgency_fields"]),
        "deadline_exact": ratio(totals["deadline_exact"], totals["deadline_fields"]),
        "latency_ms_completed": {
            "n": len(generation_latencies),
            "p50": statistics.median(generation_latencies) if generation_latencies else None,
            "p95": sorted(generation_latencies)[math.ceil(0.95 * len(generation_latencies)) - 1] if generation_latencies else None,
            "mean": statistics.mean(generation_latencies) if generation_latencies else None,
            "scope": "ReportService.generate in-process timing; excludes HTTP overhead",
        },
        "generation_usage": {
            "provider_calls": len(inputs),
            "input_tokens": input_tokens,
            "cached_input_tokens": cached_tokens,
            "output_tokens": output_tokens,
            "estimated_standard_cost_usd": estimated_cost,
            "rates_usd_per_million": {
                "input": INPUT_RATE,
                "cached_input": CACHED_INPUT_RATE,
                "output": OUTPUT_RATE,
            },
            "rate_source": "https://developers.openai.com/api/docs/pricing",
            "rate_date": "2026-11-10",
            "assumptions": "GPT-6 Luna standard short-context rates; provider-reported cached-token counts; excludes taxes/account adjustments; RAGAS judge and embedding costs excluded",
        },
        "retrieval_scope_probe": {
            key: scope[key] for key in (
                "candidate_count", "returned_count", "wrong_owner_returned_count",
                "wrong_mailbox_returned_count", "expired_excluded",
                "outside_window_excluded", "other_mailbox_excluded",
            )
        },
        "saved_report_reuse": {
            key: benchmark[key] for key in (
                "benchmark_status", "initial_generation_ms", "initial_generation_provider_calls",
                "initial_generation_usage", "saved_report_reads", "saved_report_reads_all_hit",
                "saved_report_read_p50_ms", "saved_report_read_p95_ms", "saved_report_read_provider_calls",
                "saved_report_read_usage", "identical_generation_ms", "identical_generation_provider_calls",
                "identical_generation_usage", "similar_saved_read_ms", "similar_saved_read_hit",
                "similar_saved_read_provider_calls", "similar_generation_ms",
                "similar_generation_provider_calls", "similar_generation_usage",
            )
        },
        "ragas": ragas.get("by_metric"),
    }
    result = {
        "synthetic_only": True,
        "source_trace": str(trace_path),
        "ragas_summary": str(ragas_path),
        "model": metadata.get("model_id"),
        "metric_method": "actual SQL-retrieved contexts; RAGAS 0.4.3; GPT-6 Luna judge; text-embedding-3-small; answer relevancy strictness=1",
        "measured": measured,
        "safe_failure_records": failures,
        "limitations": [
            "The provider pipeline is the real ReportService, PostgreSQL repository, vault encryption, and AIProviderClient; Gmail descriptor listing is a synthetic fixture service.",
            "The saved-report reuse benchmark uses one synthetic message and service-method timing in-process, not HTTP latency.",
            "RAGAS answer metrics use the fixture question; East generates a date-scoped report rather than answering that question directly.",
            "The same model generated and judged answers; labels and RAGAS disagreements need independent human review.",
        ],
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(output_path, 0o600)
    print(json.dumps({"output": str(output_path), "measured": measured}))


if __name__ == "__main__":
    main()
