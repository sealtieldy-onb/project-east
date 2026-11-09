import { isMailboxColorIndex, type MailboxColorIndex } from "./mailbox-colors";

export type GmailCategory = "PRIMARY" | "SOCIAL" | "PROMOTIONS" | "UPDATES" | "FORUMS";

export type MailboxIdentity = {
  id: string;
  email: string;
  displayName: string;
  color: string | null;
  colorIndex: MailboxColorIndex | null;
};

export type Mailbox = MailboxIdentity & {
  syncStatus: string | null;
  lastSyncAt: string | null;
  categories: GmailCategory[];
};

export type GmailLabel = {
  id: string;
  name: string;
  color: string | null;
};

export type ThreadSummary = {
  id: string;
  mailboxId: string;
  mailbox: MailboxIdentity | null;
  subject: string;
  snippet: string | null;
  sender: string;
  senderEmail: string | null;
  latestMessageAt: string;
  unread: boolean;
  starred: boolean;
  labels: GmailLabel[];
  category: GmailCategory | null;
  messageCount: number;
  attachmentsAvailable: boolean | null;
};

export type ThreadPage = {
  items: ThreadSummary[];
  nextPageToken: string | null;
  totalEstimate: number;
};

export type AttachmentMetadata = {
  id: string | null;
  filename: string;
  mimeType: string | null;
  sizeBytes: number | null;
};

export type ThreadMessage = {
  id: string;
  sender: string;
  senderEmail: string | null;
  to: string[];
  cc: string[];
  bcc: string[];
  replyTo: string | null;
  timestamp: string;
  bodyText: string | null;
  bodyHtml: string | null;
  bodyUnavailableReason: "MESSAGE_TOO_LARGE" | "THREAD_LIMIT" | "SYNC_LIMIT" | "ON_DEMAND" | null;
  attachments: AttachmentMetadata[];
};

export type ThreadDetail = {
  id: string;
  mailboxId: string;
  mailbox: MailboxIdentity | null;
  subject: string;
  labels: GmailLabel[];
  messages: ThreadMessage[];
  attachments: AttachmentMetadata[];
};

export type ThreadListQuery = {
  mailboxId?: string;
  folder?: string;
  category?: GmailCategory;
  query?: string;
  unread?: boolean;
  starred?: boolean;
  labelId?: string;
  pageToken?: string;
  pageSize?: number;
};

export class ConversationsApiError extends Error {
  constructor(message: string, readonly code?: string) {
    super(message);
    this.name = "ConversationsApiError";
  }
}

const requestTimeoutMs = 12_000;
const mailboxListTimeoutMs = 90_000;
const attachmentTimeoutMs = 60_000;
const knownCategories: Record<string, GmailCategory> = {
  PRIMARY: "PRIMARY",
  PERSONAL: "PRIMARY",
  CATEGORY_PERSONAL: "PRIMARY",
  SOCIAL: "SOCIAL",
  CATEGORY_SOCIAL: "SOCIAL",
  PROMOTIONS: "PROMOTIONS",
  CATEGORY_PROMOTIONS: "PROMOTIONS",
  UPDATES: "UPDATES",
  CATEGORY_UPDATES: "UPDATES",
  FORUMS: "FORUMS",
  CATEGORY_FORUMS: "FORUMS",
};

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function category(value: unknown): GmailCategory | null {
  const raw = nonEmptyString(value)?.toUpperCase().replace(/[\s-]+/g, "_");
  return raw ? knownCategories[raw] ?? null : null;
}

export function isGmailCategoryLabel(value: unknown): boolean {
  const raw = nonEmptyString(value)?.toUpperCase().replace(/[\s-]+/g, "_");
  return Boolean(raw?.startsWith("CATEGORY_") && category(raw));
}

