# Mailbox disconnect and account-color lifecycle audit

## Objective and scope

Review of Phase 2 mailbox disconnect and account-color behavior against the current product decisions. The initial review was read-only; a follow-up clarified local-only disconnect behavior in the UI and product/technical documentation. No secrets, mailbox contents, Google APIs, builds, tests, or external account state were accessed.

## Findings

### Local purge is required; provider-grant revocation is not currently promised

- The product plan requires disconnecting a mailbox to stop local use and purge local cached content, credentials, insights, unsaved drafts, and staged outgoing attachments, while leaving Gmail content unchanged (`docs/product-plan.md:118-119`). The decision register repeats that local-purge boundary and says saved Gmail content remains unchanged (`docs/decisions-and-open-questions.md:63,84`).
- The technical API contract says disconnect purges local data and credentials without changing saved Gmail content (`docs/technical-plan.md:123-125,183-185`).
- The implementation deletes mailbox-owned summary rows, deletes only the current user's mailbox row (with dependent local data cascading from the mailbox), and invalidates its in-memory access-token cache (`backend/src/main/java/com/east/app/auth/MailboxService.java:103-112`; owner-scoped delete at `backend/src/main/java/com/east/app/auth/MailboxRepository.java:178-182`). No provider token-revocation call is present in this disconnect path.
- The roadmap's phrase “revocation/reconnect behavior” is ambiguous (`docs/implementation-roadmap.md:51`). Elsewhere, the product plan specifically requires visible reauthorization after Google revokes or expires a grant (`docs/product-plan.md:122`); token-refresh failure with `invalid_grant` marks the mailbox as requiring reauthorization (`backend/src/main/java/com/east/app/auth/GoogleAccessTokenService.java:79-88`).

**Recommendation:** Treat local unlink and purge as the Phase 2 disconnect guarantee. Clarify the roadmap phrase to mean handling externally revoked/expired grants and reconnecting. State in disconnect UI/help that East removes its local connection and cached data; Google may retain the OAuth grant until the user revokes it in Google Account settings. Remote grant revocation can remain an optional future control; if added, make it best-effort and never let a revocation-network failure prevent local credential/data purge. This keeps the local deletion guarantee reliable and avoids claiming revocation that is not implemented.

### Stable color is promised for connected accounts, not expressly across disconnect/reconnect

- The product requirements say a mailbox receives an application-chosen stable color and the decision register says to assign a stable distinct color to each connected account (`docs/product-plan.md:35`; `docs/decisions-and-open-questions.md:56-57`). Neither statement explicitly promises preserving a color after disconnect.
- While an account remains connected, `MailboxService.connect` retains its stored color index on repeat authorization (`backend/src/main/java/com/east/app/auth/MailboxService.java:37-46`). New mailbox allocation hashes the Google subject, then probes for an unused index (`backend/src/main/java/com/east/app/auth/MailboxRepository.java:105-118`). The persisted index is removed with the mailbox row at disconnect (`MailboxRepository.java:178-182`), so a later reconnection starts allocation again. If its initial hash index has become occupied, probing can give it a different color.
- The frontend maps the stored index deterministically without cycling the original Nord palette (`frontend/src/lib/mailbox-colors.ts:19-33`); this guarantees deterministic rendering of an index, not persistence of that index after the mailbox record is deleted.

**Recommendation:** Interpret and document “stable” as stable while connected; color preservation after disconnect is not a current requirement. If product intent is stable identity color across unlink/relink, add that as a new retention decision first: preserving a mapping after disconnect retains account-derived metadata despite the local-purge policy. Then design collision handling so reconnecting accounts keep their mapping without assigning the same color index to another connected account.

## Remaining checks

No runtime check was performed. Confirming actual local purge and reconnect-color behavior would require an app/database interaction, which was outside this read-only audit. No Google OAuth grant status was inspected.

## Follow-up — disconnect grant disclosure

- Official Google documentation states programmatic token revocation removes the OAuth scopes granted to a project, so automatically revoking on mailbox disconnect could also interrupt East sign-in and other linked access: [Google OAuth web-server token revocation](https://developers.google.com/identity/protocols/oauth2/web-server#tokenrevoke).
- Kept the existing local-purge disconnect behavior and clarified that Google may retain its OAuth grant until the user revokes East in Google Account settings. Updated the connected-account confirmation in `frontend/src/components/ConnectedDashboard.tsx`, `docs/product-plan.md`, `docs/technical-plan.md`, `docs/implementation-roadmap.md`, and `docs/decisions-and-open-questions.md`.
- No endpoint, OAuth grant, mailbox, or Gmail content was changed. No tests or builds were run for this UI-copy/documentation follow-up.
