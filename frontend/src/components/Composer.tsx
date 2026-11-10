import { useCallback, useEffect, useMemo, useRef, useState, type ClipboardEvent as ReactClipboardEvent, type DragEvent as ReactDragEvent, type FormEvent } from "react";
import {
  Bold,
  Check,
  Italic,
  Link as LinkIcon,
  List,
  ListOrdered,
  LoaderCircle,
  Mail,
  Paperclip,
  Save,
  Send,
  Sparkles,
  Trash2,
  Underline,
  X,
} from "lucide-react";
import type { Mailbox } from "../lib/conversations-api";
import { CustomDropdown } from "./ui/CustomDropdown";
import {
  addDraftAttachment,
  assistDraft,
  createDraft,
  deleteDraft,
  deleteDraftAttachment,
  draftReconciliationErrorCode,
  DraftsApiError,
  getDraft,
  getDraftReconciliationStatus,
  listDrafts,
  sendDraft,
  resolveDraftReconciliation as submitDraftReconciliation,
  saveDraftToGmail,
  updateDraft,
  type DraftAttachment,
  type DraftAssistPayload,
  type DraftKind,
  type DraftListItem,
  type DraftReconciliationOperation,
  type DraftReconciliationResolution,
  type DraftReconciliationStatus,
  type DraftRecord,
  type DraftWritePayload,
} from "../lib/drafts-api";
import { getAIProviderSettings, SettingsApiError, type AIProviderSettings } from "../lib/settings-api";
import "./composer.css";

export type ComposerRequest = {
  sessionId: number;
  kind: DraftKind;
  mailboxId: string | null;
  sourceThreadId: string | null;
  sourceMessageId: string | null;
};

type Props = {
  request: ComposerRequest;
  mailboxes: Mailbox[];
  onClose: () => void;
  onSent: () => void;
};

type ComposerForm = {
  to: string;
  cc: string;
  bcc: string;
  subject: string;
  plainTextBody: string;
  richTextHtml: string;
};

type SaveState = "idle" | "pending" | "saving" | "saved";
type ReviewState = "closed" | "loading" | "ready";

const emptyForm: ComposerForm = {
  to: "",
  cc: "",
  bcc: "",
  subject: "",
  plainTextBody: "",
  richTextHtml: "",
};

const allowedEditorTags = new Set(["a", "b", "br", "em", "i", "li", "ol", "p", "s", "strong", "u", "ul"]);
const discardedEditorTags = new Set(["script", "style", "iframe", "object", "svg", "math", "form"]);
const maxFileBytes = 10 * 1024 * 1024;
const maxTotalBytes = 16 * 1024 * 1024;
const maxAttachmentCount = 10;
const reconciliationStorageKey = "east:draft-reconciliation-required";
const uncertainSendMissingMessage = "East can no longer find this app draft after a send may have started. The send may have completed and its response may have been lost. Check this account’s Gmail Sent folder before taking any further action; do not retry or send another copy. Reload server status after checking.";

function reconciliationBlockedDraftIds(): Set<string> {
  try {
    const saved = window.sessionStorage.getItem(reconciliationStorageKey);
    const parsed: unknown = saved ? JSON.parse(saved) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === "string") : []);
  } catch {
    return new Set();
  }
}

function isDraftReconciliationBlocked(draftId: string): boolean {
  return reconciliationBlockedDraftIds().has(draftId);
}

function setDraftReconciliationBlocked(draftId: string, blocked: boolean): void {
  const blockedIds = reconciliationBlockedDraftIds();
  if (blocked) blockedIds.add(draftId);
  else blockedIds.delete(draftId);
  try {
    if (blockedIds.size === 0) window.sessionStorage.removeItem(reconciliationStorageKey);
    else window.sessionStorage.setItem(reconciliationStorageKey, JSON.stringify([...blockedIds]));
  } catch {
    // Keep the in-memory UI guard even if session storage is unavailable.
  }
}

function formFromDraft(draft: DraftRecord): ComposerForm {
  return {
    to: draft.to.join(", "),
    cc: draft.cc.join(", "),
    bcc: draft.bcc.join(", "),
    subject: draft.subject,
    plainTextBody: draft.plainTextBody,
    richTextHtml: draft.richTextHtml,
  };
}

function splitAddresses(value: string): string[] {
  return value.split(/[;,\n]+/).map((address) => address.trim()).filter(Boolean);
}

function safeProviderEndpoint(value: string | null): string | null {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
    return `${parsed.origin}${parsed.pathname === "/" ? "" : parsed.pathname}`;
  } catch {
    return null;
  }
}

function providerDestination(settings: AIProviderSettings): string {
  if (settings.provider === "openai") return "OpenAI's API";
  return safeProviderEndpoint(settings.baseUrl) ?? "the configured OpenAI-compatible endpoint";
}

function providerSettingsReady(settings: AIProviderSettings): boolean {
  return Boolean(settings.provider && settings.keyConfigured && settings.model?.trim() && settings.configurationVersion?.trim());
}

function writePayload(draft: DraftRecord, form: ComposerForm): DraftWritePayload {
  return {
    mailboxId: draft.mailboxId,
    kind: draft.kind,
    sourceThreadId: draft.sourceThreadId,
    sourceMessageId: draft.sourceMessageId,
    to: splitAddresses(form.to),
    cc: splitAddresses(form.cc),
    bcc: splitAddresses(form.bcc),
    subject: form.subject,
    plainTextBody: form.plainTextBody,
    richTextHtml: form.richTextHtml,
  };
}

function safeLink(value: string): string | null {
  try {
    const parsed = new URL(value.trim());
    return ["http:", "https:", "mailto:"].includes(parsed.protocol) ? parsed.href : null;
  } catch {
    return null;
  }
}

function safeEditorHtml(html: string): string {
  if (!html) return "";
  const source = document.createElement("template");
  source.innerHTML = html;
  const fragment = document.createDocumentFragment();
  const copy = (node: Node, parent: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      parent.appendChild(document.createTextNode(node.textContent ?? ""));
      return;
    }
    if (!(node instanceof Element)) return;
    const tag = node.tagName.toLowerCase();
    if (discardedEditorTags.has(tag)) return;
    if (!allowedEditorTags.has(tag)) {
      for (const child of Array.from(node.childNodes)) copy(child, parent);
      return;
    }
    const element = document.createElement(tag);
    if (tag === "a") {
      const href = node.getAttribute("href");
      const safeHref = href ? safeLink(href) : null;
      if (safeHref) element.setAttribute("href", safeHref);
      const title = node.getAttribute("title");
      if (title) element.setAttribute("title", title.slice(0, 200));
      element.setAttribute("target", "_blank");
      element.setAttribute("rel", "noreferrer noopener");
    }
    parent.appendChild(element);
    for (const child of Array.from(node.childNodes)) copy(child, element);
  };
  for (const child of Array.from(source.content.childNodes)) copy(child, fragment);
  const wrapper = document.createElement("div");
  wrapper.appendChild(fragment);
  return wrapper.innerHTML;
}

function fragmentFromPlainText(text: string): DocumentFragment {
  const fragment = document.createDocumentFragment();
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  lines.forEach((line, index) => {
    if (index > 0) fragment.appendChild(document.createElement("br"));
    fragment.appendChild(document.createTextNode(line));
  });
  return fragment;
}

function fragmentFromTransfer(transfer: DataTransfer): DocumentFragment {
  const html = transfer.getData("text/html");
  if (html) {
    const template = document.createElement("template");
    template.innerHTML = safeEditorHtml(html);
    return template.content;
  }
  return fragmentFromPlainText(transfer.getData("text/plain"));
}

function rangeAtDropPoint(editor: HTMLElement, x: number, y: number): Range | null {
  const caretDocument = document as Document & {
    caretRangeFromPoint?: (left: number, top: number) => Range | null;
    caretPositionFromPoint?: (left: number, top: number) => { offsetNode: Node; offset: number } | null;
  };
  const directRange = caretDocument.caretRangeFromPoint?.(x, y);
  if (directRange && editor.contains(directRange.startContainer)) return directRange;
  const caretPosition = caretDocument.caretPositionFromPoint?.(x, y);
  if (caretPosition && editor.contains(caretPosition.offsetNode)) {
    const range = document.createRange();
    range.setStart(caretPosition.offsetNode, caretPosition.offset);
    range.collapse(true);
    return range;
  }
  return null;
}

