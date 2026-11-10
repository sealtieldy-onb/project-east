# Capstone AI evaluation workstream

Date: 2026-11-10

## Original 20-case run (historical)

Completed the specification-based AI evaluation in `evals/capstone-ai-evaluation.ipynb` using GPT-6 Luna and fabricated `example.test` messages only. The shared dataset is `evals/sample-emails.json` (20 cases, 23 emails, plus two urgency probes). No Gmail, PostgreSQL, real mailbox content, or app settings were read.

The original comparison completed 60 saved calls; a prior attempt made five calls before stopping on invalid output. The saved baseline has one schema-invalid output among the 60 requests. The deadline-aware candidate completed 20/20 cases plus two policy probes. It scored 1.000 F1, 100% citation precision/recall and source validity, 100% matched transaction/urgency/deadline exactness, and 2.677s mean latency (13,349 prompt and 2,695 completion tokens). It classified both explicit urgency probes correctly. This is synthetic evidence; the candidate is not integrated into production.

RAGAS 0.4.3 scored 20 baseline and 20 candidate prompt/case rows with zero errors. GPT-6 Luna was the judge, `text-embedding-3-small` the embedding model, and AnswerRelevancy strictness was set to 1 generated question per answer. Faithfulness was 0.625 → 0.673, answer relevancy 0.382 → 0.444, and shared-context precision/recall were 0.950/0.933 for each prompt. Checkpoints and 80 synthetic generation traces are stored in `docs/agent-work/` at mode `0600`.

The notebook was moved to `evals/`; its demo/video section was removed. The requested five-minute outline is separate at `docs/capstone-demo-video-flow.md`.

## Accepted assumptions and rubric

- The user accepts user/date-scoped email retrieval followed by grounded generation as East's minimum retrieval/RAG capability.
- A private loopback deployment is allowed for restricted email data under the supplied specification.
- GPT-6 Luna (`gpt-6-luna`) is the selected evaluation model. API requests used `https://api.openai.com/v1` in the child process; `.env` was not changed.
- Urgency uses both explicit priority wording and deadline proximity. Explicit words take precedence; otherwise 0–7 days is HIGH, 8–30 MEDIUM, and over 30 LOW. No reliable cue yields null.

## Artifacts

- `evals/capstone-ai-evaluation.ipynb` — executed evaluation, custom and RAGAS tables, requirement assessment, failure analysis.
- `evals/sample-emails.json` — shared fabricated input/reference data for the notebook and runner.
- `scripts/capstone-ai-evaluation.mjs` — synthetic three-prompt comparison harness; loads cases from the shared fixture.
- `docs/agent-work/capstone-ai-evaluation-results.json` — original prompt comparison.
- `docs/agent-work/capstone-ai-evaluation-deadline-aware.json` — candidate generation and policy probes.
- `docs/agent-work/capstone-ai-evaluation-ragas-cache.jsonl` — checkpointed RAGAS scores.
- `docs/agent-work/capstone-ai-evaluation-traces.jsonl` — 80 synthetic traces with contexts, outputs, latency/tokens, and scores.
- `docs/capstone-ai-evaluation.md` — concise result and spec assessment.
- `docs/capstone-demo-video-flow.md` — previously requested video outline, kept outside the evaluation notebook.

## Verification

- `node --check scripts/capstone-ai-evaluation.mjs`: passed.
- `node scripts/capstone-ai-evaluation.mjs --self-test`: passed (20 unique cases and valid expected source IDs).
- `node scripts/capstone-ai-evaluation.mjs --mock-provider-smoke`: passed (identical input across prompt variants, structured output and scorer checks).
- Notebook schema/cell syntax validation: passed; end-to-end execution: 9 code cells, no errors.
- RAGAS cache has successful final rows for 20 context-precision cases, 20 context-recall cases, 40 faithfulness cases, and 40 AnswerRelevancy cases at strictness 1. Earlier integration errors in the notebook were corrected; their failed rows are retained under prior cache keys, while the active result table has zero metric errors.
- The key was read from `.env` without displaying or recording it. Candidate report, RAGAS cache, trace file, and original baseline results are local and owner-only. No application source changed, so no branch workflow or app restart applied.
- No model or embedding pricing was verified; cost is unestimated. Some evaluator calls were repeated during notebook integration fixes; the final reported metrics use only successful cached scores.

