import {
  ArrowRight,
  ArrowUpRight,
  CalendarClock,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  Clock3,
  FileText,
  MailCheck,
  ReceiptText,
  Sparkles,
} from "lucide-react";
import { demoAccounts, dashboardSummary } from "../data/demo-data";
import "./dashboard.css";

const accountById = new Map(demoAccounts.map((account) => [account.id, account] as const));

const actionItems = [
  {
    title: "Review the launch brief",
    subject: "Northstar launch — final review",
    accountId: "work",
    due: "Today · 3:00 PM",
    kind: "Likely reply needed",
    confidence: "High confidence",
    evidence: "Mira asked for a review before the 3 pm team check-in.",
    icon: Clock3,
    tone: "urgent",
  },
  {
    title: "Appointment reminder",
    subject: "Juniper Dental · Thursday, Nov 12",
    accountId: "personal",
    due: "Thursday · 2:30 PM",
    kind: "Important notice",
    confidence: "High confidence",
    evidence: "The reminder says to reply only if you need to make a change.",
    icon: CalendarClock,
    tone: "notice",
  },
  {
    title: "Domain renewal coming up",
    subject: "Paperplane domain renewal",
    accountId: "work",
    due: "Nov 18 · $24.00 USD",
    kind: "Likely follow-up",
    confidence: "Medium confidence",
    evidence: "The note asks whether anything should change before renewal.",
    icon: FileText,
    tone: "follow-up",
  },
];

const responseItems = [
  {
    sender: "Avery Brooks",
    subject: "Updated budget estimate",
    preview: "The revised numbers match what we discussed on Tuesday.",
    date: "Yesterday · 3:05 PM",
    accountId: "work",
  },
];

const transactionItems = [
  {
    type: "Receipt",
    merchant: "Wildfern Supply",
    description: "Order WF-2048 · ceramic planters",
    amount: "$48.00",
    currency: "USD",
    date: "Today",
    accountId: "studio",
  },
  {
    type: "Invoice",
    merchant: "Paperplane domain",
    description: "Annual renewal · due Nov 18",
    amount: "$24.00",
    currency: "USD",
    date: "Nov 18",
    accountId: "work",
  },
];

function AccountMark({ accountId }: { accountId: string }) {
  const account = accountById.get(accountId);
  if (!account) return null;

  return (
    <span className={`dashboard-account dashboard-account--${account.color}`}>
      <span className="dashboard-account-dot" aria-hidden="true" />
      <span>{account.name}</span>
    </span>
  );
}

