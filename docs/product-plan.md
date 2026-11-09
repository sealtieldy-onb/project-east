# Product plan

## 1. Product summary

East is a local web application that helps a user understand and manage messages across multiple Gmail accounts. It provides a consolidated dashboard for summaries and follow-ups, while keeping each message visibly associated with its source account. A separate Conversations page provides a familiar Gmail-style email workspace.

The assistant may identify likely action items, responses to earlier inquiries, and transaction messages. On request, it may draft new emails or replies for the user to review. It must never send a message without an explicit user action to send that specific message.

This project is intended to run on the user's computer. The product must not assume a particular person, Gmail address, account list, or mailbox color. Users authenticate with Google and connect one or more mailboxes through the UI.

The web app is accessible only from the same computer through localhost; do not expose it to other devices on the local network.

## 2. Users and goals

### Primary user

Any person running the local application who wants to connect one or more Gmail accounts and review them in one place.

### User goals

- Understand recent email without reading every message first.
- Find messages that may need a reply or other action.
- Notice replies to messages the user previously sent.
- Review purchases, receipts, invoices, refunds, and other transaction-related messages.
- Read and organize conversations across connected accounts.
- Ask for help drafting a new email or response while retaining control over whether it is saved to Gmail or sent.

## 3. Main navigation and experience

The application has two primary pages:

1. **Dashboard** — account-scoped summaries, action prompts, reply updates, and transaction highlights. The default is a combined view with account filters and a toggle to group results by account.
2. **Conversations** — a Gmail-style mailbox view with account selection, message list, thread reading, search, filters, labels, and message actions supported by granted permissions.

Every mailbox gets an account identity treatment generated from its connected Google account, such as its display name/email and a stable color chosen by the application. The user can distinguish messages and dashboard cards by account. Account identity is data, not a hardcoded UI branch.

### Visual direction

