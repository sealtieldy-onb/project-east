import { useEffect, useRef, type MouseEvent, type SyntheticEvent } from "react";
import { LogIn, Settings2, ShieldCheck, X } from "lucide-react";
import { Button } from "./ui/button";
import "./settings-dialog.css";

type DemoSettingsDialogProps = {
  onClose: () => void;
  onReturnToSignIn: () => void;
};

export default function DemoSettingsDialog({ onClose, onReturnToSignIn }: DemoSettingsDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  function handleCancel(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault();
    onClose();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDialogElement>) {
    if (event.target === dialogRef.current) onClose();
  }

  return (
    <dialog
      ref={dialogRef}
      className="settings-dialog settings-demo-dialog"
      aria-labelledby="demo-settings-title"
      aria-describedby="demo-settings-description"
      onCancel={handleCancel}
      onClick={handleBackdropClick}
    >
      <header className="settings-dialog-header">
        <div className="settings-dialog-heading">
          <span className="settings-dialog-mark" aria-hidden="true"><Settings2 size={18} /></span>
          <div>
            <p className="settings-dialog-eyebrow">FICTIONAL DEMO</p>
            <h2 id="demo-settings-title">Settings</h2>
          </div>
        </div>
        <Button variant="ghost" size="icon" aria-label="Close settings" onClick={onClose}>
          <X size={18} />
        </Button>
      </header>

      <div className="settings-dialog-scroll settings-dialog-shell" role="region" aria-label="Demo settings content" tabIndex={0}>
        <p className="settings-dialog-description" id="demo-settings-description">
          This workspace uses fictional sample accounts and messages. Personal account, AI provider, report schedule, and cache settings are available after Google sign-in.
        </p>

        <div className="settings-demo-message" role="status">
          <ShieldCheck size={18} aria-hidden="true" />
          <p><strong>Sample data only.</strong> The demo has no connected Google account, so these settings are not loaded or saved here.</p>
        </div>

        <footer className="settings-dialog-footer">
          <span><ShieldCheck size={14} aria-hidden="true" />Fictional demo workspace</span>
          <div className="settings-demo-actions">
            <Button variant="outline" onClick={onClose}>Close</Button>
            <Button onClick={onReturnToSignIn}><LogIn size={15} />Go to sign in</Button>
          </div>
        </footer>
      </div>
    </dialog>
  );
}