function parseIdentity(value: unknown, fallbackId?: string): MailboxIdentity | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id) ?? fallbackId ?? null;
  const email = nonEmptyString(candidate.email) ?? nonEmptyString(candidate.address);
  if (!id || !email) return null;
  const displayName = nonEmptyString(candidate.displayName) ?? nonEmptyString(candidate.name) ?? email;
  const colorIndex = isMailboxColorIndex(candidate.colorIndex)
    ? candidate.colorIndex
    : null;
  if (candidate.colorIndex !== undefined && candidate.colorIndex !== null && colorIndex === null) return null;
  return {
    id,
    email,
    displayName,
    color: nullableString(candidate.color),
    colorIndex,
  };
}

function parseMailbox(value: unknown): Mailbox | null {
  const candidate = object(value);
  if (!candidate) return null;
  const identity = parseIdentity(candidate);
  if (!identity) return null;
  const rawCategories = candidate.categories ?? candidate.categoryTabs ?? candidate.availableCategories;
  if (rawCategories !== undefined && !Array.isArray(rawCategories)) return null;
  const categories = (Array.isArray(rawCategories) ? rawCategories : [])
    .map((entry) => {
      const item = object(entry);
      return category(typeof entry === "string" ? entry : item?.id ?? item?.name);
    })
    .filter((entry): entry is GmailCategory => entry !== null);
  return {
    ...identity,
    syncStatus: nullableString(candidate.syncStatus ?? candidate.syncState),
    lastSyncAt: nullableString(candidate.lastSyncAt),
    categories: Array.from(new Set(categories)),
  };
}

function parseLabel(value: unknown): GmailLabel | null {
  if (typeof value === "string" && value.trim()) {
    return { id: value.trim(), name: value.trim(), color: null };
  }
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id) ?? nonEmptyString(candidate.labelId) ?? nonEmptyString(candidate.name);
  const name = nonEmptyString(candidate.name) ?? nonEmptyString(candidate.displayName) ?? id;
  if (!id || !name) return null;
  return { id, name, color: nullableString(candidate.color) };
}

function parseLabels(value: unknown): GmailLabel[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) return null;
  const labels = value.map(parseLabel);
  if (labels.some((label) => label === null)) return null;
  return (labels as GmailLabel[]).filter(
    (label) => !isGmailCategoryLabel(label.id) && !isGmailCategoryLabel(label.name),
  );
}

function categoryFromLabels(value: unknown): GmailCategory | null {
  if (!Array.isArray(value)) return null;
  for (const entry of value) {
    const candidate = object(entry);
    const values = typeof entry === "string"
      ? [entry]
      : [candidate?.id, candidate?.labelId, candidate?.name, candidate?.displayName];
    for (const candidateValue of values) {
      if (isGmailCategoryLabel(candidateValue)) return category(candidateValue);
    }
  }
  return null;
}

function parseSender(value: unknown): { name: string; email: string | null } | null {
  const direct = nonEmptyString(value);
  if (direct) return { name: direct, email: null };
  const candidate = object(value);
  if (!candidate) return null;
  const email = nonEmptyString(candidate.email) ?? nonEmptyString(candidate.address);
  const name = nonEmptyString(candidate.name) ?? nonEmptyString(candidate.displayName) ?? email;
  return name ? { name, email } : null;
}

function parseRecipientList(value: unknown): string[] {
  const entries = typeof value === "string" ? [value] : Array.isArray(value) ? value : [];
  return entries.map((entry) => {
    const direct = nonEmptyString(entry);
    if (direct) return direct;
    const sender = parseSender(entry);
    return sender?.email && sender.name !== sender.email
      ? `${sender.name} <${sender.email}>`
      : sender?.email ?? sender?.name ?? "";
  }).filter(Boolean);
}

function parseAttachment(value: unknown): AttachmentMetadata | null {
  const candidate = object(value);
  if (!candidate) return null;
  const filename = nonEmptyString(candidate.filename) ?? nonEmptyString(candidate.name);
  if (!filename) return null;
  const size = candidate.sizeBytes ?? candidate.size;
  return {
    id: nonEmptyString(candidate.id) ?? nonEmptyString(candidate.attachmentId),
    filename,
    mimeType: nullableString(candidate.mimeType) ?? nullableString(candidate.contentType),
    sizeBytes: typeof size === "number" && Number.isSafeInteger(size) && size >= 0 ? size : null,
  };
}