function insertTransferIntoEditor(editor: HTMLElement, transfer: DataTransfer, dropPoint?: { x: number; y: number }): void {
  const fragment = fragmentFromTransfer(transfer);
  if (!fragment.hasChildNodes()) return;
  const selection = window.getSelection();
  const selectedRange = selection?.rangeCount ? selection.getRangeAt(0) : null;
  const range = dropPoint
    ? rangeAtDropPoint(editor, dropPoint.x, dropPoint.y) ?? (selectedRange && editor.contains(selectedRange.startContainer) ? selectedRange.cloneRange() : null)
    : selectedRange && editor.contains(selectedRange.startContainer) ? selectedRange.cloneRange() : null;
  const insertionRange = range ?? document.createRange();
  if (!range) {
    insertionRange.selectNodeContents(editor);
    insertionRange.collapse(false);
  }
  insertionRange.deleteContents();
  const lastInsertedNode = fragment.lastChild;
  insertionRange.insertNode(fragment);
  if (lastInsertedNode && selection) {
    const caret = document.createRange();
    caret.setStartAfter(lastInsertedNode);
    caret.collapse(true);
    selection.removeAllRanges();
    selection.addRange(caret);
  }
  editor.focus();
}

function editorPlainText(element: HTMLElement): string {
  return (element.innerText ?? element.textContent ?? "").replace(/\u00a0/g, " ");
}

function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(size < 10 * 1024 ? 1 : 0)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function draftKindTitle(kind: DraftKind): string {
  switch (kind) {
    case "NEW": return "New message";
    case "REPLY": return "Reply";
    case "REPLY_ALL": return "Reply all";
    case "FORWARD": return "Forward";
  }
}

function draftListTitle(draft: DraftListItem, mailboxes: Mailbox[]): string {
  const mailbox = mailboxes.find((item) => item.id === draft.mailboxId);
  const status = draft.state === "LOCAL" ? "App draft"
    : draft.state === "SAVED_TO_GMAIL" ? "Gmail draft"
      : draft.state === "SAVE_PENDING" ? "Save needs Gmail check"
        : "Send needs Gmail check";
  const account = mailbox?.displayName ?? mailbox?.email ?? "Mailbox";
  const kind = draftKindTitle(draft.kind);
  return `${status} · ${kind} · ${account}`;
}

function listItemFromDraft(
  draft: DraftRecord,
  state: DraftListItem["state"] = draft.state,
  reconciliationOperation: DraftReconciliationOperation | null = null,
  reconciliationStartedAt: string | null = null,
): DraftListItem {
  return {
    id: draft.id,
    mailboxId: draft.mailboxId,
    kind: draft.kind,
    state,
    sourceThreadId: draft.sourceThreadId,
    attachmentCount: draft.attachments.length,
    createdAt: draft.createdAt,
    updatedAt: draft.updatedAt,
    savedAt: draft.savedAt,
    reconciliationOperation,
    reconciliationStartedAt,
  };
}

function errorMessage(failure: unknown, fallback: string): string {
  return failure instanceof DraftsApiError ? failure.message : fallback;
}

function requiresGmailReconciliation(failure: unknown): failure is DraftsApiError {
  return failure instanceof DraftsApiError && (
    failure.errorCode === draftReconciliationErrorCode ||
    failure.status === null ||
    (failure.status !== null && failure.status >= 500)
  );
}

function ReviewBody({ draft }: { draft: DraftRecord }) {
  const html = safeEditorHtml(draft.richTextHtml);
  return html ? (
    <div className="east-composer-review-body" dangerouslySetInnerHTML={{ __html: html }} />
  ) : (
    <pre className="east-composer-review-plain">{draft.plainTextBody || "(No message body)"}</pre>
  );
}

