# OAuth and vault source review

## Objective

Read-only source review of OAuth callback/configuration, local security boundaries, sessions/CSRF, vault passphrase lifecycle, and reset behavior. Do not inspect `.env`, initiate Google authorization, call Gmail, change source, or run tests/builds.

## Findings

### P1 — Loopback-only binding is now enforced

- Rechecked the application entry point and found an earlier fail-fast customizer in `EastApplication`; moved that guard into the isolated `backend/src/main/java/com/east/app/config/LoopbackServerConfiguration.java` rather than leaving duplicate customizers.
- The new servlet web-server customizer is ordered at `Ordered.LOWEST_PRECEDENCE`, rejects any resolved `server.address` other than `127.0.0.1`, then sets the factory bind address to the numeric IPv4 loopback literal. This both fails closed on environment/system-property overrides and pins the socket factory to loopback when configuration is valid.
- Fixed `server.address` in `application.yaml` and removed `EAST_SERVER_ADDRESS` from `.env.example`, so East no longer advertises an address option.
- Source-level verification: inspected cached Spring Boot 4.1.2 bytecode. `ServletWebServerFactoryCustomizer#getOrder()` returns `0`; `WebServerFactoryCustomizerBeanPostProcessor` sorts customizers with `AnnotationAwareOrderComparator` before applying them; `ConfigurableServletWebServerFactory` exposes inherited `setAddress(InetAddress)`. The East customizer therefore checks and sets the address after Boot applies `server.address` properties.
- No build or runtime socket inspection was run for this follow-up, per task instruction.

### P2 — The current `127.0.0.1` frontend origin cannot perform state-changing requests

- The current East browser tab is at `http://127.0.0.1:5173/` (ambient tab state). The Vite dev server binds that interface (`frontend/vite.config.ts:7-12`), but the backend accepts only `http://localhost:5173` and `http://localhost:4173` origins (`LocalRequestBoundaryFilter.java:25-27`, `77-90`; `LocalCorsConfiguration.java:17-24`).
- The boundary filter rejects state-changing requests unless their `Origin` is in that set (`LocalRequestBoundaryFilter.java:61-64`). Vault setup/unlock and other POST operations from the currently open `127.0.0.1:5173` tab therefore receive 403. `GET /api/v1/vault/status` can still render the setup screen because the origin header is normally absent on same-origin GET.
- Recommendation: either make the local browser URL reliably `localhost` (the documented OAuth host) or add the explicit loopback IP frontend origin consistently to the request-boundary and CORS allowlists. If supporting both aliases, keep OAuth initiation/callback on the same hostname so the session cookie and session-bound OAuth state are returned.

Follow-up: opened a separate East browser tab at `http://localhost:5173/`, which matches the configured local origin. The original `127.0.0.1` tab was left untouched. No passphrase was entered and no state-changing request was made.

## Positive source checks

- `GoogleOAuthProperties.isConfigured()` is fail-closed unless both OAuth credential fields are nonblank and the redirect exactly matches `http://localhost:<configured server port>/login/oauth2/code/google` (`GoogleOAuthProperties.java:11-15`). `GoogleOAuthService.requireConfigured()` gates both start and callback processing (`GoogleOAuthService.java:197-200`).
- OAuth state is 256-bit random, stored server-side per HTTP session, one-time consumed, expires after ten minutes, and is bounded to eight pending flows (`OAuthSessionStateStore.java:19-21, 31-70`). The OIDC callback validates issuer/signature, audience/`azp`, expiry, nonce, and verified email before anchoring the local user (`GoogleOAuthConfiguration.java:27-75`, `GoogleOAuthService.java:83-103, 120-140`).
- Mailbox-link state carries the app-user ID in the server-side session and the callback checks both that intent and the current session user (`GoogleOAuthCallbackController.java:49-61`). Mailbox queries/deletion are scoped by app-user ID (`MailboxService.java:94-106`, `MailboxRepository.java:54-60, 159-163`); refresh tokens are encrypted through the unlocked vault before persistence (`MailboxService.java:32-34, 55-82`).
- Session authentication changes the session ID and saves the security context (`AppSessionService.java:53-68`). Logout invalidates the current session. Cookies are HttpOnly for the session, SameSite=Lax, with CSRF enforced for state changes and strict localhost origins (`application.yaml:38-46`, `LocalSecurityConfiguration.java:67-100`).
- Vault uses Argon2id to derive a 256-bit key and AES-GCM for encrypted records; the key stays in process memory, idle expiry clears it after 15 minutes, and application APIs are rejected while locked (`VaultService.java:26-30, 131-180, 225-241`; `VaultActivityTrackingFilter.java:28-48`). Reset requires the exact confirmation and clears East-owned data, then logs out (`VaultService.java:183-189`, `LocalDataResetService.java:10-39`, `VaultController.java:54-62`).

