# Sender photo decision

## Request

The user asked whether sender profile pictures could be shown in Conversations. After learning that a real lookup needs Google Contacts read-only access and mailbox reauthorization, the user chose to keep initials avatars for now.

## Source check

The current `ThreadSummary` and `ThreadMessage` frontend models expose sender name and sender email, but no photo URL. The mail-read response is built from those sender fields. The current OAuth flow does not request the Google Contacts `contacts.readonly` scope.

## Decision

Keep the existing initials avatars. Do not add the Contacts scope or a sender-photo lookup unless the user asks for it later.

## Verification

Documentation-only update. No code, tests, builds, OAuth requests, or Google Contacts calls were made.
