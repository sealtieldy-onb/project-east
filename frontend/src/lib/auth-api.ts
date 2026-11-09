import { getCsrfHeaders } from "./csrf";
import { isMailboxColorIndex, type MailboxColorIndex } from "./mailbox-colors";

export type ConnectedMailboxSummary = {
  id: string;
  email: string;
  displayName: string | null;
  colorIndex: MailboxColorIndex;
  reauthorizationRequired: boolean;
  syncStatus: MailboxSyncState;
  lastSyncAt: string | null;
};

export type MailboxSyncState = "pending" | "syncing" | "ready" | "needs_reauthorization" | "partial_failure" | "error";
type RawConnectedMailboxSummary = Omit<ConnectedMailboxSummary, "syncStatus"> & { syncStatus?: MailboxSyncState };

const mailboxSyncStates = new Set<MailboxSyncState>([
  "pending",
  "syncing",
  "ready",
  "needs_reauthorization",
  "partial_failure",
  "error",
]);

export type EastUser = {
  id: string;
  email: string;
  displayName: string | null;
  mailboxes: ConnectedMailboxSummary[];
};

export class AuthApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthApiError";
  }
}

const timeoutMs = 8_000;

async function request(path: string, method: "GET" | "POST" | "DELETE" = "GET", requestTimeoutMs = timeoutMs): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const headers = method === "GET" ? undefined : getCsrfHeaders();
    return await fetch(path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers,
    });
  } catch {
    throw new AuthApiError("The local sign-in service could not be reached. Try again when East is running.");
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isMailbox(value: unknown): value is RawConnectedMailboxSummary {
  if (!isObject(value)) return false;
  const syncStatus = value.syncStatus;
  return typeof value.id === "string"
    && typeof value.email === "string"
    && (typeof value.displayName === "string" || value.displayName === null)
    && isMailboxColorIndex(value.colorIndex)
    && typeof value.reauthorizationRequired === "boolean"
    && (syncStatus === undefined || (typeof syncStatus === "string" && mailboxSyncStates.has(syncStatus as MailboxSyncState)))
    && (typeof value.lastSyncAt === "string" || value.lastSyncAt === null);
}

function parseUser(value: unknown): EastUser | null {
  if (!isObject(value)
    || typeof value.id !== "string"
    || typeof value.email !== "string"
    || (typeof value.displayName !== "string" && value.displayName !== null)
    || !Array.isArray(value.mailboxes)) return null;
  if (!value.mailboxes.every(isMailbox)) return null;
  return {
    id: value.id,
    email: value.email,
    displayName: value.displayName,
    mailboxes: value.mailboxes.map((mailbox) => ({
      ...mailbox,
      syncStatus: mailbox.syncStatus ?? (mailbox.reauthorizationRequired
        ? "needs_reauthorization"
        : mailbox.lastSyncAt
          ? "ready"
          : "pending"),
    })),
  };
}

export async function getGoogleOAuthConfiguration(): Promise<boolean> {
  const response = await request("/api/v1/auth/configuration");
  if (!response.ok) throw new AuthApiError(`Google sign-in configuration was unavailable (HTTP ${response.status}).`);
  try {
    const payload: unknown = await response.json();
    if (typeof payload === "boolean") return payload;
    if (isObject(payload) && typeof payload.googleOAuthConfigured === "boolean") return payload.googleOAuthConfigured;
  } catch {
    // A non-JSON response is reported below as an invalid configuration.
  }
  throw new AuthApiError("The local sign-in service returned an invalid configuration response.");
}

export async function getCurrentEastUser(): Promise<EastUser | null> {
  const response = await request("/api/v1/me");
  if (response.status === 401) return null;
  if (!response.ok) throw new AuthApiError(`The current sign-in could not be loaded (HTTP ${response.status}).`);
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new AuthApiError("The local sign-in service returned an invalid user response.");
  }
  const user = parseUser(payload);
  if (!user) throw new AuthApiError("The local sign-in service returned an invalid user response.");
  return user;
}

export function startGoogleSignIn(): void {
  window.location.assign("/api/v1/auth/google/start");
}

export async function connectGoogleMailbox(): Promise<void> {
  const response = await request("/api/v1/mailboxes/connect", "POST");
  if (!response.ok) throw new AuthApiError(`Mailbox connection could not start (HTTP ${response.status}).`);
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new AuthApiError("The local sign-in service returned an invalid mailbox authorization response.");
  }
  const url = isObject(payload) && typeof payload.authorizationUrl === "string" ? payload.authorizationUrl : null;
  if (!url || !isTrustedGoogleAuthorizationUrl(url)) {
    throw new AuthApiError("The mailbox authorization response was invalid.");
  }
  window.location.assign(url);
}

export async function logoutGoogleUser(): Promise<void> {
  const response = await request("/api/v1/auth/logout", "POST");
  if (!response.ok) throw new AuthApiError(`Sign-out was rejected (HTTP ${response.status}).`);
}

export async function disconnectGoogleMailbox(mailboxId: string): Promise<void> {
  const safeMailboxId = encodeURIComponent(mailboxId);
  const response = await request(`/api/v1/mailboxes/${safeMailboxId}`, "DELETE");
  if (!response.ok) throw new AuthApiError(`Mailbox removal was rejected (HTTP ${response.status}).`);
}

export type MailboxSyncStatus = "SYNCED" | "IN_PROGRESS" | "FAILED" | "NOT_FOUND" | "LOCKED";

export type MailboxSyncResult = {
  status: MailboxSyncStatus;
  failureCode: string | null;
};

export async function syncGoogleMailbox(mailboxId: string): Promise<MailboxSyncResult> {
  const safeMailboxId = encodeURIComponent(mailboxId);
  const response = await request(`/api/v1/mailboxes/${safeMailboxId}/sync`, "POST", 120_000);
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new AuthApiError("The mailbox sync service returned an invalid response.");
  }
  const candidate = isObject(payload) && typeof payload.status === "string" ? payload.status : null;
  const rawFailureCode = isObject(payload) && typeof payload.failureCode === "string" ? payload.failureCode : null;
  const failureCode = rawFailureCode && /^(?:gmail_http_\d{3}_[A-Za-z0-9_.-]{1,64}|auth_http_\d{3}|sync_exception_[A-Za-z0-9_]{1,160}|sync_failed)$/.test(rawFailureCode)
    ? rawFailureCode
    : null;
  if (candidate === "SYNCED" || candidate === "IN_PROGRESS" || candidate === "FAILED"
      || candidate === "NOT_FOUND" || candidate === "LOCKED") {
    return { status: candidate, failureCode };
  }
  throw new AuthApiError(`The mailbox sync service returned an invalid response (HTTP ${response.status}).`);
}

function isTrustedGoogleAuthorizationUrl(value: string): boolean {
  try {
    const url = new URL(value, window.location.origin);
    return url.protocol === "https:" && url.hostname === "accounts.google.com";
  } catch {
    return false;
  }
}
