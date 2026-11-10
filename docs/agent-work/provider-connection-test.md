# AI provider connection test

## Objective

Add an explicit action in Settings to check the selected OpenAI or OpenAI-compatible endpoint, model, and API key. The check uses a fixed structured prompt with no mailbox content, and does not persist unsaved settings or credentials.

## Implementation

- Added `POST /api/v1/settings/ai-provider/test-connection`, authenticated and CSRF-protected by the existing local API security policy. The request takes the current provider, endpoint, model, and an optional key. If the key is omitted, the backend decrypts the saved key; it never returns a key.
- The provider adapter sends only a short connection-check prompt with a one-boolean structured response contract. It uses the same endpoint restrictions, request/response limits, and provider transport as report processing. Request and response byte buffers and decrypted key buffers are cleared after the call.
- Provider errors are mapped to fixed safe messages. Provider response bodies, credentials, and mailbox content are not returned or logged.
- Added an accessible Settings button and success/error status. The check preserves unsaved form values so the user can save them afterward; editing provider fields clears the previous result. Its request timeout is bounded to cover the adapter's connection/read timeouts.
- Updated the product, technical, roadmap, and decision-register docs.

## Files

- `backend/src/main/java/com/east/app/ai/AIProviderClient.java`
- `backend/src/main/java/com/east/app/settings/AIProviderConnectionTestRequest.java`
- `backend/src/main/java/com/east/app/settings/AIProviderConnectionTestResponse.java`
- `backend/src/main/java/com/east/app/settings/SettingsController.java`
- `backend/src/main/java/com/east/app/settings/SettingsService.java`
- `frontend/src/components/SettingsDialog.tsx`
- `frontend/src/lib/settings-api.ts`
- Product, technical, roadmap, decision, and progress documentation.

## Verification

- `mvn -o -q -DskipTests compile -f backend/pom.xml` — passed.
- `npm run build --prefix frontend` — passed; Vite transformed 2,081 modules.
- `git diff --check` — passed.
- No tests were run, and no model provider was contacted during implementation.

## Security notes

- The endpoint is called only from the explicit button action.
- A new key is sent once to the local backend and then to the selected provider, without saving it. A blank key uses the saved encrypted value if one exists.
- Test prompts never include mail, mailbox identity, or other user content.
- No credentials or provider response bodies are recorded in this note.

## Review follow-up

- Independent review found the new decision row above the decision-log table header. Moved it below the header and separator; implementation remains unchanged. No tests were run for this documentation-only correction.
