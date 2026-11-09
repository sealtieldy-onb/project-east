import { AlertTriangle, LoaderCircle, Mail, Plus, RefreshCw, Settings2, ShieldCheck, Sparkles } from "lucide-react";
import type { CSSProperties } from "react";
import { Button } from "./ui/button";
import type { ConnectedMailboxSummary } from "../lib/auth-api";
import { mailboxColorAt } from "../lib/mailbox-colors";
import "./connected-dashboard.css";

type ConnectedDashboardProps = {
  displayName: string | null;
  mailboxes: ConnectedMailboxSummary[];
  syncingMailboxIds: Set<string>;
  disconnectingMailboxIds: Set<string>;
  onSyncMailbox: (mailboxId: string) => void;
  onDisconnectMailbox: (mailboxId: string) => void;
  onConnectMailbox: () => void;
  onOpenSettings: () => void;
};

export default function ConnectedDashboard({ displayName, mailboxes, syncingMailboxIds, disconnectingMailboxIds, onSyncMailbox, onDisconnectMailbox, onConnectMailbox, onOpenSettings }: ConnectedDashboardProps) {
  const firstName = displayName?.trim().split(/\s+/)[0] || "there";

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
        <div className="connected-dashboard-report-icon"><Sparkles size={18} /></div>
        <div className="connected-dashboard-report-copy">
          <span className="connected-dashboard-kicker">AI REPORTS</span>
          <h2 id="connected-report-title">Reports are available in Phase 3</h2>
          <p>East is not analyzing or sending email content to an AI provider in this phase. You can save provider settings locally for later.</p>
        </div>
        <Button variant="outline" onClick={onOpenSettings}><Settings2 size={15} />AI settings</Button>
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
            {mailboxes.map((mailbox) => (
              <article className="connected-dashboard-mailbox" key={mailbox.id}>
                <span className="connected-dashboard-account-dot" style={{ "--account-color": mailboxColorAt(mailbox.colorIndex) ?? "#88c0d0" } as CSSProperties} aria-hidden="true" />
                <div className="connected-dashboard-mailbox-copy"><strong>{mailbox.displayName || mailbox.email}</strong><span>{mailbox.email}</span></div>
                {mailbox.reauthorizationRequired ? (
                  <span className="connected-dashboard-reauth-state" role="status">
                    <AlertTriangle size={14} />Google access needs reconnection
                  </span>
                ) : (
                  <span className="connected-dashboard-sync-state">
                    <ShieldCheck size={14} />
                    {mailbox.lastSyncAt ? "Synced" : "Awaiting first sync"}
                  </span>
                )}
                {mailbox.reauthorizationRequired ? (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={syncingMailboxIds.has(mailbox.id) || disconnectingMailboxIds.has(mailbox.id)}
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
                    disabled={syncingMailboxIds.has(mailbox.id) || disconnectingMailboxIds.has(mailbox.id)}
                    onClick={() => onSyncMailbox(mailbox.id)}
                    aria-label={`${syncingMailboxIds.has(mailbox.id) ? "Syncing" : "Sync"} ${mailbox.email}`}
                  >
                    {syncingMailboxIds.has(mailbox.id) ? <LoaderCircle size={14} className="connected-dashboard-sync-spinner" /> : <RefreshCw size={14} />}
                    {syncingMailboxIds.has(mailbox.id) ? "Syncing" : mailbox.lastSyncAt ? "Refresh" : "Sync now"}
                  </Button>
                )}
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={syncingMailboxIds.has(mailbox.id) || disconnectingMailboxIds.has(mailbox.id)}
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
            ))}
          </div>
        )}
      </section>

      <p className="connected-dashboard-privacy"><ShieldCheck size={14} />Cached content and credentials are encrypted locally; the key file is restricted to this computer account.</p>
    </section>
  );
}
