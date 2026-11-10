# East

East is a local email workspace for bringing multiple Gmail accounts together. Use **Conversations** to browse, search, read, and manage mail, and the **Dashboard** to review source-linked reports about replies, action items, and transactions. East runs on your computer and is available through `localhost` only.

Google sign-in and Gmail access are separate steps. Gmail changes and sends require explicit user actions. Dashboard reports and AI-assisted drafts disclose the provider and the content being sent before processing; daily reports are optional. A fictional demo workspace is available without connecting Google or an AI provider.

## Install and run

### Requirements

- Java 21
- Maven 3.9 or newer
- Docker Compose
- Node.js 22.12 or newer and npm
- A Google account for connected Gmail use
- An AI provider API key only if you want reports or AI-assisted draft writing

### 1. Create a Google Cloud project

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create a project for your local East installation, or select an existing development project.
2. In **APIs & Services → Library**, find **Gmail API** and select **Enable**.
3. Open **Google Auth Platform → Branding**. Set the app name to `East`, choose a user-support email, and enter a developer contact email.
4. Open **Google Auth Platform → Audience**. Choose **External** for personal Google accounts. Choose **Internal** only when the app is restricted to people in your own Google Workspace organization. For local development, keep the publishing status at **Testing** and add each account you will use under **Test users**.
5. Open **Google Auth Platform → Data Access**. Keep the sign-in scopes `openid`, `email`, and `profile`, and add the Gmail scope:

   ```text
   https://www.googleapis.com/auth/gmail.modify
   ```

   East uses this Gmail permission for the connected mailbox features. Google classifies it as a restricted scope; review the current [Gmail API scope list](https://developers.google.com/workspace/gmail/api/auth/scopes).
6. Open **Google Auth Platform → Clients → Create client**. Choose **Web application** and add this exact **Authorized redirect URI**:

   ```text
   http://localhost:8080/login/oauth2/code/google
   ```

   East uses a server-side OAuth flow, so an authorized JavaScript origin is not needed for this local setup.
7. Save the client. Copy its client ID and client secret into the local `.env` file described below. Never put either value in frontend code or commit it.

An External app in Testing can be used only by its listed test users. Google's current limit is 100 test users, and test-user authorizations expire after seven days, so you may need to connect the Gmail account again during development. See Google's [audience and testing guidance](https://support.google.com/cloud/answer/15549945) and [OAuth web-server flow documentation](https://developers.google.com/identity/protocols/oauth2/web-server).

For local development, leave the app in Testing. Publishing or distributing an app that requests Gmail restricted scopes has additional Google verification requirements; review the current [restricted-scope verification requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification) before sharing it with other users.

### 2. Configure the local environment

If you do not already have a local `.env`, copy the example and protect it:

```sh
cp .env.example .env
chmod 600 .env
```

Edit `.env` and set the Google client ID and secret, a unique local PostgreSQL password, and the matching callback:

```dotenv
EAST_GOOGLE_CLIENT_ID=your-client-id
EAST_GOOGLE_CLIENT_SECRET=your-client-secret
EAST_SERVER_PORT=8080
EAST_GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google
EAST_DB_PASSWORD=choose-a-local-password
```

The callback must match the URI in Google Cloud exactly. If you change `EAST_SERVER_PORT`, update both the callback in `.env` and the authorized redirect URI in Google Cloud Console, and use the same port in the backend startup command below. Use `localhost` consistently in the browser and callback; do not switch to `127.0.0.1` for the browser session.

The root `.gitignore` excludes `.env`. East stores its random local encryption key at `~/.east/vault.key` with owner-only permissions on POSIX systems. Enter an AI provider key through East **Settings**; do not put it in `.env`.

### 3. Start PostgreSQL and the application

Start PostgreSQL from the project root:

```sh
docker compose up -d postgres
```

In one terminal, start the backend from the project root:

```sh
cd backend
EAST_SERVER_PORT=8080 EAST_GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google mvn spring-boot:run
```

In another terminal, start the frontend:

```sh
cd frontend
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). PostgreSQL is published on loopback port `15432` by default. See the [local run guide](docs/run-local.md) for port changes, backup and restore instructions, and other operational details.

## How to use East

1. At the sign-in screen, choose the fictional demo workspace to explore sample data, or sign in with Google using an account listed as a test user in Google Cloud.
2. In the connected workspace, choose **Connect mailbox** or **Add account** on the Dashboard and authorize Gmail. This is separate from signing in to East; connect each mailbox you want to use.
3. Use **Conversations** to browse folders, search mail, read threads, manage labels, and compose or reply. Review each change before applying it. Sending always requires a separate review and explicit send action.
4. Use the **Dashboard** to review existing reports or request a new one. Choose the mailbox and date range, review the eligible-message count and provider disclosure, then confirm generation. Loading the Dashboard alone does not send email content to an AI provider.
5. To enable reports or AI-assisted draft writing, open **Settings** and configure a supported provider, model, and API key. AI-assisted text is kept as a local draft until you explicitly save it to Gmail; sending still requires a separate send action. Daily reports are optional and can be configured in Settings.

Gmail and Conversations remain available without an AI provider. East keeps cached sensitive data encrypted locally. Email text goes to the configured AI endpoint only after the relevant report or draft disclosure and user confirmation, or when the user enables scheduled daily reports.
