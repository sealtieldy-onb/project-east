# Local Google OAuth setup

East uses a Spring Boot web-server OAuth flow. Google client credentials stay in the local backend configuration; they are never included in the React bundle. Gmail account linking is a separate action from Google sign-in and will request the Gmail permissions only when the user chooses to connect an account.

## 1. Create OAuth credentials

1. In [Google Cloud Console](https://console.cloud.google.com/), create or select a project.
2. Enable the Gmail API for that project.
3. Configure the OAuth consent screen. If the app is **External** and remains in **Testing**, add each account you intend to use as a test user. Google currently limits Testing refresh tokens that include Gmail scopes to seven days, so development may require reconnecting accounts weekly.
   - Add the Gmail scope `https://www.googleapis.com/auth/gmail.modify` under the consent screen's data-access/scopes section. It is needed for the Phase 2 read, search, label, mailbox-action, draft, and user-confirmed send workflows.
4. Create an OAuth client with application type **Web application**.
5. For the current East preview, add this exact authorized redirect URI:

   ```text
   http://localhost:8081/login/oauth2/code/google
   ```

6. Copy the generated client ID and client secret into the root `.env` file. Do not paste them into frontend code, documentation, or chat.

East uses `localhost` consistently for the browser origin and OAuth callback. Do not register one hostname and browse the app using the other (`127.0.0.1`), because cookies and OAuth redirect matching are host-sensitive. If you change `EAST_SERVER_PORT`, update the `.env` redirect URI and register `http://localhost:<EAST_SERVER_PORT>/login/oauth2/code/google` as the authorized redirect URI in Google Cloud. East disables Google sign-in when the redirect URI does not match its actual local server port.

For the current East preview, port 8080 is occupied by another local service, so the backend runs on port 8081. The protected `.env` uses `EAST_SERVER_PORT=8081` and the matching localhost callback, and the user saved that redirect in Google Cloud Console. Sign-in has since succeeded with this configuration. If the backend port changes later, update both the local configuration and Google Console before signing in again.

## 2. Fill in the local environment file

Copy `.env.example` to `.env` (a local `.env` is already provided in this workspace), then set:

```dotenv
EAST_GOOGLE_CLIENT_ID=your-client-id
EAST_GOOGLE_CLIENT_SECRET=your-client-secret
EAST_SERVER_PORT=8081
EAST_GOOGLE_REDIRECT_URI=http://localhost:8081/login/oauth2/code/google
```

Protect the file so only your operating-system user can read it:

```sh
chmod 600 .env
```

East's Compose PostgreSQL service is published on `127.0.0.1:15432` by default. Set `EAST_DB_PORT` in `.env` and keep it consistent with the port exposed by Docker Compose if you need to move it.

The root `.gitignore` excludes `.env`; `.env.example` contains placeholders and is safe to share. East creates an encryption key at `~/.east/vault.key` by default, with owner-only permissions on POSIX systems; an optional `EAST_VAULT_KEY_FILE` setting can select another protected path. Enter an OpenAI API key through East settings; East encrypts it in its local vault. The key file is accessible to the same operating-system account, so localhost hosting does not protect data from other processes running as that account.

## 3. Requested Google permissions

Google sign-in uses the OpenID Connect scopes `openid`, `email`, and `profile`. Linking a Gmail account is a separate authorization flow. Phase 2 requests `https://www.googleapis.com/auth/gmail.modify`, which covers the selected Gmail reading, search, labeling, mailbox-management, draft, and send operations. The application still requires an explicit user action for every mailbox change and a separate message-specific confirmation before sending.

Google classifies `gmail.modify` as a restricted scope. OAuth consent-screen testing is appropriate for local development, but it may restrict access to listed test users and Gmail refresh tokens may expire after seven days. Public distribution can require OAuth verification and a security assessment depending on how restricted data is processed. Review Google's [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [OAuth Testing and production policies](https://developers.google.com/identity/protocols/oauth2/policies), [OAuth verification requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification), and [Workspace user-data policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy) before sharing East beyond personal local development.

## References

- [Google OAuth for web-server applications](https://developers.google.com/identity/protocols/oauth2/web-server)
- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [Google OAuth best practices](https://developers.google.com/identity/protocols/oauth2/resources/best-practices)
