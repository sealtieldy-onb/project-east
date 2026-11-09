# Phase 3 provider adapter and structured output

## Status

Ported from the existing preliminary Phase 3 worktree into the current Phase 2 checkout after Codex refused attachment because that worktree is owned by another task. The original worktree was left unchanged. This adapter is now part of the active Phase 3 work; no report caller exists yet.

## Scope and implementation

- Added `com.east.app.ai` provider-client and validation types only. `AIProviderClient` reads the local singleton provider settings when called, decrypts the saved key with the existing associated data, and sends a bounded text-only request. It does not store the request or response, retry, log provider bodies, or run from startup, a controller, or a scheduled job.
- The adapter currently implements the OpenAI Chat Completions wire shape: it appends `/chat/completions` to the configured API base, sends `response_format.json_schema` with `strict: true`, and reads `choices[0].message.content`. The OpenAI default base is `https://api.openai.com/v1`. The current OpenAI API documents Chat Completions and JSON Schema structured output; the Responses API is also available. See [Chat Completions API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) and [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat).
- Custom endpoint URLs are restricted to HTTPS or HTTP on loopback, without credentials, query, or fragment, matching the local Settings validation. Connection/read timeouts and request/response size limits are applied. Provider failures use fixed safe messages; response bodies are not included in exceptions.
- `AIProviderPrompt` permits bounded system/user text only. `StructuredOutputContract<T>` supplies each feature's JSON Schema and typed semantic validator. `StructuredOutputValidator` rejects malformed, duplicate-key, trailing, over-limit, or non-object JSON before invoking the feature validator.
- No report or draft contract/caller was added. A configured key alone cannot trigger processing because no endpoint or scheduled job invokes the client. Future callers must enforce an explicit report/draft request or an enabled daily schedule, provider disclosure, bounded mailbox selection, and the separate consent gate for Gmail reads beyond the initial 30-message baseline. Attachments must stay out of model inputs.
- Prompt/model content is transient in memory only. Byte arrays for the key and HTTP payloads are cleared; Java `String` values cannot be overwritten in place.

## Unresolved contracts and decisions

- Product-specific report and draft schemas are not specified in this workstream. Each later feature must supply an exact schema plus a semantic validator that checks required fields, lengths/enums, and source identifiers before persistence or display.
- The adapter assumes custom OpenAI-compatible endpoints support Chat Completions at `<configured API base>/chat/completions` and strict `response_format.json_schema`. Confirm this compatibility contract and the Settings UI's API-base URL guidance before wiring real processing. Unsupported endpoints currently fail closed; there is no plain-text fallback or alternate Responses API path.
- Provider disclosure and explicit processing authorization remain responsibilities of a future report/draft caller. The adapter does not persist or infer consent. Confirm the disclosure/acknowledgement flow before connecting it to user data.
- The user confirmed model-assisted new/reply drafting in Phase 3. This provider-adapter workstream creates no draft flow; a later explicit draft request must create an editable local draft and reuse the Phase 2 save/send safeguards.
- The existing provider-key AAD constant lives in `SettingsService`; this new package currently repeats its exact value to avoid editing Phase 2-owned code. Coordinate a shared constant if provider settings are refactored later.

## Verification and boundaries

- In the current Phase 2 checkout, `mvn -o -B -f backend/pom.xml -DskipTests compile` passed, compiling 122 Java sources.
- The original preliminary worktree reported a separate offline compile of 119 Java sources; its uncommitted files were copied read-only and that checkout was not edited.
- No live provider request, Gmail access, real email content, credential inspection, Liquibase change, or Phase 2 production-file edit occurred. Provider adapter tests are now a separate pending workstream.
