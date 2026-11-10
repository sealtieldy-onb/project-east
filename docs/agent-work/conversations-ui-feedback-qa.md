# Conversations UI feedback QA

Date: 2026-11-10

## Baseline audit

Reviewed the six annotated UI comments against the screenshots and current `fix/conversations-ui-feedback` source. The screenshots were captured from the older `feature/phase-2-acceptance` checkout, which predates the current `main` baseline.

| Comment | Baseline finding | Severity |
| --- | --- | --- |
| Active folder contrast | `.conversations-folder-button.is-active` uses `--accent-ink` (`#2e3440`) in both themes. In dark mode this is too dark against the active row background; the theme's `--text` token is light. | P2 |
| Label spacing | Label name and account email are rendered on one row. The account text can consume most of the narrow folder rail, leaving label names heavily ellipsized. | P2 |
| Sender picture | Conversation rows and message headers render only sender initials. No sender-photo lookup or image field exists in the Gmail message model. | P3 / capability request |
| Mailbox picker | The visible custom-looking trigger overlays a native `<select>`, so opening it still shows the browser's native select menu. | P2 |
| Message HTML layout | The renderer applies `display: block` to tables and a border to every `td`/`th`. This exposes layout-table cell boundaries and changes normal table layout, matching the nested boxes in the screenshot. | P2 |
| External image control | Current `main` includes a per-message “Load external images” button when sanitized HTML contains `data-east-external-src`; remote URLs remain inert until the click. The screenshots came from the older checkout, which lacked this component. The control is conditional, so messages without valid remote image URLs do not show it. | P2 if a remote image is present but no control appears; otherwise expected |

## Checks run

- Read-only source audit of `Conversations.tsx`, `conversations.css`, `MailHtmlSanitizer.java`, and the focused sanitizer/image tests.
- `mvn -q -f backend/pom.xml -Dtest=MailHtmlSanitizerTest,MailReadServiceInlineImageTest test` — passed: 5 tests, 0 failures/errors/skips.
- No Gmail mutations, sends, or external image requests were performed.

## Final retest

Reviewed the updated `Conversations.tsx` and `conversations.css` on `fix/conversations-ui-feedback`:

- **Comment 1:** the active folder now uses `--text`. In the dark palette this resolves to the light `#eceff4` foreground instead of the dark `--accent-ink`; the selected row keeps its accent-soft background. A read-only browser style check confirmed the light foreground over the selected dark row. Compositing the 14% accent background over the dark surface gives an approximate **6.72:1** text contrast ratio, above WCAG AA's 4.5:1 threshold for normal text.
- **Comment 2:** label names and account display names now occupy separate rows, with a 38px minimum row height and 4px inter-row gap. The account identity no longer competes with the label name on the same line.
- **Comment 3:** sender photos remain unsupported; the Gmail message model supplies sender name/email only. Looking up arbitrary sender photos through Google People would require enabling the API and requesting broader `contacts.readonly` consent, so the existing initials remain. See the official [People API `people.get` reference](https://developers.google.com/people/api/rest/v1/people/get) and [Google OAuth scopes](https://developers.google.com/identity/protocols/oauth2/scopes).
- **Comment 4:** both mailbox-scope controls now render as buttons with an ARIA menu and radio menu items. Browser interaction opened each menu (one menu, three options); the heading menu also moved focus with End, Home, and ArrowDown. Escape closed both menus. No account selection was changed.
- **Comment 5:** the email HTML body now has one outer border; tables retain native table layout and cell borders are removed. A read-only DOM style check of the user-selected message found one body wrapper with a border, 43 tables with native table display, and no per-cell borders. No message content was copied into this report.
- **Comment 6:** `MessageBodyContent` already exposes a per-message **Load external images** control when sanitized HTML contains a permitted `data-east-external-src`; the images remain blocked until that click, as required by the product plan regardless of sender verification. In the user-selected message, the sanitized DOM had zero image elements and zero external-image attributes, so the control was correctly absent for that message. The browser check did not request any image URL. This does not establish whether image references were absent in the original Gmail HTML or were represented using markup that the sanitizer removes; that case remains a follow-up if the user expected images in this message.

### Final checks

- `npm run build` — passed; TypeScript compiled and Vite transformed 2,081 modules.
- `git diff --check` — passed.
- `mvn -q -f backend/pom.xml -Dtest=MailHtmlSanitizerTest,MailReadServiceInlineImageTest test` — passed: 5 tests, 0 failures/errors/skips. These are synthetic sanitizer/image tests; backend sources were unchanged by this UI patch.
- Read-only browser interaction verified both custom mailbox menus open, keyboard focus moves across heading-menu options, and Escape closes them. The app continued to show its signed-in Conversations view after the backend restart. No Gmail mutation/send was attempted and no external image request was made.

### Limitations

- The live-message check inspected only DOM counts and computed styles; it did not copy message text or inspect source content. The selected message contained no rendered image tags, so the **Load external images** flow was not exercised in the browser. Synthetic sanitizer tests cover that flow's safe URL handling and CID mapping.
- No fixture-based HTML email screenshot was available to compare the revised table layout pixel-for-pixel. The checked DOM confirms table layout and border rules changed as intended.