export function DraftComposer({ request: initialRequest, mailboxes, onClose, onSent }: Props) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const linkSelectionRef = useRef<Range | null>(null);
  const initialCreateStartedRef = useRef(false);
  const activeDraftRef = useRef<DraftRecord | null>(null);
  const formRef = useRef<ComposerForm>(emptyForm);
  const editRevisionRef = useRef(0);
  const savedRevisionRef = useRef(0);
  const savePromiseRef = useRef<Promise<DraftRecord> | null>(null);
  const saveTimerRef = useRef<number | null>(null);

  const [drafts, setDrafts] = useState<DraftListItem[]>([]);
  const [draftListLoading, setDraftListLoading] = useState(true);
  const [draftListError, setDraftListError] = useState<string | null>(null);
  const [draft, setDraftState] = useState<DraftRecord | null>(null);
  const [selectedMailboxId, setSelectedMailboxId] = useState(initialRequest.mailboxId ?? "");
  const [form, setForm] = useState<ComposerForm>(emptyForm);
  const [formReady, setFormReady] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reconciliationRequired, setReconciliationRequired] = useState(false);
  const [reconciliationStatusLoaded, setReconciliationStatusLoaded] = useState(false);
  const [pendingDraft, setPendingDraft] = useState<DraftListItem | null>(null);
  const [reconciliationStatus, setReconciliationStatus] = useState<DraftReconciliationStatus | null>(null);
  const [reconciliationStatusError, setReconciliationStatusError] = useState<string | null>(null);
  const [reconciliationGmailDraftId, setReconciliationGmailDraftId] = useState("");
  const [completedAttested, setCompletedAttested] = useState(false);
  const [notCompletedAttested, setNotCompletedAttested] = useState(false);
  const reconciliationRequiredRef = useRef(false);
  const [reviewState, setReviewState] = useState<ReviewState>("closed");
  const [reviewDraft, setReviewDraft] = useState<DraftRecord | null>(null);
  const [deleteConfirmation, setDeleteConfirmation] = useState(false);
  const [showCc, setShowCc] = useState(false);
  const [showBcc, setShowBcc] = useState(false);
  const [showLinkEditor, setShowLinkEditor] = useState(false);
  const [linkUrl, setLinkUrl] = useState("");
  const [assistOpen, setAssistOpen] = useState(false);
  const [assistSettings, setAssistSettings] = useState<AIProviderSettings | null>(null);
  const [assistSettingsLoading, setAssistSettingsLoading] = useState(false);
  const [assistSettingsError, setAssistSettingsError] = useState<string | null>(null);
  const [assistInstructions, setAssistInstructions] = useState("");
  const [assistDisclosureConfirmed, setAssistDisclosureConfirmed] = useState(false);

  const mailbox = useMemo(() => mailboxes.find((item) => item.id === (draft?.mailboxId ?? selectedMailboxId)) ?? null, [draft?.mailboxId, mailboxes, selectedMailboxId]);
  const isSavedToGmail = draft?.state === "SAVED_TO_GMAIL";
  const isEditing = draft?.state === "LOCAL" && !reconciliationRequired;
  const hasRecipients = Boolean(form.to.trim() || form.cc.trim() || form.bcc.trim());
  const hasBody = Boolean(form.plainTextBody.trim() || form.richTextHtml.trim());

  const setReconciliationLock = useCallback((locked: boolean) => {
    reconciliationRequiredRef.current = locked;
    setReconciliationRequired(locked);
  }, []);

  const setActiveDraft = useCallback((next: DraftRecord | null) => {
    activeDraftRef.current = next;
    setDraftState(next);
    setAssistOpen(false);
    setAssistDisclosureConfirmed(false);
    setReconciliationLock(false);
    setReconciliationStatusLoaded(false);
    setPendingDraft(null);
    setReconciliationStatus(null);
    setReconciliationStatusError(null);
    setReconciliationGmailDraftId("");
    setCompletedAttested(false);
    setNotCompletedAttested(false);
    if (next) {
      // A successful server read/create confirms this draft is not pending.
      setDraftReconciliationBlocked(next.id, false);
      const nextForm = formFromDraft(next);
      formRef.current = nextForm;
      setForm(nextForm);
      setSelectedMailboxId(next.mailboxId);
      setFormReady(true);
      setShowCc(next.cc.length > 0);
      setShowBcc(next.bcc.length > 0);
      editRevisionRef.current = 0;
      savedRevisionRef.current = 0;
      setSaveState("saved");
      setReviewDraft(null);
      setReviewState("closed");
      setDeleteConfirmation(false);
      if (editorRef.current) {
        const safeHtml = safeEditorHtml(next.richTextHtml);
        if (safeHtml) editorRef.current.innerHTML = safeHtml;
        else editorRef.current.replaceChildren(fragmentFromPlainText(next.plainTextBody));
      }
    } else {
      formRef.current = emptyForm;
      setForm(emptyForm);
      setFormReady(false);
      setShowCc(false);
      setShowBcc(false);
      setSaveState("idle");
      setReviewDraft(null);
      setReviewState("closed");
      setDeleteConfirmation(false);
    }
  }, [setReconciliationLock]);

  function preserveUncertainSendMissing(draftId: string, listItem: DraftListItem) {
    const pendingItem: DraftListItem = {
      ...listItem,
      state: "SEND_PENDING",
      reconciliationOperation: "SEND",
    };
    setDraftReconciliationBlocked(draftId, true);
    setActiveDraft(null);
    setReconciliationLock(true);
    setPendingDraft(pendingItem);
    setReconciliationStatus(null);
    setReconciliationStatusLoaded(false);
    setReconciliationStatusError(uncertainSendMissingMessage);
    setReconciliationGmailDraftId("");
    setCompletedAttested(false);
    setNotCompletedAttested(false);
    setReviewDraft(null);
    setReviewState("closed");
    setDrafts((items) => [pendingItem, ...items.filter((item) => item.id !== draftId)]);
    setError(null);
    setNotice(null);
  }

  const refreshDraftList = useCallback(async () => {
    setDraftListLoading(true);
    try {
      const result = await listDrafts();
      setDrafts(result);
      setDraftListError(null);
    } catch (failure) {
      setDraftListError(errorMessage(failure, "App drafts could not be loaded."));
    } finally {
      setDraftListLoading(false);
    }
  }, []);

  async function loadServerDraftStatus(draftId: string, listItem: DraftListItem, manageBusy = true) {
    const wasLocallyBlocked = isDraftReconciliationBlocked(draftId);
    let serverReportedPending: boolean | null = null;
    if (manageBusy) setBusy("reconcile");
    setError(null);
    setReconciliationStatusError(null);
    setReconciliationStatusLoaded(false);
    setReconciliationGmailDraftId("");
    setCompletedAttested(false);
    setNotCompletedAttested(false);
    try {
      const status = await getDraftReconciliationStatus(draftId);
      serverReportedPending = status.required;
      if (status.required) {
        const pendingItem: DraftListItem = {
          ...listItem,
          state: status.state,
          reconciliationOperation: status.operation,
          reconciliationStartedAt: status.startedAt,
        };
        setDraftReconciliationBlocked(draftId, true);
        setActiveDraft(null);
        setReconciliationLock(true);
        setPendingDraft(pendingItem);
        setReconciliationStatus(status);
        setReconciliationStatusLoaded(true);
        setDrafts((items) => [pendingItem, ...items.filter((item) => item.id !== draftId)]);
        setNotice(null);
        return;
      }

      setDraftReconciliationBlocked(draftId, false);
      setReconciliationLock(false);
      setPendingDraft(null);
      setReconciliationStatus(null);
      setReconciliationStatusLoaded(false);
      setReconciliationStatusError(null);
      const current = await getDraft(draftId);
      setActiveDraft(current);
      setDrafts((items) => items.map((item) => item.id === draftId ? listItemFromDraft(current) : item));
      setNotice("East checked the server status and reloaded the current draft.");
      await refreshDraftList();
    } catch (failure) {
      if (failure instanceof DraftsApiError && failure.status === 404) {
        if (listItem.state === "SEND_PENDING" || listItem.reconciliationOperation === "SEND") {
          preserveUncertainSendMissing(draftId, listItem);
          return;
        }
        setDraftReconciliationBlocked(draftId, false);
        setActiveDraft(null);
        setDrafts((items) => items.filter((item) => item.id !== draftId));
        setError("This draft is no longer available. East refreshed the draft list.");
        void refreshDraftList();
        return;
      }

      const serverMayStillBePending = serverReportedPending !== false ||
        (failure instanceof DraftsApiError && failure.errorCode === draftReconciliationErrorCode);
      if (serverMayStillBePending && (
        listItem.state === "SAVE_PENDING" || listItem.state === "SEND_PENDING" || wasLocallyBlocked ||
        (failure instanceof DraftsApiError && failure.errorCode === draftReconciliationErrorCode)
      )) {
        setDraftReconciliationBlocked(draftId, true);
        setActiveDraft(null);
        setReconciliationLock(true);
        setPendingDraft(listItem);
        setReconciliationStatus(null);
        setReconciliationStatusError(errorMessage(failure, "East could not verify this draft's server status."));
        setReconciliationStatusLoaded(false);
      } else {
        setError(errorMessage(failure, "East could not load the draft's server status."));
      }
    } finally {
      if (manageBusy) setBusy(null);
    }
  }

  function requireDraftReconciliation(draftId: string, operation: DraftReconciliationOperation | null = null) {
    const current = activeDraftRef.current;
    const existing = drafts.find((item) => item.id === draftId);
    const inferredState: DraftListItem["state"] = operation === "SEND" ? "SEND_PENDING"
      : operation === "SAVE_TO_GMAIL" ? "SAVE_PENDING"
        : existing?.state ?? current?.state ?? "LOCAL";
    const base = existing ?? (current?.id === draftId ? listItemFromDraft(current, inferredState, operation) : null);
    if (!base) return;
    const pendingItem: DraftListItem = {
      ...base,
      state: inferredState,
      reconciliationOperation: operation ?? base.reconciliationOperation,
    };
    setDraftReconciliationBlocked(draftId, true);
    setActiveDraft(null);
    setReconciliationLock(true);
    setPendingDraft(pendingItem);
    setReconciliationStatus(null);
    setReconciliationStatusError(null);
    setReconciliationStatusLoaded(false);
    setReconciliationGmailDraftId("");
    setCompletedAttested(false);
    setNotCompletedAttested(false);
    setReviewDraft(null);
    setReviewState("closed");
    setDrafts((items) => [pendingItem, ...items.filter((item) => item.id !== draftId)]);
    setError(null);
    setNotice(null);
    void loadServerDraftStatus(draftId, pendingItem, false);
  }

  async function reloadDraftStatus() {
    if (!pendingDraft || busy !== null) return;
    await loadServerDraftStatus(pendingDraft.id, pendingDraft);
  }

  async function resolveDraftReconciliation(resolution: DraftReconciliationResolution) {
    const pending = pendingDraft;
    const status = reconciliationStatus;
    if (!pending || !status?.required || !reconciliationStatusLoaded || busy !== null) return;
    const completedSave = status.operation === "SAVE_TO_GMAIL" && resolution === "CONFIRMED_COMPLETED";
    if (resolution === "CONFIRMED_COMPLETED" && !completedAttested) return;
    if (resolution === "CONFIRMED_NOT_COMPLETED" && !notCompletedAttested) return;

    setBusy("resolve");
    setError(null);
    setReconciliationStatusError(null);
    try {
      const result = await submitDraftReconciliation(
        pending.id,
        resolution,
        completedSave ? reconciliationGmailDraftId.trim() : undefined,
      );
      setDraftReconciliationBlocked(pending.id, false);
      setPendingDraft(null);
      setReconciliationStatus(null);
      setReconciliationStatusLoaded(false);
      setReconciliationLock(false);
      setReconciliationGmailDraftId("");
      setCompletedAttested(false);
      setNotCompletedAttested(false);

      if (result.state === "SENT") {
        setDrafts((items) => items.filter((item) => item.id !== pending.id));
        setActiveDraft(null);
        await refreshDraftList();
        setBusy(null);
        onSent();
        onClose();
        return;
      }

      setActiveDraft(null);
      let uncertainSendDraftMissing = false;
      try {
        const current = await getDraft(pending.id);
        setActiveDraft(current);
        setNotice(result.state === "SAVED_TO_GMAIL"
          ? "East verified the Gmail Draft. Review its current contents before sending."
          : "East restored the draft after your confirmation that Gmail did not complete the action.");
      } catch (failure) {
        if (failure instanceof DraftsApiError && failure.status === 404 && status.operation === "SEND") {
          preserveUncertainSendMissing(pending.id, pending);
          uncertainSendDraftMissing = true;
        } else {
          setError(errorMessage(failure, "The reconciliation was recorded, but East could not reload the current draft."));
        }
      }
      if (!uncertainSendDraftMissing) await refreshDraftList();
    } catch (failure) {
      const sendMayHaveCompleted = pending.state === "SEND_PENDING" ||
        pending.reconciliationOperation === "SEND" || status.operation === "SEND";
      if (failure instanceof DraftsApiError && failure.status === 404 && sendMayHaveCompleted) {
        preserveUncertainSendMissing(pending.id, pending);
        return;
      }
      setError(errorMessage(failure, "East could not reconcile this draft. Reload server status and try again."));
      setReconciliationStatusLoaded(false);
      setReconciliationStatusError("Reload the server status before choosing a reconciliation outcome again.");
    } finally {
      setBusy(null);
    }
  }

  const persistLatestDraft = useCallback(async (): Promise<DraftRecord | null> => {
    const current = activeDraftRef.current;
    if (reconciliationRequiredRef.current) return null;
    if (!current || current.state !== "LOCAL") return current;
    if (saveTimerRef.current !== null) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    if (savePromiseRef.current) {
      try {
        await savePromiseRef.current;
      } catch {
        return null;
      }
      if (savedRevisionRef.current < editRevisionRef.current) return persistLatestDraft();
      return activeDraftRef.current;
    }
    if (savedRevisionRef.current === editRevisionRef.current) return current;

    const revision = editRevisionRef.current;
    const payload = writePayload(current, formRef.current);
    setSaveState("saving");
    setError(null);
    const promise = updateDraft(current.id, payload);
    savePromiseRef.current = promise;
    try {
      const updated = await promise;
      if (activeDraftRef.current?.id === current.id && activeDraftRef.current.state === "LOCAL") {
        const merged = { ...activeDraftRef.current, updatedAt: updated.updatedAt };
        activeDraftRef.current = merged;
        setDraftState(merged);
      }
      savedRevisionRef.current = revision;
      if (revision === editRevisionRef.current) setSaveState("saved");
    } catch (failure) {
      if (requiresGmailReconciliation(failure)) requireDraftReconciliation(current.id);
      else setError(errorMessage(failure, "The app draft could not be saved locally."));
      setSaveState("pending");
      return null;
    } finally {
      savePromiseRef.current = null;
    }
    if (savedRevisionRef.current < editRevisionRef.current) return persistLatestDraft();
    return activeDraftRef.current;
  }, []);

  const changeForm = useCallback((updates: Partial<ComposerForm>) => {
    if (reconciliationRequiredRef.current) return;
    const next = { ...formRef.current, ...updates };
    formRef.current = next;
    editRevisionRef.current += 1;
    setForm(next);
    setAssistDisclosureConfirmed(false);
    setSaveState("pending");
    setError(null);
    setNotice(null);
    setReviewDraft(null);
    setReviewState("closed");
  }, []);

  async function openAssistPanel() {
    setAssistOpen(true);
    setAssistSettings(null);
    setAssistSettingsError(null);
    setAssistDisclosureConfirmed(false);
    setAssistSettingsLoading(true);
    try {
      const settings = await getAIProviderSettings();
      setAssistSettings(settings);
      if (!providerSettingsReady(settings)) {
        setAssistSettingsError("AI drafting is not configured. Set a provider, model, and API key in Settings before generating a draft.");
      }
    } catch (failure) {
      setAssistSettingsError(failure instanceof SettingsApiError
        ? failure.message
        : "East could not load the AI provider details for disclosure.");
    } finally {
      setAssistSettingsLoading(false);
    }
  }

  async function handleGenerateWithAI() {
    const currentDraft = activeDraftRef.current;
    const disclosedSettings = assistSettings;
    if (
      !currentDraft || currentDraft.state !== "LOCAL" || !disclosedSettings ||
      !assistDisclosureConfirmed || busy !== null || reconciliationRequiredRef.current
    ) return;
    if (currentDraft.kind !== "NEW" && currentDraft.kind !== "REPLY" && currentDraft.kind !== "REPLY_ALL") return;
    if (currentDraft.kind === "NEW" && !assistInstructions.trim()) {
      setAssistSettingsError("Describe what the new email should say before generating it.");
      return;
    }
    if (!providerSettingsReady(disclosedSettings)) {
      setAssistSettingsError("AI provider settings are incomplete. Configure a provider, model, and API key in Settings.");
      setAssistDisclosureConfirmed(false);
      return;
    }

    setBusy("assist");
    setError(null);
    setAssistSettingsError(null);
    try {
      const currentSettings = await getAIProviderSettings();
      setAssistSettings(currentSettings);
      if (
        !providerSettingsReady(currentSettings) ||
        currentSettings.configurationVersion !== disclosedSettings.configurationVersion ||
        currentSettings.provider !== disclosedSettings.provider ||
        currentSettings.baseUrl !== disclosedSettings.baseUrl ||
        currentSettings.model !== disclosedSettings.model
      ) {
        setAssistDisclosureConfirmed(false);
        setAssistSettingsError("The provider settings changed. Review the updated provider and destination, then confirm again before generating.");
        return;
      }

      const savedCurrent = await persistLatestDraft();
      if (!savedCurrent || savedCurrent.id !== currentDraft.id) {
        setAssistSettingsError("East could not save the current local draft. Resolve that save before generating another draft.");
        return;
      }
      setDrafts((items) => items.map((item) => item.id === savedCurrent.id ? listItemFromDraft(savedCurrent) : item));

      const payload: DraftAssistPayload = {
        mailboxId: currentDraft.mailboxId,
        kind: currentDraft.kind,
        ...(currentDraft.sourceThreadId ? { sourceThreadId: currentDraft.sourceThreadId } : {}),
        ...(currentDraft.sourceMessageId ? { sourceMessageId: currentDraft.sourceMessageId } : {}),
        to: splitAddresses(formRef.current.to),
        cc: splitAddresses(formRef.current.cc),
        bcc: splitAddresses(formRef.current.bcc),
        ...(formRef.current.subject.trim() ? { subject: formRef.current.subject.trim() } : {}),
        instructions: assistInstructions.trim(),
        providerDisclosureConfirmed: true,
        providerConfigurationVersion: currentSettings.configurationVersion!,
      };
      const generated = await assistDraft(payload);
      setDrafts((items) => [
        listItemFromDraft(generated),
        ...items.filter((item) => item.id !== generated.id),
      ]);
      setActiveDraft(generated);
      setAssistOpen(false);
      setAssistDisclosureConfirmed(false);
      setNotice("AI created a separate encrypted local draft. Review and edit it; the previous draft remains available. Saving to Gmail and sending are separate actions.");
    } catch (failure) {
      if (failure instanceof SettingsApiError) {
        setAssistDisclosureConfirmed(false);
        setAssistSettingsError(failure.message);
      } else if (failure instanceof DraftsApiError && failure.errorCode === "DISCLOSURE_CHANGED") {
        setAssistDisclosureConfirmed(false);
        setAssistSettingsError(failure.message);
        try {
          setAssistSettings(await getAIProviderSettings());
        } catch (settingsFailure) {
          if (settingsFailure instanceof SettingsApiError) setAssistSettingsError(settingsFailure.message);
        }
      } else setError(errorMessage(failure, "East could not generate the assisted draft."));
    } finally {
      setBusy(null);
    }
  }

  async function makeDraft(mailboxId: string) {
    if (!mailboxId || reconciliationRequiredRef.current) return;
    setBusy("create");
    setError(null);
    setNotice(null);
    try {
      const created = await createDraft({
        mailboxId,
        kind: initialRequest.kind,
        sourceThreadId: initialRequest.sourceThreadId,
        sourceMessageId: initialRequest.sourceMessageId,
        to: [],
        cc: [],
        bcc: [],
        subject: "",
        plainTextBody: "",
        richTextHtml: "",
      });
      setActiveDraft(created);
      setDrafts((current) => [
        listItemFromDraft(created),
        ...current.filter((item) => item.id !== created.id),
      ]);
      setNotice(created.kind === "FORWARD" && created.attachments.length > 0 ? "Forwarded attachments are included. Remove any you do not want to send." : "This draft is stored locally until you choose Save to Gmail or Delete.");
    } catch (failure) {
      setError(errorMessage(failure, "East could not create the app draft."));
    } finally {
      setBusy(null);
    }
  }

  async function loadExistingDraft(draftId: string) {
    if (!draftId || busy !== null || reconciliationRequiredRef.current) return;
    const listItem = drafts.find((item) => item.id === draftId);
    if (!listItem) return;
    setError(null);
    setNotice(null);
    if (activeDraftRef.current?.state === "LOCAL" && !reconciliationRequiredRef.current && !isDraftReconciliationBlocked(activeDraftRef.current.id)) {
      setBusy("load");
      try {
        const saved = await persistLatestDraft();
        if (!saved) return;
      } finally {
        setBusy(null);
      }
    }
    await loadServerDraftStatus(draftId, listItem);
  }

  async function handleClose() {
    if (busy) return;
    if (reconciliationRequiredRef.current || pendingDraft) {
      onClose();
      return;
    }
    if (activeDraftRef.current?.state === "LOCAL") {
      const saved = await persistLatestDraft();
      if (!saved) return;
    }
    onClose();
  }

  async function handleSaveToGmail() {
    const current = activeDraftRef.current;
    if (!current || current.state !== "LOCAL" || reconciliationRequiredRef.current) return;
    setBusy("save-gmail");
    setError(null);
    setNotice(null);
    try {
      const savedLocally = await persistLatestDraft();
      if (!savedLocally) return;
      const saved = await saveDraftToGmail(current.id);
      const savedDraft: DraftRecord = {
        ...current,
        ...writePayload(current, formRef.current),
        state: saved.state,
        updatedAt: savedLocally.updatedAt,
        savedAt: saved.savedAt,
      };
      setActiveDraft(savedDraft);
      setDrafts((items) => items.map((item) => item.id === savedDraft.id
        ? { ...item, state: saved.state, savedAt: saved.savedAt }
        : item));
      setNotice("Saved to Gmail Drafts. East is loading the current Gmail copy for review.");
      try {
        const readback = await getDraft(current.id);
        setActiveDraft(readback);
        setNotice("Saved to Gmail Drafts. Review the current message before sending.");
      } catch {
        setError("Saved to Gmail Drafts, but East could not reload its current contents. The draft is now read-only; use Review before sending to fetch the Gmail copy again.");
      }
      await refreshDraftList();
    } catch (failure) {
      if (requiresGmailReconciliation(failure)) {
        requireDraftReconciliation(current.id, "SAVE_TO_GMAIL");
      } else {
        setError(errorMessage(failure, "East could not save this draft to Gmail."));
      }
    } finally {
      setBusy(null);
    }
  }

  async function handleOpenReview() {
    const current = activeDraftRef.current;
    if (!current || reconciliationRequiredRef.current) return;
    setBusy("review");
    setReviewState("loading");
    setReviewDraft(null);
    setError(null);
    try {
      if (current.state === "LOCAL") {
        const latestLocal = await persistLatestDraft();
        if (!latestLocal) {
          setReviewState("closed");
          return;
        }
      }
      const readback = await getDraft(current.id);
      if (!readback.reviewFingerprint) {
        throw new DraftsApiError("East could not verify the latest draft contents for review. Reload the draft and try again.");
      }
      setReviewDraft(readback);
      setReviewState("ready");
    } catch (failure) {
      setReviewState("closed");
      if (requiresGmailReconciliation(failure)) requireDraftReconciliation(current.id);
      else setError(errorMessage(failure, "The draft could not be prepared for review."));
    } finally {
      setBusy(null);
    }
  }

  async function handleSend() {
    const snapshot = reviewDraft;
    if (!snapshot?.reviewFingerprint || reconciliationRequiredRef.current) return;
    setBusy("send");
    setError(null);
    try {
      await sendDraft(snapshot.id, snapshot.updatedAt, snapshot.reviewFingerprint);
      setNotice("Message sent.");
      setDraftReconciliationBlocked(snapshot.id, false);
      setActiveDraft(null);
      setDrafts((current) => current.filter((item) => item.id !== snapshot.id));
      setReviewState("closed");
      onSent();
    } catch (failure) {
      setReviewDraft(null);
      setReviewState("closed");
      if (requiresGmailReconciliation(failure)) {
        requireDraftReconciliation(snapshot.id, "SEND");
      } else {
        setError(errorMessage(failure, "The message was not sent. Reload it and review it again."));
      }
      if (!requiresGmailReconciliation(failure) && failure instanceof DraftsApiError && failure.status === 409) {
        try {
          const latest = await getDraft(snapshot.id);
          setActiveDraft(latest);
        } catch {
          // Keep the sanitized conflict message and require the user to reopen the draft if it still exists.
        }
      }
    } finally {
      setBusy(null);
    }
  }

  async function handleDelete() {
    const current = activeDraftRef.current;
    if (!current || reconciliationRequiredRef.current) return;
    if (!deleteConfirmation) {
      setDeleteConfirmation(true);
      return;
    }
    setBusy("delete");
    setError(null);
    try {
      await deleteDraft(current.id);
      setDraftReconciliationBlocked(current.id, false);
      setDrafts((items) => items.filter((item) => item.id !== current.id));
      setActiveDraft(null);
      setDeleteConfirmation(false);
      setNotice(current.state === "LOCAL" ? "Deleted the app-local draft and its staged files." : "Deleted the Gmail draft.");
      await refreshDraftList();
    } catch (failure) {
      if (requiresGmailReconciliation(failure)) requireDraftReconciliation(current.id);
      else setError(errorMessage(failure, "East could not delete this draft."));
    } finally {
      setBusy(null);
    }
  }

  async function handleChooseFiles(event: FormEvent<HTMLInputElement>) {
    const files = Array.from(event.currentTarget.files ?? []);
    event.currentTarget.value = "";
    const current = activeDraftRef.current;
    if (!current || current.state !== "LOCAL" || reconciliationRequiredRef.current || files.length === 0) return;
    const currentCount = current.attachments.length;
    const currentBytes = current.attachments.reduce((sum, attachment) => sum + attachment.byteSize, 0);
    if (currentCount + files.length > maxAttachmentCount) {
      setError(`A draft can contain at most ${maxAttachmentCount} attachments.`);
      return;
    }
    let selectedBytes = 0;
    for (const file of files) {
      selectedBytes += file.size;
      if (file.size === 0 || file.size > maxFileBytes) {
        setError(`${file.name} must be non-empty and no larger than 10 MiB.`);
        return;
      }
    }
    if (currentBytes + selectedBytes > maxTotalBytes) {
      setError("Attachments in one draft cannot exceed 16 MiB total.");
      return;
    }

    setBusy("upload");
    setError(null);
    setNotice(null);
    try {
      const saved = await persistLatestDraft();
      if (!saved) return;
      for (const file of files) await addDraftAttachment(current.id, file);
      const latest = await getDraft(current.id);
      setActiveDraft(latest);
      setDrafts((items) => items.map((item) => item.id === latest.id ? { ...item, attachmentCount: latest.attachments.length, updatedAt: latest.updatedAt } : item));
    setNotice("Selected files of any type are encrypted in this app-local draft until you save it to Gmail or delete it.");
    } catch (failure) {
      if (requiresGmailReconciliation(failure)) {
        requireDraftReconciliation(current.id);
      } else {
        setError(errorMessage(failure, "One or more selected files could not be staged."));
        try {
          const latest = await getDraft(current.id);
          setActiveDraft(latest);
        } catch {
          // Preserve the upload error and the user's current editor state.
        }
      }
    } finally {
      setBusy(null);
    }
  }

  async function handleRemoveAttachment(attachment: DraftAttachment) {
    const current = activeDraftRef.current;
    if (!current || current.state !== "LOCAL" || reconciliationRequiredRef.current || !attachment.id) return;
    setBusy(`remove:${attachment.id}`);
    setError(null);
    setNotice(null);
    try {
      const saved = await persistLatestDraft();
      if (!saved) return;
      await deleteDraftAttachment(current.id, attachment.id);
      const latest = await getDraft(current.id);
      setActiveDraft(latest);
      setDrafts((items) => items.map((item) => item.id === latest.id ? { ...item, attachmentCount: latest.attachments.length, updatedAt: latest.updatedAt } : item));
      setNotice(`Removed “${attachment.filename}” from this draft.`);
    } catch (failure) {
      if (requiresGmailReconciliation(failure)) requireDraftReconciliation(current.id);
      else setError(errorMessage(failure, "The attachment could not be removed."));
    } finally {
      setBusy(null);
    }
  }

  function updateEditorFromDom() {
    const editor = editorRef.current;
    if (!editor) return;
    changeForm({ richTextHtml: editor.innerHTML, plainTextBody: editorPlainText(editor) });
  }

  function handleEditorPaste(event: ReactClipboardEvent<HTMLDivElement>) {
    event.preventDefault();
    if (!isEditing || busy !== null || reconciliationRequiredRef.current) return;
    insertTransferIntoEditor(event.currentTarget, event.clipboardData);
    updateEditorFromDom();
  }

  function handleEditorDrop(event: ReactDragEvent<HTMLDivElement>) {
    event.preventDefault();
    if (!isEditing || busy !== null || reconciliationRequiredRef.current) return;
    if (event.dataTransfer.files.length > 0) {
      setError("Use Attach files to add files to this draft. Dropped content is only accepted as sanitized text and formatting.");
      return;
    }
    insertTransferIntoEditor(event.currentTarget, event.dataTransfer, { x: event.clientX, y: event.clientY });
    updateEditorFromDom();
  }

  function runEditorCommand(command: "bold" | "italic" | "underline" | "insertUnorderedList" | "insertOrderedList") {
    if (reconciliationRequiredRef.current || !activeDraftRef.current || activeDraftRef.current.state !== "LOCAL") return;
    editorRef.current?.focus();
    document.execCommand(command, false);
    updateEditorFromDom();
  }

  function beginLinkEditor() {
    if (reconciliationRequiredRef.current || !activeDraftRef.current || activeDraftRef.current.state !== "LOCAL") return;
    const selection = window.getSelection();
    linkSelectionRef.current = selection && selection.rangeCount > 0 ? selection.getRangeAt(0).cloneRange() : null;
    setShowLinkEditor((shown) => !shown);
    setLinkUrl("");
  }

  function insertLink(event: FormEvent) {
    event.preventDefault();
    if (reconciliationRequiredRef.current || !activeDraftRef.current || activeDraftRef.current.state !== "LOCAL") return;
    const href = safeLink(linkUrl);
    if (!href) {
      setError("Enter a valid http, https, or mailto link.");
      return;
    }
    editorRef.current?.focus();
    const selection = window.getSelection();
    if (selection && linkSelectionRef.current) {
      selection.removeAllRanges();
      selection.addRange(linkSelectionRef.current);
    }
    document.execCommand("createLink", false, href);
    setShowLinkEditor(false);
    setLinkUrl("");
    updateEditorFromDom();
  }

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      if (saveTimerRef.current !== null) window.clearTimeout(saveTimerRef.current);
      if (dialog?.open) dialog.close();
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    listDrafts(controller.signal).then((items) => {
      if (!controller.signal.aborted) setDrafts(items);
    }).catch((failure: unknown) => {
      if (!controller.signal.aborted) setDraftListError(errorMessage(failure, "App drafts could not be loaded."));
    }).finally(() => {
      if (!controller.signal.aborted) setDraftListLoading(false);
    });
    return () => controller.abort();
  }, [initialRequest.sessionId]);

  useEffect(() => {
    if (
      initialRequest.kind === "NEW" || !initialRequest.mailboxId || !initialRequest.sourceThreadId ||
      !initialRequest.sourceMessageId || initialCreateStartedRef.current
    ) return;
    initialCreateStartedRef.current = true;
    void makeDraft(initialRequest.mailboxId);
  }, [initialRequest.sessionId]);

  useEffect(() => {
    if (!draft || draft.state !== "LOCAL" || !formReady || reconciliationRequired) return;
    if (saveTimerRef.current !== null) window.clearTimeout(saveTimerRef.current);
    saveTimerRef.current = window.setTimeout(() => {
      saveTimerRef.current = null;
      void persistLatestDraft();
    }, 700);
    return () => {
      if (saveTimerRef.current !== null) window.clearTimeout(saveTimerRef.current);
    };
  }, [draft?.id, draft?.state, form, formReady, persistLatestDraft, reconciliationRequired]);

  useEffect(() => {
    const editor = editorRef.current;
    if (!editor || !draft) return;
    const safeHtml = safeEditorHtml(draft.richTextHtml);
    if (safeHtml) editor.innerHTML = safeHtml;
    else editor.replaceChildren(fragmentFromPlainText(draft.plainTextBody));
  }, [draft?.id, draft?.plainTextBody, draft?.richTextHtml, reviewState]);

  const currentMailboxLabel = mailbox ? `${mailbox.displayName} · ${mailbox.email}` : "Choose an account";
  const canSendReview = Boolean(draft && hasRecipients && hasBody && busy === null && !reconciliationRequired);
  const pendingOperation = reconciliationStatus?.operation ?? pendingDraft?.reconciliationOperation ?? null;
  const pendingStartedAt = reconciliationStatus?.startedAt ?? pendingDraft?.reconciliationStartedAt ?? null;
  const pendingMailbox = pendingDraft ? mailboxes.find((item) => item.id === pendingDraft.mailboxId) ?? null : null;
  const pendingFolder = pendingOperation === "SEND" ? "Gmail Sent" : "Gmail Drafts";
  const isPendingSave = reconciliationStatusLoaded && reconciliationStatus?.required === true && reconciliationStatus.operation === "SAVE_TO_GMAIL";
  const isPendingSend = reconciliationStatusLoaded && reconciliationStatus?.required === true && reconciliationStatus.operation === "SEND";

  return (
    <dialog
      ref={dialogRef}
      className="east-composer-dialog"
      aria-labelledby="east-composer-title"
      onCancel={(event) => { event.preventDefault(); void handleClose(); }}
      onClick={(event) => { if (event.target === dialogRef.current) void handleClose(); }}
    >
      <header className="east-composer-header">
        <div>
          <span className="east-composer-eyebrow">EAST · CONNECTED MAIL</span>
          <h2 id="east-composer-title">{pendingDraft ? `Resolve ${draftKindTitle(pendingDraft.kind).toLowerCase()}` : draft ? draftKindTitle(draft.kind) : draftKindTitle(initialRequest.kind)}</h2>
          <p>{pendingDraft ? "A Gmail action is pending confirmation" : draft?.state === "SAVED_TO_GMAIL" ? "Gmail draft · Review before sending" : "App-local draft · Encrypted on this computer"}</p>
        </div>
        <button type="button" className="east-composer-icon-button" onClick={() => void handleClose()} aria-label="Close composer" disabled={busy !== null}>
          <X size={18} aria-hidden="true" />
        </button>
      </header>

      <div className="east-composer-draftbar">
        <div className="east-composer-draft-picker" role="group" aria-label="Saved drafts">
          <span>Open a saved draft</span>
          <CustomDropdown
            value=""
            options={[
              { value: "", label: draftListLoading ? "Loading drafts…" : drafts.length > 0 ? `Choose a draft (${drafts.length})` : "No saved drafts" },
              ...drafts.map((item) => ({ value: item.id, label: draftListTitle(item, mailboxes) })),
            ]}
            onChange={(draftId) => { if (draftId) void loadExistingDraft(draftId); }}
            ariaLabel="Open a saved draft"
            triggerClassName="east-composer-draft-select"
            disabled={busy !== null || drafts.length === 0 || reconciliationRequired}
            minimumMenuWidth={280}
          />
        </div>
        <span>{draftListLoading ? "Checking app drafts…" : draftListError ? "Draft list unavailable" : "Drafts stay until you save or delete them"}</span>
      </div>

      {draftListError ? <div className="east-composer-inline-error east-composer-inline-error--retry" role="alert"><span>{draftListError}</span><button type="button" className="east-composer-inline-retry" onClick={() => void refreshDraftList()} disabled={draftListLoading}>Retry</button></div> : null}
      {error ? <div className="east-composer-inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="east-composer-inline-notice" role="status">{notice}</div> : null}

      {pendingDraft ? (
        <section className="east-composer-reconciliation" role="alert" aria-labelledby="east-composer-reconciliation-title">
          <div>
            <strong id="east-composer-reconciliation-title">Check Gmail before resolving this action</strong>
            <p>East has paused editing, saving, sending, attachments, and deletion for this draft. {reconciliationStatus?.required
              ? `The server reports ${pendingOperation === "SEND" ? "a send may have completed" : pendingOperation === "SAVE_TO_GMAIL" ? "a save to Gmail Drafts may have completed" : "a Gmail action needs reconciliation"}.`
              : "East is checking the server to confirm whether reconciliation is required."}</p>
            <p>{reconciliationStatus?.inProgress ? "The Gmail action is still running. Wait for it to finish, then reload server status." : `Check ${pendingFolder} for this account before choosing an outcome.`}</p>
            <p>Account: {pendingMailbox?.email ?? "selected mailbox"}{pendingStartedAt ? ` · Started ${new Date(pendingStartedAt).toLocaleString()}` : ""}</p>
            {reconciliationStatus ? <p>Server state: {reconciliationStatus.state} · Previous state: {reconciliationStatus.previousState ?? "unknown"}</p> : null}
          </div>
          {!reconciliationStatusLoaded || !reconciliationStatus?.required ? (
            <div className="east-composer-reconciliation-loading" role={reconciliationStatusError ? "alert" : "status"}>
              {busy === "reconcile" ? <LoaderCircle size={14} /> : null}
              <span>{reconciliationStatusError ?? "Loading the current server reconciliation status. No resolution choice is available until it is confirmed."}</span>
              <button type="button" onClick={() => void reloadDraftStatus()} disabled={busy !== null}>Reload server status</button>
            </div>
          ) : reconciliationStatus.inProgress ? (
            <div className="east-composer-reconciliation-loading" role="status">
              <span><strong>Gmail action still running; wait and reload status.</strong></span>
              <button type="button" onClick={() => void reloadDraftStatus()} disabled={busy !== null}>{busy === "reconcile" ? <LoaderCircle size={13} /> : null}Reload server status</button>
            </div>
          ) : (
            <div className="east-composer-reconciliation-form">
              {isPendingSave ? (
                <>
                  <p>To confirm a completed save, check Gmail Drafts. East will search the 50 most recent drafts for one exact match. If the match is ambiguous, enter the Gmail Draft ID from the opened draft’s URL so East can verify that copy.</p>
                  <label className="east-composer-reconciliation-field"><span>Gmail Draft ID <small>Optional</small></span><input type="text" autoComplete="off" value={reconciliationGmailDraftId} onChange={(event) => setReconciliationGmailDraftId(event.target.value)} placeholder="Optional: ID from the matching draft URL" disabled={busy !== null} /></label>
                  <label className="east-composer-reconciliation-attestation"><input type="checkbox" checked={completedAttested} onChange={(event) => { setCompletedAttested(event.target.checked); if (event.target.checked) setNotCompletedAttested(false); }} disabled={busy !== null} /><span>I checked Gmail Drafts and found the matching saved draft.</span></label>
                  {completedAttested ? <button type="button" onClick={() => void resolveDraftReconciliation("CONFIRMED_COMPLETED")} disabled={busy !== null}>CONFIRMED_COMPLETED · Save is in Gmail Drafts</button> : null}
                </>
              ) : null}
              {isPendingSend ? (
                <>
                  <p>The completed-send choice is only for a message you found in this account’s Gmail Sent folder.</p>
                  <label className="east-composer-reconciliation-attestation"><input type="checkbox" checked={completedAttested} onChange={(event) => { setCompletedAttested(event.target.checked); if (event.target.checked) setNotCompletedAttested(false); }} disabled={busy !== null} /><span>I checked Gmail Sent and found this message there.</span></label>
                  {completedAttested ? <button type="button" onClick={() => void resolveDraftReconciliation("CONFIRMED_COMPLETED")} disabled={busy !== null}>CONFIRMED_COMPLETED · Message is in Gmail Sent</button> : null}
                </>
              ) : null}
              <label className="east-composer-reconciliation-attestation"><input type="checkbox" checked={notCompletedAttested} onChange={(event) => { setNotCompletedAttested(event.target.checked); if (event.target.checked) setCompletedAttested(false); }} disabled={busy !== null} /><span>{pendingOperation === "SEND"
                ? reconciliationStatus.previousState === "SAVED_TO_GMAIL"
                  ? "I checked Gmail Sent, confirmed the message was not sent, and verified the original Gmail Draft is still in Drafts."
                  : "I checked Gmail Sent and confirmed this message was not sent."
                : "I checked Gmail Drafts and confirmed this save did not create the draft."}</span></label>
              {notCompletedAttested ? <button type="button" onClick={() => void resolveDraftReconciliation("CONFIRMED_NOT_COMPLETED")} disabled={busy !== null}>CONFIRMED_NOT_COMPLETED · Restore draft</button> : null}
              <button type="button" className="east-composer-reconciliation-refresh" onClick={() => void reloadDraftStatus()} disabled={busy !== null}>{busy === "reconcile" ? <LoaderCircle size={13} /> : null}Reload server status</button>
            </div>
          )}
        </section>
      ) : reviewState === "loading" ? (
        <div className="east-composer-loading" role="status"><LoaderCircle size={18} /><span>Loading the current draft for review…</span></div>
      ) : reviewState === "ready" && reviewDraft ? (
        <section className="east-composer-review" aria-label="Review message before sending">
          <div className="east-composer-review-title">
            <div><span className="east-composer-eyebrow">FINAL REVIEW</span><h3>Review this exact message</h3></div>
            <button type="button" className="east-composer-icon-button" onClick={() => { setReviewDraft(null); setReviewState("closed"); }} aria-label="Return to draft"><X size={16} /></button>
          </div>
          <dl className="east-composer-review-fields">
            <div><dt>From account</dt><dd>{reviewDraft.fromAddress ?? mailbox?.email ?? currentMailboxLabel}</dd></div>
            <div><dt>To</dt><dd>{reviewDraft.to.join(", ") || "—"}</dd></div>
            <div><dt>CC</dt><dd>{reviewDraft.cc.join(", ") || "—"}</dd></div>
            <div><dt>BCC</dt><dd>{reviewDraft.bcc.join(", ") || "—"}</dd></div>
            <div><dt>Subject</dt><dd>{reviewDraft.subject || "(No subject)"}</dd></div>
          </dl>
          <div className="east-composer-review-attachments">
            <strong>Attachments ({reviewDraft.attachments.length})</strong>
            {reviewDraft.attachments.length > 0 ? reviewDraft.attachments.map((attachment, index) => (
              <span key={attachment.id ?? `${attachment.filename}-${index}`}><Paperclip size={13} />{attachment.filename}<small>{formatBytes(attachment.byteSize)}</small></span>
            )) : <span>No attachments</span>}
          </div>
          <div className="east-composer-review-message"><span>Message</span><ReviewBody draft={reviewDraft} /></div>
          <p className="east-composer-confirm-note">Sending is a separate, explicit action. East will check this review fingerprint before it sends.</p>
          <div className="east-composer-review-actions">
            <button type="button" className="east-composer-secondary" onClick={() => { setReviewDraft(null); setReviewState("closed"); }} disabled={busy !== null}>Back to draft</button>
            <button type="button" className="east-composer-send" onClick={() => void handleSend()} disabled={busy !== null || !reviewDraft.reviewFingerprint || !(reviewDraft.to.length + reviewDraft.cc.length + reviewDraft.bcc.length) || !(reviewDraft.plainTextBody.trim() || reviewDraft.richTextHtml.trim())}>
              {busy === "send" ? <LoaderCircle size={15} /> : <Send size={15} />}Send this exact message
            </button>
          </div>
        </section>
      ) : draft ? (
        <div className="east-composer-body">
          <div className="east-composer-account-line">
            <span>From</span>
            {isSavedToGmail
              ? <strong>{draft.fromAddress ?? mailbox?.email ?? currentMailboxLabel}</strong>
              : <strong>{mailbox?.email ?? currentMailboxLabel}</strong>}
            {draft.state === "LOCAL" ? <span className={`east-composer-save-state is-${saveState}`}>{saveState === "saving" ? "Saving locally…" : saveState === "saved" ? <><Check size={12} />Saved locally</> : "Unsaved changes"}</span> : <span className="east-composer-gmail-state">Saved to Gmail Drafts</span>}
          </div>

          <label className="east-composer-field">
            <span>To</span>
            <input type="text" autoComplete="off" value={form.to} onChange={(event) => changeForm({ to: event.target.value })} placeholder="name@example.com · separate addresses with commas" disabled={!isEditing || busy !== null} />
          </label>
          {showCc ? <label className="east-composer-field"><span>CC</span><input type="text" autoComplete="off" value={form.cc} onChange={(event) => changeForm({ cc: event.target.value })} placeholder="Add CC recipients" disabled={!isEditing || busy !== null} /></label> : null}
          {showBcc ? <label className="east-composer-field"><span>BCC</span><input type="text" autoComplete="off" value={form.bcc} onChange={(event) => changeForm({ bcc: event.target.value })} placeholder="Add BCC recipients" disabled={!isEditing || busy !== null} /></label> : null}
          <div className="east-composer-recipient-actions">
            {!showCc ? <button type="button" onClick={() => setShowCc(true)} disabled={!isEditing}>CC</button> : null}
            {!showBcc ? <button type="button" onClick={() => setShowBcc(true)} disabled={!isEditing}>BCC</button> : null}
          </div>
          <label className="east-composer-field east-composer-subject">
            <span>Subject</span>
            <input type="text" value={form.subject} onChange={(event) => changeForm({ subject: event.target.value })} placeholder="Subject" maxLength={500} disabled={!isEditing || busy !== null} />
          </label>

          <div className="east-composer-editor-toolbar" role="toolbar" aria-label="Message formatting">
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => runEditorCommand("bold")} disabled={!isEditing || busy !== null} aria-label="Bold"><Bold size={15} /></button>
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => runEditorCommand("italic")} disabled={!isEditing || busy !== null} aria-label="Italic"><Italic size={15} /></button>
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => runEditorCommand("underline")} disabled={!isEditing || busy !== null} aria-label="Underline"><Underline size={15} /></button>
            <span />
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={beginLinkEditor} disabled={!isEditing || busy !== null} aria-label="Insert link"><LinkIcon size={15} /></button>
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => runEditorCommand("insertUnorderedList")} disabled={!isEditing || busy !== null} aria-label="Bulleted list"><List size={15} /></button>
            <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => runEditorCommand("insertOrderedList")} disabled={!isEditing || busy !== null} aria-label="Numbered list"><ListOrdered size={15} /></button>
          </div>
          {showLinkEditor && isEditing ? <form className="east-composer-link-form" onSubmit={insertLink}><label><span>Link address</span><input type="url" autoFocus value={linkUrl} onChange={(event) => setLinkUrl(event.target.value)} placeholder="https://example.com" /></label><button type="submit">Add link</button><button type="button" onClick={() => setShowLinkEditor(false)}>Cancel</button></form> : null}
          <div
            ref={editorRef}
            className="east-composer-editor"
            role="textbox"
            aria-label="Message body"
            aria-multiline="true"
            contentEditable={isEditing && busy === null}
            suppressContentEditableWarning
            onInput={updateEditorFromDom}
            onBlur={updateEditorFromDom}
            onPaste={handleEditorPaste}
            onDragOver={(event) => { if (isEditing && busy === null && !reconciliationRequired) event.preventDefault(); }}
            onDrop={handleEditorDrop}
            data-placeholder="Write your message…"
          />

          {isEditing && (draft.kind === "NEW" || draft.kind === "REPLY" || draft.kind === "REPLY_ALL") ? (
            <section className="east-composer-assist" aria-label="AI-assisted draft generation">
              {!assistOpen ? (
                <button type="button" className="east-composer-assist-toggle" onClick={() => void openAssistPanel()} disabled={busy !== null}>
                  <Sparkles size={14} aria-hidden="true" />Generate with AI
                </button>
              ) : (
                <>
                  <div className="east-composer-assist-heading">
                    <div><strong id="east-composer-assist-title">AI-assisted draft</strong><span>Creates a separate editable app-local draft.</span></div>
                    <button type="button" className="east-composer-assist-cancel" onClick={() => { setAssistOpen(false); setAssistDisclosureConfirmed(false); }} disabled={busy !== null}>Close</button>
                  </div>
                  {assistSettingsLoading ? <p className="east-composer-assist-status" role="status"><LoaderCircle size={13} />Loading provider details…</p> : null}
                  {assistSettings && assistSettings.provider ? (
                    <p className="east-composer-assist-provider">
                      Provider: <strong>{assistSettings.provider === "openai" ? "OpenAI" : "OpenAI-compatible endpoint"}</strong>
                      {assistSettings.model ? <> · Model: <strong>{assistSettings.model}</strong></> : null}
                      <> · Destination: <strong>{providerDestination(assistSettings)}</strong></>
                    </p>
                  ) : null}
                  {assistSettingsError ? <p className="east-composer-assist-error" role="alert">{assistSettingsError}</p> : null}
                  {assistSettings && providerSettingsReady(assistSettings) ? (
                    <>
                      <p className="east-composer-assist-disclosure">
                        {draft.kind === "NEW"
                          ? "For a new message, East sends your instructions and subject context, if provided."
                          : "For a reply, East sends the selected source message text, sender, date, subject context, and your instructions."} Those details go to {assistSettings.provider === "openai" ? "OpenAI" : "the configured endpoint operator"}, using {assistSettings.model}, at {providerDestination(assistSettings)}. Attachment contents are excluded. The response creates an encrypted local draft; it does not save to Gmail or send. {assistSettings.provider === "openai" ? <>OpenAI's <a href="https://openai.com/policies/privacy-policy/" target="_blank" rel="noreferrer noopener">privacy terms</a> apply.</> : "The endpoint operator controls processing and retention."}
                      </p>
                      <label className="east-composer-assist-instructions" htmlFor="east-composer-assist-instructions">
                        <span>Instructions{draft.kind === "NEW" ? " · required" : " · optional"}</span>
                        <textarea
                          id="east-composer-assist-instructions"
                          value={assistInstructions}
                          onChange={(event) => { setAssistInstructions(event.target.value); setAssistSettingsError(null); setAssistDisclosureConfirmed(false); }}
                          maxLength={8000}
                          rows={3}
                          placeholder={draft.kind === "NEW" ? "Describe the email you want East to draft…" : "Add points or a tone for the reply…"}
                          disabled={busy !== null}
                          required={draft.kind === "NEW"}
                        />
                      </label>
                      <label className="east-composer-assist-confirm">
                        <input type="checkbox" checked={assistDisclosureConfirmed} onChange={(event) => setAssistDisclosureConfirmed(event.target.checked)} disabled={busy !== null} />
                        <span>I understand that the disclosed email text and my instructions will be processed by the provider shown above. Attachment contents are excluded.</span>
                      </label>
                      <div className="east-composer-assist-actions">
                        <button type="button" className="east-composer-send" onClick={() => void handleGenerateWithAI()} disabled={busy !== null || !assistDisclosureConfirmed || (draft.kind === "NEW" && !assistInstructions.trim())}>
                          {busy === "assist" ? <LoaderCircle size={14} /> : <Sparkles size={14} />}Generate editable draft
                        </button>
                        <span>Save to Gmail and Send remain separate actions.</span>
                      </div>
                    </>
                  ) : null}
                </>
              )}
            </section>
          ) : null}

          <section className="east-composer-attachments" aria-label="Draft attachments">
            <div className="east-composer-section-heading"><strong>Attachments ({draft.attachments.length})</strong><span>Up to 10 files · 16 MiB total</span></div>
            {draft.attachments.length > 0 ? draft.attachments.map((attachment, index) => (
              <div className="east-composer-attachment" key={attachment.id ?? `${attachment.filename}-${index}`}>
                <Paperclip size={14} aria-hidden="true" />
                <span><strong>{attachment.filename}</strong><small>{attachment.mimeType ?? "File"} · {formatBytes(attachment.byteSize)}</small></span>
                {isEditing && attachment.id ? <button type="button" onClick={() => void handleRemoveAttachment(attachment)} disabled={busy !== null} aria-label={`Remove ${attachment.filename}`}><X size={14} /></button> : null}
              </div>
            )) : <p>No attachments</p>}
            {isEditing ? <><input ref={fileInputRef} className="east-composer-file-input" type="file" multiple onChange={(event) => void handleChooseFiles(event)} /><button type="button" className="east-composer-attach-button" onClick={() => fileInputRef.current?.click()} disabled={busy !== null || draft.attachments.length >= maxAttachmentCount}><Paperclip size={14} />Attach files</button></> : null}
          </section>

          <footer className="east-composer-footer">
            <div className="east-composer-footer-left">
              {isEditing ? <button type="button" className="east-composer-delete" onClick={() => void handleDelete()} disabled={busy !== null || reconciliationRequired}><Trash2 size={14} />{deleteConfirmation ? "Confirm delete" : "Delete draft"}</button> : null}
              {isSavedToGmail ? <button type="button" className="east-composer-delete" onClick={() => void handleDelete()} disabled={busy !== null || reconciliationRequired}><Trash2 size={14} />{deleteConfirmation ? "Confirm delete" : "Delete Gmail draft"}</button> : null}
              {deleteConfirmation ? <button type="button" className="east-composer-secondary" onClick={() => setDeleteConfirmation(false)} disabled={busy !== null || reconciliationRequired}>Keep draft</button> : null}
            </div>
            <div className="east-composer-footer-right">
              {isEditing ? <button type="button" className="east-composer-secondary" onClick={() => void handleSaveToGmail()} disabled={busy !== null || saveState === "saving" || !mailbox || reconciliationRequired}>
                {busy === "save-gmail" ? <LoaderCircle size={14} /> : <Save size={14} />}Save to Gmail Drafts
              </button> : null}
              <button type="button" className="east-composer-send" onClick={() => void handleOpenReview()} disabled={!canSendReview}>
                {busy === "review" ? <LoaderCircle size={14} /> : <Send size={14} />}Review before sending
              </button>
            </div>
          </footer>
        </div>
      ) : (
        <div className="east-composer-start">
          {initialRequest.kind === "NEW" ? (
            <div className="east-composer-field"><span>From account</span><CustomDropdown
              value={selectedMailboxId}
              options={[
                { value: "", label: "Choose a sending account" },
                ...mailboxes.map((item) => ({ value: item.id, label: `${item.displayName} · ${item.email}` })),
              ]}
              onChange={setSelectedMailboxId}
              ariaLabel="From account"
              triggerClassName="east-composer-field-select"
              disabled={busy !== null || Boolean(initialRequest.mailboxId)}
            /></div>
          ) : <p>{busy === "create" ? "Preparing the message from the selected conversation…" : `From ${currentMailboxLabel}`}</p>}
          {busy === "create" ? <div className="east-composer-loading" role="status"><LoaderCircle size={17} />Preparing local draft…</div> : null}
          {initialRequest.kind === "NEW" ? <button type="button" className="east-composer-send" onClick={() => void makeDraft(selectedMailboxId)} disabled={busy !== null || !selectedMailboxId}><Mail size={15} />Start local draft</button> : null}
          {initialRequest.kind !== "NEW" && !initialRequest.sourceMessageId ? <p role="alert">Open a conversation message before creating a reply or forward.</p> : null}
          {initialRequest.kind !== "NEW" && initialRequest.sourceMessageId && busy !== "create" ? <button type="button" className="east-composer-secondary" onClick={() => initialRequest.mailboxId && void makeDraft(initialRequest.mailboxId)} disabled={busy !== null || !initialRequest.mailboxId}><Mail size={14} />Try preparing again</button> : null}
        </div>
      )}

      {busy && busy !== "review" && busy !== "send" ? <div className="east-composer-busy" role="status"><LoaderCircle size={14} />{busy === "assist" ? "Generating an encrypted app draft…" : busy === "upload" ? "Encrypting selected files…" : busy === "save-gmail" ? "Saving to Gmail Drafts…" : busy === "reconcile" ? "Reloading draft status…" : busy.startsWith("remove:") ? "Removing attachment…" : busy === "delete" ? "Deleting draft…" : busy === "load" ? "Opening draft…" : "Saving draft…"}</div> : null}
      <div className="east-composer-safe-note"><Check size={12} />East will not send until you review this message and choose Send.</div>
    </dialog>
  );
}