function parseAttachments(value: unknown): AttachmentMetadata[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) return null;
  const attachments = value.map(parseAttachment);
  return attachments.some((entry) => entry === null) ? null : attachments as AttachmentMetadata[];
}

function parseThreadSummary(value: unknown): ThreadSummary | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id) ?? nonEmptyString(candidate.threadId);
  const mailboxId = nonEmptyString(candidate.mailboxId) ?? nonEmptyString(object(candidate.mailbox)?.id);
  const subject = nullableString(candidate.subject);
  const snippet = candidate.snippet === null || candidate.snippet === undefined ? null : nullableString(candidate.snippet);
  const sender = parseSender(candidate.sender ?? candidate.from);
  const latestMessageAt = nonEmptyString(candidate.latestMessageAt) ?? nonEmptyString(candidate.latestMessageTime);
  const labels = parseLabels(candidate.labels);
  const unread = candidate.unread;
  const starred = candidate.starred;
  const messageCount = candidate.messageCount;
  if (
    !id || !mailboxId || subject === null ||
    (candidate.snippet !== undefined && candidate.snippet !== null && snippet === null) || !sender ||
    !latestMessageAt || !labels || typeof unread !== "boolean" || typeof starred !== "boolean" ||
    typeof messageCount !== "number" || !Number.isSafeInteger(messageCount) || messageCount < 0
  ) return null;

  const nestedMailbox = parseIdentity(candidate.mailbox ?? candidate.account, mailboxId);
  const attachmentsAvailable = typeof candidate.attachmentsAvailable === "boolean"
    ? candidate.attachmentsAvailable
    : typeof candidate.hasAttachments === "boolean" ? candidate.hasAttachments : null;

  return {
    id,
    mailboxId,
    mailbox: nestedMailbox,
    subject,
    snippet,
    sender: sender.name,
    senderEmail: sender.email,
    latestMessageAt,
    unread,
    starred,
    labels,
    category: category(candidate.category) ?? categoryFromLabels(candidate.labels),
    messageCount,
    attachmentsAvailable,
  };
}

function parseThreadMessage(value: unknown): ThreadMessage | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id) ?? nonEmptyString(candidate.messageId);
  const sender = parseSender(candidate.sender ?? candidate.from);
  const timestamp = nonEmptyString(candidate.timestamp) ?? nonEmptyString(candidate.sentAt) ?? nonEmptyString(candidate.date);
  const rawBody = candidate.bodyText ?? candidate.body;
  const bodyText = rawBody === null || rawBody === undefined ? null : typeof rawBody === "string" ? rawBody : undefined;
  const rawUnavailableReason = candidate.bodyUnavailableReason;
  const bodyUnavailableReason = rawUnavailableReason === "MESSAGE_TOO_LARGE"
      || rawUnavailableReason === "THREAD_LIMIT"
      || rawUnavailableReason === "SYNC_LIMIT"
      || rawUnavailableReason === "ON_DEMAND"
    ? rawUnavailableReason
    : null;
  const to = parseRecipientList(candidate.to ?? candidate.recipients);
  const cc = parseRecipientList(candidate.cc);
  const bcc = parseRecipientList(candidate.bcc);
  const replyTo = nullableString(candidate.replyTo);
  const rawBodyHtml = candidate.bodyHtml;
  const bodyHtml = rawBodyHtml === null || rawBodyHtml === undefined
    ? null
    : typeof rawBodyHtml === "string" ? rawBodyHtml : undefined;
  const attachments = parseAttachments(candidate.attachments);
  if (!id || !sender || !timestamp || bodyText === undefined || bodyHtml === undefined || !attachments) return null;
  return {
    id,
    sender: sender.name,
    senderEmail: sender.email,
    to,
    cc,
    bcc,
    replyTo,
    timestamp,
    bodyText,
    bodyHtml,
    bodyUnavailableReason,
    attachments,
  };
}

