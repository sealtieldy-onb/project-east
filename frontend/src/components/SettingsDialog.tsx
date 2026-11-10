import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type SyntheticEvent } from "react";
import { AlertCircle, AlertTriangle, Check, Clock3, ExternalLink, KeyRound, LoaderCircle, Mail, RotateCcw, Settings2, ShieldCheck, X } from "lucide-react";
import { Button } from "./ui/button";
import {
  deleteAIProviderSettings,
  getAIProviderSettings,
  getRetentionSettings,
  patchRetentionSettings,
  putAIProviderSettings,
  testAIProviderConnection,
  SettingsApiError,
  type AIProviderConnectionTestResponse,
  type AIProviderSettings,
  type AIProviderType,
  type AIProviderUpdate,
  type RetentionSettings,
} from "../lib/settings-api";
import {
  DailyReportSettingsApiError,
  getDailyReportSettings,
  putDailyReportSettings,
  type DailyReportSettings,
} from "../lib/daily-report-settings-api";
import "./settings-dialog.css";

type SettingsDialogProps = {
  onClose: () => void;
  onResetLocalData: (confirmation: string) => Promise<boolean>;
};

type LoadedSettings = {
  ai: AIProviderSettings;
  retention: RetentionSettings;
};

function safeMessage(error: unknown): string {
  return error instanceof SettingsApiError
    ? error.message
    : "The settings request failed. Check that the local service is available and try again.";
}

function localTimezoneId(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

function safeEndpointUrl(value: string | null): string | null {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
    return `${parsed.origin}${parsed.pathname === "/" ? "" : parsed.pathname}`;
  } catch {
    return null;
  }
}

