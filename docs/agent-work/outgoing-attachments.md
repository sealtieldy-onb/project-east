# Outgoing attachment type workstream scratchpad

## Objective

Remove the file-extension restriction for user-selected outgoing attachments while retaining ownership checks, safe filenames, encrypted staging, MIME-header safety, the existing size/count limits, and the explicit save/review/send flow. Forwarded Gmail attachments keep their current fetch, default-inclusion, removal, and review behavior. Do not run tests or make Gmail calls.

## Progress

- Removed the frontend extension allowlist. Selection still rejects empty files, files above 10 MiB, more than 10 files per draft, and more than 16 MiB total.
- Replaced extension-derived MIME validation with safe-token validation of the declared MIME type. East uses `application/octet-stream` when the declared type is missing or malformed; uploaded files remain attachment-only and are not executed or previewed by East.
- Preserved backend filename validation, ownership scoping, encrypted storage, Gmail save/send confirmation, and existing forwarded-attachment handling.
- Updated product, technical, roadmap, decision-register, draft-workstream, and project-progress documentation.

## Verification

- Source review only. No tests, build, runtime verification, or Gmail calls were performed for this change.

## Remaining limit

- East permits up to 10 files per draft, 10 MiB per file, and 16 MiB total. Gmail's own restrictions may reject a file when saving or sending.
