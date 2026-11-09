import {
  ArrowRight,
  Check,
  CircleCheck,
  LockKeyhole,
  Mail,
  Sparkles,
} from "lucide-react";
import "./login-page.css";

type LoginPageProps = {
  onDemoSignIn: () => void;
  googleOAuthConfigured?: boolean;
  onGoogleSignIn?: () => void;
};

const previewItems = [
  { color: "login-dot-frost", title: "Launch brief ready for review", meta: "Work · 10:42 AM" },
  { color: "login-dot-aurora", title: "A reply to your estimate", meta: "Work · Yesterday" },
  { color: "login-dot-ocean", title: "Receipt from Wildfern Supply", meta: "Studio · Yesterday" },
];

export function LoginPage({ onDemoSignIn, googleOAuthConfigured = false, onGoogleSignIn }: LoginPageProps) {
  return (
    <main className="login-page">
      <section className="login-story" aria-labelledby="login-heading">
        <header className="login-brand-row">
          <div className="login-brand-lockup" aria-label="East local email assistant">
            <span className="login-brand-mark" aria-hidden="true">
              <Mail size={19} strokeWidth={2.1} />
              <i />
            </span>
            <span className="login-brand-name">East</span>
          </div>
          <span className="login-local-pill"><span /> Runs locally</span>
        </header>

        <div className="login-story-copy">
          <div className="login-eyebrow"><Sparkles size={14} /> A little more clarity, every day</div>
          <h1 id="login-heading">Make room for<br />what matters.</h1>
          <p className="login-lede">
            East brings conversations, follow-ups, and the small details worth remembering into one thoughtful view.
          </p>

          <div className="login-value-list" aria-label="East product highlights">
            <div className="login-value-item">
              <span className="login-value-icon"><CircleCheck size={17} /></span>
              <span><strong>See the whole picture</strong><small>Keep your inboxes together and easy to scan.</small></span>
            </div>
            <div className="login-value-item">
              <span className="login-value-icon"><Sparkles size={17} /></span>
              <span><strong>Know what needs a look</strong><small>Bring likely follow-ups into view with context.</small></span>
            </div>
            <div className="login-value-item">
              <span className="login-value-icon"><LockKeyhole size={17} /></span>
              <span><strong>Stay in control</strong><small>Your assistant can suggest; you decide what happens.</small></span>
            </div>
          </div>
        </div>

        <footer className="login-story-footer">
          <span>Built for a calmer inbox.</span>
          <span className="login-footer-separator" aria-hidden="true">·</span>
          <span>Designed to run on your computer.</span>
        </footer>
      </section>

      <section className="login-entry" aria-label="Sign in to East">
        <div className="login-entry-glow login-entry-glow-one" aria-hidden="true" />
        <div className="login-entry-glow login-entry-glow-two" aria-hidden="true" />

        <div className="login-preview-card" aria-label="Preview of fictional demo insights">
          <div className="login-preview-header">
            <div>
              <span className="login-preview-kicker">SAMPLE OVERVIEW</span>
              <strong>A clearer kind of inbox</strong>
            </div>
            <span className="login-preview-sparkle"><Sparkles size={17} /></span>
          </div>
          <div className="login-preview-stats">
            <div><strong>3</strong><span>sample accounts</span></div>
            <div><strong>4</strong><span>items to review</span></div>
          </div>
          <div className="login-preview-divider" />
          <div className="login-preview-list">
            {previewItems.map((item) => (
              <div className="login-preview-item" key={item.title}>
                <span className={`login-preview-dot ${item.color}`} />
                <span className="login-preview-item-copy">
                  <strong>{item.title}</strong>
                  <small>{item.meta}</small>
                </span>
                <Check className="login-preview-check" size={14} />
              </div>
            ))}
          </div>
          <span className="login-preview-watermark">FICTIONAL DEMO CONTENT</span>
        </div>

        <div className="login-card">
          <div className="login-card-brand">
            <span className="login-brand-mark login-brand-mark-small" aria-hidden="true"><Mail size={16} strokeWidth={2.1} /><i /></span>
            <span>Welcome to East</span>
          </div>
          <h2>Your inbox, in focus.</h2>
          <p className="login-card-description">
            Sign in with Google to use East, then connect one or more Gmail accounts. You can also explore with fictional demo data.
          </p>

          <button className="login-demo-button" type="button" onClick={onDemoSignIn}>
            <span>Enter demo workspace</span>
            <ArrowRight size={17} aria-hidden="true" />
          </button>

          {googleOAuthConfigured && onGoogleSignIn ? (
            <>
              <div className="login-google-divider" aria-hidden="true"><span>OR SIGN IN WITH GOOGLE</span></div>
              <button className="login-google-button" type="button" onClick={onGoogleSignIn}>
                <svg className="login-google-mark" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.05 5.05 0 0 1-2.2 3.31v2.77h3.57c2.08-1.91 3.27-4.73 3.27-8.09Z" />
                  <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.56-2.77c-.98.66-2.23 1.06-3.72 1.06-2.87 0-5.3-1.94-6.17-4.55H2.15v2.84A11 11 0 0 0 12 23Z" />
                  <path fill="#FBBC05" d="M5.83 14.08a6.6 6.6 0 0 1 0-4.16V7.08H2.15a11 11 0 0 0 0 9.84l3.68-2.84Z" />
                  <path fill="#EA4335" d="M12 4.36c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 1.09 14.97 0 12 0a11 11 0 0 0-9.85 6.08l3.68 2.84C6.7 6.3 9.13 4.36 12 4.36Z" />
                </svg>
                <span>Continue with Google</span>
              </button>
            </>
          ) : null}

          <div className="login-demo-caption">
            <span className="login-demo-caption-mark"><Check size={12} /></span>
            The demo contains fictional messages only
          </div>

          <div className="login-card-divider"><span>ABOUT THIS DEMO</span></div>

          <div className="login-demo-note">
            <span className="login-demo-note-icon"><LockKeyhole size={16} /></span>
            <p><strong>Demo stays fictional.</strong> Google sign-in and Gmail access are separate steps; the demo never connects to a mailbox.</p>
          </div>
        </div>

        <div className="login-entry-footer">
          <span className="login-footer-check"><Check size={13} /></span>
          <span>{googleOAuthConfigured && onGoogleSignIn ? "Sign in with Google, then add Gmail accounts, or explore the demo." : "Google sign-in is not configured yet; the fictional demo is available."}</span>
        </div>
      </section>
    </main>
  );
}

export default LoginPage;
