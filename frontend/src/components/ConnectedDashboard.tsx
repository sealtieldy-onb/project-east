import { useEffect, useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from "react";
import {
  AlertCircle,
  AlertTriangle,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  CircleDollarSign,
  ExternalLink,
  FileText,
  LoaderCircle,
  Mail,
  Plus,
  RefreshCw,
  Settings2,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { Button } from "./ui/button";
import type { ConnectedMailboxSummary } from "../lib/auth-api";
import { mailboxColorAt } from "../lib/mailbox-colors";
import {
  generateManualReport,
  getDashboardReport,
  ReportsApiError,
  type DashboardReportSnapshot,
  type ManualReportRequest,
  type ReportDisclosureRequired,
  type ReportExpansionRequired,
  type ReportInsight,
  type ReportSourceReference,
} from "../lib/reports-api";
import {
  getAIProviderSettings,
  SettingsApiError,
  type AIProviderSettings,
} from "../lib/settings-api";
import "./connected-dashboard.css";

type ConnectedDashboardProps = {
  displayName: string | null;
  mailboxes: ConnectedMailboxSummary[];
  mailboxesMissingRequiredPermission: Set<string>;
  syncingMailboxIds: Set<string>;
  disconnectingMailboxIds: Set<string>;
  onSyncMailbox: (mailboxId: string) => void;
  onDisconnectMailbox: (mailboxId: string) => void;
  onConnectMailbox: () => void;
  onOpenSettings: () => void;
};

type ReportWindow = 7 | 30;
type ReportSourceEventDetail = Pick<ReportSourceReference, "mailboxId" | "threadId" | "messageId">;

function localDateString(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function reportPeriod(days: ReportWindow): { periodStart: string; periodEnd: string } {
  const endDate = new Date();
  const startDate = new Date(endDate.getFullYear(), endDate.getMonth(), endDate.getDate());
  startDate.setDate(startDate.getDate() - days + 1);
  return { periodStart: localDateString(startDate), periodEnd: localDateString(endDate) };
}

function providerLabel(settings: AIProviderSettings): string {
  return settings.provider === "openai" ? "OpenAI" : "OpenAI-compatible endpoint";
}

function safeHttpUrl(value: string | null): string | null {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
    return `${parsed.origin}${parsed.pathname === "/" ? "" : parsed.pathname}`;
  } catch {
    return null;
  }
}

function insightTitle(insight: ReportInsight): string {
  switch (insight.kind) {
    case "URGENT_ACTION": return "Urgent action";
    case "AWAITING_REPLY": return "Likely reply needed";
    case "REPLY_RECEIVED": return "Reply received";
    case "TRANSACTION": return "Receipt, invoice, or refund";
  }
}

function transactionDetails(insight: ReportInsight): string | null {
  const transaction = insight.transaction;
  if (!transaction) return null;
  const parts = [transaction.merchant, transaction.type];
  if (transaction.amount !== null) {
    parts.push([transaction.currency, String(transaction.amount)].filter(Boolean).join(" "));
  }
  if (transaction.date) parts.push(transaction.date);
  const present = parts.filter((part): part is string => Boolean(part?.trim()));
  return present.length > 0 ? present.join(" · ") : null;
}

function dispatchSourceNavigation(source: ReportSourceReference) {
  const detail: ReportSourceEventDetail = {
    mailboxId: source.mailboxId,
    threadId: source.threadId,
    messageId: source.messageId,
  };
  window.dispatchEvent(new CustomEvent<ReportSourceEventDetail>("east:openConversationSource", { detail }));
}

function SourceLink({ source, mailboxes }: { source: ReportSourceReference; mailboxes: ConnectedMailboxSummary[] }) {
  const mailbox = mailboxes.find((entry) => entry.id === source.mailboxId);
  const accountName = source.mailboxName || mailbox?.displayName || source.mailboxEmail || mailbox?.email || "Connected account";
  return (
    <button
      type="button"
      className="connected-report-source-link"
      onClick={() => dispatchSourceNavigation(source)}
      aria-label={`Open source message from ${accountName}`}
    >
      <Mail size={13} aria-hidden="true" />
      <span>{accountName}</span>
      <ExternalLink size={12} aria-hidden="true" />
    </button>
  );
}

function InsightCard({ insight, mailboxes }: { insight: ReportInsight; mailboxes: ConnectedMailboxSummary[] }) {
  const transaction = transactionDetails(insight);
  return (
    <article className="connected-report-insight">
      <div className="connected-report-insight-heading">
        <span className="connected-report-insight-kind">{insightTitle(insight)}</span>
        <span className="connected-report-confidence">{Math.round(insight.confidence * 100)}% confidence</span>
      </div>
      <p>{insight.text}</p>
      {insight.urgency ? <p className="connected-report-insight-meta"><strong>Urgency:</strong> {insight.urgency}</p> : null}
      {insight.deadline ? <p className="connected-report-insight-meta"><strong>Deadline:</strong> {insight.deadline}</p> : null}
      {transaction ? <p className="connected-report-insight-meta"><CircleDollarSign size={13} aria-hidden="true" />{transaction}</p> : null}
      {insight.status && insight.status !== "OPEN" ? (
        <p className="connected-report-insight-state"><CheckCircle2 size={13} aria-hidden="true" />{insight.status === "HANDLED" ? "Marked handled in East" : "Dismissed in East"}</p>
      ) : null}
      <div className="connected-report-sources" aria-label="Evidence messages">
        {insight.sources.map((source) => <SourceLink key={`${source.mailboxId}:${source.messageId}`} source={source} mailboxes={mailboxes} />)}
      </div>
    </article>
  );
}

function ReportContent({ snapshot, mailboxes }: { snapshot: DashboardReportSnapshot; mailboxes: ConnectedMailboxSummary[] }) {
  const coverage = snapshot.coverage;
  const summaryState = snapshot.summary?.status?.toUpperCase();
  const isPartial = coverage.status === "PARTIAL" || summaryState === "PARTIAL";
  const messagesNotIncluded = Math.max(0, coverage.eligibleMessageCount - coverage.selectedMessageCount);
  return (
    <>
      {snapshot.summary ? (
        <article className="connected-report-narrative">
          <div className="connected-report-narrative-heading">
            <span><FileText size={15} aria-hidden="true" />Report narrative</span>
            {snapshot.summary.generatedAt ? <time dateTime={snapshot.summary.generatedAt}>{new Date(snapshot.summary.generatedAt).toLocaleString()}</time> : null}
          </div>
          <p>{snapshot.summary.narrative}</p>
        </article>
      ) : (
        <div className="connected-report-empty" role="status">
          <Sparkles size={17} aria-hidden="true" />
          <span>No report is saved for this period yet. Generate one after reviewing the provider disclosure.</span>
        </div>
      )}
      {snapshot.summary && coverage.status !== "UNKNOWN" ? (
        <div className={`connected-report-coverage${isPartial ? " is-partial" : ""}`} role="status">
          <ShieldCheck size={14} aria-hidden="true" />
          <span>
            {isPartial ? "Partial coverage" : "Complete coverage"} · {coverage.selectedMessageCount} of {coverage.eligibleMessageCount} eligible messages used
            {isPartial && messagesNotIncluded > 0 ? ` · ${messagesNotIncluded} eligible messages were not included` : ""}
          </span>
        </div>
      ) : null}
      {snapshot.summary && snapshot.insights.length > 0 ? (
        <div className="connected-report-insight-list" aria-label="Evidence-linked insights">
          {snapshot.insights.map((insight) => <InsightCard key={insight.id} insight={insight} mailboxes={mailboxes} />)}
        </div>
      ) : snapshot.summary ? (
        <p className="connected-report-no-insights">No sourced insights were produced for this period.</p>
      ) : null}
    </>
  );
}

type ReportFilterOption = { value: string; label: string };

function ReportFilterDropdown({
  label,
  value,
  options,
  onChange,
  disabled = false,
  alignMenu = "left",
}: {
  label: string;
  value: string;
  options: ReportFilterOption[];
  onChange: (value: string) => void;
  disabled?: boolean;
  alignMenu?: "left" | "right";
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const labelId = useId();
  const valueId = useId();
  const menuId = useId();
  const selectedIndex = Math.max(0, options.findIndex((option) => option.value === value));
  const selectedOption = options[selectedIndex];

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
    const next = (index + options.length) % options.length;
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
      focusOption(options.length - 1);
    } else if (event.key === "Escape") {
      event.preventDefault();
      closeMenu(true);
    }
  }

  return (
    <div className={`connected-report-filter${alignMenu === "right" ? " connected-report-filter--align-right" : ""}`} ref={rootRef}>
      <span className="connected-report-filter-label" id={labelId}>{label}</span>
      <button
        type="button"
        ref={triggerRef}
        className="connected-report-filter-trigger"
        aria-labelledby={`${labelId} ${valueId}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="connected-report-filter-value" id={valueId}>{selectedOption?.label ?? "Choose an option"}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open ? (
        <div id={menuId} className="connected-report-filter-menu" role="menu" aria-labelledby={labelId}>
          {options.map((option, index) => {
            const selected = option.value === value;
            return (
              <button
                type="button"
                role="menuitemradio"
                aria-checked={selected}
                className="connected-report-filter-option"
                key={option.value}
                ref={(element) => { optionRefs.current[index] = element; }}
                tabIndex={selected ? 0 : -1}
                onKeyDown={(event) => handleOptionKeyDown(event, index)}
                onClick={() => {
                  onChange(option.value);
                  closeMenu(true);
                }}
              >
                <span>{option.label}</span>
                {selected ? <Check size={14} aria-hidden="true" /> : null}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

export default function ConnectedDashboard({ displayName, mailboxes, mailboxesMissingRequiredPermission, syncingMailboxIds, disconnectingMailboxIds, onSyncMailbox, onDisconnectMailbox, onConnectMailbox, onOpenSettings }: ConnectedDashboardProps) {
  const firstName = displayName?.trim().split(/\s+/)[0] || "there";
  const [rangeDays, setRangeDays] = useState<ReportWindow>(7);
  const hasManuallyChosenRange = useRef(false);
  const [mailboxScope, setMailboxScope] = useState("");
  const [snapshot, setSnapshot] = useState<DashboardReportSnapshot | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(true);
  const [snapshotError, setSnapshotError] = useState<string | null>(null);
  const [providerSettings, setProviderSettings] = useState<AIProviderSettings | null>(null);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [disclosureConfirmed, setDisclosureConfirmed] = useState(false);
  const [reportPreview, setReportPreview] = useState<ReportDisclosureRequired | null>(null);
  const [pendingExpansion, setPendingExpansion] = useState<ReportExpansionRequired | null>(null);
  const [generationBusy, setGenerationBusy] = useState(false);
  const [generationError, setGenerationError] = useState<string | null>(null);
  const period = useMemo(() => reportPeriod(rangeDays), [rangeDays]);
  const connectedMailboxIds = useMemo(() => mailboxes.map((mailbox) => mailbox.id).join(","), [mailboxes]);
  const query = useMemo(() => ({
    ...period,
    ...(mailboxScope ? { mailboxId: mailboxScope } : {}),
  }), [mailboxScope, period]);
  const selectedMailbox = mailboxScope ? mailboxes.find((mailbox) => mailbox.id === mailboxScope) ?? null : null;
  const scopeLabel = selectedMailbox
    ? selectedMailbox.displayName && selectedMailbox.displayName !== selectedMailbox.email
      ? `${selectedMailbox.displayName} · ${selectedMailbox.email}`
      : selectedMailbox.email
    : `all connected accounts (${mailboxes.map((mailbox) => mailbox.email).join(", ") || "none"})`;
  const aiConfigured = snapshot?.aiConfigured ?? false;
  const providerIsReady = Boolean(
    aiConfigured && providerSettings?.keyConfigured && providerSettings.model?.trim() && providerSettings.provider && providerSettings.configurationVersion?.trim(),
  );
  const openAIPrivacyUrl = "https://openai.com/policies/privacy-policy/";
  const customEndpointUrl = safeHttpUrl(providerSettings?.baseUrl ?? null);
  const messageCount = reportPreview?.eligibleMessageCount ?? null;
  const messageDisclosure = messageCount === null
    ? "Review the current message count before confirming email-content processing."
    : `${messageCount} eligible ${messageCount === 1 ? "message is" : "messages are"} in the selected period.`;

  useEffect(() => {
    const controller = new AbortController();
    setSnapshot(null);
    setSnapshotLoading(true);
    setSnapshotError(null);
    getDashboardReport(query, controller.signal).then((result) => {
      if (!controller.signal.aborted) {
        setSnapshot(result);
        if (result.firstReportRecommended && !hasManuallyChosenRange.current) setRangeDays(30);
      }
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) {
        setSnapshotError(error instanceof ReportsApiError ? error.message : "The dashboard report could not be loaded.");
      }
    }).finally(() => {
      if (!controller.signal.aborted) setSnapshotLoading(false);
    });
    return () => controller.abort();
  }, [query, connectedMailboxIds]);

  useEffect(() => {
    let active = true;
    getAIProviderSettings().then((settings) => {
      if (active) {
        setProviderSettings(settings);
        setProviderError(null);
      }
    }).catch((error: unknown) => {
      if (active) {
        setProviderSettings(null);
        setProviderError(error instanceof SettingsApiError ? error.message : "AI provider details could not be loaded.");
      }
    });
    return () => { active = false; };
  }, []);

  function changeRange(value: string) {
    if (value !== "7" && value !== "30") return;
    hasManuallyChosenRange.current = true;
    setRangeDays(Number(value) as ReportWindow);
    setDisclosureConfirmed(false);
    setReportPreview(null);
    setPendingExpansion(null);
    setGenerationError(null);
  }

  function changeScope(value: string) {
    if (value && !mailboxes.some((mailbox) => mailbox.id === value)) return;
    setMailboxScope(value);
    setDisclosureConfirmed(false);
    setReportPreview(null);
    setPendingExpansion(null);
    setGenerationError(null);
  }

  async function previewReport() {
    if (!providerIsReady || generationBusy || !providerSettings) return;
    setGenerationBusy(true);
    setGenerationError(null);
    try {
      const currentProviderSettings = await getAIProviderSettings();
      const destinationChanged = currentProviderSettings.provider !== providerSettings.provider
        || currentProviderSettings.baseUrl !== providerSettings.baseUrl
        || currentProviderSettings.model !== providerSettings.model
        || currentProviderSettings.configurationVersion !== providerSettings.configurationVersion;
      setProviderSettings(currentProviderSettings);
      if (destinationChanged || !currentProviderSettings.configurationVersion || !currentProviderSettings.keyConfigured) {
        setReportPreview(null);
        setDisclosureConfirmed(false);
        setGenerationError("The provider settings changed. Review the updated destination before checking the message count.");
        return;
      }
      const result = await generateManualReport({
        periodStart: query.periodStart,
        periodEnd: query.periodEnd,
        ...(mailboxScope ? { mailboxIds: [mailboxScope] } : {}),
        providerDisclosureConfirmed: false,
        providerConfigurationVersion: currentProviderSettings.configurationVersion,
      });
      if (result.resultType !== "DISCLOSURE_REQUIRED") {
        throw new ReportsApiError("The report service did not return a message-count preview.");
      }
      setReportPreview(result);
      setPendingExpansion(null);
      setDisclosureConfirmed(false);
    } catch (error) {
      setGenerationError(error instanceof ReportsApiError || error instanceof SettingsApiError ? error.message : "East could not check the current message count.");
    } finally {
      setGenerationBusy(false);
    }
  }

  async function submitReport(expandedReadDecision?: "ALLOW" | "DECLINE") {
    if (!providerIsReady || !disclosureConfirmed || !reportPreview || generationBusy) return;
    setGenerationBusy(true);
    setGenerationError(null);
    try {
      const currentProviderSettings = await getAIProviderSettings();
      const destinationChanged = currentProviderSettings.provider !== providerSettings?.provider
        || currentProviderSettings.baseUrl !== providerSettings?.baseUrl
        || currentProviderSettings.model !== providerSettings?.model
        || currentProviderSettings.configurationVersion !== providerSettings?.configurationVersion;
      setProviderSettings(currentProviderSettings);
      if (destinationChanged) {
        setDisclosureConfirmed(false);
        setReportPreview(null);
        setPendingExpansion(null);
        setGenerationError("The provider or model changed. Review the updated disclosure and confirm it before generating a report.");
        return;
      }
      if (!currentProviderSettings.keyConfigured || !currentProviderSettings.model || !currentProviderSettings.configurationVersion) {
        setDisclosureConfirmed(false);
        setReportPreview(null);
        setPendingExpansion(null);
        setGenerationError("AI provider settings are no longer ready. Review AI settings, then confirm the disclosure again.");
        return;
      }
      const request: ManualReportRequest = {
        periodStart: query.periodStart,
        periodEnd: query.periodEnd,
        ...(mailboxScope ? { mailboxIds: [mailboxScope] } : {}),
        providerDisclosureConfirmed: true,
        providerConfigurationVersion: currentProviderSettings.configurationVersion,
        expectedEligibleMessageCount: reportPreview.eligibleMessageCount,
        expectedAdditionalMessageCount: reportPreview.additionalMessageCount,
        ...(expandedReadDecision ? { expandedReadDecision } : {}),
      };
      const result = await generateManualReport(request);
      if (result.resultType === "DISCLOSURE_REQUIRED") {
        setReportPreview(result);
        setDisclosureConfirmed(false);
        setPendingExpansion(null);
        setGenerationError("The eligible-message count changed. Review the updated count and confirm disclosure again.");
        return;
      }
      if (result.resultType === "EXPANSION_REQUIRED") {
        setPendingExpansion(result);
        return;
      }
      setSnapshot(result);
      setPendingExpansion(null);
      setDisclosureConfirmed(false);
    } catch (error) {
      setGenerationError(error instanceof ReportsApiError || error instanceof SettingsApiError ? error.message : "East could not generate this report.");
    } finally {
      setGenerationBusy(false);
    }
  }

  const providerName = providerSettings ? providerLabel(providerSettings) : "the configured provider";
  const providerModel = providerSettings?.model || "model details unavailable";
  const selectedPeriodLabel = rangeDays === 30 ? "Past 30 days" : "Past 7 days";
  const reportPeriodNote = snapshot?.firstReportRecommended
    ? `${selectedPeriodLabel} is selected for this account's first report.`
    : `${selectedPeriodLabel} is selected.`;
  const descriptionForProvider = providerSettings?.provider === "openai-compatible"
    ? "Email text will go to this endpoint. Its operator controls how submitted data is processed and retained; East cannot verify those terms."
    : providerSettings?.provider === "openai"
      ? "Email text will be sent to OpenAI for this report. Review OpenAI's current privacy terms before continuing."
      : "Load the current provider and model details before confirming disclosure.";

  return (
    <section className="connected-dashboard" aria-labelledby="connected-dashboard-title">
      <header className="connected-dashboard-heading">
        <div>
          <p className="connected-dashboard-eyebrow"><Sparkles size={14} /> YOUR LOCAL WORKSPACE</p>
          <h1 id="connected-dashboard-title">Good to see you, {firstName}.</h1>
          <p>East brings your connected mailboxes together while keeping each account identifiable.</p>
        </div>
        {mailboxes.length === 0 ? (
          <Button onClick={onConnectMailbox}><Plus size={16} />Connect mailbox</Button>
        ) : null}
      </header>

      <section className="connected-dashboard-report" aria-labelledby="connected-report-title">
        <div className="connected-dashboard-report-topbar">
          <div className="connected-dashboard-report-heading">
            <div className="connected-dashboard-report-icon"><Sparkles size={18} /></div>
            <div className="connected-dashboard-report-copy">
              <span className="connected-dashboard-kicker">AI REPORTS</span>
              <h2 id="connected-report-title">Your mail, distilled</h2>
            </div>
          </div>
          <Button variant="outline" onClick={onOpenSettings}><Settings2 size={15} />AI settings</Button>
        </div>

        <div className="connected-dashboard-report-content">
          <p className="connected-dashboard-report-description">Generate a report only when you ask. Loading this Dashboard never calls the AI provider.</p>
          {mailboxes.length > 0 ? (
            <div className="connected-report-controls">
              <ReportFilterDropdown
                label="Report period"
                value={String(rangeDays)}
                onChange={changeRange}
                disabled={generationBusy}
                options={[
                  { value: "30", label: "Past 30 days" },
                  { value: "7", label: "Past 7 days" },
                ]}
              />
              <ReportFilterDropdown
                label="Account scope"
                value={mailboxScope}
                onChange={changeScope}
                disabled={generationBusy}
                alignMenu="right"
                options={[
                  { value: "", label: "All connected accounts" },
                  ...mailboxes.map((mailbox) => ({
                    value: mailbox.id,
                    label: mailbox.displayName && mailbox.displayName !== mailbox.email
                      ? `${mailbox.displayName} · ${mailbox.email}`
                      : mailbox.email,
                  })),
                ]}
              />
              <div className="connected-report-period-summary"><CalendarDays size={14} aria-hidden="true" /><span>{period.periodStart} – {period.periodEnd} · {scopeLabel}</span></div>
            </div>
          ) : null}

          {mailboxes.length === 0 ? (
            <div className="connected-report-empty-state"><Mail size={17} /><span>Connect a Gmail account to create an AI report.</span></div>
          ) : snapshotLoading ? (
            <div className="connected-report-empty-state" role="status"><LoaderCircle size={17} className="connected-dashboard-sync-spinner" /><span>Loading saved reports…</span></div>
          ) : snapshotError ? (
            <div className="connected-report-error" role="alert"><AlertCircle size={16} /><span>{snapshotError}</span><Button type="button" variant="ghost" size="sm" onClick={() => setSnapshotError(null)}>Dismiss</Button></div>
          ) : !snapshot ? (
            <div className="connected-report-error" role="alert"><AlertCircle size={16} /><span>The report response is unavailable. Reload this Dashboard and try again.</span></div>
          ) : !aiConfigured ? (
            <div className="connected-report-configure" role="status">
              <div><strong>Configure an AI provider to generate a report</strong><p>East keeps Gmail and Conversations available without AI settings. A saved key alone never starts processing.</p></div>
              <Button variant="outline" onClick={onOpenSettings}><Settings2 size={14} />Configure AI</Button>
            </div>
          ) : (
            <>
              <div className="connected-report-provider" aria-label="Configured AI provider">
                <span className="connected-report-provider-label">Provider</span>
                <strong>{providerName}</strong>
                <span className="connected-report-provider-model">{providerSettings?.model || "Loading provider details…"}</span>
                {providerSettings?.provider === "openai-compatible" && customEndpointUrl ? <span className="connected-report-provider-url">{customEndpointUrl}</span> : null}
              </div>

              {providerError ? <p className="connected-report-inline-error" role="alert">{providerError}</p> : null}
              <ReportContent snapshot={snapshot} mailboxes={mailboxes} />

              {!pendingExpansion && providerIsReady ? (
                <div className="connected-report-disclosure">
                  <p className="connected-report-disclosure-copy">
                    {reportPreview
                      ? <>{messageDisclosure} This report covers {scopeLabel}, {period.periodStart} to {period.periodEnd}. {descriptionForProvider} East does not send attachment contents. The first 30 messages per selected account are the initial allowance; East will pause and ask before retrieving any additional message bodies.</>
                      : <>East first checks message headers for {scopeLabel}, {period.periodStart} to {period.periodEnd}, so it can show the current eligible-message count. This check does not fetch message bodies or call the AI provider.</>}
                    {providerSettings?.provider === "openai"
                      ? <> <a href={openAIPrivacyUrl} target="_blank" rel="noreferrer">OpenAI privacy policy<ExternalLink size={11} aria-hidden="true" /></a></>
                      : customEndpointUrl
                        ? <> <a href={customEndpointUrl} target="_blank" rel="noreferrer">Open configured endpoint<ExternalLink size={11} aria-hidden="true" /></a></>
                        : null}
                  </p>
                  {reportPreview ? <label className="connected-report-disclosure-check">
                    <input
                      type="checkbox"
                      checked={disclosureConfirmed}
                      onChange={(event) => {
                        setDisclosureConfirmed(event.target.checked);
                        setGenerationError(null);
                      }}
                      disabled={generationBusy || !providerIsReady}
                    />
                    <span>I understand this email text will be sent to {providerName} ({providerModel}) to create the report.</span>
                  </label> : null}
                  <div className="connected-report-generation-actions">
                    <Button
                      type="button"
                      onClick={() => void (reportPreview ? submitReport() : previewReport())}
                      disabled={!providerIsReady || (reportPreview && !disclosureConfirmed) || generationBusy || snapshotLoading}
                    >
                      {generationBusy ? <LoaderCircle size={15} className="connected-dashboard-sync-spinner" /> : reportPreview ? <Sparkles size={15} /> : <Mail size={15} />}
                      {generationBusy ? (reportPreview ? "Generating report…" : "Checking message count…") : reportPreview ? "Generate report" : "Review message count"}
                    </Button>
                    <span>{reportPreview ? reportPeriodNote : "Message bodies stay unread until you confirm the report disclosure."}</span>
                  </div>
                </div>
              ) : pendingExpansion ? (
                <div className="connected-report-expansion" role="group" aria-labelledby="connected-report-expansion-title">
                  <div className="connected-report-expansion-heading"><ShieldCheck size={17} /><div><strong id="connected-report-expansion-title">East has not read or sent the additional messages</strong><p>Across the selected accounts, {pendingExpansion.additionalMessageCount} eligible {pendingExpansion.additionalMessageCount === 1 ? "message is" : "messages are"} beyond the initial {pendingExpansion.initialLimit} per account. East can fetch up to {pendingExpansion.maxAdditionalMessageCount} additional bodies for this report. Any remaining messages stay unread and the report will show partial coverage.</p></div></div>
                  <div className="connected-report-generation-actions">
                    <Button type="button" onClick={() => void submitReport("ALLOW")} disabled={generationBusy || !providerIsReady}>
                      {generationBusy ? <LoaderCircle size={15} className="connected-dashboard-sync-spinner" /> : <Mail size={15} />}
                      {generationBusy ? "Preparing report…" : `Allow up to ${pendingExpansion.maxAdditionalMessageCount} more`}
                    </Button>
                    <Button type="button" variant="outline" onClick={() => void submitReport("DECLINE")} disabled={generationBusy || !providerIsReady}>
                      Use first {pendingExpansion.initialLimit} only
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="connected-report-empty-state" role="status">
                  <ShieldCheck size={16} aria-hidden="true" />
                  <span>{providerError ?? "Current provider details are loading. Report generation stays unavailable until the destination can be disclosed."}</span>
                </div>
              )}

              {generationError ? <p className="connected-report-inline-error" role="alert">{generationError}</p> : null}
              {snapshot?.summary ? <p className="connected-report-retention-note"><ShieldCheck size={13} aria-hidden="true" />Report text and insights expire with the encrypted message-body cache. Insights link to their supporting Gmail messages.</p> : null}
            </>
          )}
        </div>
      </section>

      <section className="connected-dashboard-mailboxes" aria-labelledby="connected-mailboxes-title">
        <div className="connected-dashboard-section-heading">
          <div>
            <span className="connected-dashboard-kicker">MAILBOXES</span>
            <h2 id="connected-mailboxes-title">Connected accounts</h2>
          </div>
          {mailboxes.length > 0 ? <Button variant="outline" onClick={onConnectMailbox}><Plus size={15} />Add account</Button> : null}
        </div>

        {mailboxes.length === 0 ? (
          <div className="connected-dashboard-empty">
            <span className="connected-dashboard-empty-icon"><Mail size={20} /></span>
            <div><strong>No Gmail accounts connected yet</strong><p>Add an account to load its mailbox in Conversations.</p></div>
            <Button variant="ghost" onClick={onConnectMailbox}><Plus size={15} />Connect Gmail</Button>
          </div>
        ) : (
          <div className="connected-dashboard-mailbox-list">
            {mailboxes.map((mailbox) => {
              const mailboxSyncing = syncingMailboxIds.has(mailbox.id) || mailbox.syncStatus === "syncing";
              const permissionReconnectRequired = mailboxesMissingRequiredPermission.has(mailbox.id);
              const needsReauthorization = permissionReconnectRequired || mailbox.reauthorizationRequired || mailbox.syncStatus === "needs_reauthorization";
              return (
                <article className="connected-dashboard-mailbox" key={mailbox.id}>
                  <span className="connected-dashboard-account-dot" style={{ "--account-color": mailboxColorAt(mailbox.colorIndex) ?? "#88c0d0" } as CSSProperties} aria-hidden="true" />
                  <div className="connected-dashboard-mailbox-copy"><strong>{mailbox.displayName || mailbox.email}</strong><span>{mailbox.email}</span></div>
                  {needsReauthorization ? (
                    <span className="connected-dashboard-reauth-state" role="status">
                      <AlertTriangle size={14} />{permissionReconnectRequired ? "Gmail permission needs approval" : "Google access needs reconnection"}
                    </span>
                  ) : mailbox.syncStatus === "error" || mailbox.syncStatus === "partial_failure" ? (
                    <span className="connected-dashboard-sync-error" role="status">
                      <AlertCircle size={14} />{mailbox.syncStatus === "partial_failure" ? "Partly synced · refresh to retry" : "Sync failed · refresh to retry"}
                    </span>
                  ) : mailboxSyncing ? (
                    <span className="connected-dashboard-sync-state connected-dashboard-syncing-state" role="status">
                      <LoaderCircle size={14} className="connected-dashboard-sync-spinner" />Syncing
                    </span>
                  ) : (
                    <span className="connected-dashboard-sync-state">
                      <ShieldCheck size={14} />
                      {mailbox.syncStatus === "ready" ? "Synced" : "Awaiting first sync"}
                    </span>
                  )}
                  {needsReauthorization ? (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={mailboxSyncing || disconnectingMailboxIds.has(mailbox.id)}
                      onClick={onConnectMailbox}
                      aria-label={`Reconnect ${mailbox.email}; choose this account in Google`}
                      title={`Choose ${mailbox.email} when Google asks which account to connect`}
                    >
                      <RefreshCw size={14} />Reconnect
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={mailboxSyncing || disconnectingMailboxIds.has(mailbox.id)}
                      onClick={() => onSyncMailbox(mailbox.id)}
                      aria-label={`${mailboxSyncing ? "Syncing" : "Sync"} ${mailbox.email}`}
                    >
                      {mailboxSyncing ? <LoaderCircle size={14} className="connected-dashboard-sync-spinner" /> : <RefreshCw size={14} />}
                      {mailboxSyncing ? "Syncing" : mailbox.syncStatus === "pending" ? "Sync now" : "Refresh"}
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={mailboxSyncing || disconnectingMailboxIds.has(mailbox.id)}
                    onClick={() => {
                      const confirmed = window.confirm(
                        `Disconnect ${mailbox.email} from East? East will remove its local credentials, cached messages, and drafts. This does not change Gmail content, but Google may retain East's authorization until you revoke it in your Google Account.`,
                      );
                      if (confirmed) onDisconnectMailbox(mailbox.id);
                    }}
                  >
                    {disconnectingMailboxIds.has(mailbox.id) ? "Removing…" : "Disconnect"}
                  </Button>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <p className="connected-dashboard-privacy"><ShieldCheck size={14} />Cached content and credentials are encrypted locally; the key file is restricted to this computer account.</p>
    </section>
  );
}
