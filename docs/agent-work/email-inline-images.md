# Email inline images

## Scope

- Render message-local `cid:` images automatically when the user opens a conversation, using only owned attachment metadata and an owner-scoped local API route.
- Keep external image URLs inert until the user selects the accessible per-message **Load external images** action. Set `no-referrer` before assigning any remote image `src`.
- Allow inline rendering only for GIF, JPEG, PNG, and WebP. SVG and other unsupported content remain on the explicit attachment download/preview path.
- Keep image bytes encrypted in the existing attachment cache and use its configured `body_cache_ttl_days` value, so image bytes follow the same 60-day user-selected content lifetime as message bodies.
- Own only Liquibase changeset 011 (`inline-image-content-id`). Changeset 010 remains with the cache/session workstream.

## Decision

- Load local `cid:` raster images automatically as part of a user opening a conversation. Always keep external images blocked until the user selects that message's **Load external images** action, regardless of sender-authentication results or inbox classification.

## Implementation and recovery notes

- `GmailContentId` normalizes bounded MIME Content-ID values. Gmail MIME field masks now request part headers while structure-only sync still omits every `body.data` field.
- Gmail sync stores Content-ID on attachment metadata; `MailReadService` rewrites only unambiguous supported-raster CID matches to local attachment URLs. Unmatched, ambiguous, SVG, and other unsupported CID images have no active `src`.
- The attachment route checks the authenticated owner, thread, message, Content-ID, and raster MIME type before serving `Content-Disposition: inline`. It uses the existing encrypted attachment cache and dynamic cache TTL.
- External image URLs are moved to an inert sanitized data attribute only after validating absolute HTTP(S), rejecting local/reserved names, loopback/private/reserved IP ranges, and malformed URLs. No DNS resolution occurs during sanitization, avoiding pre-consent network activity; a public hostname that resolves to a private address is not detected until the user chooses to load it.
- The React reader assigns remote `src` values only after the per-message action and sets `referrerPolicy = "no-referrer"` first. The user action is scoped to that message and resets when the message component unmounts.
- No live Gmail account or running East application was used.

## Assumptions

- Opening a conversation is the explicit user action that authorizes local `cid:` images to load as part of message display. Other attachments still require their existing Preview or Download action.
- External images require a separate explicit action regardless of sender authentication results or inbox classification.
- The 60-day setting is represented by the existing per-user `body_cache_ttl_days` setting; image cache expiry follows that value rather than introducing a second TTL.
- The integration branch was rebased onto local main af808f7, which owns changeset 010. The changelog registers 010 before this branch-owned 011. No configured application database was migrated; only the two disposable QA databases used for acceptance applied 001–011, and both were dropped afterward.

## Exact checks and results

- `mvn -q -f backend/pom.xml -Dtest=MailHtmlSanitizerTest,GmailContentIdTest,InlineImagePolicyTest,AttachmentContentServiceTest test` — passed (12 tests; 0 failures, 0 errors).
- `mvn -q -f backend/pom.xml test` — passed (79 tests; 0 failures, 0 errors, 1 skipped).
- `npm --prefix frontend ci --offline --no-audit --no-fund` — passed using the local npm cache (42 packages installed; lockfile unchanged).
- `npm --prefix frontend run build` — passed (`tsc -b` and Vite 8.3.6 production build; 2,080 modules transformed).
- `git diff --check` — passed.
- No runtime, browser, database migration, or live Gmail checks were run.

## Rebase and integrated verification (2026-11-10)

- Rebased `fix/email-inline-images` onto local `main` at `af808f7`, after the persistent-mail-cache/session integration merged. Resolved the two expected conflicts in `MailReadService.java` and `db.changelog-master.xml`.
- `MailReadService` selects the cache-filtered attachment rows for a cache-only detail read and passes those same rows to CID rewriting. `MailReadRepository.findAttachmentsForCache` selects/maps `content_id`, preserving the 60-day source/cache eligibility filter. The changelog registers changeset 010 before 011.
- Product, technical, roadmap, and decision-register docs record that owned raster CID parts use the authenticated local attachment endpoint; external images stay inert until the per-message action, independently of sender authentication/inbox classification. They also document no-referrer, absolute HTTP(S) checks, literal local/private/reserved-host rejection, no pre-consent DNS/request, and untrusted body handling.
- The first opt-in acceptance attempt caught a duplicate trailing fragment in the Liquibase master file and failed during XML parsing before migration DDL. The duplicate tail was removed, and XML parsing plus all requested checks were rerun successfully.
- `mvn -q -f backend/pom.xml test` — passed after the changelog correction: 86 tests, 0 failures, 0 errors, 2 opt-in PostgreSQL methods skipped.
- `EAST_DB_PORT=25432 EAST_SESSION_TEST_DB=east_session_test_qa EAST_RETENTION_TEST_DB=east_retention_test_qa EAST_RUN_SESSION_POSTGRES_ACCEPTANCE=true EAST_RUN_POSTGRES_ACCEPTANCE=true mvn -q -f backend/pom.xml -Dtest=PersistentSessionPostgresAcceptanceTest,RetentionSettingsPostgresAcceptanceTest test` — passed using the supplied ephemeral QA credential (not recorded): each acceptance method passed (1 test each; 0 failures/errors/skips). Both fresh databases applied 11 changesets, including 010 and 011, then were dropped.
- `npm --prefix frontend run build` — passed `tsc -b` and Vite 8.3.6 production build; 2,081 modules transformed.
- `git diff --check` — passed for the integration diff after correction.
- Preflight created only `east_session_test_qa` and `east_retention_test_qa` on `projecteast-postgres-1`/15432 after verifying they were absent. They remained empty; both exact task-created databases were dropped before any test/migration used that fixture. No other database there was inspected or changed.
- No live Gmail request, user-facing East process, or configured application-database migration was used.