## Scope and verification

- Source inspection only. `.env` was not read or printed.
- The follow-up changed only the loopback server configuration, application entry point/config, `.env.example`, and this scratchpad. No `.env` access, OAuth/Gmail requests, runtime requests, tests, or builds were performed.
- The current app session/vault state and actual resolved `EAST_SERVER_ADDRESS` were not queried; findings describe the source/configuration behavior and the browser URL supplied in task context.

## Re-audit after the local-key decision

### Confirmed against the current Phase 2 decision

- New installs create a random 32-byte key with `SecureRandom`, save it before creating the verification marker, and reload it at startup (`VaultService.java:46-86, 162-189`). No passphrase is requested for normal startup. There is no idle timer or routine lock endpoint; `VaultService` retains the loaded key until process exit/reset (`VaultService.java:131-149`).
- The only passphrase path is the one-time legacy migration endpoint and screen, offered when existing encrypted data cannot be opened with the local key file (`VaultService.java:94-128`, `VaultController.java:31-35`, `VaultGate.tsx:53-57, 87-115`). The UI also offers destructive reset. This is not a normal-startup gate.
- The default key path is `~/.east/vault.key`; `EAST_VAULT_KEY_FILE` can override it (`LocalVaultKeyStore.java:37-42`). On POSIX, the key file is restricted to owner read/write and the key directory to owner read/write/execute when writing (`LocalVaultKeyStore.java:27-33, 63-83, 105-121`). Symlink/non-file key paths and key files of the wrong length are rejected (`LocalVaultKeyStore.java:44-60`).
- Sensitive records use the same AES-256-GCM vault key: OAuth refresh tokens (`MailboxService.java:62-74`), AI provider keys (`SettingsService.java:79-115`), message bodies and snippets (`GmailSyncService.java:536-546, 604-609`), user-fetched attachment bytes (`AttachmentContentService.java:135-163`), local draft payloads and staged files (`DraftService.java:117-149, 1313-1339`). Minimal inbox/navigation metadata remains plaintext by the recorded product decision.
- Reset requires the exact `RESET LOCAL DATA` phrase, truncates the East-owned tables, deletes the local key file, creates a fresh key, and logs out the current session (`VaultService.java:152-160`, `LocalDataResetService.java:10-39`, `VaultController.java:37-45`). It does not call Gmail.
- The API is still forced to IPv4 loopback and rejects a non-loopback `server.address` (`LoopbackServerConfiguration.java:14-38`).
- Body-cache default remains seven days (`001-initial-local-schema.sql:11-18`). The TTL is user-configurable; cleanup runs every minute and deletes expired summaries/insights/attachments, clears body ciphertext/snippets, and retains message navigation metadata (`SettingsService.java:137-202`, `RetentionCleanupService.java:24-33`, `RetentionCleanupRepository.java:18-82`). Drafts are intentionally outside body-cache expiry per the product decision.

### Discrepancies and caveats

- **P2, portability:** on non-POSIX filesystems `LocalVaultKeyStore` silently skips POSIX permission changes and relies on the inherited home-directory ACL (`LocalVaultKeyStore.java:105-132`). It does not inspect or set an equivalent Windows owner-only ACL. The POSIX path is explicitly restricted; owner-only protection is not source-verified cross-platform.
- **P2, future Phase 3 TTL adjustment:** when the user changes retention, `SettingsService.updateBodyCacheDays` recalculates `summary_run.expires_at` only from linked `assistant_insight` rows (`SettingsService.java:193-200`). A narrative-only summary run with no insight rows has no source-message link used by this update and can retain its previous expiry when the user shortens the body-cache lifetime. Phase 2 does not create summaries, so this affects the Phase 3 integration point.
- Reset spans PostgreSQL and the filesystem rather than one atomic transaction: the DB truncate commits before `keyStore.delete()` and new-key creation (`VaultService.java:152-160`). If filesystem operations fail at that point, local DB data may already be gone while reset reports failure. No failure-injection/runtime check was performed.

### Verification limits

- Current-source inspection only. I did not read `.env`, inspect `~/.east/vault.key` or its actual permissions, access a mailbox, contact Google/Gmail, run the application, or run tests/builds.
- The standard POSIX permission behavior follows the code path; actual filesystem permissions for this installation were not checked.