export default function Dashboard() {
  return (
    <section className="dashboard-page" aria-labelledby="dashboard-title">
      <div className="dashboard-heading">
        <div>
          <div className="dashboard-eyebrow">
            <span className="dashboard-demo-pip" aria-hidden="true" />
            Sample workspace <span className="dashboard-eyebrow-divider">/</span> Sunday, November 8
          </div>
          <h1 id="dashboard-title">Your inbox, in focus.</h1>
          <p className="dashboard-subtitle">A calm overview of what may need your attention.</p>
        </div>
        <div className="dashboard-view-switch" aria-label="Dashboard view options">
          <button className="dashboard-view-option is-active" type="button" aria-current="true">
            Combined
          </button>
          <button
            className="dashboard-view-option"
            type="button"
            aria-disabled="true"
            title="Visual prototype only"
          >
            By account
          </button>
        </div>
      </div>

      <section className="dashboard-summary-card" aria-labelledby="summary-heading">
        <div className="dashboard-summary-glow" aria-hidden="true" />
        <div className="dashboard-summary-topline">
          <div className="dashboard-summary-label">
            <span className="dashboard-summary-icon"><Sparkles size={16} strokeWidth={1.8} /></span>
            <span>Weekly overview</span>
            <span className="dashboard-summary-range">Last 7 days</span>
          </div>
          <span className="dashboard-summary-sample">Fictional demo insight</span>
        </div>
        <div className="dashboard-summary-content">
          <div className="dashboard-summary-copy">
            <h2 id="summary-heading">A little more clarity, at a glance.</h2>
            <p>{dashboardSummary}</p>
          </div>
          <div className="dashboard-summary-stats" aria-label="Sample account overview">
            <div className="dashboard-summary-stat">
              <span className="dashboard-stat-value">08</span>
              <span className="dashboard-stat-label">Conversations</span>
            </div>
            <span className="dashboard-summary-stat-divider" aria-hidden="true" />
            <div className="dashboard-summary-stat">
              <span className="dashboard-stat-value">03</span>
              <span className="dashboard-stat-label">Sample accounts</span>
            </div>
            <span className="dashboard-summary-stat-divider" aria-hidden="true" />
            <div className="dashboard-summary-stat">
              <span className="dashboard-stat-value">01</span>
              <span className="dashboard-stat-label">Replies to review</span>
            </div>
          </div>
        </div>
        <div className="dashboard-summary-footer">
          <div className="dashboard-account-stack" aria-label="Included sample accounts">
            {demoAccounts.map((account) => (
              <span
                key={account.id}
                className={`dashboard-account-avatar dashboard-account-avatar--${account.color}`}
                title={`${account.name} · ${account.email}`}
                aria-label={account.name}
              >
                {account.monogram}
              </span>
            ))}
            <span className="dashboard-account-stack-label">Across all sample accounts</span>
          </div>
          <span className="dashboard-summary-updated"><CheckCircle2 size={14} /> Example data · not synced</span>
        </div>
      </section>

      <div className="dashboard-section-heading">
        <div>
          <div className="dashboard-section-kicker">A gentle nudge</div>
          <h2>Needs your attention <span className="dashboard-count">03</span></h2>
        </div>
        <span className="dashboard-section-caption">Ordered by deadline and urgency</span>
      </div>

      <section className="dashboard-insights-grid" aria-label="Urgent actions and insights">
        <div className="dashboard-card dashboard-actions-card">
          <div className="dashboard-card-heading">
            <div className="dashboard-card-heading-left">
              <span className="dashboard-card-icon dashboard-card-icon--warm"><CircleAlert size={17} /></span>
              <div>
                <h3>Suggested actions</h3>
                <p>Possible follow-ups from your email</p>
              </div>
            </div>
            <button className="dashboard-icon-link" type="button" aria-disabled="true" title="Visual prototype only" aria-label="More suggested actions">
              <ArrowUpRight size={17} />
            </button>
          </div>
          <div className="dashboard-action-list">
            {actionItems.map((item, index) => {
              const Icon = item.icon;
              return (
                <article className={`dashboard-action-row dashboard-action-row--${item.tone}`} key={item.title}>
                  <div className="dashboard-action-symbol"><Icon size={17} strokeWidth={1.8} /></div>
                  <div className="dashboard-action-body">
                    <div className="dashboard-action-topline">
                      <span className="dashboard-action-kind">{item.kind}</span>
                      <span className="dashboard-action-confidence">{item.confidence}</span>
                    </div>
                    <h4>{item.title}</h4>
                    <p className="dashboard-action-subject">{item.subject}</p>
                    <p className="dashboard-action-evidence">“{item.evidence}”</p>
                    <div className="dashboard-action-footer">
                      <AccountMark accountId={item.accountId} />
                      <span className="dashboard-action-due">{item.due}</span>
                    </div>
                  </div>
                  {index === 0 && <span className="dashboard-action-priority">Today</span>}
                </article>
              );
            })}
          </div>
          <div className="dashboard-card-footnote">
            <span className="dashboard-footnote-dot" aria-hidden="true" />
            Suggestions are examples. Review the original message before acting.
          </div>
        </div>

        <div className="dashboard-right-column">
          <section className="dashboard-card dashboard-replies-card" aria-labelledby="replies-title">
            <div className="dashboard-card-heading">
              <div className="dashboard-card-heading-left">
                <span className="dashboard-card-icon dashboard-card-icon--cool"><MailCheck size={17} /></span>
                <div>
                  <h3 id="replies-title">Replies to your inquiries</h3>
                  <p>New responses in ongoing threads</p>
                </div>
              </div>
              <span className="dashboard-new-indicator">1 new</span>
            </div>
            <div className="dashboard-reply-list">
              {responseItems.map((reply) => (
                <article className="dashboard-reply-item" key={reply.subject}>
                  <div className="dashboard-reply-avatar">{reply.sender.split(" ").map((part) => part[0]).join("")}</div>
                  <div className="dashboard-reply-main">
                    <div className="dashboard-reply-fromline"><strong>{reply.sender}</strong><time>{reply.date}</time></div>
                    <h4>Re: {reply.subject}</h4>
                    <p>{reply.preview}</p>
                    <div className="dashboard-reply-footer">
                      <AccountMark accountId={reply.accountId} />
                      <span className="dashboard-evidence-label"><CheckCircle2 size={13} /> Earlier inquiry matched</span>
                    </div>
                  </div>
                </article>
              ))}
            </div>
            <button className="dashboard-text-link" type="button" aria-disabled="true" title="Visual prototype only">
              View conversation <ArrowRight size={15} />
            </button>
          </section>

          <section className="dashboard-card dashboard-transactions-card" aria-labelledby="transactions-title">
            <div className="dashboard-card-heading">
              <div className="dashboard-card-heading-left">
                <span className="dashboard-card-icon dashboard-card-icon--green"><ReceiptText size={17} /></span>
                <div>
                  <h3 id="transactions-title">Receipts & invoices</h3>
                  <p>Items found in sample email</p>
                </div>
              </div>
              <button className="dashboard-icon-link" type="button" aria-disabled="true" title="Visual prototype only" aria-label="More transactions">
                <ChevronRight size={17} />
              </button>
            </div>
            <div className="dashboard-transaction-list">
              {transactionItems.map((item) => (
                <article className="dashboard-transaction-item" key={`${item.merchant}-${item.type}`}>
                  <div className="dashboard-transaction-mark"><ReceiptText size={15} /></div>
                  <div className="dashboard-transaction-main">
                    <div className="dashboard-transaction-titleline">
                      <span className="dashboard-transaction-merchant">{item.merchant}</span>
                      <span className="dashboard-transaction-amount">{item.amount} <small>{item.currency}</small></span>
                    </div>
                    <div className="dashboard-transaction-description">{item.description}</div>
                    <div className="dashboard-transaction-meta">
                      <span className="dashboard-transaction-type">{item.type}</span>
                      <span>{item.date}</span>
                      <AccountMark accountId={item.accountId} />
                    </div>
                  </div>
                </article>
              ))}
            </div>
            <div className="dashboard-transaction-note">
              <span>No refunds in this sample period</span>
              <button type="button" className="dashboard-text-link" aria-disabled="true" title="Visual prototype only">
                See receipts <ArrowRight size={14} />
              </button>
            </div>
          </section>
        </div>
      </section>

      <div className="dashboard-bottom-note">
        <span className="dashboard-bottom-note-icon"><Sparkles size={14} /></span>
        <span>East only suggests what might matter. Every mailbox action stays in your hands.</span>
        <span className="dashboard-bottom-note-divider" aria-hidden="true" />
        <span>No mailbox sync has run</span>
      </div>
    </section>
  );
}