## Remaining gaps

1. Review and decide whether to promote `deadline-aware-triage`; no app prompt was changed in this workstream.
2. Instrument production retrieval/provider traces if required for the deployed flow.
3. Add deterministic prompt-injection and PII input guards/refusal behavior if pursuing Section 6.
4. Measure report/body cache effect, cost, and invalidation behavior.
5. Obtain the separate grading rubric if a score-level audit is needed.

## Expanded-set retest — 2026-11-10 (complete)

At the user's request, expanded `evals/sample-emails.json` to benchmark `expanded-60-v1`: 60 cases, 71 fabricated emails, original-20 regression split plus new-40 challenge split. References, rationales, and the explicit-wording/deadline rubric were frozen before the fresh four-prompt run. All four prompt texts remain identical to the original comparison; the new outputs are not used for prompt tuning. Fixture file SHA-256 at freeze: `c8fe4f61da5756bac2d2bbb50c34dab59d2154bf0b34ee1df01976265adbd24a`.

The expanded run writes checkpointed results under `evals/results/` and preserves the original `docs/agent-work/` artifacts. The notebook now loads the single shared reference set, reports both splits, retains structural failures, and distinguishes conditional field scores from failure-aware F1, expected-action coverage, and strict case passes. Standard-metric contexts now include full synthetic message metadata/direction/date as well as body; earlier subject/body-only semantic scores are not directly comparable. Synthetic labels are assistant-authored/reviewed; independent user/instructor review is pending.

Offline checks passed: Node syntax, 60-case fixture self-check, four-prompt identical-input mock smoke, notebook cell compilation, and evaluation-only wording. Fresh generation and standard-metric execution completed; final results follow. No application code or dependencies changed, so the application branch/reviewer/restart workflow does not apply.

### Expanded results and verification

- Fresh generation: 240 requests; 239 valid outputs. Evidence-first case 06 failed transaction urgency validation and remains counted as a failure. No failed generation was silently rerun.
- Failure-aware F1 (all 60 / new 40): current 0.729/0.708; evidence-first 0.817/0.875; conservative 0.968/0.952; deadline-aware 1.000/1.000.
- Strict case passes: current 39/60; evidence-first 41/60; conservative 46/60; deadline-aware 60/60 (20/20 original and 40/40 new). Candidate matches all 48 expected insights, 30 action priorities/deadlines, 15 transaction records, and expected citations. Mean candidate latency 2.682s.
- RAGAS 0.4.3: 120 baseline/candidate answer rows, 360 unique successful metric records, zero metric errors. Faithfulness was 0.856 → 0.742; answer relevancy was 0.344 → 0.369. Shared context precision/recall: 0.967/0.854. Same judge/model and strictness-1 limitations apply. Full-message metadata is now included in standard-metric evidence, so older semantic scores are not directly comparable.
- Four assistant judge spot checks/eight outputs are saved with context labels, actual outputs, metric scores, and qualitative notes; independent adjudication remains pending.
- All three pure injection inputs produced no insights for every prompt in this retest; the older baseline false positive did not recur. Prompt texts are unchanged, and no candidate promotion occurred.
- Expanded outputs, summary, 54 structural failures, 240 generation traces, and checkpointed metrics are under `evals/results/`, with generated artifacts at mode `0600`; earlier results are preserved.
- The expanded notebook executed end-to-end, then was rerun with provider calls disabled to verify matching metric-cache loading. No external requests were made by the offline replay. Notebook schema/code compilation, shared-fixture self-check, four-prompt mock smoke, and scoped Git whitespace checks passed.
- Metric scheduling was changed from sequential cases to independent tasks under the existing semaphore limit of three. The run resumed 159 completed metric checkpoints; unfinished in-flight evaluator requests may have repeated. No generation requests were repeated.
- No application code/dependencies changed and no application restart, commit, remote, or push occurred. `.env` stayed unchanged; only fabricated fixture inputs went to the model/judge. Independent label review, live retrieval traces, deterministic safety guards, cache measurement, and candidate promotion remain follow-up work.

- Final scoped whitespace checks passed for all evaluation files and touched tracked docs. The repository-wide check flags an unrelated new blank line at EOF in `docs/agent-work/browser-annotations-ui.md`; that file was not modified by this workstream and was left untouched.

## Actual application pipeline and saved-report reuse — 2026-11-10

