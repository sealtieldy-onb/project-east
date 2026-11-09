# Google sign-in UI scratchpad

## Objective

Add a Google sign-in action to the existing login page only when the parent reports OAuth is configured. Preserve the existing demo sign-in flow and keep OAuth initiation in the parent/backend integration.

## Status

Implemented. `npm run build` in `frontend/` passed (`tsc -b` and Vite production bundling; exit code 0, 2,066 modules transformed).

## Component contract

- `googleOAuthConfigured?: boolean` — parent sets this from the backend's OAuth configuration/status. Defaults to `false`.
- `onGoogleSignIn?: () => void` — parent callback that begins its Google OAuth flow.
- The Google control renders only when both the boolean is `true` and the callback exists; it does not show a disabled or coming-soon button otherwise.
- Existing `onDemoSignIn: () => void` stays required and its demo button/action are unchanged.

## Files

- `frontend/src/components/LoginPage.tsx`
- `frontend/src/components/login-page.css`
- `docs/agent-work/google-login-ui.md`

## Safety and product behavior

- The page contains no credential fields, tokens, or account addresses.
- The component makes no network request and does not implement OAuth; the supplied callback owns that action.
- Demo mode remains available when OAuth is configured or unavailable.

## Verification

- `npm run build` in `frontend/` — passed.

## Handoff

Parent can pass `googleOAuthConfigured` from backend auth/config status and supply `onGoogleSignIn` to initiate the OAuth redirect.
