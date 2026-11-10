# Project East evaluations

`capstone-ai-evaluation.ipynb` contains the executable evaluation and result tables. `sample-emails.json` is the shared synthetic fixture: 60 cases and 71 emails, plus two historical urgency probes. The original 20 cases are a regression split; the new 40 cases were frozen before retesting the four unchanged prompt texts. Independent user/instructor review of the assistant-authored labels remains pending.

Expanded results are in `results/`. Prior 20-case outputs remain in `docs/agent-work/`. The expanded notebook includes strict case passes and both split results, so field errors and failures on new inputs remain visible.

## Run

Use an isolated Python environment with `requirements.txt`, register it as a Jupyter kernel, and open the notebook from the project root or `evals/`.

Provider requests require these variables in the **kernel process environment**:

```sh
EAST_EVAL_MODEL=gpt-6-luna
EAST_EVAL_BASE_URL=https://api.openai.com/v1
EAST_EVAL_PROMPT_RUN=1
EAST_EVAL_RAGAS_RUN=1
```

Supply `EAST_EVAL_API_KEY` securely in that environment; never place it in notebook cells. Neither the notebook nor the runner loads `.env`. With the run flags unset, the notebook loads saved generations and matching metric checkpoints for offline scoring. Set `EAST_EVAL_RAGAS_RETRY_ERRORS=1` to retry failed standard-metric cache entries. To start a fresh generation repeat, set `EAST_EVAL_RESULTS_PATH` to a result JSON file in a new run subdirectory; matching saved runs resume without repeating completed cases.

The generation helper also supports direct execution with the same provider variables:

```sh
node scripts/capstone-ai-evaluation.mjs --run --concurrency=3 --out=evals/results/expanded-60-prompt-comparison.json
```

Offline fixture and harness checks:

```sh
node --check scripts/capstone-ai-evaluation.mjs
node scripts/capstone-ai-evaluation.mjs --self-test
node scripts/capstone-ai-evaluation.mjs --mock-provider-smoke
```

All messages are fabricated and use `example.test`. Generated artifacts use owner-only permissions. This evaluates supplied contexts, not live Gmail/database retrieval. Standard metrics include full message metadata as evidence; earlier subject/body-only RAGAS scores are not directly comparable. The evaluated candidate is not promoted to the application by running this notebook.

## Actual application pipeline extension

The notebook above remains the four-prompt comparison on fixture-selected contexts. The separate `actual-pipeline/ReportPipelineEvaluation.java` runner starts the real Spring report service, PostgreSQL repository, local vault, and AI provider client against an isolated disposable database, with a synthetic Gmail descriptor service and the same 60-case fixture. It saves safe retrieval/provider spans under `results/actual-pipeline/`. It does not call live Gmail or use real mailbox data.

`actual-pipeline/score_actual_pipeline_ragas.py` scores the contexts recorded from the real SQL retrieval path with the same four RAGAS metrics. Supply `EAST_EVAL_API_KEY`, `EAST_EVAL_MODEL`, and optional `EAST_EVAL_BASE_URL` in the process environment; this script does not read `.env`, print the key, or save it. `actual-pipeline/summarize_actual_pipeline.py` rebuilds the structural, retrieval, latency, token, cost, saved-report reuse, and RAGAS summary from the trace artifacts. See `docs/agent-work/capstone-ai-evaluation.md` for the tested disposable-database invocation and actual results.

The saved-report benchmark covers exact-scope reads, identical explicit generation, and a one-day-wider scope. It is a service-method benchmark with synthetic data, not HTTP latency or a production-mailbox claim. It does not introduce a second cache.
