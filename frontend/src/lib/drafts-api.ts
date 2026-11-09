import { getCsrfHeaders } from "./csrf";

export type DraftKind = "NEW" | "REPLY" | "REPLY_ALL" | "FORWARD";
export type DraftState = "LOCAL" | "SAVED_TO_GMAIL" | "SAVE_PENDING" | "SEND_PENDING";
export type DraftReconciliationOperation = "SAVE_TO_GMAIL" | "SEND";
export type DraftReconciliationResolution = "CONFIRMED_COMPLETED" | "CONFIRMED_NOT_COMPLETED";
export const draftReconciliationErrorCode = "DRAFT_RECONCILIATION_REQUIRED";

export type DraftAttachment = {
  id: string | null;
  filename: string;
  mimeType: string | null;
  byteSize: number;
};

export type DraftListItem = {
  id: string;
  mailboxId: string;
  kind: DraftKind;
  state: DraftState;
  sourceThreadId: string | null;
  attachmentCount: number;
  createdAt: string;
  updatedAt: string;
  savedAt: string | null;
  reconciliationOperation: DraftReconciliationOperation | null;
  reconciliationStartedAt: string | null;
};

export type DraftReconciliationStatus = {
  draftId: string;
  required: boolean;
  inProgress: boolean;
  operation: DraftReconciliationOperation | null;
  state: DraftState;
  previousState: "LOCAL" | "SAVED_TO_GMAIL" | null;
  startedAt: string | null;
};

export type DraftReconciliationResponse = {
  draftId: string;
  state: "LOCAL" | "SAVED_TO_GMAIL" | "SENT";
  operation: DraftReconciliationOperation;
  resolvedAt: string;
};

export type DraftRecord = {
  id: string;
  mailboxId: string;
  kind: DraftKind;
  state: DraftState;
  sourceThreadId: string | null;
  sourceMessageId: string | null;
  to: string[];
  cc: string[];
  bcc: string[];
  subject: string;
  plainTextBody: string;
  richTextHtml: string;
  attachments: DraftAttachment[];
  createdAt: string;
  updatedAt: string;
  savedAt: string | null;
  fromAddress: string | null;
  reviewFingerprint: string | null;
};

export type DraftWritePayload = {
  mailboxId: string;
  kind: DraftKind;
  sourceThreadId: string | null;
  sourceMessageId: string | null;
  to: string[];
  cc: string[];
  bcc: string[];
  subject: string;
  plainTextBody: string;
  richTextHtml: string;
};

export type DraftSaveResponse = {
  id: string;
  state: "SAVED_TO_GMAIL";
  gmailDraftId: string;
  savedAt: string;
};

export class DraftsApiError extends Error {
  readonly status: number | null;
  readonly errorCode: string | null;

  constructor(message: string, status: number | null = null, errorCode: string | null = null) {
    super(message);
    this.name = "DraftsApiError";
    this.status = status;
    this.errorCode = errorCode;
  }
}

const requestTimeoutMs = 15_000;
const uploadTimeoutMs = 90_000;

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function safeErrorMessage(status: number, errorCode: string | null = null): string {
  if (errorCode === draftReconciliationErrorCode) {
    return "Gmail may have accepted this action, but East could not confirm the draft state. Check Gmail Sent and Drafts before retrying.";
  }
  if (status === 401 || status === 403) return "Reconnect the selected mailbox, then try again.";
  if (status === 404) return "This draft or attachment is no longer available.";
  if (status === 409) return "The draft or source message changed. Reload it and review it again.";
  if (status === 413) return "The selected file is too large. Choose a smaller attachment.";
  if (status === 423) return "Unlock East before working with drafts.";
  if (status === 502) return "Gmail could not complete that draft action. Check Gmail before retrying.";
  if (status >= 500) return "The local draft service could not complete that action.";
  return "East rejected that draft action. Check the fields and try again.";
}

async function errorFromResponse(response: Response): Promise<DraftsApiError> {
  let errorCode: string | null = null;
  try {
    const payload = object(await response.json());
    errorCode = typeof payload?.errorCode === "string" ? payload.errorCode : null;
  } catch {
    // Only a known, sanitized error code changes the client message.
  }
  return new DraftsApiError(safeErrorMessage(response.status, errorCode), response.status, errorCode);
}

function abortError(): DOMException {
  return new DOMException("The request was cancelled.", "AbortError");
}

