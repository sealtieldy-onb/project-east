import { getCsrfHeaders } from "./csrf";

export type MailAction =
  | "ARCHIVE"
  | "MOVE_TO_INBOX"
  | "TRASH"
  | "UNTRASH"
  | "MARK_READ"
  | "MARK_UNREAD"
  | "SPAM"
  | "NOT_SPAM"
  | "STAR"
  | "UNSTAR"
  | "ADD_LABELS"
  | "REMOVE_LABELS";

export type MailActionOutcome = {
  threadId: string;
  success: boolean;
  errorCode: string | null;
  gmailHttpStatus: number | null;
  googleErrorCode: string | null;
};

export type MailLabelRecord = {
  id: string;
  mailboxId: string;
  name: string;
  type: MailLabelType;
};

export type MailLabelType = "SYSTEM" | "USER";

export class MailActionsApiError extends Error {
  readonly status: number | null;

  constructor(message: string, status: number | null = null) {
    super(message);
    this.name = "MailActionsApiError";
    this.status = status;
  }
}

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

const labelRequestTimeoutMs = 12_000;

function safeErrorMessage(status: number): string {
  if (status === 401 || status === 403) return "Reconnect the affected mailbox, then try again.";
  if (status === 404) return "The thread, mailbox, or label is no longer available.";
  if (status === 423) return "Unlock East before changing mailbox data.";
  if (status === 409) return "A mailbox refresh or change is already in progress.";
  if (status >= 500) return "Gmail or the local mail service could not complete the change.";
  return "East rejected the mailbox change. Check your selection and try again.";
}

async function request(path: string, method: "PATCH" | "POST" | "DELETE", body?: unknown): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        ...getCsrfHeaders(),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch {
    throw new MailActionsApiError("The local mail service could not be reached. Check the connection and try again.");
  }

  if (!response.ok) throw new MailActionsApiError(safeErrorMessage(response.status), response.status);
  if (response.status === 204) return null;

  try {
    return await response.json() as unknown;
  } catch {
    throw new MailActionsApiError("The local mail service returned an invalid response.", response.status);
  }
}

function parseOutcome(value: unknown): MailActionOutcome | null {
  const candidate = object(value);
  if (!candidate || typeof candidate.threadId !== "string" || typeof candidate.success !== "boolean") return null;
  const optionalString = (entry: unknown) => entry === null || entry === undefined || typeof entry === "string" ? entry ?? null : undefined;
  const errorCode = optionalString(candidate.errorCode);
  const googleErrorCode = optionalString(candidate.googleErrorCode);
  const gmailHttpStatus = candidate.gmailHttpStatus === null || candidate.gmailHttpStatus === undefined
    ? null
    : typeof candidate.gmailHttpStatus === "number" && Number.isSafeInteger(candidate.gmailHttpStatus)
      ? candidate.gmailHttpStatus
      : undefined;
  if (errorCode === undefined || googleErrorCode === undefined || gmailHttpStatus === undefined) return null;
  return { threadId: candidate.threadId, success: candidate.success, errorCode, gmailHttpStatus, googleErrorCode };
}

function parseLabel(value: unknown): MailLabelRecord | null {
  const candidate = object(value);
  if (
    !candidate || typeof candidate.id !== "string" || !candidate.id ||
    typeof candidate.mailboxId !== "string" || !candidate.mailboxId ||
    typeof candidate.name !== "string" || !candidate.name
  ) return null;
  return { id: candidate.id, mailboxId: candidate.mailboxId, name: candidate.name, type: "USER" };
}

function parseListedLabel(value: unknown, mailboxId: string): MailLabelRecord | null {
  const candidate = object(value);
  if (
    !candidate || typeof candidate.id !== "string" || !candidate.id.trim() ||
    typeof candidate.name !== "string" || !candidate.name.trim() ||
    (candidate.type !== "SYSTEM" && candidate.type !== "USER") ||
    (candidate.color !== null && candidate.color !== undefined && typeof candidate.color !== "string")
  ) return null;
  return { id: candidate.id.trim(), mailboxId, name: candidate.name.trim(), type: candidate.type };
}

