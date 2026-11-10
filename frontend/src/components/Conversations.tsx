import { useCallback, useEffect, useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import {
  AlertCircle,
  Archive,
  ArchiveRestore,
  Bell,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  Download,
  Ellipsis,
  Expand,
  Eye,
  FileText,
  Forward,
  Inbox,
  LoaderCircle,
  Mail,
  MailOpen,
  MessageCircleMore,
  MoreHorizontal,
  Paperclip,
  Plus,
  RefreshCw,
  Reply,
  ReplyAll,
  Search,
  Send,
  ShieldAlert,
  ShieldCheck,
  Star,
  Tag,
  X,
  Trash2,
  type LucideIcon,
} from "lucide-react";
import { demoAccounts, demoThreads, type DemoAccount, type DemoThread } from "../data/demo-data";
import { DraftComposer, type ComposerRequest } from "./Composer";
import {
  ConversationsApiError,
  getMailboxes,
  getAttachmentBlob,
  getThread,
  getCachedThread,
  getThreadPage,
  isGmailCategoryLabel,
  type AttachmentMetadata,
  type GmailCategory,
  type GmailLabel,
  type Mailbox,
  type MailboxIdentity,
  type ThreadDetail,
  type ThreadListQuery,
  type ThreadMessage,
  type ThreadSummary,
} from "../lib/conversations-api";
import {
  MailActionsApiError,
  createUserLabel,
  deleteUserLabel,
  listMailboxLabels,
  renameUserLabel,
  updateThreadAction,
  updateThreadsAction,
  type MailAction,
  type MailActionOutcome,
  type MailLabelRecord,
  type MailLabelType,
} from "../lib/mail-actions-api";
import type { DraftKind } from "../lib/drafts-api";
import type { ReportSourceReference } from "../lib/reports-api";
import { mailboxColorAt } from "../lib/mailbox-colors";
import "./conversations.css";

type Density = "comfortable" | "compact";

const accountById = new Map(demoAccounts.map((account) => [account.id, account] as const));
const folders: { name: string; count?: number; icon: LucideIcon; active?: boolean }[] = [
  { name: "Inbox", count: 8, icon: Inbox, active: true },
  { name: "Starred", icon: Star },
  { name: "Snoozed", icon: Bell },
  { name: "Sent", icon: Send },
  { name: "Drafts", count: 2, icon: FileText },
  { name: "All mail", icon: ArchiveRestore },
  { name: "Spam", icon: ShieldAlert },
  { name: "Trash", icon: Trash2 },
];
const categories = ["Primary", "Social", "Promotions", "Updates", "Forums"] as const;

function AccountMark({ account, size = "normal" }: { account: DemoAccount; size?: "normal" | "small" }) {
  return (
    <span className={`conversations-account-mark conversations-account-mark--${account.color} conversations-account-mark--${size}`} aria-hidden="true">
      {account.monogram}
    </span>
  );
}

function StaticAction({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <button className="conversations-icon-button" type="button" disabled title={`${label} — visual prototype only`} aria-label={`${label} (visual prototype only)`}>
      {children}
    </button>
  );
}

function FolderList() {
  return (
    <aside className="conversations-folder-rail" aria-label="Mailbox folders">
      <div className="conversations-folder-account">
        <div className="conversations-account-stack" aria-hidden="true">
          {demoAccounts.map((account) => <AccountMark account={account} size="small" key={account.id} />)}
        </div>
        <span>All accounts</span>
        <ChevronDown size={15} aria-hidden="true" />
      </div>

      <div className="conversations-folder-group">
        {folders.map(({ name, count, icon: Icon, active }) => (
          <div className={`conversations-folder-row${active ? " is-active" : ""}`} key={name} aria-current={active ? "page" : undefined} title={`${name} — visual prototype only`}>
            <Icon size={17} strokeWidth={1.8} aria-hidden="true" />
            <span>{name}</span>
            {count ? <span className="conversations-folder-count">{count}</span> : null}
          </div>
        ))}
      </div>

      <div className="conversations-label-heading">
        <span>Labels</span>
        <button type="button" disabled className="conversations-label-add" aria-label="Create label (visual prototype only)" title="Create label — visual prototype only">+</button>
      </div>
      <div className="conversations-folder-group conversations-label-list">
        <div className="conversations-label-row"><span className="conversations-label-square conversations-label-square--blue" />Projects</div>
        <div className="conversations-label-row"><span className="conversations-label-square conversations-label-square--green" />Receipts</div>
        <div className="conversations-label-row"><span className="conversations-label-square conversations-label-square--purple" />Personal</div>
        <div className="conversations-label-row"><span className="conversations-label-square conversations-label-square--orange" />Operations</div>
      </div>

      <div className="conversations-folder-note">
        <span className="conversations-demo-dot" />
        <span>Demo mailbox<br /><strong>Sample data only</strong></span>
      </div>
    </aside>
  );
}

function ThreadRow({
  thread,
  selected,
  onSelect,
  density,
}: {
  thread: DemoThread;
  selected: boolean;
  onSelect: (id: string) => void;
  density: Density;
}) {
  const account = accountById.get(thread.accountId)!;
  const initial = thread.sender === "You" ? "Y" : thread.sender.charAt(0);

  return (
    <button
      type="button"
      className={`conversations-thread-row${thread.unread ? " is-unread" : ""}${selected ? " is-selected" : ""}`}
      data-density={density}
      onClick={() => onSelect(thread.id)}
      aria-current={selected ? "true" : undefined}
      aria-label={`${thread.sender}, ${thread.subject}, account ${account.name}, ${thread.time}`}
    >
      <span className="conversations-row-check" aria-hidden="true"><span /></span>
      <span className={`conversations-sender-avatar conversations-sender-avatar--${account.color}`} aria-hidden="true">{initial}</span>
      <span className="conversations-thread-copy">
        <span className="conversations-thread-topline">
          <span className="conversations-thread-sender">{thread.sender}</span>
          <span className="conversations-thread-time">{thread.time}</span>
        </span>
        <span className="conversations-thread-subject">{thread.subject}</span>
        <span className="conversations-thread-preview">{thread.preview}</span>
        <span className="conversations-thread-meta">
          <span className={`conversations-account-chip conversations-account-chip--${account.color}`}><span />{account.name}</span>
          {thread.labels?.map((label) => <span className="conversations-mini-label" key={label}>{label}</span>)}
          <span className="conversations-category-hint">{thread.category}</span>
        </span>
      </span>
      <span className={`conversations-thread-star${thread.starred ? " is-starred" : ""}`} aria-label={thread.starred ? "Starred" : undefined}>
        <Star size={16} fill={thread.starred ? "currentColor" : "none"} aria-hidden="true" />
      </span>
    </button>
  );
}

function ThreadReader({ thread, onBack, listCollapsed, onShowList }: { thread: DemoThread; onBack: () => void; listCollapsed: boolean; onShowList: () => void }) {
  const account = accountById.get(thread.accountId)!;

  return (
    <section className="conversations-reader-pane" aria-label="Selected conversation">
      <div className="conversations-reader-toolbar">
        <button type="button" className="conversations-back-button" onClick={onBack} aria-label="Back to message list">
          <ChevronLeft size={18} />
          <span>Inbox</span>
        </button>
        {listCollapsed ? <button type="button" className="conversations-pane-restore" onClick={onShowList}><ChevronLeft size={14} aria-hidden="true" />Show list</button> : null}
        <div className="conversations-reader-actions" aria-label="Message actions">
          <StaticAction label="Archive"><Archive size={17} /></StaticAction>
          <StaticAction label="Mark unread"><MailOpen size={17} /></StaticAction>
          <StaticAction label="Mark as spam"><ShieldAlert size={17} /></StaticAction>
          <StaticAction label="Mark as not spam"><ShieldCheck size={17} /></StaticAction>
          <StaticAction label="Move to trash"><Trash2 size={17} /></StaticAction>
          <span className="conversations-toolbar-divider" />
          <StaticAction label="More actions"><Ellipsis size={18} /></StaticAction>
        </div>
        <span className="conversations-message-position">1 of 8</span>
        <StaticAction label="Previous message"><ChevronLeft size={17} /></StaticAction>
        <StaticAction label="Next message"><ChevronRight size={17} /></StaticAction>
      </div>

      <div className="conversations-reader-scroll">
        <div className="conversations-thread-heading">
          <div className="conversations-thread-title-line">
            <h2>{thread.subject}</h2>
            <span className="conversations-inbox-tag"><Inbox size={12} />Inbox</span>
          </div>
          <div className="conversations-thread-context">
            <span className={`conversations-account-chip conversations-account-chip--${account.color}`}><span />{account.name}</span>
            <span>to you</span>
            <span className="conversations-context-separator">·</span>
            <span>{thread.date}</span>
          </div>
        </div>

        <div className="conversations-message-stack">
          {thread.messages.map((message, index) => {
            const isLatest = index === 0;
            const messageInitial = message.sender === "You" ? "Y" : message.sender.charAt(0);
            return (
              <article className={`conversations-message-card${isLatest ? " is-latest" : " is-collapsed"}`} key={message.id}>
                <div className={`conversations-message-avatar${message.sender === "You" ? " is-you" : ""}`} aria-hidden="true">{messageInitial}</div>
                <div className="conversations-message-content">
                  <div className="conversations-message-header">
                    <div>
                      <div className="conversations-message-sender">{message.sender}</div>
                      <div className="conversations-message-recipient">{message.address} <span>to {message.to}</span></div>
                    </div>
                    <span className="conversations-message-time">{message.time}</span>
                  </div>
                  {isLatest ? (
                    <div className="conversations-message-body">
                      {message.body.map((paragraph, paragraphIndex) => <p key={`${message.id}-${paragraphIndex}`}>{paragraph}</p>)}
                    </div>
                  ) : (
                    <p className="conversations-collapsed-preview">{message.body[0]}</p>
                  )}
                </div>
              </article>
            );
          })}
        </div>

        <div className="conversations-reply-placeholder" aria-label="Reply composer preview">
          <span className="conversations-message-avatar is-you" aria-hidden="true">Y</span>
          <div className="conversations-reply-box">
            <div className="conversations-reply-to"><Reply size={14} />Reply to {thread.sender}</div>
            <span className="conversations-reply-note">Reply controls are shown for preview only</span>
            <div className="conversations-reply-controls">
              <span>Formatting · Attach file</span>
              <button type="button" disabled title="Sending is not available in the demo"><Send size={13} />Send</button>
            </div>
          </div>
        </div>
        <div className="conversations-reader-footer"><span className="conversations-demo-dot" />Sample thread · Not connected to Gmail</div>
      </div>
    </section>
  );
}

// Each pane stays at least 30% open; reaching 20% previews collapse during the drag.
const PANE_MIN_SIZE_RATIO = 0.3;
const PANE_COLLAPSE_THRESHOLD_RATIO = 0.2;
const PANE_DIVIDER_WIDTH = 12;

function ConversationContentFrame({
  isThreadOpen,
  selectionKey,
  renderList,
  renderReader,
}: {
  isThreadOpen: boolean;
  selectionKey: string | null;
  renderList: (readerCollapsed: boolean, onShowReader: () => void) => ReactNode;
  renderReader: (listCollapsed: boolean, onShowList: () => void) => ReactNode;
}) {
  const [listRatio, setListRatio] = useState(30);
  const [collapsedPane, setCollapsedPane] = useState<"list" | "reader" | null>(null);
  const [dragging, setDragging] = useState(false);
  const frameRef = useRef<HTMLDivElement>(null);
  const dividerRef = useRef<HTMLButtonElement>(null);
  const dragStartXRef = useRef<number | null>(null);
  const dragStartedCollapsedPaneRef = useRef<"list" | "reader" | null>(null);
  const dragCollapsePreviewRef = useRef<"list" | "reader" | null>(null);
  const dragReopenTransitionRef = useRef(false);
  const dragMovedRef = useRef(false);
  const ignoreNextDividerClickRef = useRef(false);
  const focusRestoreAfterCollapseRef = useRef(false);
  const lastExpandedRatioRef = useRef(30);
  const previousSelectionKeyRef = useRef(selectionKey);
  const effectiveCollapsedPane = isThreadOpen ? collapsedPane : null;
  const listCollapsed = effectiveCollapsedPane === "list";
  const readerCollapsed = effectiveCollapsedPane === "reader";
  const canResize = isThreadOpen;
  const gridTemplateColumns = !isThreadOpen
    ? "minmax(0, 1fr)"
    : listCollapsed
      ? "0fr 0px 1fr"
      : readerCollapsed
        ? "1fr 0px 0fr"
        : `${listRatio}fr ${PANE_DIVIDER_WIDTH}px ${100 - listRatio}fr`;

  useEffect(() => {
    if (!isThreadOpen) {
      setCollapsedPane(null);
      setDragging(false);
      dragStartXRef.current = null;
      dragStartedCollapsedPaneRef.current = null;
      dragCollapsePreviewRef.current = null;
      dragReopenTransitionRef.current = false;
      dragMovedRef.current = false;
    } else if (previousSelectionKeyRef.current !== selectionKey) {
      setCollapsedPane(null);
      setDragging(false);
      dragStartXRef.current = null;
      dragCollapsePreviewRef.current = null;
      dragReopenTransitionRef.current = false;
    }
    previousSelectionKeyRef.current = selectionKey;
  }, [isThreadOpen, selectionKey]);

  useEffect(() => {
    if (!collapsedPane || !focusRestoreAfterCollapseRef.current) return;
    frameRef.current?.querySelector<HTMLButtonElement>(".conversations-pane-restore")?.focus();
    focusRestoreAfterCollapseRef.current = false;
  }, [collapsedPane]);

  const showList = useCallback(() => setCollapsedPane(null), []);
  const showReader = useCallback(() => setCollapsedPane(null), []);

  const getPaneGeometry = () => {
    const frame = frameRef.current;
    if (!frame) return null;
    const frameRect = frame.getBoundingClientRect();
    return {
      contentLeft: frameRect.left + 9,
      availableWidth: frameRect.width - 30,
    };
  };

  const getListWidthAtPointer = (clientX: number, geometry: NonNullable<ReturnType<typeof getPaneGeometry>>) => {
    return Math.max(0, Math.min(geometry.availableWidth, clientX - geometry.contentLeft - PANE_DIVIDER_WIDTH / 2));
  };

  const handleDividerPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!canResize || event.button !== 0) return;
    event.preventDefault();
    dragStartXRef.current = event.clientX;
    dragStartedCollapsedPaneRef.current = collapsedPane;
    dragCollapsePreviewRef.current = null;
    dragReopenTransitionRef.current = false;
    dragMovedRef.current = false;
    ignoreNextDividerClickRef.current = false;
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(!collapsedPane);
  };

  const handleDividerPointerMove = (clientX: number) => {
    if (dragStartXRef.current === null) return;
    if (Math.abs(clientX - dragStartXRef.current) > 3) {
      dragMovedRef.current = true;
      if (dragStartedCollapsedPaneRef.current && !dragReopenTransitionRef.current) setDragging(true);
    }
    if (dragStartedCollapsedPaneRef.current && !dragMovedRef.current) return;
    const geometry = getPaneGeometry();
    if (!geometry || geometry.availableWidth <= 0) return;
    const { availableWidth: availablePaneWidth } = geometry;
    const listWidth = getListWidthAtPointer(clientX, geometry);
    const readerWidth = availablePaneWidth - listWidth;
    const minRatio = PANE_MIN_SIZE_RATIO * 100;
    const maxRatio = 100 - minRatio;
    const pointerRatio = listWidth / availablePaneWidth * 100;
    const collapseThreshold = availablePaneWidth * PANE_COLLAPSE_THRESHOLD_RATIO;

    if (dragStartedCollapsedPaneRef.current) {
      const startedCollapsedPane = dragStartedCollapsedPaneRef.current;
      const draggedPastThreshold = startedCollapsedPane === "list"
        ? listWidth > collapseThreshold
        : readerWidth > collapseThreshold;
      if (!draggedPastThreshold) return;

      dragStartedCollapsedPaneRef.current = null;
      dragReopenTransitionRef.current = true;
      setCollapsedPane(null);
      setListRatio(startedCollapsedPane === "list" ? minRatio : maxRatio);
      setDragging(false);
      return;
    }

    const collapsePreview = listWidth <= collapseThreshold
      ? "list"
      : readerWidth <= collapseThreshold
        ? "reader"
        : null;

    if (collapsePreview) {
      dragCollapsePreviewRef.current = collapsePreview;
      dragReopenTransitionRef.current = false;
      setCollapsedPane(collapsePreview);
      // Re-enable the grid-track transition while the pointer remains captured.
      setDragging(false);
      return;
    }

    if (dragCollapsePreviewRef.current) {
      const previouslyCollapsed = dragCollapsePreviewRef.current;
      dragCollapsePreviewRef.current = null;
      dragReopenTransitionRef.current = true;
      setCollapsedPane(null);
      // Crossing back above 20% restores at the 30% minimum before tracking again.
      setListRatio(previouslyCollapsed === "list" ? minRatio : maxRatio);
      // Keep the grid transition enabled until this reopen finishes, then resume direct resizing.
      setDragging(false);
      return;
    }

    const visibleRatio = Math.max(minRatio, Math.min(maxRatio, pointerRatio));
    setListRatio(visibleRatio);
    const minimumPaneWidth = availablePaneWidth * PANE_MIN_SIZE_RATIO;
    if (listWidth >= minimumPaneWidth && readerWidth >= minimumPaneWidth) {
      lastExpandedRatioRef.current = visibleRatio;
    }
  };

  const finishDividerDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const clickToRestore = Boolean(dragStartedCollapsedPaneRef.current) && !dragMovedRef.current;
    const wasDrag = dragMovedRef.current && !clickToRestore;
    dragCollapsePreviewRef.current = null;
    dragReopenTransitionRef.current = false;
    if (dragMovedRef.current && !clickToRestore) {
      const geometry = getPaneGeometry();
      const availablePaneWidth = geometry?.availableWidth ?? 0;
      if (geometry && availablePaneWidth > 0) {
        const listWidth = getListWidthAtPointer(event.clientX, geometry);
        const readerWidth = availablePaneWidth - listWidth;
        const collapseThreshold = availablePaneWidth * PANE_COLLAPSE_THRESHOLD_RATIO;
        const collapsed = listWidth <= collapseThreshold ? "list" : readerWidth <= collapseThreshold ? "reader" : null;
        if (collapsed) {
          focusRestoreAfterCollapseRef.current = document.activeElement === dividerRef.current;
          setCollapsedPane(collapsed);
          setListRatio(lastExpandedRatioRef.current);
        } else {
          const minRatio = PANE_MIN_SIZE_RATIO * 100;
          const maxRatio = 100 - minRatio;
          const ratio = Math.max(minRatio, Math.min(maxRatio, Math.round(listWidth / availablePaneWidth * 100)));
          setCollapsedPane(null);
          setListRatio(ratio);
          lastExpandedRatioRef.current = ratio;
        }
      }
    }
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    dragStartXRef.current = null;
    dragStartedCollapsedPaneRef.current = null;
    dragMovedRef.current = false;
    setDragging(false);
    if (wasDrag) {
      ignoreNextDividerClickRef.current = true;
      window.setTimeout(() => {
        ignoreNextDividerClickRef.current = false;
      }, 0);
    }
  };

  const handleDividerClick = () => {
    if (ignoreNextDividerClickRef.current) {
      ignoreNextDividerClickRef.current = false;
      return;
    }
    if (!collapsedPane) return;
    setCollapsedPane(null);
    setListRatio(lastExpandedRatioRef.current);
  };

  const handleDividerKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      const adjustment = event.key === "ArrowLeft" ? -5 : 5;
      const availablePaneWidth = (frameRef.current?.getBoundingClientRect().width ?? 0) - 30;
      if (availablePaneWidth <= 0) return;
      const minRatio = PANE_MIN_SIZE_RATIO * 100;
      const maxRatio = 100 - minRatio;
      const nextRatio = listRatio + adjustment;
      if (nextRatio < minRatio) {
        focusRestoreAfterCollapseRef.current = document.activeElement === dividerRef.current;
        setCollapsedPane("list");
      } else if (nextRatio > maxRatio) {
        focusRestoreAfterCollapseRef.current = document.activeElement === dividerRef.current;
        setCollapsedPane("reader");
      } else {
        setListRatio(nextRatio);
        lastExpandedRatioRef.current = nextRatio;
      }
    } else if (event.key === "Home") {
      event.preventDefault();
      focusRestoreAfterCollapseRef.current = document.activeElement === dividerRef.current;
      setCollapsedPane("list");
    } else if (event.key === "End") {
      event.preventDefault();
      focusRestoreAfterCollapseRef.current = document.activeElement === dividerRef.current;
      setCollapsedPane("reader");
    }
  };

  return (
    <div
      ref={frameRef}
      className={`conversations-content-frame${isThreadOpen ? " is-thread-open" : ""}${listCollapsed ? " is-list-collapsed" : ""}${readerCollapsed ? " is-reader-collapsed" : ""}${dragging ? " is-dragging" : ""}`}
      style={{ gridTemplateColumns }}
      onTransitionEnd={(event) => {
        if (event.target !== event.currentTarget || event.propertyName !== "grid-template-columns" || !dragReopenTransitionRef.current) return;
        dragReopenTransitionRef.current = false;
        if (dragStartXRef.current !== null) setDragging(true);
      }}
    >
      {renderList(readerCollapsed, showReader)}
      {canResize ? (
        <button
          type="button"
          className={`conversations-pane-divider${dragging ? " is-dragging" : ""}${listCollapsed || readerCollapsed ? " is-collapsed" : ""}`}
          role="separator"
          aria-label={listCollapsed ? "Show or resize email list" : readerCollapsed ? "Show or resize email contents" : "Resize email list and contents"}
          aria-orientation="vertical"
          aria-valuemin={PANE_MIN_SIZE_RATIO * 100}
          aria-valuemax={(1 - PANE_MIN_SIZE_RATIO) * 100}
          aria-valuenow={Math.round(listRatio)}
          aria-valuetext={listCollapsed ? "Email list collapsed. Activate or drag to reopen." : readerCollapsed ? "Email contents collapsed. Activate or drag to reopen." : undefined}
          onPointerDown={handleDividerPointerDown}
          onPointerMove={(event) => handleDividerPointerMove(event.clientX)}
          onPointerUp={finishDividerDrag}
          onPointerCancel={finishDividerDrag}
          onLostPointerCapture={() => {
            setDragging(false);
          }}
          onKeyDown={handleDividerKeyDown}
          onClick={handleDividerClick}
        >
          {listCollapsed ? <ChevronLeft size={14} aria-hidden="true" /> : readerCollapsed ? <ChevronRight size={14} aria-hidden="true" /> : null}
        </button>
      ) : null}
      {renderReader(listCollapsed, showList)}
    </div>
  );
}

