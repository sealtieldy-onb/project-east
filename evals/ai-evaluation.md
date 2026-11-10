# AI evaluation

## Expanded retest result

The expanded evaluation completed on 2026-11-10 using GPT-6 Luna (`gpt-6-luna`): **60 cases, 71 synthetic emails, four unchanged prompts, and 240 fresh generation attempts**. Of these, 239 passed output validation; the evidence-first invoice case failed because it placed urgency/deadline fields on a transaction. The invalid result remains a failed case.

The [notebook](../evals/capstone-ai-evaluation.ipynb) contains the prompt comparison. The [shared fixture](../evals/sample-emails.json) contains inputs, structured labels, natural-language references, split labels, and new-case rationales. All messages are fabricated with `example.test` addresses. The original prompt comparison used no Gmail, app database, or real email content. The separate actual-pipeline extension below uses an isolated disposable PostgreSQL database seeded only with those synthetic fixtures.

The original 20 cases form a regression split. Forty new cases were frozen before the retest, covering urgency/date boundaries, explicit priority conflicts and negation, multilingual requests, missing transaction fields, multiple items, thread cancellation, retrieval distractors, and injection. All four prompt texts match the prior evaluation exactly; no prompt was tuned on these new outputs. Labels are assistant-authored/reviewed; independent user/instructor review remains pending.

## Prompt comparison

| Prompt | Valid | F1, all 60 | F1, new 40 | Strict cases passed | Expected-action urgency coverage | Mean latency |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `current-report-v1` | 60/60 | 0.729 | 0.708 | 39/60 | 40.0% | 10.024 s |
| `evidence-first` | 59/60 | 0.817 | 0.875 | 41/60 | 43.3% | 3.263 s |
| `conservative-triage` | 60/60 | 0.968 | 0.952 | 46/60 | 50.0% | 2.997 s |
| `deadline-aware-triage` | 60/60 | 1.000 | 1.000 | 60/60 | 100.0% | 2.682 s |

F1 in this table is failure-aware: invalid generations count expected items as missed. Insight matching checks type and overlapping supporting source IDs. Strict case passes additionally require all expected citations and correct transaction, urgency, and deadline fields. Expected-action urgency coverage includes missed actions in its denominator; the notebook also reports matched-item field scores separately. Mean latency covers valid outputs and is observational: prompts ran in sequential batches rather than randomized order.

The deadline-aware candidate passed **60/60 strict cases**, including **40/40 new cases**. It matched all 48 expected insights (24 actions, six outgoing inquiries, three received replies, and 15 transactions), with 100% citation precision/recall, source validity, transaction-field exactness, urgency exactness, and deadline exactness. These are small synthetic benchmark results; the candidate remains evaluation-only and has not been promoted to the app.

The fixed urgency policy uses explicit priority first, respecting negation. Otherwise, a stated deadline 0–7 calendar days after the work-request message is HIGH, 8–30 MEDIUM, and over 30 LOW. No reliable cue means null. Explicit priority overrides deadline proximity. Informational dates and transaction due dates do not automatically become action deadlines.

## Standard RAGAS results

RAGAS 0.4.3 evaluated 60 current-prompt and 60 candidate answers, with **zero metric errors**. Context precision/recall are evaluated once per case and shared. This produced 360 unique metric records: 60 each for context precision/recall and 120 each for faithfulness/answer relevancy. The judge was GPT-6 Luna; answer relevancy used `text-embedding-3-small` with strictness 1 (one generated question per answer).

| Prompt | Faithfulness | Answer relevancy | Context precision | Context recall |
| --- | ---: | ---: | ---: | ---: |
| `current-report-v1` | 0.856 | 0.344 | 0.967 | 0.854 |
| `deadline-aware-triage` | 0.742 | 0.369 | 0.967 | 0.854 |

Faithfulness was 0.856 → 0.742; answer relevancy was 0.344 → 0.369. The notebook includes per-split semantic results and the lowest-scoring examples. The candidate shows a small answer-relevance gain and a faithfulness regression on this run. Semantic scores and strict structural case passes measure different properties; the perfect structural result is not sufficient evidence to promote the candidate.

