# Retention cleanup workstream

## Objective

Add a local scheduled cleanup worker that removes expired email snippets, cached message bodies, attachment metadata and bytes, and derived reports/insights while preserving minimal message metadata.

## Status

Implementation added; follow-up source review removed the vault-unlocked gate so TTL cleanup continues while locked. Maven compile is pending the parent agent's source-settled signal.

## Files

- `backend/src/main/java/com/east/app/retention/RetentionCleanupService.java`
- `backend/src/main/java/com/east/app/retention/RetentionCleanupRepository.java`
- `docs/agent-work/retention-cleanup.md`

## Behavior and decisions

- The scheduled worker runs with a 60-second fixed delay whether the vault is locked or unlocked. Cleanup only deletes encrypted rows or clears their ciphertext columns; it does not need the vault key or decrypt content. Sync and report generation still pause while locked.
- One UTC instant is used as the cutoff for the whole cleanup. Deletes and updates execute in one Spring transaction.
- Cleanup uses the row expiry timestamps that writers set from the user's configured body-cache lifetime; it does not independently calculate or override TTL.
- Expired summaries are deleted using `summary_run.expires_at`. Runs with insights sourced from an expired message body are also deleted, which cascades to their insights and source rows. Insights sourced from expired bodies are deleted directly as well.
- Attachment rows, including cached bytes and attachment metadata, are deleted when the byte expiry is reached or the source message body expires. When an expired thread is opened, the read path fetches its current attachment metadata again from Gmail; bytes remain user-triggered only.
- Expired message bodies and thread snippets are cleared, retaining thread/message navigation metadata such as sender, subject, and timestamps.
- No email content, ciphertext, identifiers, credentials, or token values are logged.

## Assumptions and limitations

- `summary_run` has an expiry column but no direct source-message relation. Report generation must set its expiry no later than the earliest source-body expiry. The cleanup additionally removes a run when an expired source is linked through one of its insights, but a run with no linked insights relies on `summary_run.expires_at`.
- The worker does not decrypt data, touch the vault idle timer, or depend on the unlocked key. It may run concurrently with lock/unlock, but only performs expiry-based SQL deletion/clearing.

## Verification

- Read the current vault API, scheduling setup, and Liquibase schema to confirm the service uses existing expiry columns and retains minimal message metadata.
- Maven compilation and tests have not been run; waiting for parent coordination.

## Handoff

Parent agent: compile this workstream together with the settled backend source after signaling the coordinated Maven build. Check the `summary_run.expires_at` contract with the future report writer so body-derived narratives expire with their source cache.