async function request(
  path: string,
  method: "GET" | "POST" | "PUT" | "DELETE",
  body?: unknown,
  signal?: AbortSignal,
): Promise<unknown> {
  const controller = new AbortController();
  const abortFromCaller = () => controller.abort();
  if (signal?.aborted) controller.abort();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = window.setTimeout(() => controller.abort(), requestTimeoutMs);

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: {
        ...(method === "GET" ? {} : getCsrfHeaders()),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch {
    if (signal?.aborted) throw abortError();
    throw new DraftsApiError("The local draft service could not be reached. Check the connection and try again.");
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromCaller);
  }

  if (!response.ok) throw await errorFromResponse(response);
  if (response.status === 204) return null;
  try {
    return await response.json() as unknown;
  } catch {
    throw new DraftsApiError("The local draft service returned an invalid response.", response.status);
  }
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function nullableString(value: unknown): string | null | undefined {
  if (value === null || value === undefined) return null;
  return typeof value === "string" ? value : undefined;
}

function parseKind(value: unknown): DraftKind | null {
  return value === "NEW" || value === "REPLY" || value === "REPLY_ALL" || value === "FORWARD" ? value : null;
}

function parseState(value: unknown): DraftState | null {
  return value === "LOCAL" || value === "SAVED_TO_GMAIL" || value === "SAVE_PENDING" || value === "SEND_PENDING" ? value : null;
}

function parseReconciliationOperation(value: unknown): DraftReconciliationOperation | null {
  return value === "SAVE_TO_GMAIL" || value === "SEND" ? value : null;
}

function parseStringArray(value: unknown): string[] | null {
  if (value === null || value === undefined) return [];
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== "string")) return null;
  return value as string[];
}

function parseAttachment(value: unknown): DraftAttachment | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nullableString(candidate.id);
  const mimeType = nullableString(candidate.mimeType);
  const filename = nonEmptyString(candidate.filename);
  const byteSize = candidate.byteSize;
  if (
    id === undefined || mimeType === undefined || !filename ||
    typeof byteSize !== "number" || !Number.isSafeInteger(byteSize) || byteSize < 0
  ) return null;
  return { id, filename, mimeType, byteSize };
}

function parseAttachments(value: unknown): DraftAttachment[] | null {
  if (!Array.isArray(value)) return null;
  const attachments = value.map(parseAttachment);
  return attachments.some((attachment) => attachment === null) ? null : attachments as DraftAttachment[];
}

function parseDraftListItem(value: unknown): DraftListItem | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id);
  const mailboxId = nonEmptyString(candidate.mailboxId);
  const kind = parseKind(candidate.kind);
  const state = parseState(candidate.state);
  const sourceThreadId = nullableString(candidate.sourceThreadId);
  const createdAt = nonEmptyString(candidate.createdAt);
  const updatedAt = nonEmptyString(candidate.updatedAt);
  const savedAt = nullableString(candidate.savedAt);
  const rawOperation = candidate.reconciliationOperation;
  const reconciliationOperation = rawOperation === null || rawOperation === undefined
    ? null
    : parseReconciliationOperation(rawOperation);
  const reconciliationStartedAt = nullableString(candidate.reconciliationStartedAt);
  const attachmentCount = candidate.attachmentCount;
  if (
    !id || !mailboxId || !kind || !state || sourceThreadId === undefined || !createdAt || !updatedAt || savedAt === undefined ||
    (rawOperation !== null && rawOperation !== undefined && !reconciliationOperation) || reconciliationStartedAt === undefined ||
    typeof attachmentCount !== "number" || !Number.isSafeInteger(attachmentCount) || attachmentCount < 0
  ) return null;
  return { id, mailboxId, kind, state, sourceThreadId, attachmentCount, createdAt, updatedAt, savedAt, reconciliationOperation, reconciliationStartedAt: reconciliationStartedAt ?? null };
}