function parseThreadDetail(value: unknown, requestedId: string): ThreadDetail | null {
  const candidate = object(value);
  if (!candidate) return null;
  const id = nonEmptyString(candidate.id) ?? nonEmptyString(candidate.threadId) ?? requestedId;
  const mailboxId = nonEmptyString(candidate.mailboxId) ?? nonEmptyString(object(candidate.mailbox)?.id);
  const subject = nullableString(candidate.subject);
  const labels = parseLabels(candidate.labels);
  const rawMessages = candidate.messages;
  const attachments = parseAttachments(candidate.attachments);
  if (!mailboxId || subject === null || !labels || !Array.isArray(rawMessages) || !attachments) return null;
  const messages = rawMessages.map(parseThreadMessage);
  if (messages.some((message) => message === null)) return null;
  return {
    id,
    mailboxId,
    mailbox: parseIdentity(candidate.mailbox ?? candidate.account, mailboxId),
    subject,
    labels,
    messages: messages as ThreadMessage[],
    attachments,
  };
}

async function readResponse(response: Response): Promise<unknown> {
  if (!response.ok) {
    const unavailable = response.status === 404 || response.status >= 500;
    let errorCode: string | undefined;
    let responseDetail: string | undefined;
    if (!unavailable) {
      try {
        const payload: unknown = await response.json();
        if (payload && typeof payload === "object") {
          if ("errorCode" in payload && typeof payload.errorCode === "string") {
            errorCode = payload.errorCode;
          }
          if ("detail" in payload && typeof payload.detail === "string") {
            responseDetail = payload.detail;
          }
        }
      } catch {
        // Keep the existing generic response when the service has no JSON error body.
      }
    }
    const mailboxReauthorizationRequired = response.status === 401 && (
      responseDetail === "This mailbox needs to be reconnected to Google."
      || responseDetail === "This mailbox needs to be connected again."
    );
    if (response.status === 401 && !errorCode) {
      errorCode = mailboxReauthorizationRequired ? "MAILBOX_AUTH_REQUIRED" : "EAST_SESSION_EXPIRED";
    }
    throw new ConversationsApiError(
      errorCode === "INVALID_PAGE_TOKEN"
        ? "The conversation page expired. East will refresh the list."
        : errorCode === "EAST_SESSION_EXPIRED"
          ? "Your East sign-in may have expired. Reload East and sign in again to continue."
          : errorCode === "MAILBOX_AUTH_REQUIRED"
            ? "A connected Gmail account needs reconnection. Reload East to see its recovery prompt."
        : unavailable
          ? "The local mail service is unavailable. Try again when East is running."
          : `The mail request was rejected (HTTP ${response.status}). Refresh the view and try again.`,
      errorCode,
    );
  }
  try {
    return await response.json();
  } catch {
    throw new ConversationsApiError("The local mail service returned an invalid response.");
  }
}

async function getJson(path: string, signal?: AbortSignal, timeoutMs = requestTimeoutMs): Promise<unknown> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);
  const abortFromCaller = () => controller.abort();
  signal?.addEventListener("abort", abortFromCaller, { once: true });
  try {
    const response = await fetch(path, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    });
    return await readResponse(response);
  } catch (error) {
    if (error instanceof ConversationsApiError) throw error;
    if (signal?.aborted) throw error;
    throw new ConversationsApiError("The local mail service could not be reached. Check the connection and try again.");
  } finally {
    signal?.removeEventListener("abort", abortFromCaller);
    window.clearTimeout(timeoutId);
  }
}

