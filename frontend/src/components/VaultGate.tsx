import { useState, type FormEvent } from "react";
import { AlertCircle, ArrowRight, KeyRound, LockKeyhole, RotateCcw } from "lucide-react";
import { Button } from "./ui/button";
import "./vault-gate.css";

export type VaultGateMode = "migration";

type VaultGateProps = {
  busy: boolean;
  error: string | null;
  onClearError: () => void;
  onUnlock: (passphrase: string) => Promise<void>;
  onReset: (confirmation: string) => Promise<boolean>;
};

export function VaultGate({ busy, error, onClearError, onUnlock, onReset }: VaultGateProps) {
  const [passphrase, setPassphrase] = useState("");
  const [resetOpen, setResetOpen] = useState(false);
  const [resetConfirmation, setResetConfirmation] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError(null);
    const submittedPassphrase = passphrase;
    setPassphrase("");
    await onUnlock(submittedPassphrase);
  }

  async function handleReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError(null);
    if (resetConfirmation !== "RESET LOCAL DATA") {
      setFormError("Type RESET LOCAL DATA exactly to confirm the destructive reset.");
      return;
    }
    setResetConfirmation("");
    await onReset("RESET LOCAL DATA");
  }

  return (
    <main className="vault-gate-page">
      <div className="vault-gate-orbit vault-gate-orbit--one" aria-hidden="true" />
      <div className="vault-gate-orbit vault-gate-orbit--two" aria-hidden="true" />

      <div className="vault-gate-card">
        <header className="vault-gate-brand">
          <span className="vault-gate-brand-mark" aria-hidden="true"><LockKeyhole size={18} /></span>
          <span><strong>East</strong><small>Local email assistant</small></span>
        </header>

        <div className="vault-gate-icon" aria-hidden="true"><KeyRound size={22} /></div>
        <div className="vault-gate-copy">
          <div className="vault-gate-eyebrow">ONE-TIME LOCAL MIGRATION</div>
          <h1>Keep your existing East data</h1>
          <p>Enter your current app passphrase once. East will move the encryption key to an owner-only file on this computer and open automatically on future starts.</p>
        </div>

        {resetOpen ? (
          <form className="vault-reset-form" onSubmit={handleReset}>
            <div className="vault-reset-warning">
              <AlertCircle size={17} />
              <p><strong>This permanently resets local data.</strong> Mailbox links, saved credentials, cached content, and app data are removed. Your Gmail contents are not changed.</p>
            </div>
            <label className="vault-field-label" htmlFor="vault-reset-confirmation">Type RESET LOCAL DATA to continue</label>
            <input
              id="vault-reset-confirmation"
              className="vault-input"
              type="text"
              value={resetConfirmation}
              onChange={(event) => setResetConfirmation(event.target.value)}
              autoComplete="off"
              spellCheck={false}
              autoFocus
              required
            />
            {formError ? <div className="vault-form-error" role="alert"><AlertCircle size={15} />{formError}</div> : null}
            {error ? <div className="vault-form-error" role="alert"><AlertCircle size={15} />{error}</div> : null}
            <div className="vault-reset-actions">
              <Button variant="outline" type="button" disabled={busy} onClick={() => { setResetOpen(false); setResetConfirmation(""); setFormError(null); }}>Cancel</Button>
              <Button type="submit" disabled={busy || resetConfirmation !== "RESET LOCAL DATA"}>
                {busy ? "Resetting…" : "Reset local data"}
              </Button>
            </div>
          </form>
        ) : (
          <form className="vault-form" onSubmit={handleSubmit}>
            <label className="vault-field-label" htmlFor="vault-passphrase">Current app passphrase</label>
            <input
              id="vault-passphrase"
              className="vault-input"
              type="password"
              value={passphrase}
              onChange={(event) => setPassphrase(event.target.value)}
              autoComplete="current-password"
              maxLength={1024}
              autoFocus
              required
              aria-describedby="vault-passphrase-note"
            />
            <p className="vault-passphrase-note" id="vault-passphrase-note">
              <LockKeyhole size={13} />The passphrase is used only to migrate your existing key. East never stores it.
            </p>
            {formError ? <div className="vault-form-error" role="alert"><AlertCircle size={15} />{formError}</div> : null}
            {error ? <div className="vault-form-error" role="alert"><AlertCircle size={15} />{error}</div> : null}
            <Button className="vault-submit-button" type="submit" disabled={busy || passphrase.length === 0}>
              <span>{busy ? "Migrating local key…" : "Migrate and open East"}</span>
              {busy ? null : <ArrowRight size={17} />}
            </Button>
            <div className="vault-gate-footer-note">
              <span className="vault-footer-lock"><LockKeyhole size={13} /></span>
              <span>After migration, East starts without a passphrase prompt.</span>
            </div>
            <button className="vault-reset-link" type="button" onClick={() => { setResetOpen(true); setFormError(null); onClearError(); }}>
              <RotateCcw size={14} />Forgot passphrase? Reset local data
            </button>
          </form>
        )}
      </div>
      <footer className="vault-gate-page-footer"><span />Only accessible from this computer</footer>
    </main>
  );
}