function DemoConversations() {
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  const [density, setDensity] = useState<Density>("comfortable");
  const selectedThread = useMemo(
    () => demoThreads.find((thread) => thread.id === selectedThreadId) ?? null,
    [selectedThreadId],
  );

  return (
    <section className={`conversations-page${selectedThread ? " conversations-page--mobile-reader" : ""}`}>
      <header className="conversations-page-heading">
        <div>
          <div className="conversations-eyebrow">MAILBOX <span>·</span> ALL ACCOUNTS</div>
          <h1>Conversations</h1>
          <p>One view across three sample inboxes.</p>
        </div>
        <div className="conversations-heading-actions">
          <span className="conversations-sample-pill"><span />Demo workspace</span>
          <button className="conversations-account-picker" type="button" disabled title="Account selection — visual prototype only">
            <span className="conversations-account-stack" aria-hidden="true">
              {demoAccounts.map((account) => <AccountMark account={account} size="small" key={account.id} />)}
            </span>
            <span>All accounts</span>
            <ChevronDown size={15} />
          </button>
          <button className="conversations-compose-button" type="button" disabled title="Compose — visual prototype only">
            <Mail size={16} />Compose
          </button>
        </div>
      </header>

      <div className={`conversations-mail-layout${selectedThread ? " is-thread-open" : ""}`}>
        <FolderList />

        <ConversationContentFrame
          isThreadOpen={Boolean(selectedThread)}
          selectionKey={selectedThread?.id ?? null}
          renderList={(readerCollapsed, onShowReader) => (
        <section className="conversations-list-pane" aria-label="Inbox thread list">
          <div className="conversations-list-toolbar">
            <div className="conversations-search-wrap">
              <Search size={16} aria-hidden="true" />
              <input type="search" placeholder="Search all conversations" aria-label="Search all conversations (visual prototype only)" readOnly />
              <span className="conversations-search-shortcut">⌘ K</span>
            </div>
            <div className="conversations-list-controls">
              <div className="conversations-filter-chips" aria-label="Inbox filters">
                <span className="conversations-filter-chip is-active">Inbox</span>
                <span className="conversations-filter-chip">Unread</span>
                <span className="conversations-filter-chip">Read</span>
                <span className="conversations-filter-chip">Starred</span>
                <span className="conversations-filter-chip"><Tag size={12} />Labels <ChevronDown size={12} /></span>
              </div>
              <div className="conversations-density-control" role="group" aria-label="Message list density">
                <button type="button" onClick={() => setDensity("comfortable")} className={density === "comfortable" ? "is-selected" : ""} aria-pressed={density === "comfortable"} title="Comfortable row density">
                  <span className="conversations-density-lines conversations-density-lines--comfortable" aria-hidden="true"><i /><i /><i /></span>
                  <span className="conversations-sr-only">Comfortable</span>
                </button>
                <button type="button" onClick={() => setDensity("compact")} className={density === "compact" ? "is-selected" : ""} aria-pressed={density === "compact"} title="Compact row density">
                  <span className="conversations-density-lines conversations-density-lines--compact" aria-hidden="true"><i /><i /><i /></span>
                  <span className="conversations-sr-only">Compact</span>
                </button>
              </div>
            </div>
          </div>

          <nav className="conversations-category-tabs" aria-label="Inbox categories">
            {categories.map((category, index) => (
              <span className={`conversations-category-tab${index === 0 ? " is-active" : ""}`} aria-current={index === 0 ? "page" : undefined} key={category}>
                {index === 0 ? <Inbox size={15} /> : null}
                <span>{category}</span>
                {index === 0 ? <span className="conversations-primary-count">8</span> : null}
              </span>
            ))}
          </nav>

          <div className="conversations-list-summary">
            <span>Showing <strong>8</strong> sample conversations</span>
            <div className="conversations-list-summary-actions">
              <span>Newest first</span><ChevronDown size={13} />
              <span className="conversations-toolbar-divider" />
              <StaticAction label="More list options"><MoreHorizontal size={16} /></StaticAction>
            </div>
            {readerCollapsed ? <button type="button" className="conversations-pane-restore" onClick={onShowReader}><MailOpen size={14} aria-hidden="true" />Show email</button> : null}
          </div>

          <div className="conversations-thread-list" data-density={density}>
            {demoThreads.map((thread) => (
              <ThreadRow
                key={thread.id}
                thread={thread}
                selected={thread.id === selectedThreadId}
                onSelect={(threadId) => {
                  onShowReader();
                  setSelectedThreadId(threadId);
                }}
                density={density}
              />
            ))}
          </div>
          <div className="conversations-list-footer"><span>End of sample inbox</span><span>8 of 8</span></div>
        </section>

          )}
          renderReader={(listCollapsed, onShowList) => (
        <div className="conversations-reader-stage" aria-hidden={!selectedThread}>
          {selectedThread ? (
            <ThreadReader thread={selectedThread} onBack={() => setSelectedThreadId(null)} listCollapsed={listCollapsed} onShowList={onShowList} />
          ) : (
            <section className="conversations-reader-empty" aria-label="No conversation selected">
              <div className="conversations-empty-icon"><MessageCircleMore size={22} /></div>
              <strong>Select a conversation</strong>
              <span>Choose a thread to read its sample messages.</span>
            </section>
          )}
        </div>
          )}
        />
      </div>

      <div className="conversations-prototype-note"><CircleHelp size={13} />Sample data only. Search, filters, labels, and mailbox actions are not connected.</div>
    </section>
  );
}

type ConversationSourceNavigation = Pick<ReportSourceReference, "mailboxId" | "threadId" | "messageId">;

export default function Conversations({
  realMailboxMode = false,
  sourceNavigation = null,
  onSourceNavigationHandled,
}: {
  realMailboxMode?: boolean;
  sourceNavigation?: ConversationSourceNavigation | null;
  onSourceNavigationHandled?: () => void;
}) {
  return realMailboxMode
    ? <RealConversations sourceNavigation={sourceNavigation} onSourceNavigationHandled={onSourceNavigationHandled} />
    : <DemoConversations />;
}

const gmailFolders: { name: string; value: string; icon: LucideIcon }[] = [
  { name: "Inbox", value: "INBOX", icon: Inbox },
  { name: "Starred", value: "STARRED", icon: Star },
  { name: "Snoozed", value: "SNOOZED", icon: Bell },
  { name: "Sent", value: "SENT", icon: Send },
  { name: "Drafts", value: "DRAFTS", icon: FileText },
  { name: "All mail", value: "ALL_MAIL", icon: ArchiveRestore },
  { name: "Spam", value: "SPAM", icon: ShieldAlert },
  { name: "Trash", value: "TRASH", icon: Trash2 },
];

const categoryTitles: Record<GmailCategory, string> = {
  PRIMARY: "Primary",
  SOCIAL: "Social",
  PROMOTIONS: "Promotions",
  UPDATES: "Updates",
  FORUMS: "Forums",
};

type ReadFilter = "all" | "unread" | "read" | "starred";
type MailLabelOption = GmailLabel & { mailboxId: string; type: MailLabelType | null };
type MailboxLabelCatalogState = { labels: MailLabelRecord[]; loading: boolean; error: string | null };
type ActionFeedback = {
  kind: "success" | "partial" | "error";
  message: string;
  outcomes?: { threadId: string; subject: string; account: string; success: boolean; reason: string | null }[];
};

const maxParallelLabelCatalogRequests = 3;
const maxBulkThreadCount = 100;

function actionFailureReason(outcome: MailActionOutcome): string | null {
  if (outcome.success) return null;
  if (outcome.errorCode === "NOT_FOUND") return "No longer available";
  if (outcome.errorCode === "LABEL_NOT_FOUND") return "Label is not available for this account";
  if (outcome.errorCode === "AUTHORIZATION_REQUIRED") return "Reconnect this mailbox";
  if (outcome.errorCode === "VAULT_LOCKED") return "Unlock East";
  if (outcome.errorCode === "GMAIL_API_ERROR") {
    return outcome.gmailHttpStatus ? `Gmail rejected it (HTTP ${outcome.gmailHttpStatus})` : "Gmail rejected it";
  }
  if (outcome.errorCode === "LOCAL_SYNC_FAILED") return "Gmail changed; local view needs attention";
  return "Could not update";
}

function bulkRequestFailureOutcome(threadId: string, failure: unknown): MailActionOutcome {
  const status = failure instanceof MailActionsApiError ? failure.status : null;
  return {
    threadId,
    success: false,
    errorCode: status === 401 || status === 403 ? "AUTHORIZATION_REQUIRED" : "BULK_REQUEST_FAILED",
    gmailHttpStatus: null,
    googleErrorCode: null,
  };
}

function actionSuccessMessage(action: MailAction): string {
  switch (action) {
    case "ARCHIVE": return "Archived the conversation.";
    case "MOVE_TO_INBOX": return "Moved the conversation to Inbox.";
    case "TRASH": return "Moved the conversation to Trash.";
    case "UNTRASH": return "Restored the conversation from Trash.";
    case "MARK_READ": return "Marked the conversation as read.";
    case "MARK_UNREAD": return "Marked the conversation as unread.";
    case "SPAM": return "Marked the conversation as spam.";
    case "NOT_SPAM": return "Marked the conversation as not spam.";
    case "STAR": return "Starred the conversation.";
    case "UNSTAR": return "Removed the star from the conversation.";
    case "ADD_LABELS": return "Added the label to the conversation.";
    case "REMOVE_LABELS": return "Removed the label from the conversation.";
  }
}