function parseReconciliationStatus(value: unknown, expectedDraftId: string): DraftReconciliationStatus {
  const candidate = object(value);
  const draftId = nonEmptyString(candidate?.draftId);
  const required = candidate?.required;
  const inProgress = candidate?.inProgress;
  const operationValue = candidate?.operation;
  const operation = operationValue === null || operationValue === undefined ? null : parseReconciliationOperation(operationValue);
  const state = parseState(candidate?.state);
  const rawPreviousState = candidate?.previousState;
  const previousState = rawPreviousState === null || rawPreviousState === undefined
    ? null
    : rawPreviousState === "LOCAL" || rawPreviousState === "SAVED_TO_GMAIL" ? rawPreviousState : undefined;
  const startedAt = nullableString(candidate?.startedAt);
  if (
    draftId !== expectedDraftId || typeof required !== "boolean" || typeof inProgress !== "boolean" || !state ||
    (operationValue !== null && operationValue !== undefined && !operation) ||
    previousState === undefined || startedAt === undefined ||
    (required && (
      !operation || !previousState || !startedAt ||
      (operation === "SAVE_TO_GMAIL" && state !== "SAVE_PENDING") ||
      (operation === "SEND" && state !== "SEND_PENDING")
    )) ||
    (!required && (inProgress || operation !== null || previousState !== null || startedAt !== null || state === "SAVE_PENDING" || state === "SEND_PENDING"))
  ) throw new DraftsApiError("The draft reconciliation status is invalid.");
  return { draftId, required, inProgress, operation, state, previousState, startedAt };
}

function parseReconciliationResponse(value: unknown, expectedDraftId: string): DraftReconciliationResponse {
  const candidate = object(value);
  const draftId = nonEmptyString(candidate?.draftId);
  const state = candidate?.state;
  const operation = parseReconciliationOperation(candidate?.operation);
  const resolvedAt = nonEmptyString(candidate?.resolvedAt);
  if (
    draftId !== expectedDraftId || (state !== "LOCAL" && state !== "SAVED_TO_GMAIL" && state !== "SENT") ||
    !operation || !resolvedAt
  ) throw new DraftsApiError("The draft reconciliation response is invalid.");
  return { draftId, state, operation, resolvedAt };
}

function parseDraft(value: unknown): DraftRecord | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id);
  const mailboxId = nonEmptyString(candidate.mailboxId);
  const kind = parseKind(candidate.kind);
  const state = parseState(candidate.state);
  const sourceThreadId = nullableString(candidate.sourceThreadId);
  const sourceMessageId = nullableString(candidate.sourceMessageId);
  const to = parseStringArray(candidate.to);
  const cc = parseStringArray(candidate.cc);
  const bcc = parseStringArray(candidate.bcc);
  const subject = nullableString(candidate.subject);
  const plainTextBody = nullableString(candidate.plainTextBody);
  const richTextHtml = nullableString(candidate.richTextHtml);
  const attachments = parseAttachments(candidate.attachments);
  const createdAt = nonEmptyString(candidate.createdAt);
  const updatedAt = nonEmptyString(candidate.updatedAt);
  const savedAt = nullableString(candidate.savedAt);
  const fromAddress = nullableString(candidate.fromAddress);
  const reviewFingerprint = nullableString(candidate.reviewFingerprint);
  if (
    !id || !mailboxId || !kind || !state || sourceThreadId === undefined || sourceMessageId === undefined ||
    !to || !cc || !bcc || subject === undefined || plainTextBody === undefined || richTextHtml === undefined || !attachments ||
    !createdAt || !updatedAt || savedAt === undefined || fromAddress === undefined || reviewFingerprint === undefined
  ) return null;
  return {
    id, mailboxId, kind, state, sourceThreadId, sourceMessageId, to, cc, bcc,
    subject: subject ?? "", plainTextBody: plainTextBody ?? "", richTextHtml: richTextHtml ?? "", attachments,
    createdAt, updatedAt, savedAt, fromAddress, reviewFingerprint,
  };
}

function requireDraft(value: unknown): DraftRecord {
  const draft = parseDraft(value);
  if (!draft) throw new DraftsApiError("The draft response is invalid.");
  return draft;
}

export async function listDrafts(signal?: AbortSignal): Promise<DraftListItem[]> {
  const value = await request("/api/v1/drafts", "GET", undefined, signal);
  if (!Array.isArray(value)) throw new DraftsApiError("The draft list response is invalid.");
  const drafts = value.map(parseDraftListItem);
  if (drafts.some((draft) => draft === null)) throw new DraftsApiError("The draft list response is invalid.");
  return drafts as DraftListItem[];
}