The standard metrics now receive the full synthetic message metadata/direction/date and body, matching the evidence available to generation. Earlier 20-case RAGAS scores used subject/body only and are not directly comparable. These are fixture-selected contexts, not a production Gmail/PostgreSQL retrieval benchmark. The judge is also the generation model; independent review remains a limitation. Generic RAGAS metrics judge the structured answer against message evidence; they do not fully encode East's classification and derived-urgency rules.

Assistant qualitative spot checks of four cases/eight outputs are in [expanded-60-judge-spot-checks.json](../evals/results/expanded-60-judge-spot-checks.json). For the attachment-review case, the baseline has high faithfulness while omitting the requested insight. In the ambiguous-dollar case, both answers preserve the amount and unknown currency, but the candidate receives lower generic faithfulness. These checks expose limits of the numeric judge and still need independent adjudication.

## Failure findings

- The current prompt frequently called incoming owner requests `AWAITING_REPLY` or duplicated one request as both an action and an awaiting reply. It passed 39/60 strict cases.
- Evidence-first repeated the original invoice validation failure. Its all-case F1 drops from the conditional valid-output score when that missing item is counted.
- Conservative triage omitted an attachment-review request and the second, low-priority item in two multi-message cases. It also missed urgency/deadline labels.
- All four prompts returned no insight for the three pure injection fixtures in this retest. The earlier current-prompt warning false positive did not recur; both runs are preserved. The candidate retained the legitimate checklist task when injection text was mixed into that request.
- The candidate has no structural failures in this set. Review the standard-metric cases before any app prompt promotion; deterministic guardrails and real retrieval verification remain separate work.

All 54 structural failure records across the other prompts are in [expanded-60-failures.json](../evals/results/expanded-60-failures.json). Invalid generations retain a safe validation reason rather than a raw provider response.

## Reproduce and inspect

See [evals/README.md](../evals/README.md) and the notebook's reproduction cell. Evaluation dependencies are isolated in [evals/requirements.txt](../evals/requirements.txt). The helper [runner](../scripts/capstone-ai-evaluation.mjs) checkpoints each result and refuses to reuse mismatched inputs/prompts/model settings. Matching metric checkpoints load offline without a provider key; explicit flags enable missing external work.

The original prompt-comparison run made no application source/dependency changes, app restart, Git commit, remote, or push. Its generation and RAGAS costs remain unestimated; the separate actual-pipeline generation estimate below excludes RAGAS evaluator/embedding costs. The earlier metric run used checkpoints after an interrupted attempt.

## Actual pipeline and saved-report reuse — 2026-11-10

The evaluation-only runner started East's real Spring application context on an ephemeral port with Liquibase and an isolated disposable PostgreSQL database. It used the real `ReportService`, `ReportRepository` SQL, vault encryption/decryption, and `AIProviderClient`; a synthetic Gmail descriptor service supplied fixture IDs. No live Gmail, mailbox data, or user account was accessed. Retrieval traces record candidate/returned counts and synthetic IDs; provider traces record status, timing, token counts, and synthetic model output without headers or API keys. Result files are owner-only (`0600`).

### Actual retrieval and generation results

The 60-case fixture contains 71 fabricated messages. **59 reports completed; one returned `INVALID_MODEL_OUTPUT`**. The completed calls had median backend service latency 3.292 s and nearest-rank p95 5.460 s. This timing includes the provider call but excludes HTTP/controller overhead.

The real SQL scope probe returned 1 of 4 candidates. It excluded the wrong owner, wrong mailbox, expired content, and out-of-window message. The query also requires an owned connected mailbox, the exact mailbox, the half-open report date window, JSON body type, ciphertext and nonce, and body expiry later than the query time.

The current app prompt achieved 36/60 strict synthetic case passes. Insight precision/recall were 0.717/0.688; citation precision/recall were 0.957/0.880; all emitted source IDs were valid; transaction fields were exact in matched transactions; urgency exactness was 0.688; matched deadlines were date-exact. These task metrics expose gaps the generic RAGAS scores do not.

RAGAS 0.4.3 used the **contexts returned by East's SQL retrieval**, GPT-6 Luna as judge, `text-embedding-3-small` for answer relevancy, and strictness 1. There were 238 scored metrics, 0 metric errors, and 2 unscored answer metrics for the invalid generation:

| Metric | Mean | Scored cases |
| --- | ---: | ---: |
| Faithfulness | 0.857 | 59 |
| Answer relevancy | 0.303 | 59 |
| Context precision | 0.992 | 60 |
| Context recall | 0.826 | 60 |