export async function getMailboxes(signal?: AbortSignal): Promise<Mailbox[]> {
  const payload = await getJson("/api/v1/mailboxes", signal);
  const payloadObject = object(payload);
  const candidate = Array.isArray(payload) ? payload : payloadObject?.items ?? payloadObject?.mailboxes;
  if (!Array.isArray(candidate)) throw new ConversationsApiError("The mailbox response is invalid.");
  const mailboxes = candidate.map(parseMailbox);
  if (mailboxes.some((mailbox) => mailbox === null)) throw new ConversationsApiError("The mailbox response is invalid.");
  return mailboxes as Mailbox[];
}

export async function getThreadPage(query: ThreadListQuery, signal?: AbortSignal): Promise<ThreadPage> {
  const params = new URLSearchParams();
  if (query.mailboxId) params.set("mailboxId", query.mailboxId);
  if (query.folder) params.set("folder", query.folder);
  if (query.category) params.set("category", query.category);
  if (query.query?.trim()) params.set("query", query.query.trim());
  if (query.unread !== undefined) params.set("unread", String(query.unread));
  if (query.starred !== undefined) params.set("starred", String(query.starred));
  if (query.labelId) params.set("labelId", query.labelId);
  if (query.pageToken) params.set("pageToken", query.pageToken);
  if (query.pageSize !== undefined) params.set("pageSize", String(query.pageSize));

  const payload = object(await getJson(`/api/v1/threads?${params.toString()}`, signal, mailboxListTimeoutMs));
  const rawItems = payload?.items;
  const nextPageToken = payload?.nextPageToken;
  const totalEstimate = payload?.totalEstimate;
  if (
    !payload || !Array.isArray(rawItems) ||
    (nextPageToken !== null && nextPageToken !== undefined && typeof nextPageToken !== "string") ||
    typeof totalEstimate !== "number" || !Number.isSafeInteger(totalEstimate) || totalEstimate < 0
  ) throw new ConversationsApiError("The conversation list response is invalid.");
  const items = rawItems.map(parseThreadSummary);
  if (items.some((item) => item === null)) throw new ConversationsApiError("The conversation list response is invalid.");
  return {
    items: items as ThreadSummary[],
    nextPageToken: typeof nextPageToken === "string" && nextPageToken.length > 0 ? nextPageToken : null,
    totalEstimate,
  };
}

export async function getThread(threadId: string, signal?: AbortSignal): Promise<ThreadDetail> {
  const safeId = encodeURIComponent(threadId);
  const payload = await getJson(`/api/v1/threads/${safeId}`, signal);
  const detail = parseThreadDetail(payload, threadId);
  if (!detail) throw new ConversationsApiError("The conversation detail response is invalid.");
  return detail;
}

export async function getAttachmentBlob(
  threadId: string,
  messageId: string,
  attachmentId: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const path = `/api/v1/threads/${encodeURIComponent(threadId)}`
    + `/messages/${encodeURIComponent(messageId)}`
    + `/attachments/${encodeURIComponent(attachmentId)}`;
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), attachmentTimeoutMs);
  const abortFromCaller = () => controller.abort();
  signal?.addEventListener("abort", abortFromCaller, { once: true });
  try {
    const response = await fetch(path, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        throw new ConversationsApiError("Reconnect the Gmail account to access this attachment.");
      }
      if (response.status === 404 || response.status === 410) {
        throw new ConversationsApiError("This attachment is no longer available. Refresh the conversation and try again.");
      }
      if (response.status === 423) {
        throw new ConversationsApiError("Unlock East before opening or downloading attachments.");
      }
      if (response.status >= 500) {
        throw new ConversationsApiError("The local mail service could not load this attachment. Try again.");
      }
      throw new ConversationsApiError(`The attachment request was rejected (HTTP ${response.status}).`);
    }
    return await response.blob();
  } catch (error) {
    if (error instanceof ConversationsApiError) throw error;
    if (signal?.aborted) throw error;
    throw new ConversationsApiError("The attachment could not be loaded. Check the local connection and try again.");
  } finally {
    signal?.removeEventListener("abort", abortFromCaller);
    window.clearTimeout(timeoutId);
  }
}