/** Lists the local label records for one mailbox and binds each result to that requested mailbox. */
export async function listMailboxLabels(mailboxId: string, signal?: AbortSignal): Promise<MailLabelRecord[]> {
  if (!mailboxId.trim()) throw new MailActionsApiError("Choose a connected mailbox to load its labels.");
  const controller = new AbortController();
  const abortFromCaller = () => controller.abort();
  if (signal?.aborted) controller.abort();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = window.setTimeout(() => controller.abort(), labelRequestTimeoutMs);

  let response: Response;
  try {
    response = await fetch(`/api/v1/mailboxes/${encodeURIComponent(mailboxId)}/labels`, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    });
  } catch {
    if (signal?.aborted) throw new DOMException("The request was cancelled.", "AbortError");
    throw new MailActionsApiError("The mailbox labels could not be loaded. Check the local connection and try again.");
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromCaller);
  }

  if (!response.ok) throw new MailActionsApiError(safeErrorMessage(response.status), response.status);
  let payload: unknown;
  try {
    payload = await response.json() as unknown;
  } catch {
    throw new MailActionsApiError("The mailbox label response is invalid.", response.status);
  }
  if (!Array.isArray(payload)) throw new MailActionsApiError("The mailbox label response is invalid.", response.status);
  const labels = payload.map((item) => parseListedLabel(item, mailboxId));
  if (labels.some((label) => label === null)) {
    throw new MailActionsApiError("The mailbox label response is invalid.", response.status);
  }
  return labels as MailLabelRecord[];
}

export async function updateThreadAction(
  threadId: string,
  action: MailAction,
  labelIds: string[] = [],
): Promise<void> {
  const safeId = encodeURIComponent(threadId);
  await request(`/api/v1/threads/${safeId}`, "PATCH", { action, labelIds });
}

export async function updateThreadsAction(
  threadIds: string[],
  action: MailAction,
  labelIds: string[] = [],
): Promise<MailActionOutcome[]> {
  const payload = object(await request("/api/v1/threads/bulk-actions", "POST", { threadIds, action, labelIds }));
  if (!payload || !Array.isArray(payload.outcomes)) {
    throw new MailActionsApiError("The bulk action response is invalid.");
  }
  const outcomes = payload.outcomes.map(parseOutcome);
  if (outcomes.some((outcome) => outcome === null) || outcomes.length !== threadIds.length) {
    throw new MailActionsApiError("The bulk action response is invalid.");
  }
  const parsed = outcomes as MailActionOutcome[];
  if (parsed.some((outcome, index) => outcome.threadId !== threadIds[index])) {
    throw new MailActionsApiError("The bulk response did not match the selected conversations.");
  }
  return parsed;
}

export async function createUserLabel(mailboxId: string, name: string): Promise<MailLabelRecord> {
  const safeMailboxId = encodeURIComponent(mailboxId);
  const label = parseLabel(await request(`/api/v1/mailboxes/${safeMailboxId}/labels`, "POST", { name }));
  if (!label) throw new MailActionsApiError("The created label response is invalid.");
  return label;
}

export async function renameUserLabel(mailboxId: string, labelId: string, name: string): Promise<MailLabelRecord> {
  const safeMailboxId = encodeURIComponent(mailboxId);
  const safeLabelId = encodeURIComponent(labelId);
  const label = parseLabel(await request(
    `/api/v1/mailboxes/${safeMailboxId}/labels/${safeLabelId}`,
    "PATCH",
    { name },
  ));
  if (!label) throw new MailActionsApiError("The updated label response is invalid.");
  return label;
}

export async function deleteUserLabel(mailboxId: string, labelId: string): Promise<void> {
  const safeMailboxId = encodeURIComponent(mailboxId);
  const safeLabelId = encodeURIComponent(labelId);
  await request(`/api/v1/mailboxes/${safeMailboxId}/labels/${safeLabelId}`, "DELETE");
}