Answer relevancy uses each fixture's natural-language question even though East creates a date-scoped report instead of answering that question directly. The same model generated and judged the outputs, and generic RAGAS does not encode East's classification rubric. The user reviewed the eight blind labels and seven representative current-prompt judge decisions and found them sound; this sample does not adjudicate the candidate-versus-current faithfulness regression.

### Existing saved-report reuse benchmark

The benchmark used one synthetic message and direct service-method calls in the same process. It is not an HTTP latency benchmark.

| Operation | Latency | Provider calls | Estimated GPT-6 Luna standard cost |
| --- | ---: | ---: | ---: |
| Initial `generate()` | 3.953 s | 1 | $0.0001938 |
| 30 exact-scope `getDashboard()` reads | p50 9 ms, p95 10 ms | 0 | $0 |
| Identical `generate()` | 5.269 s | 1 | $0.0002878 |
| One-day-wider `getDashboard()` | 7 ms; no saved report hit | 0 | $0 |
| One-day-wider `generate()` | 5.082 s | 1 | $0.0002113 |

The exact saved dashboard read reuses the same encrypted report and made no provider call. A similar wider date range misses that report; the dashboard read itself still makes no model call, and explicit generation creates a fresh report/provider call. `generate()` did not deduplicate identical requests. No second response cache was added. The 60 fixture-generation calls used 29,571 input and 11,688 output tokens, with 0 provider-reported cached input tokens; the estimated total was **$0.0088011** at OpenAI's standard short-context GPT-6 Luna rates ($0.10/M uncached input, $0.01/M cached input, $0.50/M output), excluding tax/account adjustments and the separate RAGAS judge/embedding calls ([pricing](https://developers.openai.com/api/docs/pricing)).

The saved-report lookup is owner-scoped and matches an exact mailbox set plus exact date window (or an applicable scheduled date), and requires a completed, encrypted, unexpired report. Report/insight expiry follows the earliest source-body expiry. Changing body-cache TTL recalculates source-derived expiries, and mailbox disconnect cleanup deletes linked reports and insights; PostgreSQL acceptance coverage for retention/disconnect cleanup passed separately. Provider/model configuration changes do not currently invalidate a saved report; its original provider/model metadata remains attached until normal expiry or deletion. This is the observed invalidation policy. Similar scopes miss, and explicit generation is fresh.

### Failure debugging and application decision

Before the parser fix, a real provider response for synthetic case `01-contract-deadline` returned a date-only deadline (`2026-10-12`) and the backend rejected it because the schema allowed a string while the parser required an ISO instant. The targeted fix documents `YYYY-MM-DD` in the schema/prompt and normalizes a date-only deadline to the start of that UTC date; explicit ISO instants remain unchanged. The pre-fix trace is `pipeline-smoke-20261110-d.jsonl`; the post-fix 60-case trace completes case 01 and stores `2026-10-12T00:00:00Z`. `ReportInsightContractTest` passed its two cases for date-only, explicit-instant, and invalid-date handling.

One distinct failure remains in case `06-invoice-known-fields`: the model attached an invoice due date to the `deadline` field of a `TRANSACTION` insight, which the existing contract rejects. The typed provider error maps to a safe HTTP 502 response in `ReportExceptionHandler`, but this runner exercised the service rather than the HTTP controller. The contract/prompt should remain aligned, and endpoint-level malformed-output evidence is still needed.

The deadline-aware prompt remains evaluation-only. It passed 60/60 strict structural cases in the earlier fixture-context comparison, but faithfulness fell from 0.856 to 0.742. The current prompt's actual-pipeline faithfulness is 0.857, while task metrics show lower structural accuracy; the user found the reviewed actual-pipeline sample sound, but that does not adjudicate the candidate-versus-current regression. Do not change the application default based on the current evidence.

### Reproduce and inspect the actual-pipeline extension