This update extends the prompt-comparison evaluation above. It uses the real Spring `ReportService`, `ReportRepository` SQL, local vault encryption/decryption, and `AIProviderClient` against an isolated disposable PostgreSQL database. The Gmail descriptor service is synthetic and serves only fixture IDs. No live Gmail/account data was accessed. The ephemeral Java server used `server.port=0`; the disposable database was reset between runs. The evaluator key was read into a child process from `.env` without printing or persisting it.

### Actual-path results

- `evals/sample-emails.json`: 60 cases / 71 synthetic messages. The actual `ReportService.generate` path completed 59/60; case `06-invoice-known-fields` failed with `INVALID_MODEL_OUTPUT`. The model returned a transaction with a non-null `deadline`, which the existing contract rejects for `TRANSACTION`. The exception maps to a generic typed HTTP 502 response in `ReportExceptionHandler`; the evaluation runner exercised the service and trace, not the HTTP controller.
- SQL scope probe: 1 of 4 candidates returned. Wrong owner, wrong mailbox, expired content, and out-of-window content were excluded. `ReportRepository.findReportInputs` enforces owner/mailbox/date/MIME/ciphertext/nonce/expiry constraints.
- Current-prompt structure across actual outputs: 36/60 strict cases; insight precision/recall 0.717/0.688; citation precision/recall 0.957/0.880; valid source-ID rate 1.000; matched transaction detail exactness 1.000; urgency exactness 0.688; matched deadline date exactness 1.000.
- Completed generation latency (includes provider; excludes HTTP): p50 3,292 ms, nearest-rank p95 5,460 ms. Sixty app generation calls used 29,571 input and 11,688 output tokens; provider reported 0 cached input tokens. Estimated standard GPT-6 Luna cost: $0.0088011, excluding RAGAS and account adjustments.
- RAGAS 0.4.3 on actual SQL-returned contexts: 238 scored metric records, zero metric errors; one invalid app output has no faithfulness/relevancy score. Means: faithfulness 0.857 (59), answer relevancy 0.303 (59), context precision 0.992 (60), context recall 0.826 (60). Judge is the same model; answer relevancy uses the fixture question even though East produces a date-scoped report.

### Existing saved-report reuse and invalidation

- Initial explicit generation: 3,953 ms, 1 provider call, $0.0001938 estimated.
- 30 exact-scope `getDashboard` reads: all returned the same encrypted saved report, p50 9 ms / p95 10 ms, zero provider calls and zero generation token cost.
- Identical explicit `generate`: 5,269 ms, one fresh provider call, $0.0002878 estimated. Similar one-day-wider `getDashboard`: no saved report hit, 7 ms, no model call. Similar-range explicit `generate`: 5,082 ms, one fresh provider call, $0.0002113 estimated.
- These are service timings for a one-message synthetic sample, not full HTTP performance. No second response cache was added.
- Saved-report lookup is owner-scoped and exact by mailbox set/date range; only completed, encrypted, unexpired reports match. Source-body expiry governs report/insight expiry; retention TTL changes recompute those dates; mailbox disconnect cleanup deletes linked reports/insights. Existing PostgreSQL retention/disconnect acceptance coverage passed. Provider/model changes do not invalidate completed reports; the stored provider/model metadata identifies the original generation until normal expiry/deletion.

### Date-only failure and decision status

Before the parser change, synthetic case `01-contract-deadline` got a successful provider response with `deadline: "2026-10-12"`, but East rejected it because the JSON schema allowed a string while `Instant.parse` required an instant. `ReportInsightContract` now accepts `YYYY-MM-DD` and normalizes it to start-of-day UTC; explicit instants are unchanged. `ReportService` tells the model to use date-only form when no time/timezone is supplied. The post-fix actual 60-case trace completed case 01 and stored `2026-10-12T00:00:00Z`. `mvn -o -B -f backend/pom.xml -Dtest=ReportInsightContractTest test` passed 2 tests (0 failures/errors).

The deadline-aware prompt remains evaluation-only: it scored 60/60 strict cases in the prior fixture-context comparison but faithfulness fell from 0.856 to 0.742. The current-prompt actual-pipeline faithfulness score is 0.857, but actual structural scoring shows only 36/60 strict passes. The user reviewed the eight blind-label cases and seven actual-pipeline RAGAS decisions and found them sound, with no corrections. This does not adjudicate the candidate-versus-current faithfulness regression, so no prompt default is changed. Review samples and the recorded outcome are in `evals/results/actual-pipeline/human-review-labels.md` and `human-review-ragas.md`.