export async function getDraft(draftId: string, signal?: AbortSignal): Promise<DraftRecord> {
  return requireDraft(await request(`/api/v1/drafts/${encodeURIComponent(draftId)}`, "GET", undefined, signal));
}

export async function getDraftReconciliationStatus(draftId: string, signal?: AbortSignal): Promise<DraftReconciliationStatus> {
  const value = await request(`/api/v1/drafts/${encodeURIComponent(draftId)}/reconciliation`, "GET", undefined, signal);
  return parseReconciliationStatus(value, draftId);
}

export async function resolveDraftReconciliation(
  draftId: string,
  resolution: DraftReconciliationResolution,
  gmailDraftId?: string,
): Promise<DraftReconciliationResponse> {
  return parseReconciliationResponse(
    await request(`/api/v1/drafts/${encodeURIComponent(draftId)}/reconciliation`, "POST", {
      confirmed: true,
      resolution,
      ...(gmailDraftId ? { gmailDraftId } : {}),
    }),
    draftId,
  );
}

export async function createDraft(payload: DraftWritePayload): Promise<DraftRecord> {
  return requireDraft(await request("/api/v1/drafts", "POST", payload));
}

export async function updateDraft(draftId: string, payload: DraftWritePayload): Promise<DraftRecord> {
  return requireDraft(await request(`/api/v1/drafts/${encodeURIComponent(draftId)}`, "PUT", payload));
}

export async function deleteDraft(draftId: string): Promise<void> {
  await request(`/api/v1/drafts/${encodeURIComponent(draftId)}`, "DELETE");
}

export async function addDraftAttachment(draftId: string, file: File): Promise<DraftAttachment> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), uploadTimeoutMs);
  const form = new FormData();
  form.append("file", file, file.name);
  let response: Response;
  try {
    response = await fetch(`/api/v1/drafts/${encodeURIComponent(draftId)}/attachments`, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: getCsrfHeaders(),
      body: form,
      signal: controller.signal,
    });
  } catch {
    throw new DraftsApiError("The selected file could not be staged in the local draft.");
  } finally {
    window.clearTimeout(timeoutId);
  }
  if (!response.ok) throw await errorFromResponse(response);
  let value: unknown;
  try {
    value = await response.json() as unknown;
  } catch {
    throw new DraftsApiError("The attachment response is invalid.", response.status);
  }
  const attachment = parseAttachment(value);
  if (!attachment || !attachment.id) throw new DraftsApiError("The attachment response is invalid.", response.status);
  return attachment;
}

export async function deleteDraftAttachment(draftId: string, attachmentId: string): Promise<void> {
  await request(
    `/api/v1/drafts/${encodeURIComponent(draftId)}/attachments/${encodeURIComponent(attachmentId)}`,
    "DELETE",
  );
}

function parseSaveResponse(value: unknown, expectedId: string): DraftSaveResponse {
  const candidate = object(value);
  const id = nonEmptyString(candidate?.id);
  const gmailDraftId = nonEmptyString(candidate?.gmailDraftId);
  const savedAt = nonEmptyString(candidate?.savedAt);
  if (id !== expectedId || candidate?.state !== "SAVED_TO_GMAIL" || !gmailDraftId || !savedAt) {
    throw new DraftsApiError(
      "Gmail may have accepted the save, but East could not read its confirmation. Check Gmail Drafts before retrying.",
      null,
      draftReconciliationErrorCode,
    );
  }
  return { id, state: "SAVED_TO_GMAIL", gmailDraftId, savedAt };
}

export async function saveDraftToGmail(draftId: string): Promise<DraftSaveResponse> {
  return parseSaveResponse(
    await request(`/api/v1/drafts/${encodeURIComponent(draftId)}/save-to-gmail`, "POST", {}),
    draftId,
  );
}

export async function sendDraft(
  draftId: string,
  expectedUpdatedAt: string,
  reviewFingerprint: string,
): Promise<void> {
  const value = await request(`/api/v1/drafts/${encodeURIComponent(draftId)}/send`, "POST", {
    confirmed: true,
    expectedUpdatedAt,
    reviewFingerprint,
  });
  const candidate = object(value);
  if (candidate?.state !== "SENT" || nonEmptyString(candidate.id) !== draftId) {
    throw new DraftsApiError(
      "Gmail may have accepted the message, but East could not read its confirmation. Check Gmail Sent before retrying.",
      null,
      draftReconciliationErrorCode,
    );
  }
}