- [ReportPipelineEvaluation.java](../evals/actual-pipeline/ReportPipelineEvaluation.java) runs the real services against a disposable database and synthetic fixture.
- [score_actual_pipeline_ragas.py](../evals/actual-pipeline/score_actual_pipeline_ragas.py) runs the four standard metrics on retrieved contexts; it reads the evaluator key from process environment only.
- [summarize_actual_pipeline.py](../evals/actual-pipeline/summarize_actual_pipeline.py) reproduces the structural, latency, token, cost, scope, reuse, and RAGAS summary.
- [Actual pipeline trace](../evals/results/actual-pipeline/actual-pipeline-60-20261110.jsonl) contains per-case retrieval/provider traces and the saved-report benchmark.
- [RAGAS rows](../evals/results/actual-pipeline/actual-pipeline-60-ragas-20261110.jsonl), [RAGAS summary](../evals/results/actual-pipeline/actual-pipeline-60-ragas-20261110-summary.json), and [consolidated measurements](../evals/results/actual-pipeline/actual-pipeline-60-summary.json) preserve the scores and derived metrics.
- [human-review-labels.md](../evals/results/actual-pipeline/human-review-labels.md) is the blind reference-label sample; [human-review-ragas.md](../evals/results/actual-pipeline/human-review-ragas.md) is the judge-decision sample. The user reviewed eight labels and seven decisions and requested no corrections.

The date-only parser fix passed independent review and was merged to local `main` as `b33a6f0`. Vite and Spring Boot were restarted from that checkout; loopback returned 200 for the frontend, 401 for the unauthenticated backend route and its Vite proxy, and the configured PostgreSQL service remained running. The review, restart commands, PIDs, and exact responses are recorded in the [workstream scratchpad](agent-work/capstone-ai-evaluation.md) and [project progress log](project-progress.md).

## Guardrail and operational-evidence audit — 2026-11-10

### Current behavior

- `ReportService.buildPrompt` tells the model that email text is untrusted. The report path has no deterministic input injection detector and no PII/credential redactor before provider transmission. The synthetic attack cases `10-injection-only`, `50-role-spoofing-injection`, and `57-structured-output-injection` produced no insights; `51-real-task-with-injection` retained the supported checklist action. These are model outcomes, not evidence that a local guard fired.
- `ReportInsightContract` enforces the JSON shape, allowed kinds and enum fields, text/array bounds, date parsing, cross-field consistency, and membership of source IDs in the retrieved set. It does not establish semantic entailment or scan generated text for PII/secrets.
- Provider refusals become `INVALID_MODEL_OUTPUT`; the report handler maps this and other provider failures to HTTP 502 with a safe fixed message. The frontend maps all 5xx responses to a generic local-service error, so it does not distinguish an invalid model result or direct the user to review the source. No HTTP/controller or UI failure scenario was run here.
- When there are no eligible messages or no unexpired bodies, `ReportService` has a non-model fallback narrative. No focused acceptance test currently asserts those empty-context paths and the zero-provider-call behavior.

### Verification performed

Ran `mvn -Dtest=AIProviderClientTest,ReportInsightContractTest,ReportPhase3AcceptanceTest test` in `backend/`: **11 tests passed, 0 failures/errors/skips**. The client tests cover safe provider failure handling without retries, rejection of invalid structured output, missing configuration without transport, and loopback endpoint restrictions. Contract tests cover date-only deadlines and invalid dates. Phase 3 acceptance tests cover disclosure/expansion, saved dashboard reads, encrypted partial report persistence, and daily scheduling; they do not cover empty knowledge or endpoint/UI failure behavior.

The actual synthetic case `06-invoice-known-fields` also returned typed `INVALID_MODEL_OUTPUT`: the model put an invoice payment due date in the action-only `deadline` field. The report exception handler maps this to HTTP 502; the actual-pipeline runner exercised the service and did not exercise that HTTP response path.

### Logging and remaining guardrail evidence

The report/provider completion path does not log prompts, email bodies, model outputs, or per-request latency. Scheduler failure logs are generic and omit report content/provider details; Gmail sync logs use mailbox IDs/error codes rather than message bodies. This is a privacy-positive source inspection, but request/error/latency observability is not yet evidenced. Evaluation traces contain synthetic content only and are owner-only artifacts; they are not production logging evidence.

To close Section 6, add and test deterministic, privacy-preserving input/output checks with documented false-positive behavior, verify that a guard fires on synthetic attacks while legitimate messages pass, test the HTTP/UI refusal and malformed-output path, test empty/expired context without a provider call, and demonstrate safe operational telemetry. The PII/secret masking policy needs an explicit product decision before implementation because report generation currently transmits user-selected email text only after disclosure and confirmation. No candidate prompt was promoted and no application code changed during this audit.