### Artifacts and status

- `evals/actual-pipeline/ReportPipelineEvaluation.java` — real service/SQL/provider trace runner and saved-report benchmark.
- `evals/actual-pipeline/score_actual_pipeline_ragas.py` — RAGAS scoring against actual retrieval contexts; the evaluator key is process-only.
- `evals/actual-pipeline/summarize_actual_pipeline.py` — reproduces the structural, timing, token/cost, scope, cache, and RAGAS summary.
- `evals/results/actual-pipeline/actual-pipeline-60-20261110.jsonl` — 60 cases, traces, scope probe, benchmark, summary record.
- `evals/results/actual-pipeline/actual-pipeline-60-ragas-20261110.jsonl`, its `-cache.jsonl`, and `-summary.json` — metric rows/checkpoints/summary.
- `evals/results/actual-pipeline/actual-pipeline-60-summary.json` — consolidated measurement.
- `evals/results/actual-pipeline/human-review-labels.md` and `human-review-ragas.md` — user review packets.
- `evals/capstone-ai-evaluation.ipynb` and `evals/README.md` now describe the separate actual-path extension; the prompt-comparison notebook still evaluates fixture-selected contexts.

The date-only parser fix was independently reviewed against `main` with no blocking findings and merged locally as `b33a6f0` (feature commit `5614e43`). The two focused contract tests passed. The review noted a possible prior-day display if the UI localizes UTC midnight; source inspection found the dashboard renders the raw ISO deadline string, so no timezone conversion occurs there.

After the merge, stopped the East Spring Boot/Maven PIDs 61358/61109 and Vite/npm PIDs 61756/61488. Restarted from the merged `main` checkout using `EAST_SERVER_PORT=8080 EAST_GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google mvn spring-boot:run` from `backend/`, and `npm run dev` from `frontend/`. The backend started on `127.0.0.1:8080`; Liquibase found all 11 changesets current and ran 0. Vite started on `127.0.0.1:5173`. Loopback checks returned HTTP 200 for `/`, HTTP 401 for `/api/v1/dashboard` directly and via the Vite proxy (expected without an authenticated session). PostgreSQL stayed running on `127.0.0.1:15432`; the finished evaluation-only database on 25432 was stopped and removed. The backend restart clears its in-memory login session.

No remote or push was configured or used. Remaining capstone gaps include endpoint-level malformed-output evidence, deterministic input injection/PII guards and refusal behavior, product-level logging evidence, instructor confirmation of the private/RAG interpretation, and the separate grading rubric/submission media.

## Guardrail and operational-evidence audit — 2026-11-10

- Source inspection: report prompt says email is untrusted, but there is no deterministic injection scan or PII/credential redactor before provider calls. Four actual synthetic attack cases returned safe-looking model outputs; no guard event was recorded, so these do not prove blocking. One mixed legitimate-task case preserved its supported action.
- `ReportInsightContract` provides structural/type/cross-field/source-token checks; it does not verify semantic entailment or scan output for PII/secrets. Provider refusals and malformed structured outputs become typed safe failures (HTTP 502 per `ReportExceptionHandler`); frontend maps 5xx to a generic service error. HTTP/UI refusal behavior was not exercised.
- No-input/no-unexpired-body paths have fixed fallback narratives and avoid the model call in service code, but no focused acceptance test asserts this.
- Focused verification passed: `mvn -Dtest=AIProviderClientTest,ReportInsightContractTest,ReportPhase3AcceptanceTest test` — 11 tests, 0 failures/errors/skips. Tests cover sanitized provider errors/no retry, invalid structured output, missing configuration, contract date handling, disclosure/expansion, saved-report reuse and scheduled-report behavior; they do not cover HTTP/UI mapping or empty context.
- Runtime report generation does not log prompts, bodies, outputs, or latency. Scheduler logs are generic; Gmail sync logs identify mailbox IDs and error codes. This reduces content exposure but leaves request/error/latency telemetry evidence incomplete.
- The remaining implementation decision is the exact PII/secret masking scope and false-positive behavior. Record it in the decision register before changing what approved email content is sent to a provider. No application source changes or service restarts were made in this audit.