function accountColor(identity: MailboxIdentity | null): string {
  if (!identity) return "#88c0d0";
  if (identity.color && /^#[0-9a-fA-F]{6}$/.test(identity.color)) return identity.color;
  if (identity.colorIndex !== null) return mailboxColorAt(identity.colorIndex) ?? "#88c0d0";
  return "#88c0d0";
}

function formatTime(value: string, full = false): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  const date = new Date(timestamp);
  const options: Intl.DateTimeFormatOptions = full
    ? { dateStyle: "medium", timeStyle: "short" }
    : date.toDateString() === new Date().toDateString()
      ? { hour: "numeric", minute: "2-digit" }
      : { month: "short", day: "numeric" };
  return new Intl.DateTimeFormat(undefined, options).format(date);
}

function formatBytes(size: number | null): string | null {
  if (size === null) return null;
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(size < 10 * 1024 ? 1 : 0)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function RealAccountMark({ identity, small = false }: { identity: MailboxIdentity; small?: boolean }) {
  const style = { "--account-color": accountColor(identity) } as CSSProperties;
  const initial = identity.displayName.trim().charAt(0).toLocaleUpperCase() || identity.email.charAt(0).toLocaleUpperCase();
  return <span className={`conversations-account-mark conversations-account-mark--live${small ? " conversations-account-mark--small" : ""}`} style={style} aria-hidden="true">{initial}</span>;
}

function RealAccountChip({ identity, fallbackId }: { identity: MailboxIdentity | null; fallbackId?: string }) {
  if (!identity) {
    return <span className="conversations-account-chip"><span />Mailbox identity unavailable{fallbackId ? ` · ${fallbackId}` : ""}</span>;
  }
  const style = { "--account-color": accountColor(identity) } as CSSProperties;
  return <span className="conversations-account-chip" style={style}><span />{identity.displayName}</span>;
}

function MailboxScopePicker({
  value,
  mailboxes,
  onChange,
  ariaLabel,
  variant,
}: {
  value: string;
  mailboxes: Mailbox[];
  onChange: (value: string) => void;
  ariaLabel: string;
  variant: "heading" | "rail";
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const menuId = useId();
  const selectedMailbox = mailboxes.find((mailbox) => mailbox.id === value);
  const selectedLabel = value === "all"
    ? "All accounts"
    : selectedMailbox?.displayName ?? selectedMailbox?.email ?? "Selected account";
  const selectedIndex = value === "all" ? 0 : Math.max(0, mailboxes.findIndex((mailbox) => mailbox.id === value) + 1);

  useEffect(() => {
    if (!open) return;
    const outsidePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const outsideFocus = (event: FocusEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", outsidePointer);
    document.addEventListener("focusin", outsideFocus);
    return () => {
      document.removeEventListener("pointerdown", outsidePointer);
      document.removeEventListener("focusin", outsideFocus);
    };
  }, [open]);

  useEffect(() => {
    if (open) optionRefs.current[selectedIndex]?.focus();
  }, [open, selectedIndex]);

  function focusOption(index: number) {
    const count = mailboxes.length + 1;
    const next = (index + count) % count;
    optionRefs.current[next]?.focus();
  }

  function closeMenu(restoreFocus: boolean) {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }

  function handleTriggerKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    if (!open) setOpen(true);
    else focusOption(selectedIndex + (event.key === "ArrowDown" ? 1 : -1));
  }

  function handleOptionKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusOption(index + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusOption(index - 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      focusOption(0);
    } else if (event.key === "End") {
      event.preventDefault();
      focusOption(mailboxes.length);
    } else if (event.key === "Escape") {
      event.preventDefault();
      closeMenu(true);
    }
  }

  function renderOption(optionValue: string, index: number, mailbox?: Mailbox) {
    const isSelected = value === optionValue;
    const title = mailbox?.displayName ?? "All accounts";
    return (
      <button
        type="button"
        role="menuitemradio"
        aria-checked={isSelected}
        className="conversations-scope-option"
        key={optionValue}
        ref={(element) => { optionRefs.current[index] = element; }}
        tabIndex={isSelected ? 0 : -1}
        onKeyDown={(event) => handleOptionKeyDown(event, index)}
        onClick={() => {
          onChange(optionValue);
          closeMenu(true);
        }}
      >
        {mailbox
          ? <RealAccountMark identity={mailbox} small />
          : <span className="conversations-scope-option-stack" aria-hidden="true">{mailboxes.slice(0, 3).map((item) => <RealAccountMark identity={item} small key={item.id} />)}</span>}
        <span className="conversations-scope-option-copy">
          <span className="conversations-scope-option-name">{title}</span>
          <span className="conversations-scope-option-email">{mailbox?.email ?? "Combined mailbox view"}</span>
        </span>
        {isSelected ? <Check size={15} aria-hidden="true" /> : null}
      </button>
    );
  }

  return (
    <div className={`conversations-scope-picker conversations-scope-picker--${variant}`} ref={rootRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`conversations-account-picker conversations-account-picker--live conversations-scope-trigger${variant === "rail" ? " conversations-scope-trigger--rail" : ""}`}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        title={selectedLabel}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={handleTriggerKeyDown}
      >
        {selectedMailbox
          ? <RealAccountMark identity={selectedMailbox} small />
          : <span className="conversations-account-stack" aria-hidden="true">{mailboxes.slice(0, 3).map((mailbox) => <RealAccountMark identity={mailbox} small key={mailbox.id} />)}</span>}
        <span className="conversations-live-scope-name">{selectedLabel}</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>
      {open ? (
        <div id={menuId} className="conversations-scope-menu" role="menu" aria-label="Choose mailbox scope">
          {renderOption("all", 0)}
          {mailboxes.map((mailbox, index) => renderOption(mailbox.id, index + 1, mailbox))}
        </div>
      ) : null}
    </div>
  );
}

function RealThreadRow({
  thread,
  identity,
  selected,
  checked,
  density,
  mutationBusy,
  onOpen,
  onToggleSelection,
  onToggleStar,
}: {
  thread: ThreadSummary;
  identity: MailboxIdentity | null;
  selected: boolean;
  checked: boolean;
  density: Density;
  mutationBusy: boolean;
  onOpen: (threadId: string) => void;
  onToggleSelection: (threadId: string) => void;
  onToggleStar: (thread: ThreadSummary) => void;
}) {
  const initials = thread.sender.trim().charAt(0).toLocaleUpperCase() || "?";
  const style = { "--account-color": accountColor(identity) } as CSSProperties;
  const label = thread.sender + ", " + thread.subject + ", " + (identity?.displayName ?? "mailbox identity unavailable") + ", " + formatTime(thread.latestMessageAt, true);
  return (
    <div className={"conversations-live-thread-row" + (thread.unread ? " is-unread" : "") + (selected ? " is-selected" : "")} data-density={density}>
      <button
        type="button"
        className={"conversations-live-row-select" + (checked ? " is-checked" : "")}
        aria-label={(checked ? "Deselect" : "Select") + " conversation: " + (thread.subject || "No subject")}
        aria-pressed={checked}
        onClick={() => onToggleSelection(thread.id)}
        disabled={mutationBusy}
      >
        <span aria-hidden="true">{checked ? <Check size={13} strokeWidth={2.5} /> : null}</span>
      </button>
      <button
        type="button"
        className="conversations-live-thread-open"
        onClick={() => onOpen(thread.id)}
        aria-current={selected ? "true" : undefined}
        aria-label={label}
      >
        <span className="conversations-sender-avatar conversations-sender-avatar--live" style={style} aria-hidden="true">{initials}</span>
        <span className="conversations-thread-copy">
          <span className="conversations-thread-topline">
            <span className="conversations-thread-sender">{thread.sender}</span>
            <span className="conversations-thread-time">{formatTime(thread.latestMessageAt)}</span>
          </span>
          <span className="conversations-thread-subject">{thread.subject || "(No subject)"}</span>
          <span className="conversations-thread-preview">{thread.snippet ?? "Preview is not available in the local cache."}</span>
          <span className="conversations-thread-meta">
            <RealAccountChip identity={identity} />
            {thread.labels.map((label) => <span className="conversations-mini-label" key={label.id} title={label.name}>{label.name}</span>)}
            {thread.category ? <span className="conversations-category-hint">{categoryTitles[thread.category]}</span> : null}
            {thread.attachmentsAvailable ? <Paperclip className="conversations-row-attachment" size={12} aria-label="Has attachments" /> : null}
          </span>
        </span>
      </button>
      <button
        type="button"
        className={"conversations-thread-star conversations-live-star-button" + (thread.starred ? " is-starred" : "")}
        aria-label={(thread.starred ? "Unstar" : "Star") + " conversation: " + (thread.subject || "No subject")}
        aria-pressed={thread.starred}
        onClick={() => onToggleStar(thread)}
        disabled={mutationBusy}
      >
        <Star size={16} fill={thread.starred ? "currentColor" : "none"} aria-hidden="true" />
      </button>
    </div>
  );
}

type AttachmentPreview = {
  url: string;
  filename: string;
  mimeType: string;
};

const previewableImageTypes = new Set([
  "image/avif",
  "image/bmp",
  "image/gif",
  "image/jpeg",
  "image/png",
  "image/webp",
]);

function normalizedMimeType(value: string | null | undefined): string {
  return value?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
}

function supportsInlinePreview(mimeType: string | null | undefined): boolean {
  const normalized = normalizedMimeType(mimeType);
  return normalized === "application/pdf" || previewableImageTypes.has(normalized);
}

function safeDownloadName(filename: string): string {
  const cleaned = filename.replace(/[\\/\u0000-\u001f\u007f]+/g, "_").trim();
  return !cleaned || cleaned === "." || cleaned === ".." ? "attachment" : cleaned;
}

function AttachmentPreviewDialog({ preview, onClose }: { preview: AttachmentPreview; onClose: () => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const closeHandler = () => onClose();
    dialog.addEventListener("close", closeHandler);
    return () => {
      dialog.removeEventListener("close", closeHandler);
      if (dialog.open) dialog.close();
    };
  }, [onClose]);

  const handleDownload = () => {
    const anchor = document.createElement("a");
    anchor.href = preview.url;
    anchor.download = safeDownloadName(preview.filename);
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  };

  return (
    <dialog
      ref={dialogRef}
      className="conversations-attachment-preview"
      aria-labelledby="attachment-preview-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClick={(event) => { if (event.target === dialogRef.current) onClose(); }}
    >
      <header className="conversations-attachment-preview-header">
        <div>
          <span className="conversations-attachment-preview-eyebrow">MAIL ATTACHMENT</span>
          <h2 id="attachment-preview-title" title={preview.filename}>{preview.filename}</h2>
        </div>
        <button type="button" className="conversations-attachment-preview-close" onClick={onClose} aria-label="Close attachment preview"><X size={18} /></button>
      </header>
      <div className="conversations-attachment-preview-content">
        {preview.mimeType === "application/pdf" ? (
          <iframe title={`PDF preview: ${preview.filename}`} src={preview.url} sandbox="allow-same-origin" />
        ) : (
          <img src={preview.url} alt={preview.filename} />
        )}
      </div>
      <footer className="conversations-attachment-preview-footer">
        <span>Preview is held temporarily in this browser.</span>
        <button type="button" className="conversations-attachment-download" onClick={handleDownload}><Download size={14} />Download</button>
      </footer>
    </dialog>
  );
}

function AttachmentItem({
  attachment,
  threadId,
  messageId,
}: {
  attachment: AttachmentMetadata;
  threadId?: string;
  messageId?: string;
}) {
  const [busyAction, setBusyAction] = useState<"preview" | "download" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<AttachmentPreview | null>(null);
  const activeRequest = useRef<AbortController | null>(null);
  const previewUrl = preview?.url;
  const canAccess = Boolean(threadId && messageId && attachment.id);
  const canPreview = supportsInlinePreview(attachment.mimeType);

  useEffect(() => () => {
    activeRequest.current?.abort();
  }, []);

  useEffect(() => {
    if (!previewUrl) return;
    return () => URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const closePreview = useCallback(() => setPreview(null), []);

  async function loadAttachment(action: "preview" | "download") {
    if (!canAccess || !threadId || !messageId || !attachment.id || busyAction) return;
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setError(null);
    setBusyAction(action);
    try {
      const blob = await getAttachmentBlob(threadId, messageId, attachment.id, controller.signal);
      const responseMimeType = normalizedMimeType(blob.type);
      const metadataMimeType = normalizedMimeType(attachment.mimeType);
      const effectiveMimeType = responseMimeType || metadataMimeType || "application/octet-stream";
      if (action === "preview" && !supportsInlinePreview(responseMimeType)) {
        throw new ConversationsApiError("This file type cannot be previewed safely. Download it to open it.");
      }

      const downloadableBlob = blob.type ? blob : new Blob([blob], { type: effectiveMimeType });
      const url = URL.createObjectURL(downloadableBlob);
      if (action === "preview") {
        setPreview({ url, filename: attachment.filename, mimeType: responseMimeType });
      } else {
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = safeDownloadName(attachment.filename);
        anchor.rel = "noopener";
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
      }
    } catch (loadError) {
      if (!controller.signal.aborted) {
        setError(loadError instanceof Error ? loadError.message : "The attachment could not be loaded.");
      }
    } finally {
      if (activeRequest.current === controller) activeRequest.current = null;
      if (!controller.signal.aborted) setBusyAction(null);
    }
  }

  return (
    <div className="conversations-live-attachment-wrap">
      <div className="conversations-live-attachment">
        <FileText size={15} aria-hidden="true" />
        <span className="conversations-live-attachment-copy">
          <strong title={attachment.filename}>{attachment.filename}</strong>
          <small>{attachment.mimeType ?? "File"}{formatBytes(attachment.sizeBytes) ? ` · ${formatBytes(attachment.sizeBytes)}` : ""}</small>
        </span>
        <span className="conversations-attachment-controls">
          {canPreview ? (
            <button type="button" onClick={() => void loadAttachment("preview")} disabled={!canAccess || busyAction !== null} aria-label={`Preview ${attachment.filename}`}>
              {busyAction === "preview" ? <LoaderCircle size={13} className="conversations-attachment-spinner" /> : <Eye size={13} />}
              <span>{busyAction === "preview" ? "Loading" : "Preview"}</span>
            </button>
          ) : null}
          <button type="button" onClick={() => void loadAttachment("download")} disabled={!canAccess || busyAction !== null} aria-label={`Download ${attachment.filename}`}>
            {busyAction === "download" ? <LoaderCircle size={13} className="conversations-attachment-spinner" /> : <Download size={13} />}
            <span>{busyAction === "download" ? "Loading" : "Download"}</span>
          </button>
        </span>
      </div>
      {!canAccess ? <small className="conversations-attachment-unavailable">Attachment actions are unavailable for this item.</small> : null}
      {error ? <p className="conversations-attachment-error" role="alert">{error}</p> : null}
      {preview ? <AttachmentPreviewDialog preview={preview} onClose={closePreview} /> : null}
    </div>
  );
}

function AttachmentList({
  attachments,
  threadId,
  messageId,
}: {
  attachments: AttachmentMetadata[];
  threadId?: string;
  messageId?: string;
}) {
  if (attachments.length === 0) return null;
  return (
    <div className="conversations-live-attachments" aria-label="Attachment details">
      <span className="conversations-live-attachments-title"><Paperclip size={14} />Attachments</span>
      {attachments.map((attachment, index) => (
        <AttachmentItem
          attachment={attachment}
          threadId={threadId}
          messageId={messageId}
          key={attachment.id ?? `${attachment.filename}-${index}`}
        />
      ))}
    </div>
  );
}

function MessageBodyContent({ message }: { message: ThreadMessage }) {
  const htmlRef = useRef<HTMLDivElement | null>(null);
  const [externalImagesLoaded, setExternalImagesLoaded] = useState(false);
  const hasExternalImages = Boolean(message.bodyHtml?.includes("data-east-external-src="));

  useEffect(() => {
    const images = htmlRef.current?.querySelectorAll<HTMLImageElement>("img[data-east-external-src]");
    images?.forEach((image) => {
      if (!externalImagesLoaded) {
        image.removeAttribute("src");
        return;
      }
      const source = image.getAttribute("data-east-external-src");
      if (!source) return;
      try {
        const remoteUrl = new URL(source);
        if (remoteUrl.protocol !== "https:" && remoteUrl.protocol !== "http:") return;
        image.referrerPolicy = "no-referrer";
        image.setAttribute("referrerpolicy", "no-referrer");
        image.loading = "lazy";
        image.src = remoteUrl.href;
      } catch {
        // The backend also validates remote image URLs; malformed values stay inert here.
      }
    });
  }, [externalImagesLoaded, message.bodyHtml]);

  return (
    <>
      {hasExternalImages ? (
        <div className="conversations-external-image-control">
          <button
            type="button"
            aria-label={`${externalImagesLoaded ? "Hide" : "Load"} external images in this message`}
            onClick={() => setExternalImagesLoaded((loaded) => !loaded)}
          >
            {externalImagesLoaded ? "Hide external images" : "Load external images"}
          </button>
          <span aria-live="polite">{externalImagesLoaded ? "External images loaded." : "External images are blocked."}</span>
        </div>
      ) : null}
      {message.bodyHtml
        ? <div ref={htmlRef} className="conversations-message-body-html" dangerouslySetInnerHTML={{ __html: message.bodyHtml }} />
        : message.bodyText?.split(/\n\s*\n/).map((paragraph, index) => <p key={`${message.id}-${index}`}>{paragraph}</p>)}
    </>
  );
}

function MessageExpandControl({
  message,
  subject,
  threadId,
  onRetry,
}: {
  message: ThreadMessage;
  subject: string;
  threadId: string;
  onRetry: () => void;
}) {
  const [open, setOpen] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const dialogId = `east-expanded-message-${encodeURIComponent(message.id)}`;
  const titleId = `${dialogId}-title`;
  const closeDialog = useCallback(() => {
    const dialog = dialogRef.current;
    if (dialog?.open) dialog.close();
    else setOpen(false);
  }, []);

  useEffect(() => {
    if (!open) return;
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const handleClose = () => setOpen(false);
    dialog.addEventListener("close", handleClose);
    return () => {
      dialog.removeEventListener("close", handleClose);
      if (dialog.open) dialog.close();
    };
  }, [open]);

  return (
    <>
      <button
        type="button"
        className="conversations-message-expand-trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={dialogId}
        onClick={() => setOpen(true)}
      >
        <Expand size={13} aria-hidden="true" />Expand
      </button>
      {open ? (
        <dialog
          ref={dialogRef}
          id={dialogId}
          className="conversations-message-expanded-dialog"
          aria-labelledby={titleId}
          onCancel={(event) => { event.preventDefault(); closeDialog(); }}
          onClick={(event) => { if (event.target === dialogRef.current) closeDialog(); }}
        >
          <header className="conversations-message-expanded-header">
            <div>
              <span className="conversations-message-expanded-eyebrow">FULL EMAIL</span>
              <h2 id={titleId}>{subject || "(No subject)"}</h2>
            </div>
            <button type="button" className="conversations-attachment-preview-close" onClick={closeDialog} aria-label="Close expanded email"><X size={18} /></button>
          </header>
          <div className="conversations-message-expanded-scroll">
            <article className="conversations-message-expanded-card">
              <div className="conversations-message-expanded-meta">
                <div>
                  <div className="conversations-message-sender">{message.sender}</div>
                  <div className="conversations-message-recipient">
                    {message.senderEmail ?? "Sender address unavailable"}
                    {message.to.length > 0 || message.cc.length > 0 || message.bcc.length > 0 || message.replyTo ? (
                      <details className="conversations-message-recipient-details">
                        <summary>
                          {message.to.length > 0 ? `to ${message.to.join(", ")}` : "Message details"}
                          {message.cc.length > 0 || message.bcc.length > 0 ? " · includes Cc/Bcc" : ""}
                        </summary>
                        <dl>
                          {message.to.length > 0 ? <div><dt>To</dt><dd>{message.to.join(", ")}</dd></div> : null}
                          {message.cc.length > 0 ? <div><dt>Cc</dt><dd>{message.cc.join(", ")}</dd></div> : null}
                          {message.bcc.length > 0 ? <div><dt>Bcc</dt><dd>{message.bcc.join(", ")}</dd></div> : null}
                          {message.replyTo ? <div><dt>Reply-To</dt><dd>{message.replyTo}</dd></div> : null}
                        </dl>
                      </details>
                    ) : null}
                  </div>
                </div>
                <time className="conversations-message-time" dateTime={message.timestamp}>{formatTime(message.timestamp, true)}</time>
              </div>
              {message.bodyText === null ? (
                <p className="conversations-live-body-unavailable">
                  {message.bodyUnavailableReason === "ON_DEMAND"
                    ? "East could not load this message body from Gmail."
                    : message.bodyUnavailableReason
                    ? "This message exceeded East’s local display/cache limit."
                    : "Message body is not available in the local cache."}
                  {message.bodyUnavailableReason === "ON_DEMAND" || message.bodyUnavailableReason === null
                    ? <> <button type="button" onClick={onRetry}>Retry</button></>
                    : null}
                </p>
              ) : (
                <div className="conversations-message-body">
                  <MessageBodyContent message={message} />
                </div>
              )}
              <AttachmentList attachments={message.attachments} threadId={threadId} messageId={message.id} />
            </article>
          </div>
        </dialog>
      ) : null}
    </>
  );
}

function RealThreadReader({
  thread,
  identity,
  detail,
  loading,
  cacheState,
  refreshError,
  error,
  onBack,
  listCollapsed,
  onShowList,
  onRetry,
  labels,
  selectedLabelId,
  onSelectLabel,
  onAction,
  onCompose,
  mutationBusy,
  sourceMessageId,
}: {
  thread: ThreadSummary | null;
  identity: MailboxIdentity | null;
  detail: ThreadDetail | null;
  loading: boolean;
  cacheState: "none" | "refreshing" | "fresh" | "stale";
  refreshError: string | null;
  error: string | null;
  onBack: () => void;
  listCollapsed: boolean;
  onShowList: () => void;
  onRetry: () => void;
  labels: MailLabelOption[];
  selectedLabelId: string;
  onSelectLabel: (labelId: string) => void;
  onAction: (action: MailAction, labelIds?: string[]) => void;
  onCompose: (kind: DraftKind) => void;
  mutationBusy: boolean;
  sourceMessageId: string | null;
}) {
  useEffect(() => {
    if (!detail || !sourceMessageId) return;
    const target = document.getElementById(`east-source-message-${encodeURIComponent(sourceMessageId)}`);
    if (!target) return;
    target.scrollIntoView({ block: "center", behavior: "smooth" });
    target.focus({ preventScroll: true });
  }, [detail, sourceMessageId]);

  const messageAttachmentKeys = new Set((detail?.messages ?? []).flatMap((message) => message.attachments.map((attachment) => attachment.id ?? attachment.filename)));
  const unassignedAttachments = (detail?.attachments ?? []).filter((attachment) => !messageAttachmentKeys.has(attachment.id ?? attachment.filename));
  const currentLabels = detail?.labels ?? thread?.labels ?? [];
  const labelIsApplied = currentLabels.some((label) => label.id === selectedLabelId);
  const isTrashed = currentLabels.some((label) => label.id === "TRASH");
  const isInInbox = currentLabels.some((label) => label.id === "INBOX");
  const isInSpam = currentLabels.some((label) => label.id === "SPAM");
  const canAct = Boolean(thread || detail);
  const unread = thread?.unread ?? false;
  const starred = thread?.starred ?? false;

  return (
    <section className="conversations-reader-pane" aria-label="Selected conversation" aria-busy={loading}>
      <div className="conversations-reader-toolbar">
        <button type="button" className="conversations-back-button" onClick={onBack} aria-label="Back to message list">
          <ChevronLeft size={18} /><span>Inbox</span>
        </button>
        {listCollapsed ? <button type="button" className="conversations-pane-restore" onClick={onShowList}><ChevronLeft size={14} aria-hidden="true" />Show list</button> : null}
        <span className="conversations-live-read-note">Opening a thread does not mark it read.</span>
      </div>

      {detail && cacheState === "refreshing" ? (
        <div className="conversations-cache-state" role="status" aria-live="polite">
          <LoaderCircle size={14} aria-hidden="true" /><span>Showing saved message content while East checks Gmail for updates…</span>
        </div>
      ) : null}
      {detail && cacheState === "stale" ? (
        <div className="conversations-cache-state conversations-cache-state--stale" role="status" aria-live="polite">
          <AlertCircle size={14} aria-hidden="true" /><span>Showing saved message content. Gmail could not refresh: {refreshError ?? "the connection is unavailable."}</span>
          <button type="button" onClick={onRetry}>Retry</button>
        </div>
      ) : null}
      <div className="conversations-live-action-panel" role="group" aria-label="Conversation actions">
        {!isInSpam ? (
          <button
            type="button"
            className="conversations-live-action-button"
            onClick={() => onAction(isTrashed ? "UNTRASH" : isInInbox ? "ARCHIVE" : "MOVE_TO_INBOX")}
            disabled={mutationBusy || !canAct}
          >
            {isTrashed ? <ArchiveRestore size={14} aria-hidden="true" /> : isInInbox ? <Archive size={14} aria-hidden="true" /> : <Inbox size={14} aria-hidden="true" />}
            <span>{isTrashed ? "Restore from Trash" : isInInbox ? "Archive" : "Move to Inbox"}</span>
          </button>
        ) : null}
        <button type="button" className="conversations-live-action-button" onClick={() => onAction(unread ? "MARK_READ" : "MARK_UNREAD")} disabled={mutationBusy || !canAct}>
          {unread ? <MailOpen size={14} aria-hidden="true" /> : <Mail size={14} aria-hidden="true" />}<span>{unread ? "Mark read" : "Mark unread"}</span>
        </button>
        <button type="button" className="conversations-live-action-button" onClick={() => onAction("SPAM")} disabled={mutationBusy || !canAct}>
          <ShieldAlert size={14} aria-hidden="true" /><span>Spam</span>
        </button>
        <button type="button" className="conversations-live-action-button" onClick={() => onAction("NOT_SPAM")} disabled={mutationBusy || !canAct}>
          <ShieldCheck size={14} aria-hidden="true" /><span>Not spam</span>
        </button>
        {!isTrashed ? (
          <button type="button" className="conversations-live-action-button" onClick={() => onAction("TRASH")} disabled={mutationBusy || !canAct}>
            <Trash2 size={14} aria-hidden="true" /><span>Trash</span>
          </button>
        ) : null}
        <button type="button" className="conversations-live-action-button" onClick={() => onAction(starred ? "UNSTAR" : "STAR")} disabled={mutationBusy || !canAct}>
          <Star size={14} fill={starred ? "currentColor" : "none"} aria-hidden="true" /><span>{starred ? "Unstar" : "Star"}</span>
        </button>
        <label className="conversations-live-label-select">
          <span className="conversations-sr-only">Choose a user label</span>
          <select value={selectedLabelId} onChange={(event) => onSelectLabel(event.target.value)} disabled={mutationBusy || labels.length === 0}>
            <option value="">Choose label</option>
            {labels.map((label) => <option value={label.id} key={label.id}>{label.name}</option>)}
          </select>
          <ChevronDown size={13} aria-hidden="true" />
        </label>
        <button type="button" className="conversations-live-action-button" onClick={() => onAction("ADD_LABELS", [selectedLabelId])} disabled={mutationBusy || !canAct || !selectedLabelId || labelIsApplied}>
          <Tag size={14} aria-hidden="true" /><span>Add label</span>
        </button>
        <button type="button" className="conversations-live-action-button" onClick={() => onAction("REMOVE_LABELS", [selectedLabelId])} disabled={mutationBusy || !canAct || !selectedLabelId || !labelIsApplied}>
          <X size={14} aria-hidden="true" /><span>Remove label</span>
        </button>
      </div>

      {loading && !detail ? (
        <div className="conversations-live-state" role="status"><LoaderCircle size={19} /><span>Loading conversation…</span></div>
      ) : error && !detail ? (
        <div className="conversations-live-state conversations-live-state--error" role="alert">
          <AlertCircle size={19} /><strong>Conversation could not be loaded</strong><span>{error}</span>
          <button type="button" className="conversations-live-retry" onClick={onRetry}>Try again</button>
        </div>
      ) : detail ? (
        <div className="conversations-reader-scroll">
          <div className="conversations-thread-heading">
            <div className="conversations-thread-title-line">
              <h2>{detail.subject || thread?.subject || "(No subject)"}</h2>
              <span className="conversations-inbox-tag"><Inbox size={12} />Mailbox</span>
            </div>
            <div className="conversations-thread-context">
              <RealAccountChip identity={detail.mailbox ?? identity} fallbackId={detail.mailboxId} />
              <span>{detail.messages.length} {detail.messages.length === 1 ? "message" : "messages"}</span>
              {detail.labels.map((label) => <span className="conversations-mini-label" key={label.id}>{label.name}</span>)}
            </div>
          </div>

          <div className="conversations-message-stack">
            {detail.messages.map((message) => {
              const style = { "--account-color": accountColor(detail.mailbox ?? identity) } as CSSProperties;
              return (
                <article
                  id={`east-source-message-${encodeURIComponent(message.id)}`}
                  className={`conversations-message-card conversations-live-message${message.id === sourceMessageId ? " is-report-source" : ""}`}
                  key={message.id}
                  tabIndex={-1}
                >
                  <div className="conversations-message-avatar conversations-message-avatar--live" style={style} aria-hidden="true">{message.sender.charAt(0).toLocaleUpperCase() || "?"}</div>
                  <div className="conversations-message-content">
                    <div className="conversations-message-header">
                      <div>
                        <div className="conversations-message-sender">{message.sender}</div>
                        <div className="conversations-message-recipient">
                          {message.senderEmail ?? "Sender address unavailable"}
                          {message.to.length > 0 || message.cc.length > 0 || message.bcc.length > 0 || message.replyTo ? (
                            <details className="conversations-message-recipient-details">
                              <summary>
                                {message.to.length > 0 ? `to ${message.to.join(", ")}` : "Message details"}
                                {message.cc.length > 0 || message.bcc.length > 0 ? " · includes Cc/Bcc" : ""}
                              </summary>
                              <dl>
                                {message.to.length > 0 ? <div><dt>To</dt><dd>{message.to.join(", ")}</dd></div> : null}
                                {message.cc.length > 0 ? <div><dt>Cc</dt><dd>{message.cc.join(", ")}</dd></div> : null}
                                {message.bcc.length > 0 ? <div><dt>Bcc</dt><dd>{message.bcc.join(", ")}</dd></div> : null}
                                {message.replyTo ? <div><dt>Reply-To</dt><dd>{message.replyTo}</dd></div> : null}
                              </dl>
                            </details>
                          ) : null}
                        </div>
                      </div>
                      <div className="conversations-message-header-actions">
                        <time className="conversations-message-time" dateTime={message.timestamp}>{formatTime(message.timestamp, true)}</time>
                        <MessageExpandControl message={message} subject={detail.subject || thread?.subject || ""} threadId={detail.id} onRetry={onRetry} />
                      </div>
                    </div>
                    {message.bodyText === null ? (
                      <p className="conversations-live-body-unavailable">
                        {message.bodyUnavailableReason === "ON_DEMAND"
                          ? "East could not load this message body from Gmail."
                          : message.bodyUnavailableReason
                          ? "This message exceeded East’s local display/cache limit."
                          : "Message body is not available in the local cache."}
                        {message.bodyUnavailableReason === "ON_DEMAND" || message.bodyUnavailableReason === null
                          ? <> <button type="button" onClick={onRetry}>Retry</button></>
                          : null}
                      </p>
                    ) : (
                      <div className="conversations-message-body">
                        <MessageBodyContent message={message} />
                      </div>
                    )}
                    <AttachmentList attachments={message.attachments} threadId={detail.id} messageId={message.id} />
                  </div>
                </article>
              );
            })}
          </div>
          <AttachmentList attachments={unassignedAttachments} threadId={detail.id} />
          <div className="conversations-live-compose-actions" role="group" aria-label="Compose from this conversation">
            <button type="button" onClick={() => onCompose("REPLY")} disabled={mutationBusy || detail.messages.length === 0}><Reply size={14} />Reply</button>
            <button type="button" onClick={() => onCompose("REPLY_ALL")} disabled={mutationBusy || detail.messages.length === 0}><ReplyAll size={14} />Reply all</button>
            <button type="button" onClick={() => onCompose("FORWARD")} disabled={mutationBusy || detail.messages.length === 0}><Forward size={14} />Forward</button>
          </div>
          <div className="conversations-reader-footer"><ShieldCheck size={13} />Mailbox content is displayed from the local service.</div>
        </div>
      ) : (
        <section className="conversations-reader-empty" aria-label="No conversation selected">
          <div className="conversations-empty-icon"><MessageCircleMore size={22} /></div>
          <strong>Select a conversation</strong>
          <span>Choose a thread to load its messages.</span>
        </section>
      )}
    </section>
  );
}

function RealConversations({
  sourceNavigation,
  onSourceNavigationHandled,
}: {
  sourceNavigation: ConversationSourceNavigation | null;
  onSourceNavigationHandled?: () => void;
}) {
  const [mailboxes, setMailboxes] = useState<Mailbox[]>([]);
  const [mailboxesLoaded, setMailboxesLoaded] = useState(false);
  const [mailboxLoadingError, setMailboxLoadingError] = useState<string | null>(null);
  const [mailboxRetryVersion, setMailboxRetryVersion] = useState(0);
  const [accountScope, setAccountScope] = useState("all");
  const [composerRequest, setComposerRequest] = useState<ComposerRequest | null>(null);
  const [folder, setFolder] = useState("INBOX");
  const [selectedCategory, setSelectedCategory] = useState<GmailCategory | null>("PRIMARY");
  const [readFilter, setReadFilter] = useState<ReadFilter>("all");
  const [labelFilter, setLabelFilter] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [query, setQuery] = useState("");
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [nextPageToken, setNextPageToken] = useState<string | null>(null);
  const [nextPageIsLocal, setNextPageIsLocal] = useState(false);
  const [listLoading, setListLoading] = useState(false);
  const [listCacheState, setListCacheState] = useState<"none" | "refreshing" | "fresh" | "stale">("none");
  const [listRefreshError, setListRefreshError] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [listErrorCode, setListErrorCode] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  const [threadDetail, setThreadDetail] = useState<ThreadDetail | null>(null);
  const [pendingSourceNavigation, setPendingSourceNavigation] = useState<ConversationSourceNavigation | null>(null);
  const [readerSourceNavigation, setReaderSourceNavigation] = useState<ConversationSourceNavigation | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailCacheState, setDetailCacheState] = useState<"none" | "refreshing" | "fresh" | "stale">("none");
  const [detailRefreshError, setDetailRefreshError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailRetryVersion, setDetailRetryVersion] = useState(0);
  const [selectedThreadMailboxIds, setSelectedThreadMailboxIds] = useState<Map<string, string>>(() => new Map());
  const selectedThreadIds = useMemo(() => new Set(selectedThreadMailboxIds.keys()), [selectedThreadMailboxIds]);
  const [bulkLabelIdsByMailbox, setBulkLabelIdsByMailbox] = useState<Map<string, string>>(() => new Map());
  const [readerLabelId, setReaderLabelId] = useState("");
  const [labelManagerOpen, setLabelManagerOpen] = useState(false);
  const [labelManagerMailboxId, setLabelManagerMailboxId] = useState("");
  const [labelManagerLabelId, setLabelManagerLabelId] = useState("");
  const [labelManagerName, setLabelManagerName] = useState("");
  const [managedLabelOverrides, setManagedLabelOverrides] = useState<Map<string, MailLabelOption>>(() => new Map());
  const [hiddenLabelIds, setHiddenLabelIds] = useState<Set<string>>(() => new Set());
  const [mailboxLabelCatalogs, setMailboxLabelCatalogs] = useState<Map<string, MailboxLabelCatalogState>>(() => new Map());
  const [busyMutation, setBusyMutation] = useState<string | null>(null);
  const [actionFeedback, setActionFeedback] = useState<ActionFeedback | null>(null);
  const [density, setDensity] = useState<Density>("comfortable");
  const masterSelectionRef = useRef<HTMLInputElement>(null);
  const threadListRef = useRef<HTMLDivElement>(null);
  const loadMoreSentinelRef = useRef<HTMLDivElement>(null);
  const listRequestVersion = useRef(0);
  const activeQueryRef = useRef("");
  const previousQueryKeyRef = useRef<string | null>(null);
  const loadMoreControllerRef = useRef<AbortController | null>(null);
  const labelCatalogRequestControllersRef = useRef<Map<string, AbortController>>(new Map());
  const composerSessionRef = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    setMailboxesLoaded(false);
    setMailboxLoadingError(null);
    getMailboxes(controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setMailboxes(result);
      setMailboxesLoaded(true);
      setAccountScope((current) => current === "all" || result.some((mailbox) => mailbox.id === current) ? current : "all");
      const available = new Set(result.flatMap((mailbox) => mailbox.categories));
      setSelectedCategory((current) => current && available.has(current) ? current : available.has("PRIMARY") ? "PRIMARY" : null);
    }).catch((failure: unknown) => {
      if (controller.signal.aborted) return;
      setMailboxesLoaded(true);
      setMailboxLoadingError(failure instanceof ConversationsApiError ? failure.message : "The connected mailboxes could not be loaded.");
    });
    return () => controller.abort();
  }, [mailboxRetryVersion]);

  useEffect(() => {
    if (!sourceNavigation || !mailboxesLoaded) return;
    const sourceMailbox = mailboxes.find((mailbox) => mailbox.id === sourceNavigation.mailboxId);
    if (!sourceMailbox) {
      setPendingSourceNavigation(null);
      setReaderSourceNavigation(null);
      setActionFeedback({ kind: "error", message: "This report source belongs to a Gmail account that is no longer connected." });
      onSourceNavigationHandled?.();
      return;
    }
    setActionFeedback(null);
    setPendingSourceNavigation(sourceNavigation);
    setReaderSourceNavigation(sourceNavigation);
    setAccountScope(sourceNavigation.mailboxId);
    setFolder("ALL_MAIL");
    setSelectedCategory(null);
    setReadFilter("all");
    setLabelFilter("");
    setSearchInput("");
    setQuery("");
    setSelectedThreadMailboxIds(new Map());
  }, [mailboxes, mailboxesLoaded, onSourceNavigationHandled, sourceNavigation]);

  useEffect(() => {
    if (!pendingSourceNavigation || !mailboxesLoaded) return;
    if (accountScope !== pendingSourceNavigation.mailboxId || folder !== "ALL_MAIL" || query || searchInput.trim() || labelFilter) return;
    setSelectedThreadId(pendingSourceNavigation.threadId);
    setDetailRetryVersion((value) => value + 1);
    setPendingSourceNavigation(null);
    onSourceNavigationHandled?.();
  }, [accountScope, folder, labelFilter, mailboxesLoaded, onSourceNavigationHandled, pendingSourceNavigation, query, searchInput]);

  const loadMailboxLabelCatalog = useCallback(async (mailboxId: string, parentSignal?: AbortSignal) => {
    labelCatalogRequestControllersRef.current.get(mailboxId)?.abort();
    const controller = new AbortController();
    const abortFromParent = () => controller.abort();
    if (parentSignal?.aborted) controller.abort();
    else parentSignal?.addEventListener("abort", abortFromParent, { once: true });
    labelCatalogRequestControllersRef.current.set(mailboxId, controller);
    setMailboxLabelCatalogs((current) => {
      const next = new Map(current);
      const previous = next.get(mailboxId);
      next.set(mailboxId, { labels: previous?.labels ?? [], loading: true, error: null });
      return next;
    });

    try {
      const labels = await listMailboxLabels(mailboxId, controller.signal);
      if (!controller.signal.aborted && labelCatalogRequestControllersRef.current.get(mailboxId) === controller) {
        setMailboxLabelCatalogs((current) => new Map(current).set(mailboxId, { labels, loading: false, error: null }));
        setManagedLabelOverrides((current) => {
          const next = new Map(current);
          for (const [id, label] of next) if (label.mailboxId === mailboxId) next.delete(id);
          return next;
        });
      }
    } catch (failure) {
      if (!controller.signal.aborted && labelCatalogRequestControllersRef.current.get(mailboxId) === controller) {
        setMailboxLabelCatalogs((current) => {
          const next = new Map(current);
          const previous = next.get(mailboxId);
          next.set(mailboxId, {
            labels: previous?.labels ?? [],
            loading: false,
            error: failure instanceof MailActionsApiError ? failure.message : "The mailbox labels could not be loaded.",
          });
          return next;
        });
      }
    } finally {
      parentSignal?.removeEventListener("abort", abortFromParent);
      if (labelCatalogRequestControllersRef.current.get(mailboxId) === controller) {
        labelCatalogRequestControllersRef.current.delete(mailboxId);
      }
    }
  }, []);

  useEffect(() => {
    if (!mailboxesLoaded) return;
    const controller = new AbortController();
    const mailboxIds = new Set(mailboxes.map((mailbox) => mailbox.id));
    setMailboxLabelCatalogs((current) => new Map(Array.from(current).filter(([mailboxId]) => mailboxIds.has(mailboxId))));

    let cursor = 0;
    const worker = async () => {
      while (!controller.signal.aborted) {
        const mailbox = mailboxes[cursor];
        cursor += 1;
        if (!mailbox) return;
        await loadMailboxLabelCatalog(mailbox.id, controller.signal);
      }
    };
    const workerCount = Math.min(maxParallelLabelCatalogRequests, mailboxes.length);
    void Promise.all(Array.from({ length: workerCount }, () => worker()));
    return () => {
      controller.abort();
      for (const request of labelCatalogRequestControllersRef.current.values()) request.abort();
    };
  }, [loadMailboxLabelCatalog, mailboxes, mailboxesLoaded]);

  const availableCategories = useMemo(() => {
    const scoped = accountScope === "all" ? mailboxes : mailboxes.filter((mailbox) => mailbox.id === accountScope);
    const values = new Set(scoped.flatMap((mailbox) => mailbox.categories));
    return (["PRIMARY", "SOCIAL", "PROMOTIONS", "UPDATES", "FORUMS"] as GmailCategory[]).filter((value) => values.has(value));
  }, [accountScope, mailboxes]);

  const accountMap = useMemo(() => new Map(mailboxes.map((mailbox) => [mailbox.id, mailbox] as const)), [mailboxes]);
  const currentMailbox = accountScope === "all" ? null : accountMap.get(accountScope) ?? null;
  const selectedMailboxIds = useMemo(() => {
    const selected = new Set(selectedThreadMailboxIds.values());
    return mailboxes.filter((mailbox) => selected.has(mailbox.id)).map((mailbox) => mailbox.id);
  }, [mailboxes, selectedThreadMailboxIds]);
  const labelCatalog = useMemo(() => {
    const byId = new Map<string, MailLabelOption>();
    for (const [mailboxId, catalog] of mailboxLabelCatalogs) {
      for (const label of catalog.labels) {
        if (label.type === "SYSTEM" || isGmailCategoryLabel(label.id) || isGmailCategoryLabel(label.name)) continue;
        byId.set(label.id, { id: label.id, name: label.name, color: null, mailboxId, type: label.type });
      }
    }
    for (const thread of threads) {
      for (const label of thread.labels) {
        if (!byId.has(label.id) && !isGmailCategoryLabel(label.id) && !isGmailCategoryLabel(label.name)) {
          byId.set(label.id, { ...label, mailboxId: thread.mailboxId, type: null });
        }
      }
    }
    if (threadDetail) {
      for (const label of threadDetail.labels) {
        if (!byId.has(label.id) && !isGmailCategoryLabel(label.id) && !isGmailCategoryLabel(label.name)) {
          byId.set(label.id, { ...label, mailboxId: threadDetail.mailboxId, type: null });
        }
      }
    }
    for (const [id, label] of managedLabelOverrides) {
      if (label.type !== "SYSTEM" && !isGmailCategoryLabel(id) && !isGmailCategoryLabel(label.name)) byId.set(id, label);
    }
    for (const id of hiddenLabelIds) byId.delete(id);
    return Array.from(byId.values()).sort((left, right) => left.name.localeCompare(right.name));
  }, [hiddenLabelIds, mailboxLabelCatalogs, managedLabelOverrides, threadDetail, threads]);
  const visibleLabels = useMemo(
    () => (accountScope === "all" ? labelCatalog : labelCatalog.filter((label) => label.mailboxId === accountScope))
      .filter((label) => label.type !== "SYSTEM" && !isGmailCategoryLabel(label.id) && !isGmailCategoryLabel(label.name)),
    [accountScope, labelCatalog],
  );
  useEffect(() => {
    if (labelFilter && !labelCatalog.some((label) => label.id === labelFilter)) setLabelFilter("");
  }, [labelCatalog, labelFilter]);

  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(searchInput.trim()), 350);
    return () => window.clearTimeout(timeout);
  }, [searchInput]);

  const effectiveCategory = !query && folder === "INBOX" && selectedCategory && availableCategories.includes(selectedCategory)
    ? selectedCategory
    : undefined;
  const listQuery = useMemo<ThreadListQuery>(() => ({
    ...(accountScope !== "all" ? { mailboxId: accountScope } : {}),
    ...(query ? {} : { folder }),
    ...(!query && effectiveCategory ? { category: effectiveCategory } : {}),
    ...(query ? { query } : {}),
    ...(readFilter === "unread" ? { unread: true } : readFilter === "read" ? { unread: false } : {}),
    ...(readFilter === "starred" ? { starred: true } : {}),
    ...(labelFilter ? { labelId: labelFilter } : {}),
    pageSize: 30,
  }), [accountScope, effectiveCategory, folder, labelFilter, query, readFilter]);
  const queryKey = JSON.stringify(listQuery);

  useEffect(() => () => loadMoreControllerRef.current?.abort(), []);

  const allVisibleSelected = threads.length > 0 && threads.every((thread) => selectedThreadIds.has(thread.id));
  useEffect(() => {
    if (masterSelectionRef.current) {
      const selectedCount = threads.filter((thread) => selectedThreadIds.has(thread.id)).length;
      masterSelectionRef.current.indeterminate = selectedCount > 0 && selectedCount < threads.length;
    }
  }, [selectedThreadIds, threads]);

  useEffect(() => {
    if (!mailboxesLoaded) return;
    if (mailboxes.length === 0) {
      setThreads([]);
      setNextPageToken(null);
      setNextPageIsLocal(false);
      setListLoading(false);
      setListCacheState("none");
      setListRefreshError(null);
      setListError(null);
      setListErrorCode(null);
      return;
    }

    const controller = new AbortController();
    loadMoreControllerRef.current?.abort();
    loadMoreControllerRef.current = null;
    setLoadingMore(false);
    const version = ++listRequestVersion.current;
    activeQueryRef.current = queryKey;
    const queryChanged = previousQueryKeyRef.current !== queryKey;
    previousQueryKeyRef.current = queryKey;
    if (queryChanged) {
      setThreads([]);
      const preservingSourceSelection = Boolean(
        pendingSourceNavigation
        && accountScope === pendingSourceNavigation.mailboxId
        && folder === "ALL_MAIL"
        && !query
        && !searchInput.trim()
        && !labelFilter,
      );
      if (!preservingSourceSelection) setSelectedThreadId(null);
      setThreadDetail(null);
      setDetailError(null);
      setSelectedThreadMailboxIds(new Map());
    }
    setNextPageToken(null);
    setNextPageIsLocal(false);
    setListCacheState("none");
    setListRefreshError(null);
    setListError(null);
    setListErrorCode(null);
    setListLoading(true);
    const loadList = async () => {
      let hasCachedResults = false;
      try {
        const cachedPage = await getThreadPage(listQuery, controller.signal, true);
        if (controller.signal.aborted || version !== listRequestVersion.current) return;
        if (cachedPage.items.length > 0) {
          hasCachedResults = true;
          setThreads(cachedPage.items);
          setNextPageToken(cachedPage.nextPageToken);
          setNextPageIsLocal(true);
          setListCacheState("refreshing");
          setListLoading(false);
        }
      } catch {
        // The Gmail refresh below remains authoritative if the local cache is unavailable.
      }
      if (controller.signal.aborted || version !== listRequestVersion.current) return;
      if (!hasCachedResults) setListLoading(true);
      try {
        const page = await getThreadPage(listQuery, controller.signal);
        if (controller.signal.aborted || version !== listRequestVersion.current) return;
        loadMoreControllerRef.current?.abort();
        loadMoreControllerRef.current = null;
        setLoadingMore(false);
        setThreads(page.items);
        setNextPageToken(page.nextPageToken);
        setNextPageIsLocal(false);
        setListCacheState("fresh");
        setListRefreshError(null);
      } catch (failure: unknown) {
        if (controller.signal.aborted || version !== listRequestVersion.current) return;
        const message = failure instanceof ConversationsApiError ? failure.message : "The conversation list could not be loaded.";
        if (hasCachedResults) {
          setListCacheState("stale");
          setListRefreshError(message);
        } else {
          setListError(message);
          setListErrorCode(failure instanceof ConversationsApiError ? failure.code ?? null : null);
        }
      } finally {
        if (!controller.signal.aborted && version === listRequestVersion.current) setListLoading(false);
      }
    };
    void loadList();
    return () => controller.abort();
    // The query key covers every serialized query field.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mailboxesLoaded, mailboxes.length, queryKey, refreshVersion]);

  useEffect(() => {
    if (!selectedThreadId) {
      setThreadDetail(null);
      setDetailLoading(false);
      setDetailCacheState("none");
      setDetailRefreshError(null);
      setDetailError(null);
      return;
    }
    const controller = new AbortController();
    setThreadDetail(null);
    setDetailLoading(true);
    setDetailCacheState("none");
    setDetailRefreshError(null);
    setDetailError(null);
    const loadThread = async () => {
      let hasCachedDetail = false;
      try {
        const cached = await getCachedThread(selectedThreadId, controller.signal);
        if (!controller.signal.aborted) {
          setThreadDetail(cached);
          setDetailCacheState("refreshing");
          hasCachedDetail = true;
        }
      } catch {
        // Continue to Gmail when no saved local thread is available.
      }
      if (controller.signal.aborted) return;
      try {
        const detail = await getThread(selectedThreadId, controller.signal);
        if (!controller.signal.aborted) {
          setThreadDetail(detail);
          setDetailCacheState("fresh");
          setDetailRefreshError(null);
        }
      } catch (failure: unknown) {
        if (controller.signal.aborted) return;
        const message = failure instanceof ConversationsApiError ? failure.message : "The conversation could not be loaded.";
        if (hasCachedDetail) {
          setDetailCacheState("stale");
          setDetailRefreshError(message);
        } else {
          setDetailError(message);
        }
      } finally {
        if (!controller.signal.aborted) setDetailLoading(false);
      }
    };
    void loadThread();
    return () => controller.abort();
  }, [selectedThreadId, detailRetryVersion]);

  const loadMore = useCallback(async () => {
    if (!nextPageToken || loadingMore || listLoading || loadMoreControllerRef.current
      || activeQueryRef.current !== queryKey) return;
    const token = nextPageToken;
    const localPage = nextPageIsLocal;
    const controller = new AbortController();
    loadMoreControllerRef.current = controller;
    setLoadingMore(true);
    setListError(null);
    setListErrorCode(null);
    try {
      const page = await getThreadPage({ ...listQuery, pageToken: token }, controller.signal, localPage);
      if (controller.signal.aborted || activeQueryRef.current !== queryKey) return;
      setThreads((current) => {
        const seen = new Set(current.map((thread) => thread.id));
        return [...current, ...page.items.filter((thread) => !seen.has(thread.id))];
      });
      setNextPageToken(page.nextPageToken);
      setNextPageIsLocal(localPage);
    } catch (failure) {
      if (failure instanceof ConversationsApiError && failure.code === "INVALID_PAGE_TOKEN"
        && activeQueryRef.current === queryKey && !controller.signal.aborted) {
        try {
          const targetCount = threads.length + (listQuery.pageSize ?? 30);
          let page = await getThreadPage(listQuery, controller.signal, localPage);
          const refreshedThreads = [...page.items];
          let seen = new Set(refreshedThreads.map((thread) => thread.id));

          while (refreshedThreads.length < targetCount && page.nextPageToken) {
            page = await getThreadPage({ ...listQuery, pageToken: page.nextPageToken }, controller.signal, localPage);
            for (const thread of page.items) {
              if (seen.has(thread.id)) continue;
              seen.add(thread.id);
              refreshedThreads.push(thread);
            }
            if (activeQueryRef.current !== queryKey) return;
          }

          if (activeQueryRef.current === queryKey) {
            setThreads(refreshedThreads);
            setNextPageToken(page.nextPageToken);
            setNextPageIsLocal(localPage);
          }
        } catch (recoveryFailure) {
          if (activeQueryRef.current === queryKey && !controller.signal.aborted) {
            setListError(recoveryFailure instanceof ConversationsApiError
              ? recoveryFailure.message
              : "The conversation list could not be refreshed after its page expired.");
            setListErrorCode(recoveryFailure instanceof ConversationsApiError ? recoveryFailure.code ?? null : null);
          }
        }
      } else if (activeQueryRef.current === queryKey && !controller.signal.aborted) {
        setListError(failure instanceof ConversationsApiError ? failure.message : "More conversations could not be loaded.");
        setListErrorCode(failure instanceof ConversationsApiError ? failure.code ?? null : null);
      }
    } finally {
      if (loadMoreControllerRef.current === controller) {
        loadMoreControllerRef.current = null;
        setLoadingMore(false);
      }
    }
  }, [listLoading, listQuery, loadingMore, nextPageIsLocal, nextPageToken, queryKey, threads]);

  useEffect(() => {
    const root = threadListRef.current;
    const sentinel = loadMoreSentinelRef.current;
    if (!root || !sentinel || !nextPageToken || listLoading || loadingMore) return;

    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore();
    }, { root, rootMargin: "180px 0px" });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [loadMore, listCacheState, listLoading, loadingMore, nextPageIsLocal, nextPageToken, threads.length]);

  const selectedSummary = threads.find((thread) => thread.id === selectedThreadId) ?? null;
  const selectedIdentity = selectedSummary?.mailbox ?? (selectedSummary ? accountMap.get(selectedSummary.mailboxId) ?? null : null) ?? threadDetail?.mailbox ?? null;
  const readerMailboxId = selectedSummary?.mailboxId ?? threadDetail?.mailboxId ?? null;
  const readerLabels = readerMailboxId
    ? labelCatalog.filter((label) => label.mailboxId === readerMailboxId && label.type === "USER")
    : [];
  useEffect(() => {
    setReaderLabelId("");
  }, [readerMailboxId]);
  const managerLabels = labelManagerMailboxId
    ? labelCatalog.filter((label) => label.mailboxId === labelManagerMailboxId && label.type === "USER")
    : [];
  const managerLabelCatalogState = labelManagerMailboxId ? mailboxLabelCatalogs.get(labelManagerMailboxId) ?? null : null;
  const existingLabelsUnavailable = !managerLabelCatalogState || managerLabelCatalogState.loading || managerLabelCatalogState.error !== null;
  const scopedLabelCatalogStates = accountScope === "all"
    ? mailboxes.map((mailbox) => mailboxLabelCatalogs.get(mailbox.id)).filter((state): state is MailboxLabelCatalogState => state !== undefined)
    : [mailboxLabelCatalogs.get(accountScope)].filter((state): state is MailboxLabelCatalogState => state !== undefined);
  const labelsLoading = scopedLabelCatalogStates.some((state) => state.loading);
  const labelCatalogFailureCount = scopedLabelCatalogStates.filter((state) => state.error !== null).length;
  const bulkLabelOptionsByMailbox = useMemo(() => {
    const byMailbox = new Map<string, MailLabelOption[]>();
    for (const mailboxId of selectedMailboxIds) {
      byMailbox.set(mailboxId, labelCatalog.filter((label) => label.mailboxId === mailboxId && label.type === "USER"));
    }
    return byMailbox;
  }, [labelCatalog, selectedMailboxIds]);
  const bulkLabelCatalogLoadingMailboxIds = selectedMailboxIds.filter((mailboxId) => {
    const state = mailboxLabelCatalogs.get(mailboxId);
    return !state || state.loading;
  });
  const bulkLabelCatalogFailedMailboxIds = selectedMailboxIds.filter((mailboxId) => {
    const state = mailboxLabelCatalogs.get(mailboxId);
    return state?.error !== null && state?.error !== undefined;
  });
  const bulkLabelSelectionMissingMailboxIds = selectedMailboxIds.filter((mailboxId) => {
    const labelId = bulkLabelIdsByMailbox.get(mailboxId);
    return !labelId || !bulkLabelOptionsByMailbox.get(mailboxId)?.some((label) => label.id === labelId);
  });
  const bulkLabelsReady = selectedMailboxIds.length > 0 &&
    bulkLabelCatalogLoadingMailboxIds.length === 0 &&
    bulkLabelCatalogFailedMailboxIds.length === 0 &&
    bulkLabelSelectionMissingMailboxIds.length === 0;
  const categoryLabel = effectiveCategory ? categoryTitles[effectiveCategory] : "All categories";
  const activeLabelFilter = visibleLabels.find((label) => label.id === labelFilter) ?? null;
  const labelFilterMailbox = activeLabelFilter ? accountMap.get(activeLabelFilter.mailboxId) ?? null : null;
  const viewScope = activeLabelFilter && accountScope === "all"
    ? labelFilterMailbox?.displayName ?? "the labeled mailbox"
    : accountScope === "all" ? "all mailboxes" : currentMailbox?.displayName ?? "the selected mailbox";
  const viewDescription = query
    ? `Search results across ${viewScope}`
    : `${folder === "INBOX" ? "Inbox" : gmailFolders.find((item) => item.value === folder)?.name ?? folder} · ${categoryLabel}`;
  const filterQuery = activeLabelFilter
    ? `${viewDescription} · ${activeLabelFilter.name}${accountScope === "all" ? ` (${labelFilterMailbox?.email ?? "connected account"})` : ""}`
    : viewDescription;

  function changeAccountScope(nextScope: string) {
    setAccountScope(nextScope);
    const selectedLabel = labelCatalog.find((label) => label.id === labelFilter);
    if (nextScope !== "all" && selectedLabel && selectedLabel.mailboxId !== nextScope) setLabelFilter("");
  }

  function openThread(threadId: string) {
    setReaderSourceNavigation(null);
    setSelectedThreadId(threadId);
  }

  function toggleThreadSelection(threadId: string) {
    const mailboxId = threads.find((thread) => thread.id === threadId)?.mailboxId;
    const alreadySelected = selectedThreadMailboxIds.has(threadId);
    if (!alreadySelected && selectedThreadMailboxIds.size >= maxBulkThreadCount) {
      setActionFeedback({
        kind: "error",
        message: `Bulk actions are limited to ${maxBulkThreadCount} conversations. Deselect one before selecting another.`,
      });
      return;
    }
    if (!alreadySelected && !mailboxId) return;
    setSelectedThreadMailboxIds((current) => {
      const next = new Map(current);
      if (next.has(threadId)) next.delete(threadId);
      else if (mailboxId) next.set(threadId, mailboxId);
      return next;
    });
  }

  function toggleVisibleThreadSelection() {
    if (!allVisibleSelected) {
      const additions = threads.filter((thread) => !selectedThreadMailboxIds.has(thread.id)).length;
      if (selectedThreadMailboxIds.size + additions > maxBulkThreadCount) {
        setActionFeedback({
          kind: "error",
          message: `Bulk actions are limited to ${maxBulkThreadCount} conversations. Deselect some conversations or clear the selection before selecting this page.`,
        });
        return;
      }
    }
    setSelectedThreadMailboxIds((current) => {
      const next = new Map(current);
      if (allVisibleSelected) threads.forEach((thread) => next.delete(thread.id));
      else threads.forEach((thread) => next.set(thread.id, thread.mailboxId));
      return next;
    });
  }

  function rememberLabel(label: MailLabelRecord) {
    const option: MailLabelOption = { id: label.id, mailboxId: label.mailboxId, name: label.name, color: null, type: label.type };
    setManagedLabelOverrides((current) => new Map(current).set(label.id, option));
    setHiddenLabelIds((current) => {
      const next = new Set(current);
      next.delete(label.id);
      return next;
    });
    return option;
  }

  async function runSingleAction(threadId: string, action: MailAction, labelIds: string[] = []) {
    setBusyMutation("thread");
    setActionFeedback(null);
    try {
      await updateThreadAction(threadId, action, labelIds);
      setActionFeedback({ kind: "success", message: actionSuccessMessage(action) });
      setRefreshVersion((value) => value + 1);
      if (selectedThreadId === threadId) setDetailRetryVersion((value) => value + 1);
    } catch (failure) {
      setActionFeedback({
        kind: "error",
        message: failure instanceof MailActionsApiError ? failure.message : "East could not complete the mailbox change.",
      });
    } finally {
      setBusyMutation(null);
    }
  }

  async function runBulkAction(action: MailAction) {
    const ids = Array.from(selectedThreadIds);
    if (ids.length === 0) return;
    if (ids.length > maxBulkThreadCount) {
      setActionFeedback({
        kind: "error",
        message: `Bulk actions are limited to ${maxBulkThreadCount} conversations. Reduce the selection before trying again.`,
      });
      return;
    }
    if (ids.some((id) => {
      const mailboxId = selectedThreadMailboxIds.get(id);
      return !mailboxId || !accountMap.has(mailboxId);
    })) {
      setActionFeedback({
        kind: "error",
        message: "A selected mailbox is no longer connected. Clear the selection and refresh conversations before continuing.",
      });
      return;
    }
    const labelAction = action === "ADD_LABELS" || action === "REMOVE_LABELS";
    if (labelAction && ids.some((id) => !selectedThreadMailboxIds.has(id))) {
      setActionFeedback({ kind: "error", message: "Reload the conversation list before changing labels on this selection." });
      return;
    }
    if (labelAction && !bulkLabelsReady) {
      const message = bulkLabelCatalogLoadingMailboxIds.length > 0
        ? "Loading labels for the selected accounts. Try again when they finish loading."
        : bulkLabelCatalogFailedMailboxIds.length > 0
          ? "Could not load labels for a selected account. Retry its labels in Manage labels."
          : "Choose one user label for each account represented in the selection.";
      setActionFeedback({ kind: "error", message });
      return;
    }
    const threadById = new Map(threads.map((thread) => [thread.id, thread] as const));
    setBusyMutation("bulk");
    setActionFeedback(null);
    try {
      let outcomes: MailActionOutcome[];
      if (labelAction) {
        const threadIdsByMailbox = new Map<string, string[]>();
        for (const threadId of ids) {
          const mailboxId = selectedThreadMailboxIds.get(threadId);
          if (!mailboxId) continue;
          const group = threadIdsByMailbox.get(mailboxId) ?? [];
          group.push(threadId);
          threadIdsByMailbox.set(mailboxId, group);
        }
        outcomes = [];
        for (const mailboxId of selectedMailboxIds) {
          const mailboxThreadIds = threadIdsByMailbox.get(mailboxId) ?? [];
          if (mailboxThreadIds.length === 0) continue;
          const labelId = bulkLabelIdsByMailbox.get(mailboxId);
          if (!labelId) {
            outcomes.push(...mailboxThreadIds.map((threadId) => ({
              threadId,
              success: false,
              errorCode: "LABEL_NOT_FOUND",
              gmailHttpStatus: null,
              googleErrorCode: null,
            })));
            continue;
          }
          try {
            outcomes.push(...await updateThreadsAction(mailboxThreadIds, action, [labelId]));
          } catch (failure) {
            outcomes.push(...mailboxThreadIds.map((threadId) => bulkRequestFailureOutcome(threadId, failure)));
          }
        }
      } else {
        outcomes = await updateThreadsAction(ids, action);
      }
      const outcomeRows = outcomes.map((outcome) => {
        const thread = threadById.get(outcome.threadId);
        const mailboxId = selectedThreadMailboxIds.get(outcome.threadId) ?? thread?.mailboxId;
        const identity = thread?.mailbox ?? (mailboxId ? accountMap.get(mailboxId) : null);
        return {
          threadId: outcome.threadId,
          subject: thread?.subject || "Conversation",
          account: identity?.displayName ?? "Mailbox",
          success: outcome.success,
          reason: actionFailureReason(outcome),
        };
      });
      const failedIds = outcomes.filter((outcome) => !outcome.success).map((outcome) => outcome.threadId);
      setSelectedThreadMailboxIds((current) => {
        const next = new Map<string, string>();
        for (const id of failedIds) {
          const mailboxId = current.get(id);
          if (mailboxId) next.set(id, mailboxId);
        }
        return next;
      });
      const succeeded = outcomes.length - failedIds.length;
      setActionFeedback({
        kind: failedIds.length === 0 ? "success" : succeeded > 0 ? "partial" : "error",
        message: failedIds.length === 0
          ? labelAction
            ? `${action === "ADD_LABELS" ? "Applied" : "Removed"} the selected account labels on all ${outcomes.length} conversations.`
            : `Updated all ${outcomes.length} selected conversations.`
          : succeeded > 0
            ? `Updated ${succeeded} of ${outcomes.length}. Failed conversations remain selected.`
            : `No selected conversations were updated. ${failedIds.length} remain selected.`,
        outcomes: outcomeRows,
      });
      if (succeeded > 0) {
        setRefreshVersion((value) => value + 1);
        if (selectedThreadId && outcomes.some((outcome) => outcome.threadId === selectedThreadId && outcome.success)) {
          setDetailRetryVersion((value) => value + 1);
        }
      }
    } catch (failure) {
      setActionFeedback({
        kind: "error",
        message: failure instanceof MailActionsApiError ? failure.message : "East could not complete the bulk mailbox change.",
      });
    } finally {
      setBusyMutation(null);
    }
  }

  async function createLabel() {
    if (!labelManagerMailboxId || !labelManagerName.trim()) return;
    setBusyMutation("label");
    setActionFeedback(null);
    try {
      const created = rememberLabel(await createUserLabel(labelManagerMailboxId, labelManagerName.trim()));
      setLabelManagerLabelId(created.id);
      setLabelManagerName(created.name);
      setActionFeedback({ kind: "success", message: `Created label “${created.name}”.` });
      setRefreshVersion((value) => value + 1);
      await loadMailboxLabelCatalog(labelManagerMailboxId);
    } catch (failure) {
      setActionFeedback({ kind: "error", message: failure instanceof MailActionsApiError ? failure.message : "East could not create the label." });
    } finally {
      setBusyMutation(null);
    }
  }

  async function renameLabel() {
    if (!labelManagerMailboxId || !labelManagerLabelId || !labelManagerName.trim()) return;
    setBusyMutation("label");
    setActionFeedback(null);
    try {
      const renamed = rememberLabel(await renameUserLabel(labelManagerMailboxId, labelManagerLabelId, labelManagerName.trim()));
      setLabelManagerName(renamed.name);
      setActionFeedback({ kind: "success", message: `Renamed label to “${renamed.name}”.` });
      setRefreshVersion((value) => value + 1);
      await loadMailboxLabelCatalog(labelManagerMailboxId);
    } catch (failure) {
      setActionFeedback({ kind: "error", message: failure instanceof MailActionsApiError ? failure.message : "East could not rename the label." });
    } finally {
      setBusyMutation(null);
    }
  }

  async function removeLabel() {
    if (!labelManagerMailboxId || !labelManagerLabelId) return;
    const selectedLabel = managerLabels.find((label) => label.id === labelManagerLabelId);
    setBusyMutation("label");
    setActionFeedback(null);
    try {
      await deleteUserLabel(labelManagerMailboxId, labelManagerLabelId);
      setHiddenLabelIds((current) => new Set(current).add(labelManagerLabelId));
      setManagedLabelOverrides((current) => {
        const next = new Map(current);
        next.delete(labelManagerLabelId);
        return next;
      });
      if (labelFilter === labelManagerLabelId) setLabelFilter("");
      if (readerLabelId === labelManagerLabelId) setReaderLabelId("");
      setBulkLabelIdsByMailbox((current) => {
        if (current.get(labelManagerMailboxId) !== labelManagerLabelId) return current;
        const next = new Map(current);
        next.delete(labelManagerMailboxId);
        return next;
      });
      setLabelManagerLabelId("");
      setLabelManagerName("");
      setActionFeedback({ kind: "success", message: `Deleted label “${selectedLabel?.name ?? "label"}”.` });
      setRefreshVersion((value) => value + 1);
      await loadMailboxLabelCatalog(labelManagerMailboxId);
    } catch (failure) {
      setActionFeedback({ kind: "error", message: failure instanceof MailActionsApiError ? failure.message : "East could not delete the label." });
    } finally {
      setBusyMutation(null);
    }
  }

  function startNewCompose() {
    setComposerRequest({
      sessionId: ++composerSessionRef.current,
      kind: "NEW",
      mailboxId: accountScope === "all" ? null : accountScope,
      sourceThreadId: null,
      sourceMessageId: null,
    });
  }

  function startThreadCompose(kind: DraftKind) {
    if (!threadDetail || threadDetail.messages.length === 0) return;
    const ownEmail = (accountMap.get(threadDetail.mailboxId)?.email ?? threadDetail.mailbox?.email ?? "").toLocaleLowerCase();
    const latestMessage = threadDetail.messages[threadDetail.messages.length - 1];
    const sourceMessage = kind === "FORWARD"
      ? latestMessage
      : [...threadDetail.messages].reverse().find((message) => !ownEmail || message.senderEmail?.toLocaleLowerCase() !== ownEmail) ?? latestMessage;
    setComposerRequest({
      sessionId: ++composerSessionRef.current,
      kind,
      mailboxId: threadDetail.mailboxId,
      sourceThreadId: threadDetail.id,
      sourceMessageId: sourceMessage.id,
    });
  }

  if (!mailboxesLoaded) {
    return (
      <section className="conversations-page conversations-live-page">
        <header className="conversations-page-heading"><div><div className="conversations-eyebrow">CONNECTED MAIL</div><h1>Conversations</h1><p>Loading connected mailboxes…</p></div></header>
        <div className="conversations-live-empty" role="status"><LoaderCircle size={20} /><span>Loading connected mailboxes…</span></div>
      </section>
    );
  }

  if (mailboxLoadingError) {
    return (
      <section className="conversations-page conversations-live-page">
        <header className="conversations-page-heading"><div><div className="conversations-eyebrow">CONNECTED MAIL</div><h1>Conversations</h1><p>Your connected mailboxes and conversations appear here.</p></div></header>
        <div className="conversations-live-empty" role="alert"><AlertCircle size={22} /><strong>Mailboxes could not be loaded</strong><span>{mailboxLoadingError}</span><button type="button" className="conversations-live-retry" onClick={() => setMailboxRetryVersion((value) => value + 1)}>Try again</button></div>
      </section>
    );
  }

  if (mailboxesLoaded && mailboxes.length === 0) {
    return (
      <section className="conversations-page conversations-live-page">
        <header className="conversations-page-heading"><div><div className="conversations-eyebrow">CONNECTED MAIL</div><h1>Conversations</h1><p>Your connected mailboxes and conversations appear here.</p></div></header>
        <div className="conversations-live-empty"><div className="conversations-empty-icon"><Mail size={22} /></div><strong>No connected mailboxes</strong><span>Connect a Google account to load its mail here.</span></div>
      </section>
    );
  }

  const selectedAccountLabel = accountScope === "all" ? "All accounts" : currentMailbox?.displayName ?? currentMailbox?.email ?? "Selected account";

  return (
    <section className={`conversations-page conversations-live-page${selectedThreadId ? " conversations-page--mobile-reader" : ""}`}>
      <header className="conversations-page-heading">
        <div>
          <div className="conversations-eyebrow">MAILBOX <span>·</span> {selectedAccountLabel.toLocaleUpperCase()}</div>
          <h1>Conversations</h1>
            <p>Browse connected Gmail folders and search across accounts. Message bodies load when you open a conversation.</p>
        </div>
        <div className="conversations-heading-actions">
          <span className="conversations-live-pill"><span />Connected mail · {mailboxes.length}</span>
          <MailboxScopePicker
            value={accountScope}
            mailboxes={mailboxes}
            onChange={changeAccountScope}
            ariaLabel="Mailbox scope"
            variant="heading"
          />
          <button className="conversations-compose-button" type="button" onClick={startNewCompose}><Mail size={16} />Compose</button>
          <button className="conversations-live-refresh" type="button" onClick={() => setRefreshVersion((value) => value + 1)} aria-label="Refresh conversations" title="Refresh conversations"><RefreshCw size={15} /></button>
        </div>
      </header>

      {busyMutation ? (
        <div className="conversations-action-feedback conversations-action-feedback--busy" role="status" aria-live="polite">
          <LoaderCircle size={15} aria-hidden="true" />
          <span>{busyMutation === "bulk" ? "Updating selected conversations…" : busyMutation === "label" ? "Saving label changes…" : "Updating conversation…"}</span>
        </div>
      ) : actionFeedback ? (
        <div className={`conversations-action-feedback conversations-action-feedback--${actionFeedback.kind}`} role={actionFeedback.kind === "error" ? "alert" : "status"} aria-live="polite">
          <div className="conversations-action-feedback-heading">
            <span>{actionFeedback.message}</span>
            <button type="button" onClick={() => setActionFeedback(null)} aria-label="Dismiss action result"><X size={14} aria-hidden="true" /></button>
          </div>
          {actionFeedback.outcomes ? (
            <ul className="conversations-action-outcomes">
              {actionFeedback.outcomes.map((outcome) => (
                <li className={outcome.success ? "is-success" : "is-failure"} key={outcome.threadId}>
                  <span><strong>{outcome.subject}</strong><small>{outcome.account}</small></span>
                  <em>{outcome.success ? "Updated" : outcome.reason}</em>
                  {!outcome.success ? (
                    <button
                      type="button"
                      className="conversations-action-outcome-deselect"
                      aria-label={`Deselect failed conversation: ${outcome.subject}`}
                      disabled={!selectedThreadIds.has(outcome.threadId)}
                      onClick={() => setSelectedThreadMailboxIds((current) => {
                        if (!current.has(outcome.threadId)) return current;
                        const next = new Map(current);
                        next.delete(outcome.threadId);
                        return next;
                      })}
                    >Deselect</button>
                  ) : null}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <div className={`conversations-mail-layout${selectedThreadId ? " is-thread-open" : ""}`}>
        <aside className="conversations-folder-rail" aria-label="Mailbox folders">
          <MailboxScopePicker
            value={accountScope}
            mailboxes={mailboxes}
            onChange={changeAccountScope}
            ariaLabel="Mailbox folder scope"
            variant="rail"
          />

          <nav className="conversations-folder-group" aria-label="Folders">
            {gmailFolders.map(({ name, value, icon: Icon }) => (
              <button className={`conversations-folder-row conversations-folder-button${!query && folder === value ? " is-active" : ""}`} type="button" aria-current={!query && folder === value ? "page" : undefined} key={value} onClick={() => { setFolder(value); setSelectedCategory(value === "INBOX" ? "PRIMARY" : null); setSearchInput(""); setQuery(""); }}>
                <Icon size={17} strokeWidth={1.8} aria-hidden="true" /><span>{name}</span>
              </button>
            ))}
          </nav>

          <div className="conversations-label-heading conversations-label-heading--live">
            <span>User labels</span>
            <button
              type="button"
              className="conversations-label-add conversations-live-label-add"
              aria-label={labelManagerOpen ? "Close label manager" : "Manage user labels"}
              aria-expanded={labelManagerOpen}
              onClick={() => {
                setLabelManagerOpen((open) => !open);
                setLabelManagerMailboxId(accountScope === "all" ? "" : accountScope);
                setLabelManagerLabelId("");
                setLabelManagerName("");
                setActionFeedback(null);
              }}
            >
              {labelManagerOpen ? <X size={14} aria-hidden="true" /> : <Plus size={14} aria-hidden="true" />}
            </button>
          </div>
          {labelManagerOpen ? (
            <section className="conversations-label-manager" aria-label="Manage user labels">
              <label>
                <span>Mailbox</span>
                <select
                  value={labelManagerMailboxId}
                  onChange={(event) => {
                    setLabelManagerMailboxId(event.target.value);
                    setLabelManagerLabelId("");
                    setLabelManagerName("");
                  }}
                  disabled={busyMutation !== null}
                >
                  <option value="">Choose an account</option>
                  {mailboxes.map((mailbox) => <option value={mailbox.id} key={mailbox.id}>{mailbox.displayName} · {mailbox.email}</option>)}
                </select>
              </label>
              <label>
                <span>Existing user label</span>
                <select
                  value={labelManagerLabelId}
                  onChange={(event) => {
                    const labelId = event.target.value;
                    setLabelManagerLabelId(labelId);
                    setLabelManagerName(managerLabels.find((label) => label.id === labelId)?.name ?? "");
                  }}
                  disabled={busyMutation !== null || !labelManagerMailboxId || existingLabelsUnavailable}
                >
                  <option value="">
                    {!managerLabelCatalogState || managerLabelCatalogState.loading
                      ? "Loading labels…"
                      : managerLabelCatalogState.error
                        ? "Labels unavailable"
                        : managerLabels.length > 0 ? "Create a new label" : "No existing labels"}
                  </option>
                  {managerLabels.map((label) => <option value={label.id} key={label.id}>{label.name}</option>)}
                </select>
              </label>
              <label>
                <span>Label name</span>
                <input
                  type="text"
                  value={labelManagerName}
                  maxLength={225}
                  placeholder="e.g. Project updates"
                  onChange={(event) => setLabelManagerName(event.target.value)}
                  disabled={busyMutation !== null || !labelManagerMailboxId}
                />
              </label>
              <div className="conversations-label-manager-actions">
                <button type="button" onClick={() => void createLabel()} disabled={busyMutation !== null || !labelManagerMailboxId || !labelManagerName.trim()}>
                  {busyMutation === "label" && !labelManagerLabelId ? <LoaderCircle size={13} aria-hidden="true" /> : <Plus size={13} aria-hidden="true" />}Create
                </button>
                <button type="button" onClick={() => void renameLabel()} disabled={busyMutation !== null || !labelManagerMailboxId || !labelManagerLabelId || !labelManagerName.trim()}>
                  Rename
                </button>
                <button type="button" onClick={() => void removeLabel()} disabled={busyMutation !== null || !labelManagerMailboxId || !labelManagerLabelId}>
                  Delete
                </button>
              </div>
              {labelManagerMailboxId && managerLabelCatalogState?.loading ? <p className="conversations-label-catalog-message" role="status">Loading labels for this mailbox…</p> : null}
              {labelManagerMailboxId && managerLabelCatalogState?.error ? (
                <div className="conversations-label-catalog-error" role="alert">
                  <span>{managerLabelCatalogState.error}</span>
                  <button type="button" className="conversations-live-retry" onClick={() => void loadMailboxLabelCatalog(labelManagerMailboxId)} disabled={managerLabelCatalogState.loading}>Retry</button>
                </div>
              ) : null}
              {accountScope === "all" && !labelManagerMailboxId ? <p>Choose an account to manage its labels.</p> : null}
            </section>
          ) : null}
          <nav className="conversations-folder-group conversations-label-list" aria-label="Gmail labels">
            {visibleLabels.length > 0 ? visibleLabels.map((label) => (
              <button type="button" className={`conversations-label-row conversations-label-button${labelFilter === label.id ? " is-active" : ""}`} key={label.id} onClick={() => setLabelFilter((current) => current === label.id ? "" : label.id)} aria-pressed={labelFilter === label.id} title={accountScope === "all" ? `${label.name} · ${accountMap.get(label.mailboxId)?.email ?? "Connected account"}` : label.name}>
                <span className="conversations-label-square" style={label.color && /^#[0-9a-fA-F]{6}$/.test(label.color) ? { backgroundColor: label.color } : undefined} />
                <span className="conversations-label-name">{label.name}</span>
                {accountScope === "all" ? <span className="conversations-label-account">{accountMap.get(label.mailboxId)?.displayName ?? "Connected account"}</span> : null}
              </button>
            )) : <span className="conversations-label-empty">
              {labelsLoading ? "Loading connected mailbox labels…" : labelCatalogFailureCount > 0 ? "Some mailbox labels could not be loaded." : "No labels found for connected mailboxes."}
            </span>}
          </nav>

          <div className="conversations-folder-note conversations-folder-note--live"><ShieldCheck size={14} /><span>Connected mailbox<br /><strong>Actions need your click</strong></span></div>
        </aside>

        <ConversationContentFrame
          isThreadOpen={Boolean(selectedThreadId)}
          selectionKey={selectedThreadId}
          renderList={(readerCollapsed, onShowReader) => (
        <>
        <section className="conversations-list-pane" aria-label="Connected mailbox thread list" aria-busy={listLoading}>
          <div className="conversations-list-toolbar">
            <div className="conversations-search-wrap">
              <Search size={16} aria-hidden="true" />
              <input type="search" placeholder="Search the full mailbox" aria-label={accountScope === "all" ? "Search across all connected mailboxes" : `Search all messages in ${currentMailbox?.displayName ?? "the selected mailbox"}`} value={searchInput} onChange={(event) => setSearchInput(event.target.value)} />
              {searchInput ? <button type="button" className="conversations-search-clear" onClick={() => { setSearchInput(""); setQuery(""); }} aria-label="Clear search">×</button> : null}
            </div>
            <div className="conversations-list-controls">
              <div className="conversations-filter-chips" aria-label="Inbox filters">
                <button type="button" className={`conversations-filter-chip${readFilter === "all" ? " is-active" : ""}`} onClick={() => setReadFilter("all")} aria-pressed={readFilter === "all"}>All</button>
                <button type="button" className={`conversations-filter-chip${readFilter === "unread" ? " is-active" : ""}`} onClick={() => setReadFilter((current) => current === "unread" ? "all" : "unread")} aria-pressed={readFilter === "unread"}>Unread</button>
                <button type="button" className={`conversations-filter-chip${readFilter === "read" ? " is-active" : ""}`} onClick={() => setReadFilter((current) => current === "read" ? "all" : "read")} aria-pressed={readFilter === "read"}>Read</button>
                <button type="button" className={`conversations-filter-chip${readFilter === "starred" ? " is-active" : ""}`} onClick={() => setReadFilter((current) => current === "starred" ? "all" : "starred")} aria-pressed={readFilter === "starred"}>Starred</button>
                <label className="conversations-filter-chip conversations-label-filter">
                  <Tag size={12} aria-hidden="true" /><span>Label</span>
                  <select aria-label="Filter by Gmail label" value={labelFilter} onChange={(event) => setLabelFilter(event.target.value)}>
                    <option value="">All labels</option>
                    {labelFilter && !visibleLabels.some((label) => label.id === labelFilter) ? <option value={labelFilter}>Active label</option> : null}
                    {visibleLabels.map((label) => <option key={label.id} value={label.id}>
                      {accountScope === "all" ? `${label.name} · ${accountMap.get(label.mailboxId)?.email ?? "Connected account"}` : label.name}
                    </option>)}
                  </select><ChevronDown size={12} aria-hidden="true" />
                </label>
              </div>
              <div className="conversations-density-control" role="group" aria-label="Message list density">
                <button type="button" onClick={() => setDensity("comfortable")} className={density === "comfortable" ? "is-selected" : ""} aria-pressed={density === "comfortable"} title="Comfortable row density"><span className="conversations-density-lines conversations-density-lines--comfortable" aria-hidden="true"><i /><i /><i /></span><span className="conversations-sr-only">Comfortable</span></button>
                <button type="button" onClick={() => setDensity("compact")} className={density === "compact" ? "is-selected" : ""} aria-pressed={density === "compact"} title="Compact row density"><span className="conversations-density-lines conversations-density-lines--compact" aria-hidden="true"><i /><i /><i /></span><span className="conversations-sr-only">Compact</span></button>
              </div>
            </div>
          </div>

          {!query && folder === "INBOX" && availableCategories.length > 0 ? (
            <nav className="conversations-category-tabs" aria-label="Inbox categories">
              <button type="button" className={`conversations-category-tab${effectiveCategory ? "" : " is-active"}`} aria-pressed={!effectiveCategory} onClick={() => { setSearchInput(""); setQuery(""); setSelectedCategory(null); }}>
                <span>All categories</span>
              </button>
              {availableCategories.map((category) => (
                <button type="button" className={`conversations-category-tab${effectiveCategory === category ? " is-active" : ""}`} aria-pressed={effectiveCategory === category} key={category} onClick={() => { setSearchInput(""); setQuery(""); setSelectedCategory((current) => current === category ? null : category); }}>
                  {category === "PRIMARY" ? <Inbox size={15} aria-hidden="true" /> : null}<span>{categoryTitles[category]}</span>
                </button>
              ))}
            </nav>
          ) : null}

          {listCacheState === "refreshing" && threads.length > 0 ? (
            <div className="conversations-cache-state" role="status" aria-live="polite">
              <LoaderCircle size={14} aria-hidden="true" /><span>{query.trim()
                ? "Showing saved matches from cached subjects and senders. They may be partial while Gmail searches the full mailbox…"
                : "Showing saved results while East refreshes Gmail metadata…"}</span>
            </div>
          ) : null}
          {listCacheState === "stale" && threads.length > 0 ? (
            <div className="conversations-cache-state conversations-cache-state--stale" role="status" aria-live="polite">
              <AlertCircle size={14} aria-hidden="true" /><span>{query.trim()
                ? `Showing possibly partial saved matches from cached subjects and senders. Gmail could not search the full mailbox: ${listRefreshError ?? "the connection is unavailable."}`
                : `Showing saved results. Gmail could not refresh: ${listRefreshError ?? "the connection is unavailable."}`}</span>
              <button type="button" onClick={() => setRefreshVersion((value) => value + 1)}>Retry</button>
            </div>
          ) : null}
          <div className="conversations-list-summary">
            <span>{listLoading ? "Loading conversations…" : listError ? "Could not load conversations" : query.trim() ? `Showing ${threads.length} search results` : `Showing ${threads.length} conversations`}</span>
            <div className="conversations-list-summary-actions"><span>{filterQuery}</span><span className="conversations-toolbar-divider" /><StaticAction label="More list options"><MoreHorizontal size={16} /></StaticAction></div>
            {readerCollapsed ? <button type="button" className="conversations-pane-restore" onClick={onShowReader}><MailOpen size={14} aria-hidden="true" />Show email</button> : null}
          </div>

          <div className="conversations-live-selection-toolbar" aria-label="Bulk conversation actions">
            <label className="conversations-select-visible">
              <input
                ref={masterSelectionRef}
                type="checkbox"
                checked={allVisibleSelected}
                onChange={toggleVisibleThreadSelection}
                disabled={threads.length === 0 || busyMutation !== null}
              />
              <span>Select visible</span>
            </label>
            {selectedThreadIds.size > 0 ? (
              <div className="conversations-live-bulk-actions">
                <span className="conversations-live-selected-count">{selectedThreadIds.size} / {maxBulkThreadCount} selected</span>
                {folder === "TRASH" ? (
                  <button type="button" onClick={() => void runBulkAction("UNTRASH")} disabled={busyMutation !== null}><ArchiveRestore size={13} />Restore to Inbox</button>
                ) : (
                  <>
                    <button type="button" onClick={() => void runBulkAction("ARCHIVE")} disabled={busyMutation !== null}><Archive size={13} />Archive</button>
                    <button type="button" onClick={() => void runBulkAction("TRASH")} disabled={busyMutation !== null}><Trash2 size={13} />Trash</button>
                  </>
                )}
                {folder === "ALL_MAIL" ? <button type="button" onClick={() => void runBulkAction("MOVE_TO_INBOX")} disabled={busyMutation !== null}><Inbox size={13} />Move to Inbox</button> : null}
                <button type="button" onClick={() => void runBulkAction("MARK_READ")} disabled={busyMutation !== null}><MailOpen size={13} />Read</button>
                <button type="button" onClick={() => void runBulkAction("MARK_UNREAD")} disabled={busyMutation !== null}><Mail size={13} />Unread</button>
                <button type="button" onClick={() => void runBulkAction("SPAM")} disabled={busyMutation !== null}><ShieldAlert size={13} />Spam</button>
                <button type="button" onClick={() => void runBulkAction("NOT_SPAM")} disabled={busyMutation !== null}><ShieldCheck size={13} />Not spam</button>
                {selectedMailboxIds.map((mailboxId) => {
                  const mailbox = accountMap.get(mailboxId);
                  const labels = bulkLabelOptionsByMailbox.get(mailboxId) ?? [];
                  const catalogState = mailboxLabelCatalogs.get(mailboxId);
                  const accountName = mailbox?.displayName ?? "Mailbox";
                  return (
                    <div className="conversations-live-bulk-label-group" key={mailboxId}>
                      <span><strong>{accountName}</strong><small>{mailbox?.email ?? "Connected account"}</small></span>
                      <label className="conversations-live-bulk-label">
                        <span className="conversations-sr-only">User label for {mailbox?.email ?? accountName}</span>
                        <select
                          aria-label={`User label for ${mailbox?.email ?? accountName}`}
                          value={bulkLabelIdsByMailbox.get(mailboxId) ?? ""}
                          onChange={(event) => setBulkLabelIdsByMailbox((current) => {
                            const next = new Map(current);
                            if (event.target.value) next.set(mailboxId, event.target.value);
                            else next.delete(mailboxId);
                            return next;
                          })}
                          disabled={busyMutation !== null || !catalogState || catalogState.loading || catalogState.error !== null || labels.length === 0}
                        >
                          <option value="">{labels.length === 0 ? "No user labels" : "Choose label"}</option>
                          {labels.map((label) => <option value={label.id} key={label.id}>{label.name}</option>)}
                        </select>
                        <ChevronDown size={12} aria-hidden="true" />
                      </label>
                    </div>
                  );
                })}
                <button type="button" onClick={() => void runBulkAction("ADD_LABELS")} disabled={busyMutation !== null || !bulkLabelsReady}><Tag size={13} />Add label</button>
                <button type="button" onClick={() => void runBulkAction("REMOVE_LABELS")} disabled={busyMutation !== null || !bulkLabelsReady}><X size={13} />Remove label</button>
                <button type="button" className="conversations-live-clear-selection" onClick={() => setSelectedThreadMailboxIds(new Map())} disabled={busyMutation !== null}>Clear</button>
                {selectedMailboxIds.length > 1 ? <span className="conversations-live-label-notice" role="status">Choose a label for each selected account. Labels stay account-specific; East will not create missing labels.</span> : null}
                {bulkLabelCatalogLoadingMailboxIds.length > 0 ? <span className="conversations-live-label-notice" role="status">Loading user labels for the selected accounts…</span> : null}
                {bulkLabelCatalogFailedMailboxIds.length > 0 ? <span className="conversations-live-label-notice" role="alert">Could not load labels for a selected account. Retry its labels in Manage labels.</span> : null}
                {bulkLabelSelectionMissingMailboxIds.length > 0 && bulkLabelCatalogLoadingMailboxIds.length === 0 && bulkLabelCatalogFailedMailboxIds.length === 0 ? (
                  <span className="conversations-live-label-notice" role="status">Choose a user label for every selected account. Create one in Manage labels if an account has no labels.</span>
                ) : null}
              </div>
            ) : <span className="conversations-live-selection-hint">Select conversations to apply actions</span>}
          </div>

          {listError ? (
            <div className="conversations-live-list-state" role="alert">
              <AlertCircle size={19} />
              <strong>Conversations could not be loaded</strong>
              <span>{listError}</span>
              {listErrorCode === "EAST_SESSION_EXPIRED" || listErrorCode === "MAILBOX_AUTH_REQUIRED" ? (
                <button type="button" className="conversations-live-retry" onClick={() => window.location.reload()}>Reload East</button>
              ) : (
                <button type="button" className="conversations-live-retry" onClick={() => setRefreshVersion((value) => value + 1)}>Try again</button>
              )}
            </div>
          ) : listLoading && threads.length === 0 ? (
            <div className="conversations-live-list-state" role="status"><LoaderCircle size={19} /><span>Loading conversations…</span></div>
          ) : threads.length === 0 ? (
            <div className="conversations-live-list-state"><div className="conversations-empty-icon"><MessageCircleMore size={21} /></div><strong>No conversations found</strong><span>{query ? "Try another search or clear some filters." : "This mailbox view has no conversations to show."}</span></div>
          ) : (
            <div className="conversations-thread-list" data-density={density} ref={threadListRef}>
              {threads.map((thread) => {
                const identity = thread.mailbox ?? accountMap.get(thread.mailboxId) ?? null;
                return <RealThreadRow
                  key={`${thread.mailboxId}:${thread.id}`}
                  thread={thread}
                  identity={identity}
                  selected={thread.id === selectedThreadId}
                  checked={selectedThreadIds.has(thread.id)}
                  density={density}
                  mutationBusy={busyMutation !== null}
                  onOpen={openThread}
                  onToggleSelection={toggleThreadSelection}
                  onToggleStar={(selected) => void runSingleAction(selected.id, selected.starred ? "UNSTAR" : "STAR")}
                />;
              })}
              {nextPageToken ? <div ref={loadMoreSentinelRef} className="conversations-pagination-sentinel" aria-hidden="true" /> : null}
            </div>
          )}

          <div className="conversations-list-footer conversations-live-list-footer">
            {nextPageToken ? <button type="button" onClick={() => void loadMore()} disabled={loadingMore || listLoading}>{loadingMore ? "Loading…" : "Load more"}</button> : <span>{threads.length > 0 ? "End of current results" : " "}</span>}
            {listLoading && threads.length === 0
              ? <span role="status">Loading conversations…</span>
              : <span>{query.trim()
                ? `Showing ${threads.length} search results loaded${nextPageToken ? " · scroll to load next 30" : ""}`
                : `Showing ${threads.length} conversations loaded${nextPageToken ? " · scroll to load next 30" : ""}`}</span>}
          </div>
        </section>
        </>
          )}
          renderReader={(listCollapsed, onShowList) => (
            <div className="conversations-reader-stage" aria-hidden={!selectedThreadId}>
        {selectedThreadId ? (
          <RealThreadReader
            thread={selectedSummary}
            identity={selectedIdentity}
            detail={threadDetail}
            loading={detailLoading}
            cacheState={detailCacheState}
            refreshError={detailRefreshError}
            error={detailError}
            onBack={() => { setReaderSourceNavigation(null); setSelectedThreadId(null); }}
            listCollapsed={listCollapsed}
            onShowList={onShowList}
            onRetry={() => setDetailRetryVersion((value) => value + 1)}
            labels={readerLabels}
            selectedLabelId={readerLabelId}
            onSelectLabel={setReaderLabelId}
            onAction={(action, labelIds) => {
              const targetThreadId = selectedThreadId ?? threadDetail?.id;
              if (targetThreadId) void runSingleAction(targetThreadId, action, labelIds);
            }}
            onCompose={startThreadCompose}
            mutationBusy={busyMutation !== null}
            sourceMessageId={readerSourceNavigation?.threadId === selectedThreadId ? readerSourceNavigation.messageId : null}
          />
        ) : (
          <section className="conversations-reader-empty" aria-label="No conversation selected">
            <div className="conversations-empty-icon"><MessageCircleMore size={22} /></div><strong>Select a conversation</strong><span>Choose a thread to load its messages.</span>
          </section>
        )}
            </div>
          )}
        />
      </div>

      {composerRequest ? <DraftComposer
        key={composerRequest.sessionId}
        request={composerRequest}
        mailboxes={mailboxes}
        onClose={() => setComposerRequest(null)}
        onSent={() => {
          setComposerRequest(null);
          setRefreshVersion((value) => value + 1);
        }}
      /> : null}

      <div className="conversations-prototype-note conversations-live-note"><ShieldCheck size={13} />Opening a thread never marks it read. Mailbox changes happen only after you choose an action.</div>
    </section>
  );
}