Use the [Nord color palette](https://www.nordtheme.com/docs/colors-and-palettes/) for the Dashboard and app shell. In light mode, use Snow Storm (`nord6`) as the page base with `nord5` and `nord4` for raised surfaces and borders. In dark mode, use Polar Night (`nord0`) as the page base with `nord1` and `nord2` for raised surfaces; use Snow Storm colors for text. Use Frost (`nord7`–`nord10`) for primary and secondary accents, and Aurora (`nord11`–`nord15`) for semantic status colors. Preserve Gmail's familiar information architecture in Conversations, follow the system preference for light or dark mode, and pair status colors with text or icons so meaning is not conveyed by color alone. Connected mailboxes receive distinct application-assigned colors that remain stable while linked. The first eight color slots use Nord accents; later slots use a deterministic Nord-compatible sequence rather than recycling colors. Disconnect removes the mailbox's local color assignment with its other local data, so reconnect can allocate a different color if the connected mailbox set has changed.

## 4. Dashboard requirements

### Summary

- Show a concise digest of messages for a selected date range.
- Format the digest as one concise narrative paragraph, with actions, replies, and transactions shown in separate sections.
- On a mailbox's first sync, fetch the latest 30 messages for that mailbox as the initial baseline. This is a count-based baseline, not a 30-day window.
- For subsequent dashboard summaries, default to the past 7 days and allow the user to choose a different range, including a custom range.
- When a mailbox is first linked, prompt the user to generate a one-time summary for the past 30 days. Do not generate it until the user confirms.
- Before content processing, provide an explicit header-only preview of the eligible-message count for the selected mailbox/date scope. The preview does not fetch bodies or call the model. Show that count, provider/model/destination, scope, and content disclosure before asking the user to confirm processing; if the count changes, show the new count and ask again.
- Apply the initial 30-message allowance per selected mailbox. If the report needs more than those initial messages, ask before fetching additional bodies; one manual report can fetch at most 240 additional bodies. If the user declines or the cap leaves messages unread, generate only from the approved inputs and clearly mark the report as partial.
- Let the user request a summary manually at any time or opt into daily reports. A daily report runs at a time selected by the user and covers the previous day.
- If the app was closed at the scheduled time, generate one catch-up report for the most recent day when the app next starts.
- Show daily reports on the dashboard and preserve source mailbox labels for every included item.
- Display the mailbox, date range, and last sync time used for each result.
- Link each insight back to its source message or thread.
- Do not send message content to a model provider until the user has configured that provider and explicitly requests a summary or draft, or enables daily reporting.
- If no AI provider is configured, show a clear Configure AI prompt and keep AI summary and insight cards unavailable; Gmail sync and Conversations remain usable.
- Expire generated summaries, action items, and transaction details when the source email-body cache expires.
- Show loading, empty, partial, stale, and error states without presenting incomplete data as a complete summary.

### Urgent actions and unanswered messages

- Surface messages or threads likely to need a user action, including threads awaiting a reply from the user and important notices.
- Distinguish an unread message from a message that is actually awaiting a reply; unread status alone is not proof that a response is needed.
- Explain why an item was surfaced and provide a link to review the source conversation.
- Include likely reply-needed messages even without an explicit deadline, and show a confidence indicator with the evidence for the suggestion.
- Surface a likely reply-needed item immediately when detected; use its confidence and any due date to convey priority rather than applying a waiting period.
- Order action items by deadlines and urgency first, using message recency as a tie-breaker.
- Let the user dismiss or mark an insight handled. These dashboard controls must not silently alter a Gmail message.
- Show urgent-action alerts in the dashboard only; do not send operating-system desktop notifications.

### Responses to earlier inquiries

- Identify newly received replies in threads where the user previously sent a message.
- Indicate the source mailbox and link to the full thread.
- Avoid treating an unrelated inbound message as a response unless its thread or available message context supports that conclusion.

### Transactions

- Show itemized receipts, invoices, and refunds found in email. Do not calculate spend totals or estimate recurring charges in the first release.
- Show available merchant/sender, date, amount, currency, and source mailbox, with a link to the email.
- Mark uncertain or missing extracted values clearly. Do not invent amounts or currencies.
- Treat transaction details as sensitive data and keep the first version limited to information needed for this view.

## 5. Conversations requirements

The goal is a Gmail-style workspace for the project's supported workflows, not a promise of complete Gmail feature parity.

- List threads from all connected mailboxes or one selected mailbox.
- Default to the combined All accounts view and let the user filter to one mailbox.
- Open to the combined Inbox by default, with Gmail category tabs (Primary, Social, Promotions, Updates, and Forums where available). Provide other Gmail folders such as All Mail, Sent, Starred, Drafts, Spam, and Trash within the selected account scope.
- Browse folders, categories, and filters with paginated Gmail metadata reads so these views can reach conversations beyond the initial local sync baseline. Load another page as the user scrolls toward the end of the list and keep a manual Load more control available. Keep full message bodies on demand when a user opens a conversation; browsing metadata does not expand the initial body-cache or AI-report fetch limit.
- Show saved local conversation pages and valid encrypted message bodies immediately when the user opens a folder, searches, or opens a thread, then refresh Gmail metadata and any absent or expired bodies. Plain-text searches may first show partial matches from cached subjects and senders; advanced Gmail query syntax waits for Gmail. Gmail's full-mailbox results remain authoritative. Keep pagination behavior intact, label saved, refreshing, and stale results clearly, and do not re-download a message body while its cache is valid.
- Visually label each thread with its source mailbox.
- Search each connected Gmail mailbox on demand, including messages older than the local sync baseline. Fetch full message details when the user opens a result; show its source mailbox. Also support account/unread/starred/Gmail-label quick filters, thread reading, pagination, read/unread state, and starring. Show Gmail's result count as an estimate. Advanced date, sender, and attachment filters can follow later.
- Provide a density toggle for the Conversations list, defaulting to comfortable rows with more preview text.
- Open threads in a side-by-side reading pane on larger screens and a full-screen detail view on small screens.
- Display Gmail system labels and user labels where permissions allow.
- Show attachment names and available metadata, and let the user explicitly open or download an attachment from its message.
- Preview common PDF and image attachments inline when supported, with a Download option; use direct download for unsupported preview types.
- Automatically render only unambiguous, message-local cid: images whose owned attachment has a supported raster MIME type (GIF, JPEG, PNG, or WebP), using East's authenticated, owner-scoped inline attachment route when the user opens the conversation. SVG and other unsupported or active content do not render inline.
- Keep external message-body images blocked until the user selects an accessible per-message **Load external images** action. Require that click regardless of sender-authentication results or inbox classification. Before retaining a URL for that action, accept only absolute HTTP(S) URLs and reject malformed URLs, local-only hostnames, and literal loopback/private/reserved IP addresses. Do not resolve DNS or send a remote request before the click; set no-referrer before assigning remote image src.
- Treat message HTML as untrusted input: remove scripts, event handlers, and active embeds, and never execute or follow instructions supplied in a message body.
- Do not fetch attachment bytes during background sync or include attachment contents in AI analysis in the first Gmail-connected release. Opening a conversation authorizes only its matched local cid: image bytes; other attachments still require their separate explicit Preview, Download, or Forward action.
- Let users create, rename, delete, apply, and remove Gmail user labels; archive or move messages to trash; and mark messages as spam or not spam. Gmail system labels remain read-only.
- Let users select multiple conversations in the combined or per-account list and bulk archive, trash, mark read/unread, mark spam/not spam, or apply/remove labels. Show the selected count and report per-account/per-message failures.
- Compose new emails with To, CC, and BCC fields, and support Reply, Reply all, and Forward actions from Conversations. Include basic rich-text formatting for bold, italic, links, and lists. Forwarding includes the source message's attachments by default; show them in the draft and let the user remove them before sending. Users can attach any local file type, subject to the per-file, per-draft size, and attachment-count limits; East uses only a syntactically safe declared MIME type or `application/octet-stream`, and does not execute or preview these outgoing files. Gmail's own restrictions still apply when saving or sending. Keep app-local drafts encrypted until the user saves them to Gmail Drafts or deletes them. Do not expire them with the message-body cache. Keep attachment bytes encrypted while the draft is unsaved, then remove the local copy after save, successful send, or deletion.
- The user confirmed that assistant help drafting new emails and replies belongs in Phase 3. On an explicit request, create an editable app draft. A separate click saves it to Gmail Drafts; another explicit action is required to send it. Phase 2 provides manual composition and reviewed save/send only.
- Show the sending mailbox, To/CC/BCC recipients, subject, body, and selected attachment filenames in the composer and require explicit review and send actions for any outgoing email. New messages inherit the selected Conversations account and require an explicit sender choice when All accounts is selected; replies default to the thread's account. The user must confirm each specific send.
- The assistant may draft message text but never selects or adds attachments. New files must be chosen by the user; original attachments are included only when the user explicitly chooses Forward, and the user can remove them before sending.

## 6. Account and identity requirements

- Sign in with Google; store the stable Google OpenID subject as the external identity key rather than using an email address as a primary key.
- Keep the local app login across backend and app restarts using a database-backed server-side session with a 60-day persistence window. Encrypt session attributes with the local key; the random session ID remains a bearer lookup value in the local database under the loopback/same-OS threat model. Explicit logout ends the session immediately. Preserve HttpOnly, SameSite cookies, session-fixation protection, and CSRF checks.
- Encrypt locally retained secrets and content at rest with a random per-install key in an owner-only file on the local computer. East loads this key automatically at startup; it does not prompt for a passphrase or idle-lock while running.
- Keep East bound to localhost. The local key file protects against accidental exposure of database-only backups; anyone with access to the same operating-system account or local files can access East's data. Do not treat this setup as a boundary between users of the same computer account.
- If the local key file is lost or unusable, offer a clearly warned destructive reset that removes local mailbox links, credentials, cached mail, attachments, drafts, generated insights, and all persisted app sessions; the user must sign in again, reconnect mailboxes, and re-enter the AI provider key. Gmail mailbox contents are unaffected. Existing passphrase-encrypted installations may request their current passphrase once to migrate the key without deleting data.
- A signed-in user can connect multiple Gmail accounts, including accounts different from the one used to sign in.
- The initial local application supports one signed-in app user per installation; that user can connect multiple Gmail accounts.
- Store mailbox connections under the signed-in application user. Enforce user ownership in every API query and database relationship.
- Display a mailbox's current email address as returned by Google; do not assume a fixed address.
- Allow users to disconnect an account. Stop syncing it and automatically purge its local cached mailbox content, credentials, insights, unsaved app drafts, and staged outgoing attachments. Do not change saved Gmail content, including Gmail Drafts. Explain that disconnect removes East's local connection but does not revoke the Google OAuth grant; the user can revoke it in Google Account settings.
- Keep full message bodies only in an encrypted local cache. The default lifetime is 60 days and users can change it in settings. Remove cached bodies and credentials when a mailbox is disconnected or the user deletes their data.
- After a body expires, retain only minimal metadata needed for inbox/navigation (Gmail IDs, sender, subject, date, and labels). Use the same configurable 60-day default for snippets, attachment metadata and bytes, and source-derived content; purge them with their source cache. An explicit attachment re-fetch after expiry may start a new TTL for that attachment and its metadata.
- Background sync must not transfer attachment-part bytes. Cache incoming attachment bytes only after the user explicitly opens, downloads, or forwards a message containing them. Encrypt them for the user-configurable cache lifetime; remove them on mailbox disconnect or user-data deletion.
- Make reconnect/re-authorization states visible if Google access is revoked or expires.
- Sync connected accounts every 15 minutes while the app is running, and provide a manual refresh action.

## 7. Outgoing email safety

- The assistant may recommend or draft, but it cannot independently modify a Gmail mailbox.
- Every Gmail mailbox change, including labels, read state, starring, archiving, and trashing, requires a user click.
- A send action must be an explicit click by the user for the specific message after the user can inspect the sending account, recipient(s), subject, and body.
- No background job, summary generation, retry, or agent workflow may call Gmail's send endpoint.
- If Gmail draft or send permissions have not been granted, the assistant can present a draft in the application without implying it has been saved or sent in Gmail.
- A request for assistant help with a new email or reply creates an app draft only. Saving to Gmail Drafts and sending are separate user actions.

## 8. Initial scope and non-goals

### Initial scope

- A localhost visual prototype that starts on a login screen with demo sign-in only; demo sign-in and navigation open Dashboard and Conversations with clearly labeled fictional data. Google sign-in is added in Phase 2. Search, filters, labels, and mailbox actions are visual placeholders in this phase.
- Show East as the application name on the login screen and main app shell.
- Support light and dark visual themes using the Nord palette, following the operating system preference by default.
- Google sign-in and multiple Gmail mailbox connections in a later phase.
- Recent message sync and Gmail-style thread display.
- In the first Gmail-connected Conversations release: search, filtering, read/unread, labels, starring, archive, Move to Inbox, trash, Restore from Trash, Spam/Not spam, new-message composition with To/CC/BCC, basic rich-text formatting, and user-selected file attachments, Reply, Reply all, Forward, and user-confirmed sending.
- Dashboard summaries and evidence-linked insights after the Gmail data layer exists.
- Phase 3 includes optional assisted drafting for new emails and replies with human review, confirmed by the user on 2026-11-09.
- Optional model-powered summaries and extraction when the user configures a provider in local settings. The first release supports OpenAI and a user-configured OpenAI-compatible endpoint with the user's own API key; native integrations for other providers can be added later.

### Not in the initial scope

- Hosted deployment, public availability, or a multi-tenant hosted service.
- Full feature parity with Gmail or support for non-Gmail providers.
- Automatic sending, deleting, archiving, labeling, or other mailbox mutations by the assistant.
- Automatic attachment fetching or AI analysis of attachment contents, calendar integration, contact management, or advanced Gmail settings.
- Automatic processing of message content by a model when the user has not configured a provider and API key.
- Sending email content to a configured model provider or endpoint without identifying the destination and explaining its data processing/retention terms or, for custom endpoints, that those terms depend on the endpoint operator.

## 9. Product acceptance criteria

- A user can use demo sign-in and navigation to open Dashboard and Conversations in the skeleton phase.
- The login screen and application shell display the name East.
- The skeleton uses Nord palette roles in light and dark themes while preserving the Gmail-style Conversations structure.
- The skeleton shows clearly fictional sample accounts with distinct generated identities and colors, with combined and per-account content states represented in the prototype.
- Inbox category tabs, search, filters, labels, star, archive, trash, Spam/Not spam, compose, and send controls are visibly presented but do not change sample or Gmail data in the visual prototype.
- After OAuth integration, a user can sign in and connect more than one Gmail mailbox without developer changes or seeded addresses.
- Data and API responses for one signed-in user cannot expose another user's mailbox records.
- Initial sync fetches at most the configured first-run baseline of 30 recent messages per mailbox, records sync state, and can report partial failure per mailbox.
- Dashboard insights include a source mailbox and a path to inspect the supporting thread.
- With no AI provider configured, Dashboard shows a Configure AI prompt and no AI summary/insight cards, while Gmail sync and Conversations continue to work.
- Conversations shows available attachment metadata, previews supported PDFs/images inline, and lets a user download any attachment; background sync excludes attachment-part bytes. Opening a message or explicitly clicking an inline attachment may transiently receive other MIME part data because Gmail cannot select one `parts[]` entry in a partial response. East retains only message text during body hydration or the selected attachment during an attachment click, and does not send attachment contents to an AI provider in the first release.
- The Gmail-connected composer supports To/CC/BCC, Reply, Reply all, Forward, basic bold/italic/link/list formatting, and a review step showing sender, all recipients, subject, body, and attachments before send.
- Bulk mailbox actions apply only to conversations selected by the user and show which selected items succeeded or failed.
- No email can be sent except through a user-initiated, message-specific review and send flow.
