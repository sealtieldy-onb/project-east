# Capstone demo video flow

The capstone specification asks for the problem, solution and agentic functions, architecture, evaluation, then a live walkthrough with a safety behavior and a gracefully handled failure. Keep the recording at or below five minutes.

| Time | Segment | What to show/say |
| --- | --- | --- |
| 0:00–0:25 | Problem | Gmail follow-ups and transaction notices get lost across threads; East serves one mailbox owner. |
| 0:25–0:55 | Solution | Explain the grounded mailbox assistant and user-controlled suggestions. Identify scoped retrieval as the agentic capability. |
| 0:55–1:35 | Architecture | Show React UI → loopback Spring Boot → user/date-scoped encrypted Gmail cache → selected model; show source IDs returned to the UI. |
| 1:35–2:20 | Evaluation | Show the 20-case set, prompt comparison, four RAGAS metrics, one failure root cause, before/after results, and limitations. |
| 2:20–4:35 | Walkthrough | Use the fictional workspace. Show report scope/provider disclosure, generate a report, follow an insight to its source conversation, demonstrate the synthetic injection case, and show an empty/provider-error state. Label any unpromoted prompt as an evaluation candidate. |
| 4:35–5:00 | Close | State that Gmail data remains private/local, deployment uses the confidentiality exception, and mention remaining tracing/cache work. |

Do not put API keys or real email content on screen. If the injection handling is not promoted into the app before recording, show it as an evaluation result and label it as a candidate prompt result.
