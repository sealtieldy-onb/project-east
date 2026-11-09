# Phase 3 provider adapter tests

## Scope

Added synthetic coverage for the explicit-call AI provider client and strict structured-output validator. The tests use a temporary local vault key, an in-memory fake provider-settings row, a synthetic API key, synthetic prompt text, and an injected `ClientHttpRequestFactory` that records requests and returns fixed responses. The fake transport cannot reach OpenAI or another endpoint.

## Cases

- `AIProviderClientTest` verifies construction performs no provider request, one explicit call decrypts the synthetic key and sends the expected model/prompt/strict JSON Schema request, non-loopback cleartext HTTP configuration fails before transport, provider errors are sanitized and not retried, invalid model content is rejected, and missing provider configuration makes no request.
- `StructuredOutputValidatorTest` verifies a valid single object reaches the feature validator and rejects duplicate keys, trailing JSON values, non-object roots, semantically invalid content, invalid schema names/root shapes, and mutation of the caller-owned schema after defensive copying.

## Test seam

The production `AIProviderClient` constructor still creates the bounded-timeout JDK HTTP request factory. A package-private constructor accepts a `ClientHttpRequestFactory` for deterministic in-memory tests; it does not change endpoint validation, request behavior, or provider-call timing.

## Verification

- The parent workstream ran the focused Maven suite; 9 synthetic tests passed.
- The tests do not exercise report persistence, the daily scheduler, draft assist, Gmail, migrations, or runtime integration.

## Boundaries

No real credentials, `.env` values, email content, Gmail APIs, browser sessions, database state, network endpoints, or model providers were read or accessed. The only prompt and key strings are synthetic test constants.
