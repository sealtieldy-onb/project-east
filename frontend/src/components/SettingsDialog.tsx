import { useEffect, useRef, useState, type FormEvent, type MouseEvent, type SyntheticEvent } from "react";
import { AlertCircle, Check, KeyRound, LoaderCircle, RotateCcw, Settings2, ShieldCheck, X } from "lucide-react";
import { Button } from "./ui/button";
import {
  deleteAIProviderSettings,
  getAIProviderSettings,
  getRetentionSettings,
  patchRetentionSettings,
  putAIProviderSettings,
  SettingsApiError,
  type AIProviderSettings,
  type AIProviderType,
  type RetentionSettings,
} from "../lib/settings-api";
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
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetConfirmation, setResetConfirmation] = useState("");

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    void loadSettings();
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

  function handleCancel(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault();
    onClose();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDialogElement>) {
    if (event.target === dialogRef.current) onClose();
  }

  async function handleProviderSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setNotice(null);

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
      setLoaded((current) => current ? { ...current, ai: updated } : current);
      setProvider(updated.provider ?? provider);
      setBaseUrl(updated.baseUrl ?? "");
      setModel(updated.model ?? trimmedModel);
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
    setBusy(true);
    try {
      await deleteAIProviderSettings();
      const updated = await getAIProviderSettings();
      setLoaded((current) => current ? { ...current, ai: updated } : current);
      setProvider("openai");
      setBaseUrl("");
      setModel("");
      setApiKey("");
      setConfirmRemove(false);
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
          <div className="settings-dialog-content" aria-busy={busy}>
            <section className="settings-section" aria-labelledby="settings-ai-title">
              <div className="settings-section-heading">
                <div className="settings-section-icon" aria-hidden="true"><KeyRound size={17} /></div>
                <div>
                  <h3 id="settings-ai-title">AI provider</h3>
                  <p>Provider settings are saved encrypted in the local vault. Phase 2 does not send email content to an AI provider; reports and AI-assisted drafts are planned for Phase 3.</p>
                </div>
              </div>

              <form className="settings-form" onSubmit={handleProviderSubmit}>
                <label className="settings-label" htmlFor="settings-provider">Provider</label>
                <select id="settings-provider" className="settings-control" value={provider} onChange={(event) => setProvider(event.target.value as AIProviderType)} disabled={busy}>
                  <option value="openai">OpenAI</option>
                  <option value="openai-compatible">Custom OpenAI-compatible endpoint</option>
                </select>

                {provider === "openai-compatible" ? (
                  <>
                    <label className="settings-label" htmlFor="settings-base-url">Endpoint URL</label>
                    <input id="settings-base-url" className="settings-control" type="url" inputMode="url" autoComplete="url" placeholder="https://api.example.com/v1" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} disabled={busy} required />
                  </>
                ) : null}

                <label className="settings-label" htmlFor="settings-model">Model</label>
                <input id="settings-model" className="settings-control" type="text" autoComplete="off" placeholder={provider === "openai" ? "e.g. gpt-4.1-mini" : "Provider model identifier"} value={model} onChange={(event) => setModel(event.target.value)} disabled={busy} required />

                <div className="settings-key-status" role="status">
                  <ShieldCheck size={16} aria-hidden="true" />
                  <span>{keyConfigured ? "An API key is saved in the local vault." : "No API key is configured."}</span>
                </div>

                <label className="settings-label" htmlFor="settings-api-key">{keyConfigured ? "Replace API key (optional)" : "API key"}</label>
                <input id="settings-api-key" className="settings-control" type="password" autoComplete="new-password" value={apiKey} onChange={(event) => setApiKey(event.target.value)} disabled={busy} required={!keyConfigured} aria-describedby="settings-key-note" />
                <p className="settings-field-note" id="settings-key-note">The key is sent only to East on this computer, saved encrypted in the local vault, and never shown again. Leave blank to keep the saved key.</p>

                {confirmRemove ? (
                  <div className="settings-confirm-remove" role="group" aria-label="Confirm removing AI provider">
                    <p>Remove the saved provider configuration and key from the local vault?</p>
                    <div>
                      <Button variant="outline" size="sm" disabled={busy} onClick={() => setConfirmRemove(false)}>Keep configuration</Button>
                      <Button size="sm" disabled={busy} onClick={() => void handleRemoveProvider()}>{busy ? "Removing…" : "Remove provider"}</Button>
                    </div>
                  </div>
                ) : null}

                <div className="settings-form-actions">
                  {keyConfigured && !confirmRemove ? <Button variant="outline" type="button" disabled={busy} onClick={() => setConfirmRemove(true)}>Remove configuration</Button> : null}
                  <Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save AI settings"}</Button>
                </div>
              </form>
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
                    <input id="settings-retention-days" className="settings-control" type="number" min="1" max="365" step="1" value={retentionDays} onChange={(event) => setRetentionDays(event.target.value)} disabled={busy} required />
                    <span>days</span>
                  </div>
                </div>
                <p className="settings-field-note">The default is 7 days. Expired content is purged; basic message metadata remains for inbox navigation.</p>
                <div className="settings-form-actions">
                  <Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save retention"}</Button>
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
                    disabled={busy}
                    required
                  />
                  <div className="settings-form-actions">
                    <Button variant="outline" type="button" disabled={busy} onClick={() => { setResetOpen(false); setResetConfirmation(""); setError(null); }}>Cancel</Button>
                    <Button type="submit" disabled={busy || resetConfirmation !== "RESET LOCAL DATA"}>{busy ? "Erasing…" : "Erase local data"}</Button>
                  </div>
                </form>
              ) : (
                <div className="settings-form-actions">
                  <Button variant="outline" type="button" disabled={busy} onClick={() => setResetOpen(true)}>Erase local data</Button>
                </div>
              )}
            </section>
          </div>
        ) : null}

        <footer className="settings-dialog-footer">
          <span><ShieldCheck size={14} aria-hidden="true" />Encrypted at rest on this computer</span>
          <Button variant="outline" onClick={onClose}>Done</Button>
        </footer>
      </div>
    </dialog>
  );
}
