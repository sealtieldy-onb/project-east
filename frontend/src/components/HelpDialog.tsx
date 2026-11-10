import { useEffect, useRef, type MouseEvent, type SyntheticEvent } from "react";
import { CircleHelp, ShieldCheck, Sparkles, X } from "lucide-react";
import { Button } from "./ui/button";
import "./help-dialog.css";

type HelpDialogProps = {
  onClose: () => void;
};

export default function HelpDialog({ onClose }: HelpDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  function closeDialog() {
    if (dialogRef.current?.open) dialogRef.current.close();
    onClose();
  }

  function handleCancel(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault();
    closeDialog();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDialogElement>) {
    if (event.target === dialogRef.current) closeDialog();
  }

  return (
    <dialog
      ref={dialogRef}
      className="help-dialog"
      aria-labelledby="help-dialog-title"
      aria-describedby="help-dialog-description"
      onCancel={handleCancel}
      onClick={handleBackdropClick}
    >
      <header className="help-dialog-header">
        <div className="help-dialog-heading">
          <span className="help-dialog-mark" aria-hidden="true"><CircleHelp size={19} /></span>
          <div>
            <p className="help-dialog-eyebrow">EAST HELP</p>
            <h2 id="help-dialog-title">Help and FAQ</h2>
          </div>
        </div>
        <Button variant="ghost" size="icon" aria-label="Close help" onClick={closeDialog}>
          <X size={18} />
        </Button>
      </header>

      <div className="help-dialog-scroll help-dialog-shell" role="region" aria-label="Help and FAQ content" tabIndex={0}>
        <p className="help-dialog-description" id="help-dialog-description">
          Quick answers about using East, AI reports, and Gmail actions.
        </p>

        <div className="help-faq-list">
          <section className="help-faq-item" aria-labelledby="help-using-title">
            <h3 id="help-using-title">How do I use East?</h3>
            <p>
              Choose Enter demo workspace on the sign-in screen to explore fictional sample accounts and messages without connecting Gmail. For connected use, sign in with Google and link one or more Gmail accounts. Use Conversations to browse and search mail, and the Dashboard to review reports and their source messages.
            </p>
            <p>
              Loading the Dashboard does not call an AI provider. A manual report uses email text only after you review its scope and provider disclosure and choose to generate it. Daily reports are optional; when enabled in Settings, East sends eligible email text already cached locally for the previous day to the configured provider on schedule.
            </p>
          </section>

          <section className="help-faq-item" aria-labelledby="help-models-title">
            <h3 id="help-models-title">Which AI models are supported?</h3>
            <p>
              Settings supports OpenAI and custom endpoints that implement OpenAI Chat Completions. Enter a model identifier offered by your provider; East does not maintain a fixed list of model versions. The model must accept East’s structured response format. The connection check sends a short prompt without mailbox content.
            </p>
          </section>

          <section className="help-faq-item" aria-labelledby="help-actions-title">
            <h3 id="help-actions-title">Which Gmail actions are supported?</h3>
            <p>
              In Conversations, you can search and read mail, mark messages read or unread, star them, create and manage user labels, archive or move messages to Inbox, trash or restore them, and mark them as spam or not spam. You can compose, reply, reply all, and forward. East supports these workflows, not every Gmail feature.
            </p>
            <p>
              Opening an unread conversation marks it as read. External images stay blocked until you choose to load them for that message.
            </p>
            <p>
              Drafts can stay in East or be saved to Gmail Drafts. Every Gmail change starts with your action. Saving a draft and sending it are separate steps; each send requires you to review and confirm that specific message.
            </p>
          </section>
        </div>

        <aside className="help-privacy-note" aria-label="Email privacy">
          <Sparkles size={16} aria-hidden="true" />
          <p>
            East keeps connected-account credentials and its local mail cache encrypted on this computer. When you request an AI report or AI-assisted draft, any relevant email text goes to the provider shown in Settings; custom endpoint privacy terms are set by that provider.
          </p>
        </aside>

        <footer className="help-dialog-footer">
          <span><ShieldCheck size={14} aria-hidden="true" /> Local workspace</span>
          <Button variant="outline" className="help-dialog-done-button" onClick={closeDialog}>Done</Button>
        </footer>
      </div>
    </dialog>
  );
}