function isValidTimezoneId(value: string): boolean {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

function sameProviderDestination(left: AIProviderSettings, right: AIProviderSettings): boolean {
  return left.provider === right.provider && left.baseUrl === right.baseUrl && left.model === right.model;
}

export default function SettingsDialog({ onClose, onResetLocalData }: SettingsDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [loaded, setLoaded] = useState<LoadedSettings | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [provider, setProvider] = useState<AIProviderType>("openai");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [retentionDays, setRetentionDays] = useState("7");
  const [busy, setBusy] = useState(false);
  const [connectionTestBusy, setConnectionTestBusy] = useState(false);
  const [connectionTestResult, setConnectionTestResult] = useState<AIProviderConnectionTestResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetConfirmation, setResetConfirmation] = useState("");
  const [dailySettings, setDailySettings] = useState<DailyReportSettings | null>(null);
  const [dailyLoading, setDailyLoading] = useState(true);
  const [dailyBusy, setDailyBusy] = useState(false);
  const [dailyError, setDailyError] = useState<string | null>(null);
  const [dailyNotice, setDailyNotice] = useState<string | null>(null);
  const [dailyEnabled, setDailyEnabled] = useState(false);
  const [dailyRunAt, setDailyRunAt] = useState("08:00");
  const [dailyTimezoneId, setDailyTimezoneId] = useState(localTimezoneId);
  const [dailyDisclosureConfirmed, setDailyDisclosureConfirmed] = useState(false);
  const [providerDestinationChanged, setProviderDestinationChanged] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    void loadSettings();
    void loadDailySettings();
    // Dialog initialization is intentionally one-time; refresh is user-triggered.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadSettings() {
    setLoadError(null);
    try {
      const [ai, retention] = await Promise.all([getAIProviderSettings(), getRetentionSettings()]);
      setLoaded({ ai, retention });
      if (ai.provider) setProvider(ai.provider);
      setBaseUrl(ai.baseUrl ?? "");
      setModel(ai.model ?? "");
      setRetentionDays(String(retention.bodyCacheDays));
    } catch (loadFailure) {
      setLoadError(safeMessage(loadFailure));
    }
  }

  async function loadDailySettings() {
    setDailyLoading(true);
    setDailyError(null);
    try {
      const settings = await getDailyReportSettings();
      setDailySettings(settings);
      setDailyEnabled(settings.providerDisclosureRequired ? false : settings.enabled);
      setDailyRunAt(settings.runAtLocal.slice(0, 5));
      setDailyTimezoneId(settings.timezoneId);
      setDailyDisclosureConfirmed(settings.enabled && !settings.providerDisclosureRequired);
      if (!settings.providerDisclosureRequired) setProviderDestinationChanged(false);
    } catch (loadFailure) {
      setDailySettings(null);
      setDailyError(loadFailure instanceof DailyReportSettingsApiError ? loadFailure.message : "Daily report settings could not be loaded.");
    } finally {
      setDailyLoading(false);
    }
  }

  function handleCancel(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault();
    onClose();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDialogElement>) {
    if (event.target === dialogRef.current) onClose();
  }

  async function handleProviderConnectionTest() {
    setError(null);
    setNotice(null);
    setConnectionTestResult(null);

    const trimmedModel = model.trim();
    const trimmedBaseUrl = baseUrl.trim();
    if (!trimmedModel) {
      setError("Enter a model name.");
      return;
    }
    if (provider === "openai-compatible") {
      try {
        const parsed = new URL(trimmedBaseUrl);
        if (parsed.protocol !== "https:" && parsed.hostname !== "localhost" && parsed.hostname !== "127.0.0.1") {
          setError("Use an HTTPS endpoint or a local HTTP endpoint.");
          return;
        }
      } catch {
        setError("Enter a valid compatible endpoint URL.");
        return;
      }
    }
    if (!keyConfigured && !apiKey.trim()) {
      setError("Enter an API key before testing the connection.");
      return;
    }

    const update: AIProviderUpdate = {
      provider,
      ...(provider === "openai-compatible" ? { baseUrl: trimmedBaseUrl } : {}),
      model: trimmedModel,
      ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
    };
    setConnectionTestBusy(true);
    try {
      setConnectionTestResult(await testAIProviderConnection(update));
    } catch (testFailure) {
      setConnectionTestResult({ connected: false, message: safeMessage(testFailure) });
    } finally {
      setConnectionTestBusy(false);
    }
  }

  async function handleProviderSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    setConnectionTestResult(null);

    const trimmedModel = model.trim();
    const trimmedBaseUrl = baseUrl.trim();
    if (!trimmedModel) {
      setError("Enter a model name.");
      return;
    }
    if (provider === "openai-compatible") {
      try {
        const parsed = new URL(trimmedBaseUrl);
        if (parsed.protocol !== "https:" && parsed.hostname !== "localhost" && parsed.hostname !== "127.0.0.1") {
          setError("Use an HTTPS endpoint or a local HTTP endpoint.");
          return;
        }
      } catch {
        setError("Enter a valid compatible endpoint URL.");
        return;
      }
    }

    const submittedKey = apiKey;
    setApiKey("");
    setBusy(true);
    try {
      await putAIProviderSettings({
        provider,
        ...(provider === "openai-compatible" ? { baseUrl: trimmedBaseUrl } : {}),
        model: trimmedModel,
        ...(submittedKey.length > 0 ? { apiKey: submittedKey } : {}),
      });
      const updated = await getAIProviderSettings();
      const destinationChanged = loaded?.ai ? !sameProviderDestination(loaded.ai, updated) : false;
      const hadDailyConsent = Boolean(dailySettings?.enabled || dailySettings?.providerDisclosureRequired);
      setLoaded((current) => current ? { ...current, ai: updated } : current);
      setProvider(updated.provider ?? provider);
      setBaseUrl(updated.baseUrl ?? "");
      setModel(updated.model ?? trimmedModel);
      if (destinationChanged) {
        setProviderDestinationChanged(hadDailyConsent);
        setDailyDisclosureConfirmed(false);
        setDailyEnabled(false);
        await loadDailySettings();
        setProviderDestinationChanged(hadDailyConsent);
        setDailyDisclosureConfirmed(false);
        setDailyEnabled(false);
      }
      setNotice("AI provider settings saved.");
    } catch (saveFailure) {
      setError(safeMessage(saveFailure));
    } finally {
      setBusy(false);
    }
  }

  async function handleRemoveProvider() {
    setError(null);
    setNotice(null);
    setConnectionTestResult(null);
    setBusy(true);
    try {
      const hadDailyConsent = Boolean(dailySettings?.enabled || dailySettings?.providerDisclosureRequired);
      await deleteAIProviderSettings();
      const updated = await getAIProviderSettings();
      setLoaded((current) => current ? { ...current, ai: updated } : current);
      setProvider("openai");
      setBaseUrl("");
      setModel("");
      setApiKey("");
      setConfirmRemove(false);
      setProviderDestinationChanged(hadDailyConsent);
      setDailyDisclosureConfirmed(false);
      setDailyEnabled(false);
      await loadDailySettings();
      setProviderDestinationChanged(hadDailyConsent);
      setNotice("AI provider configuration removed.");
    } catch (removeFailure) {
      setError(safeMessage(removeFailure));
    } finally {
      setBusy(false);
    }
  }

  async function handleRetentionSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    const days = Number(retentionDays);
    if (!Number.isSafeInteger(days) || days < 1 || days > 365) {
      setError("Choose a whole number from 1 to 365 days.");
      return;
    }

    setBusy(true);
    try {
      await patchRetentionSettings({ bodyCacheDays: days });
      const updated = await getRetentionSettings();
      setLoaded((current) => current ? { ...current, retention: updated } : current);
      setRetentionDays(String(updated.bodyCacheDays));
      setNotice("Cache retention settings saved.");
    } catch (saveFailure) {
      setError(safeMessage(saveFailure));
    } finally {
      setBusy(false);
    }
  }

  async function handleDailyReportSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setDailyError(null);
    setDailyNotice(null);
    if (!dailySettings) {
      setDailyError("Daily report settings are not available yet. Retry loading them.");
      return;
    }
    if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(dailyRunAt)) {
      setDailyError("Choose a valid local time.");
      return;
    }
    if (!isValidTimezoneId(dailyTimezoneId.trim())) {
      setDailyError("Enter a valid IANA time zone, such as Asia/Manila.");
      return;
    }
    if (dailyEnabled && !dailyDisclosureConfirmed) {
      setDailyError("Review the daily-report disclosure and confirm it before enabling the schedule.");
      return;
    }

    setDailyBusy(true);
    try {
      if (dailyEnabled) {
        const currentProvider = await getAIProviderSettings();
        if (!loaded?.ai || !sameProviderDestination(loaded.ai, currentProvider)) {
          setLoaded((current) => current ? { ...current, ai: currentProvider } : current);
          setProvider(currentProvider.provider ?? "openai");
          setBaseUrl(currentProvider.baseUrl ?? "");
          setModel(currentProvider.model ?? "");
          setApiKey("");
          setProviderDestinationChanged(true);
          setDailyDisclosureConfirmed(false);
          setDailyEnabled(false);
          setDailyError("The provider or model changed. The schedule is paused; review the updated destination and confirm it before re-enabling daily reports.");
          await loadDailySettings();
          setProviderDestinationChanged(true);
          setDailyDisclosureConfirmed(false);
          setDailyEnabled(false);
          return;
        }
        if (!currentProvider.keyConfigured || !currentProvider.provider || !currentProvider.model) {
          setDailyError("Configure an AI provider and model before enabling daily reports.");
          return;
        }
        if (!currentProvider.configurationVersion) {
          setDailyError("Reload the provider disclosure before enabling daily reports.");
          return;
        }
      }

      const updated = await putDailyReportSettings({
        enabled: dailyEnabled,
        runAtLocal: dailyRunAt,
        timezoneId: dailyTimezoneId.trim(),
        providerDisclosureConfirmed: dailyEnabled && dailyDisclosureConfirmed,
        providerConfigurationVersion: dailyEnabled ? loaded?.ai.configurationVersion ?? null : null,
      });
      setDailySettings(updated);
      setDailyEnabled(updated.providerDisclosureRequired ? false : updated.enabled);
      setDailyRunAt(updated.runAtLocal.slice(0, 5));
      setDailyTimezoneId(updated.timezoneId);
      setDailyDisclosureConfirmed(updated.enabled && !updated.providerDisclosureRequired);
      setProviderDestinationChanged(false);
      if (dailyEnabled && updated.providerDisclosureRequired) {
        setDailyError("East requires renewed provider disclosure before it can enable daily reports. Review the destination and confirm again.");
      } else {
        setDailyNotice(updated.enabled ? "Daily reports are enabled." : "Daily reports are disabled.");
      }
    } catch (saveFailure) {
      setDailyError(saveFailure instanceof DailyReportSettingsApiError ? saveFailure.message : safeMessage(saveFailure));
    } finally {
      setDailyBusy(false);
    }
  }

  async function handleLocalResetSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    if (resetConfirmation !== "RESET LOCAL DATA") {
      setError("Type RESET LOCAL DATA exactly to confirm the destructive reset.");
      return;
    }

    setBusy(true);
    try {
      const completed = await onResetLocalData("RESET LOCAL DATA");
      if (completed) {
        setResetOpen(false);
        setResetConfirmation("");
      } else {
        setError("The local data could not be reset. Review the app status and try again.");
      }
    } catch (resetFailure) {
      setError(safeMessage(resetFailure));
    } finally {
      setBusy(false);
    }
  }

  const keyConfigured = loaded?.ai.keyConfigured ?? false;
  const providerConnectionTestDisabled = busy || connectionTestBusy || confirmRemove
    || !model.trim() || (provider === "openai-compatible" && !baseUrl.trim())
    || (!keyConfigured && !apiKey.trim());
  const dailyProviderReady = Boolean(loaded?.ai.keyConfigured && loaded.ai.provider && loaded.ai.model?.trim());
  const dailyProviderName = loaded?.ai.provider === "openai" ? "OpenAI" : "Custom OpenAI-compatible endpoint";
  const dailyEndpoint = safeEndpointUrl(loaded?.ai.baseUrl ?? null);
  const dailyProviderDestination = loaded?.ai.provider === "openai" ? "OpenAI" : dailyEndpoint ?? "the configured endpoint";
  const dailyNeedsDisclosure = Boolean(dailySettings?.providerDisclosureRequired || providerDestinationChanged);
  const isLoading = !loaded && !loadError;

  return (
    <dialog
      ref={dialogRef}
      className="settings-dialog"
      aria-labelledby="settings-dialog-title"
      aria-describedby="settings-dialog-description"
      onCancel={handleCancel}
      onClick={handleBackdropClick}
    >
      <div className="settings-dialog-shell">
        <header className="settings-dialog-header">
          <div className="settings-dialog-heading">
            <span className="settings-dialog-mark" aria-hidden="true"><Settings2 size={18} /></span>
            <div>
              <p className="settings-dialog-eyebrow">LOCAL WORKSPACE</p>
              <h2 id="settings-dialog-title">Settings</h2>
            </div>
          </div>
          <Button variant="ghost" size="icon" aria-label="Close settings" onClick={onClose}>
            <X size={18} />
          </Button>
        </header>

        <p className="settings-dialog-description" id="settings-dialog-description">
          Configure the AI provider used when you request analysis and control how long encrypted email content stays cached.
        </p>

        {loadError ? (
          <div className="settings-load-error" role="alert">
            <AlertCircle size={17} aria-hidden="true" />
            <div><strong>Settings could not be loaded</strong><span>{loadError}</span></div>
            <Button variant="outline" size="sm" onClick={() => void loadSettings()}>Retry</Button>
          </div>
        ) : null}

        {error ? <div className="settings-message settings-message--error" role="alert"><AlertCircle size={16} />{error}</div> : null}
        {notice ? <div className="settings-message settings-message--success" role="status"><Check size={16} />{notice}</div> : null}

        {isLoading ? (
          <div className="settings-loading" role="status"><LoaderCircle size={17} />Loading local settings…</div>
        ) : null}

        {loaded ? (
          <div className="settings-dialog-content" aria-busy={busy || connectionTestBusy}>
            <section className="settings-section" aria-labelledby="settings-ai-title">
              <div className="settings-section-heading">
                <div className="settings-section-icon" aria-hidden="true"><KeyRound size={17} /></div>
                <div>
                  <h3 id="settings-ai-title">AI provider</h3>
                  <p>Provider settings are saved encrypted in the local vault. Email content is sent only when you explicitly request an AI report or draft, or enable daily reports.</p>
                </div>
              </div>

              <form className="settings-form" onSubmit={handleProviderSubmit}>
                <label className="settings-label" htmlFor="settings-provider">Provider</label>
                <select id="settings-provider" className="settings-control" value={provider} onChange={(event) => { setProvider(event.target.value as AIProviderType); setConnectionTestResult(null); }} disabled={busy || connectionTestBusy}>
                  <option value="openai">OpenAI</option>
                  <option value="openai-compatible">Custom OpenAI-compatible endpoint</option>
                </select>

                {provider === "openai-compatible" ? (
                  <>
                    <label className="settings-label" htmlFor="settings-base-url">Endpoint URL</label>
                    <input id="settings-base-url" className="settings-control" type="url" inputMode="url" autoComplete="url" placeholder="https://api.example.com/v1" value={baseUrl} onChange={(event) => { setBaseUrl(event.target.value); setConnectionTestResult(null); }} disabled={busy || connectionTestBusy} required />
                  </>
                ) : null}

                <label className="settings-label" htmlFor="settings-model">Model</label>
                <input id="settings-model" className="settings-control" type="text" autoComplete="off" placeholder={provider === "openai" ? "e.g. gpt-4.1-mini" : "Provider model identifier"} value={model} onChange={(event) => { setModel(event.target.value); setConnectionTestResult(null); }} disabled={busy || connectionTestBusy} required />

                <div className="settings-key-status" role="status">
                  <ShieldCheck size={16} aria-hidden="true" />
                  <span>{keyConfigured ? "An API key is saved in the local vault." : "No API key is configured."}</span>
                </div>

                <label className="settings-label" htmlFor="settings-api-key">{keyConfigured ? "Replace API key (optional)" : "API key"}</label>
                <input id="settings-api-key" className="settings-control" type="password" autoComplete="new-password" value={apiKey} onChange={(event) => { setApiKey(event.target.value); setConnectionTestResult(null); }} disabled={busy || connectionTestBusy} required={!keyConfigured} aria-describedby="settings-key-note" />
                <p className="settings-field-note" id="settings-key-note">East sends the key to the selected provider only for explicit AI requests or connection tests. Saved keys are encrypted locally and never shown again. Leave blank to keep the saved key.</p>
                <p className="settings-field-note settings-provider-test-note">Test connection sends a short prompt to the selected provider and may incur provider usage charges. It includes no mailbox content and does not save unsaved settings.</p>
                {connectionTestResult ? (
                  <div className={`settings-message ${connectionTestResult.connected ? "settings-message--success" : "settings-message--error"}`} role={connectionTestResult.connected ? "status" : "alert"}>
                    {connectionTestResult.connected ? <Check size={16} aria-hidden="true" /> : <AlertCircle size={16} aria-hidden="true" />}
                    {connectionTestResult.message}
                  </div>
                ) : null}

                {confirmRemove ? (
                  <div className="settings-confirm-remove" role="group" aria-label="Confirm removing AI provider">
                    <p>Remove the saved provider configuration and key from the local vault?</p>
                    <div>
                      <Button variant="outline" size="sm" disabled={busy || connectionTestBusy} onClick={() => setConfirmRemove(false)}>Keep configuration</Button>
                      <Button size="sm" disabled={busy || connectionTestBusy} onClick={() => void handleRemoveProvider()}>{busy ? "Removing…" : "Remove provider"}</Button>
                    </div>
                  </div>
                ) : null}

                <div className="settings-form-actions">
                  <Button variant="outline" type="button" disabled={providerConnectionTestDisabled} onClick={() => void handleProviderConnectionTest()}>
                    {connectionTestBusy ? "Testing…" : "Test connection"}
                  </Button>
                  {keyConfigured && !confirmRemove ? <Button className="settings-remove-configuration-button" variant="outline" type="button" disabled={busy || connectionTestBusy} onClick={() => setConfirmRemove(true)}>Remove configuration</Button> : null}
                  <Button type="submit" disabled={busy || connectionTestBusy}>{busy ? "Saving…" : "Save AI settings"}</Button>
                </div>
              </form>
            </section>

            <section className="settings-section" aria-labelledby="settings-daily-report-title">
              <div className="settings-section-heading">
                <div className="settings-section-icon" aria-hidden="true"><Clock3 size={17} /></div>
                <div>
                  <h3 id="settings-daily-report-title">Daily AI report</h3>
                  <p>Optional and off by default. When enabled, East prepares one report for the previous day at your selected local time.</p>
                </div>
              </div>

              {dailyError && dailySettings ? <div className="settings-message settings-message--error" role="alert"><AlertCircle size={16} />{dailyError}</div> : null}
              {dailyNotice ? <div className="settings-message settings-message--success" role="status"><Check size={16} />{dailyNotice}</div> : null}
              {dailyNeedsDisclosure ? (
                <div className="settings-daily-renewal" role="alert">
                  <AlertTriangle size={16} aria-hidden="true" />
                  <span>Daily reports are paused because the saved provider or model changed. Review the current destination and confirm the disclosure again before re-enabling.</span>
                </div>
              ) : null}
              {dailyLoading ? (
                <div className="settings-loading settings-daily-loading" role="status"><LoaderCircle size={16} />Loading daily report settings…</div>
              ) : dailyError && !dailySettings ? (
                <div className="settings-daily-unavailable">
                  <span>{dailyError}</span>
                  <Button type="button" variant="outline" size="sm" onClick={() => void loadDailySettings()}>Retry</Button>
                </div>
              ) : dailySettings ? (
                <form className="settings-form settings-daily-form" onSubmit={handleDailyReportSubmit}>
                  <label className="settings-daily-toggle">
                    <input
                      type="checkbox"
                      checked={dailyEnabled}
                      onChange={(event) => {
                        setDailyEnabled(event.target.checked);
                        setDailyDisclosureConfirmed(false);
                        setDailyError(null);
                        setDailyNotice(null);
                      }}
                      disabled={busy || dailyBusy || dailyLoading || !dailyProviderReady}
                    />
                    <span><strong>Enable daily reports</strong><small>East will process eligible email text each day while this is enabled.</small></span>
                  </label>

                  {!dailyProviderReady ? <p className="settings-daily-unconfigured" role="status">Configure an AI provider, model, and API key above before enabling daily reports.</p> : null}

                  <div className="settings-daily-schedule-fields">
                    <label>
                      <span className="settings-label">Run time</span>
                      <input className="settings-control" type="time" value={dailyRunAt} onChange={(event) => setDailyRunAt(event.target.value)} disabled={busy || dailyBusy || dailyLoading} required />
                    </label>
                    <label>
                      <span className="settings-label">Time zone</span>
                      <input className="settings-control" type="text" value={dailyTimezoneId} onChange={(event) => setDailyTimezoneId(event.target.value)} placeholder="Asia/Manila" autoComplete="off" disabled={busy || dailyBusy || dailyLoading} required />
                    </label>
                  </div>
                  <p className="settings-field-note">If East is closed at the scheduled time, it creates at most one catch-up report for the most recent missed day after reopening.</p>

                  {dailyEnabled && dailyProviderReady ? (
                    <div className="settings-daily-disclosure">
                      <div className="settings-daily-disclosure-heading"><Mail size={15} aria-hidden="true" /><strong>Daily processing disclosure</strong></div>
                      <p>
                        Each day at {dailyRunAt} ({dailyTimezoneId}), East will send eligible message text from all connected mailbox accounts to {dailyProviderDestination} ({loaded?.ai.model}) to prepare the report. The schedule uses already-permitted messages and never authorizes extra Gmail reads. Attachment contents are excluded. {loaded?.ai.provider === "openai-compatible" ? "The endpoint operator controls its own processing and retention terms." : "Review OpenAI's current privacy terms."}
                        {loaded?.ai.provider === "openai"
                          ? <> <a href="https://openai.com/policies/privacy-policy/" target="_blank" rel="noreferrer">OpenAI privacy policy<ExternalLink size={11} aria-hidden="true" /></a></>
                          : dailyEndpoint
                            ? <> <a href={dailyEndpoint} target="_blank" rel="noreferrer">Open configured endpoint<ExternalLink size={11} aria-hidden="true" /></a></>
                            : null}
                      </p>
                      <label className="settings-daily-confirmation">
                        <input type="checkbox" checked={dailyDisclosureConfirmed} onChange={(event) => setDailyDisclosureConfirmed(event.target.checked)} disabled={busy || dailyBusy} />
                        <span>I understand eligible email text will be processed by {dailyProviderName} each day at {dailyRunAt} ({dailyTimezoneId}) while daily reports are enabled.</span>
                      </label>
                    </div>
                  ) : null}

                  <div className="settings-form-actions">
                    <Button type="submit" disabled={busy || dailyBusy || dailyLoading || (dailyEnabled && (!dailyProviderReady || !dailyDisclosureConfirmed))}>
                      {dailyBusy ? "Saving…" : dailyEnabled ? "Enable daily reports" : "Save daily report settings"}
                    </Button>
                  </div>
                </form>
              ) : null}
            </section>

            <section className="settings-section" aria-labelledby="settings-retention-title">
              <div className="settings-section-heading">
                <div className="settings-section-icon" aria-hidden="true"><ShieldCheck size={17} /></div>
                <div>
                  <h3 id="settings-retention-title">Encrypted cache retention</h3>
                  <p>Message bodies, snippets, insights, and downloaded attachments expire together.</p>
                </div>
              </div>

              <form className="settings-form settings-retention-form" onSubmit={handleRetentionSubmit}>
                <div className="settings-retention-input-row">
                  <label className="settings-label" htmlFor="settings-retention-days">Keep cached content for</label>
                  <div className="settings-retention-value">
                    <input id="settings-retention-days" className="settings-control" type="number" min="1" max="365" step="1" value={retentionDays} onChange={(event) => setRetentionDays(event.target.value)} disabled={busy || connectionTestBusy} required />
                    <span>days</span>
                  </div>
                </div>
                <p className="settings-field-note settings-retention-note">The default is 7 days. Expired content is purged; basic message metadata remains for inbox navigation.</p>
                <div className="settings-form-actions">
                  <Button type="submit" disabled={busy || connectionTestBusy}>{busy ? "Saving…" : "Save retention"}</Button>
                </div>
              </form>
            </section>

            <section className="settings-section settings-danger-zone" aria-labelledby="settings-reset-title">
              <div className="settings-section-heading">
                <div className="settings-section-icon settings-section-icon--danger" aria-hidden="true"><RotateCcw size={17} /></div>
                <div>
                  <h3 id="settings-reset-title">Erase local East data</h3>
                  <p>Remove local mailbox links, saved credentials, cached messages, drafts, AI settings, and other East data. Gmail itself is not changed.</p>
                </div>
              </div>

              {resetOpen ? (
                <form className="settings-reset-form" onSubmit={handleLocalResetSubmit}>
                  <label className="settings-label" htmlFor="settings-reset-confirmation">Type RESET LOCAL DATA to continue</label>
                  <input
                    id="settings-reset-confirmation"
                    className="settings-control"
                    type="text"
                    value={resetConfirmation}
                    onChange={(event) => setResetConfirmation(event.target.value)}
                    autoComplete="off"
                    spellCheck={false}
                    disabled={busy || connectionTestBusy}
                    required
                  />
                  <div className="settings-form-actions">
                    <Button variant="outline" type="button" disabled={busy || connectionTestBusy} onClick={() => { setResetOpen(false); setResetConfirmation(""); setError(null); }}>Cancel</Button>
                    <Button type="submit" disabled={busy || resetConfirmation !== "RESET LOCAL DATA"}>{busy ? "Erasing…" : "Erase local data"}</Button>
                  </div>
                </form>
              ) : (
                <div className="settings-form-actions">
                  <Button className="settings-reset-open-button" variant="outline" type="button" disabled={busy || connectionTestBusy} onClick={() => setResetOpen(true)}>Erase local data</Button>
                </div>
              )}
            </section>
          </div>
        ) : null}

        <footer className="settings-dialog-footer">
          <span><ShieldCheck size={14} aria-hidden="true" />Encrypted at rest on this computer</span>
          <Button className="settings-dialog-done-button" variant="outline" onClick={onClose}>Done</Button>
        </footer>
      </div>
    </dialog>
  );
}
