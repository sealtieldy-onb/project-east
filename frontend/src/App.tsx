import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import {
  AlertCircle,
  AlertTriangle,
  CircleHelp,
  Inbox,
  LayoutDashboard,
  LogOut,
  Mail,
  Plus,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import Conversations from "./components/Conversations";
import ConnectedDashboard from "./components/ConnectedDashboard";
import Dashboard from "./components/Dashboard";
import { LoginPage } from "./components/LoginPage";
import SettingsDialog from "./components/SettingsDialog";
import DemoSettingsDialog from "./components/DemoSettingsDialog";
import { VaultGate, type VaultGateMode } from "./components/VaultGate";
import { Button } from "./components/ui/button";
import { demoAccounts } from "./data/demo-data";
import {
  AuthApiError,
  connectGoogleMailbox,
  disconnectGoogleMailbox,
  getCurrentEastUser,
  getGoogleOAuthConfiguration,
  logoutGoogleUser,
  syncGoogleMailbox,
  startGoogleSignIn,
  type ConnectedMailboxSummary,
  type EastUser,
} from "./lib/auth-api";
import {
  getVaultStatus,
  resetVault,
  unlockVault,
  VaultRequestError,
  type VaultStatus,
} from "./lib/vault-api";
import { mailboxColorAt } from "./lib/mailbox-colors";
import type { ReportSourceReference } from "./lib/reports-api";

type Page = "dashboard" | "conversations";

function mailboxColor(index: number): string {
  return mailboxColorAt(index) ?? "#88c0d0";
}

function mailboxSyncFailureNotice(failureCode: string | null): string {
  if (!failureCode || failureCode === "sync_failed") {
    return "East could not sync this mailbox. Check the mailbox connection and local setup, then try again.";
  }

  const localMatch = /^sync_exception_([A-Za-z0-9_]{1,160})$/.exec(failureCode);
  if (localMatch) {
    return `East hit a local sync error (${localMatch[1]}). Retry once; if it persists, share this code.`;
  }

  const authMatch = /^auth_http_(\d{3})$/.exec(failureCode);
  if (authMatch) {
    if (authMatch[1] === "401") return "Google authorization needs attention. Reconnect this mailbox from the Dashboard.";
    if (authMatch[1] === "403") return "This mailbox has not granted East the Gmail permission it needs. Reconnect it and approve the requested permissions.";
    return "Google could not refresh access to this mailbox. Check your connection or reconnect the account.";
  }

  const gmailMatch = /^gmail_http_(\d{3})_([A-Za-z0-9_.-]{1,64})$/.exec(failureCode);
  if (!gmailMatch) return "East could not sync this mailbox. Try again from the Dashboard.";
  const [, status, googleCode] = gmailMatch;
  const normalizedCode = googleCode.toLowerCase();
  if (status === "401") return "Google authorization expired. Reconnect this mailbox from the Dashboard.";
  if (normalizedCode.includes("quota") || normalizedCode.includes("ratelimit") || status === "429") {
    return "Google temporarily rate-limited this mailbox. Wait a little and try syncing again.";
  }
  if (status === "403" && (normalizedCode.includes("accessnotconfigured") || normalizedCode.includes("servicedisabled"))) {
    return `Gmail API is not enabled for this Google Cloud project. Enable it, then retry (Google: ${googleCode}).`;
  }
  if (status === "403" && (normalizedCode.includes("permission") || normalizedCode.includes("forbidden"))) {
    return `Google denied this Gmail API request. Check the account and project permissions, then retry (Google: ${googleCode}).`;
  }
  if (Number(status) >= 500) return `Google is temporarily unable to sync this mailbox. Try again later (HTTP ${status}).`;
  return `Google rejected the Gmail sync request (HTTP ${status}, ${googleCode}). Check the Gmail API and account permissions.`;
}

function EastMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`east-brand ${compact ? "east-brand-compact" : ""}`}>
      <span className="east-mark" aria-hidden="true">
        <Mail size={18} strokeWidth={2.2} />
      </span>
      {!compact && (
        <span className="east-brand-copy">
          <strong>East</strong>
          <span>your inbox, in focus</span>
        </span>
      )}
    </div>
  );
}

function AppSidebar({
  page,
  mailboxes,
  currentUser,
  onNavigate,
  onOpenSettings,
  onConnectMailbox,
  onSignOut,
}: {
  page: Page;
  mailboxes: ConnectedMailboxSummary[] | null;
  currentUser: EastUser | null;
  onNavigate: (page: Page) => void;
  onOpenSettings: () => void;
  onConnectMailbox: () => void;
  onSignOut: () => void;
}) {
  const isDemo = mailboxes === null;
  const visibleMailboxes = isDemo ? demoAccounts.map((account, index) => ({
    id: account.id,
    title: account.name,
    email: account.email,
    color: mailboxColor(index),
  })) : mailboxes.map((mailbox) => ({
    id: mailbox.id,
    title: mailbox.displayName || mailbox.email,
    email: mailbox.email,
    color: mailboxColor(mailbox.colorIndex),
  }));
  const displayName = currentUser?.displayName || currentUser?.email || "Demo user";
  const monogram = (currentUser?.displayName || currentUser?.email || "E").trim().charAt(0).toLocaleUpperCase() || "E";

  return (
    <aside className="app-sidebar">
      <div className="sidebar-top">
        <EastMark />
        <div className="workspace-pill">
          <span className="workspace-pill-dot" />
          <span>{isDemo ? "Demo workspace" : "Local workspace"}</span>
        </div>
      </div>

      <div className="sidebar-section-label">WORKSPACE</div>
      <nav className="primary-nav" aria-label="Main navigation">
        <Button
          type="button"
          variant="ghost"
          className={`primary-nav-link ${page === "dashboard" ? "active" : ""}`}
          aria-current={page === "dashboard" ? "page" : undefined}
          onClick={() => onNavigate("dashboard")}
        >
          <LayoutDashboard size={17} />
          <span>Dashboard</span>
        </Button>
        <Button
          type="button"
          variant="ghost"
          className={`primary-nav-link ${page === "conversations" ? "active" : ""}`}
          aria-current={page === "conversations" ? "page" : undefined}
          onClick={() => onNavigate("conversations")}
        >
          <Inbox size={17} />
          <span>Conversations</span>
          {isDemo ? <span className="nav-count">8</span> : null}
        </Button>
      </nav>

      <div className="sidebar-divider" />
      <div className="sidebar-heading-row">
        <div className="sidebar-section-label">{isDemo ? "SAMPLE ACCOUNTS" : "CONNECTED ACCOUNTS"}</div>
        {isDemo ? <span className="account-total">{visibleMailboxes.length}</span> : (
          <button type="button" className="account-add-button" onClick={onConnectMailbox} aria-label="Connect another Gmail account" title="Connect another Gmail account"><Plus size={15} /></button>
        )}
      </div>
      <div className="account-list" aria-label={isDemo ? "Fictional sample accounts" : "Connected Gmail accounts"}>
        {visibleMailboxes.length === 0 ? <div className="account-empty-note">No mailboxes linked</div> : null}
        {visibleMailboxes.map((account) => (
          <div className="account-list-item" key={account.id}>
            <span className={`account-dot${isDemo ? ` account-${demoAccounts.find((sample) => sample.id === account.id)?.color ?? ""}` : ""}`} style={{ "--account-color": account.color } as CSSProperties} />
            <span className="account-list-copy">
              <strong>{account.title}</strong>
              <span>{account.email}</span>
            </span>
          </div>
        ))}
      </div>

      <div className="sidebar-grow" />
      <div className="sidebar-note">
        {isDemo ? <Sparkles size={15} /> : <ShieldCheck size={15} />}
        <span>{isDemo ? "Everything here is sample data." : "Mailbox access stays in this local session."}</span>
      </div>
      <div className="sidebar-footer">
        <div className="local-status">
          <ShieldCheck size={15} />
          <span>{isDemo ? "Local demo" : "Encrypted local session"}</span>
        </div>
        <div className="sidebar-footer-actions">
          <button type="button" className="sidebar-icon-control" onClick={onOpenSettings}>
            <Settings2 size={16} />
            <span>Settings</span>
          </button>
          <button type="button" className="sidebar-icon-control help-control" title="Help" aria-disabled="true" tabIndex={-1}>
            <CircleHelp size={16} />
            <span>Help</span>
          </button>
        </div>
        <div className="sidebar-user">
          <div className="user-monogram">{monogram}</div>
          <div className="sidebar-user-copy">
            <strong>{displayName}</strong>
            <span>{isDemo ? "Fictional sample workspace" : "Signed in with Google"}</span>
          </div>
          {!isDemo ? <button type="button" className="sidebar-signout" onClick={onSignOut} aria-label="Sign out" title="Sign out"><LogOut size={15} /></button> : <span className="user-caret" aria-hidden="true">•••</span>}
        </div>
      </div>
    </aside>
  );
}

function AppHeader({ page, isDemo, displayName, onConnectMailbox, onOpenSettings, onSignOut }: {
  page: Page;
  isDemo: boolean;
  displayName: string;
  onConnectMailbox: () => void;
  onOpenSettings: () => void;
  onSignOut: () => void;
}) {
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const userMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!userMenuOpen) return;
    const closeWhenOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !userMenuRef.current?.contains(event.target)) {
        setUserMenuOpen(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setUserMenuOpen(false);
      userMenuRef.current?.querySelector<HTMLButtonElement>(".header-avatar")?.focus();
    };
    document.addEventListener("pointerdown", closeWhenOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeWhenOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [userMenuOpen]);

  return (
    <header className="app-header">
      <div className="app-header-title">
        <div className="header-eyebrow">EAST / WORKSPACE</div>
        <div className="header-page-title">{page === "dashboard" ? "Dashboard" : "Conversations"}</div>
      </div>
      <div className="app-header-actions">
        <button type="button" className="global-search" aria-disabled="true" tabIndex={-1}>
          <Search size={16} />
          <span>Search your mail</span>
          <kbd>⌘ K</kbd>
        </button>
        {isDemo ? <span className="header-demo-badge"><span /> Sample workspace</span> : <Button variant="outline" size="sm" className="header-add-mailbox" onClick={onConnectMailbox}><Plus size={14} />Add mailbox</Button>}
        <div className="header-user-menu" ref={userMenuRef} onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setUserMenuOpen(false);
        }}>
          <button
            type="button"
            className="header-avatar"
            aria-label={`${displayName} account actions`}
            aria-expanded={userMenuOpen}
            aria-controls="header-user-menu-panel"
            onClick={() => setUserMenuOpen((open) => !open)}
          >
            {displayName.trim().charAt(0).toLocaleUpperCase() || "E"}
          </button>
          <div className="header-user-menu-panel" id="header-user-menu-panel" role="group" aria-label="Account actions" hidden={!userMenuOpen}>
            <strong className="header-user-menu-name">{displayName}</strong>
            <button type="button" className="header-user-menu-action" onClick={() => { setUserMenuOpen(false); onOpenSettings(); }}>
              <Settings2 size={15} aria-hidden="true" />
              <span>Settings</span>
            </button>
            {!isDemo ? (
              <button type="button" className="header-user-menu-action" onClick={() => { setUserMenuOpen(false); onSignOut(); }}>
                <LogOut size={15} aria-hidden="true" />
                <span>Log out</span>
              </button>
            ) : null}
          </div>
        </div>
      </div>
    </header>
  );
}

export default function App() {
  const [demoSignedIn, setDemoSignedIn] = useState(false);
  const [googleOAuthConfigured, setGoogleOAuthConfigured] = useState(false);
  const [googleUser, setGoogleUser] = useState<EastUser | null>(null);
  const [authNotice, setAuthNotice] = useState<string | null>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [page, setPage] = useState<Page>("dashboard");
  const [conversationSourceNavigation, setConversationSourceNavigation] = useState<ReportSourceReference | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [vaultMode, setVaultMode] = useState<"checking" | "unavailable" | VaultGateMode | "unlocked">("checking");
  const [vaultBusy, setVaultBusy] = useState(false);
  const [vaultError, setVaultError] = useState<string | null>(null);
  const [vaultNotice, setVaultNotice] = useState<string | null>(null);
  const [syncNotice, setSyncNotice] = useState<string | null>(null);
  const [syncingMailboxIds, setSyncingMailboxIds] = useState<Set<string>>(() => new Set());
  const [mailboxesMissingRequiredPermission, setMailboxesMissingRequiredPermission] = useState<Set<string>>(() => new Set());
  const [disconnectingMailboxIds, setDisconnectingMailboxIds] = useState<Set<string>>(() => new Set());
  const lastMailboxStatusRefreshAt = useRef(0);
  const googleUserRef = useRef<EastUser | null>(googleUser);
  const signingOut = useRef(false);
  const activeMailboxSyncs = useRef(new Set<string>());
  const attemptedInitialSyncs = useRef(new Set<string>());
  googleUserRef.current = googleUser;

  const clearConversationSourceNavigation = useCallback(() => setConversationSourceNavigation(null), []);

  useEffect(() => {
    const handleSourceNavigation = (event: Event) => {
      const detail = (event as CustomEvent<unknown>).detail;
      if (!detail || typeof detail !== "object") return;
      const source = detail as Record<string, unknown>;
      const mailboxId = typeof source.mailboxId === "string" ? source.mailboxId.trim() : "";
      const threadId = typeof source.threadId === "string" ? source.threadId.trim() : "";
      const messageId = typeof source.messageId === "string" ? source.messageId.trim() : "";
      if (!mailboxId || !threadId || !messageId) return;
      setConversationSourceNavigation({
        mailboxId,
        threadId,
        messageId,
        mailboxEmail: null,
        mailboxName: null,
      });
      setPage("conversations");
    };
    window.addEventListener("east:openConversationSource", handleSourceNavigation);
    return () => window.removeEventListener("east:openConversationSource", handleSourceNavigation);
  }, []);

  const refreshGoogleSession = useCallback(async () => {
    const user = await getCurrentEastUser();
    if (signingOut.current) return null;
    if (user) {
      setGoogleUser(user);
      const ownedMailboxIds = new Set(user.mailboxes.map((mailbox) => mailbox.id));
      setMailboxesMissingRequiredPermission((current) => {
        const next = new Set([...current].filter((mailboxId) => ownedMailboxIds.has(mailboxId)));
        return next.size === current.size ? current : next;
      });
      return user;
    }
    setMailboxesMissingRequiredPermission(new Set());
    if (googleUserRef.current) {
      googleUserRef.current = null;
      setGoogleUser(null);
      setDemoSignedIn(false);
      setConversationSourceNavigation(null);
      setSettingsOpen(false);
      setPage("dashboard");
      setSyncNotice(null);
      setAuthNotice("Your East sign-in expired. Sign in with Google again to continue.");
      attemptedInitialSyncs.current.clear();
    }
    return null;
  }, []);

  const refreshVault = useCallback(async (): Promise<VaultStatus | null> => {
    try {
      const status = await getVaultStatus();
      setVaultError(null);
      setVaultNotice(null);
      const nextMode = status.initialized ? status.unlocked ? "unlocked" : "migration" : "unavailable";
      setVaultMode(nextMode);
      if (!status.initialized) {
        setVaultNotice("East could not initialize its local encryption key. Check the local key-file permissions and restart East.");
      }
      if (nextMode !== "unlocked") {
        setDemoSignedIn(false);
        setGoogleUser(null);
        setMailboxesMissingRequiredPermission(new Set());
        setSettingsOpen(false);
        setPage("dashboard");
        setSyncNotice(null);
        attemptedInitialSyncs.current.clear();
      }
      return status;
    } catch (error) {
      setVaultMode("unavailable");
      setGoogleUser(null);
      setMailboxesMissingRequiredPermission(new Set());
      setVaultNotice(error instanceof VaultRequestError ? error.message : "The local vault service is unavailable. You can continue with fictional demo data.");
      return null;
    }
  }, []);

  useEffect(() => {
    void refreshVault();
  }, [refreshVault]);

  useEffect(() => {
    if (vaultMode !== "unlocked") {
      setGoogleOAuthConfigured(false);
      setGoogleUser(null);
      return;
    }
    if (demoSignedIn) {
      setGoogleUser(null);
      return;
    }

    let active = true;
    setAuthNotice(null);
    void Promise.allSettled([getGoogleOAuthConfiguration(), getCurrentEastUser()]).then(([configuration, user]) => {
      if (!active) return;
      if (configuration.status === "fulfilled") {
        setGoogleOAuthConfigured(configuration.value);
      } else {
        setGoogleOAuthConfigured(false);
        setAuthNotice(configuration.reason instanceof AuthApiError ? configuration.reason.message : "Google sign-in is currently unavailable.");
      }
      if (user.status === "fulfilled") {
        setGoogleUser(user.value);
      } else {
        setGoogleUser(null);
        setAuthNotice((current) => current ?? (user.reason instanceof AuthApiError ? user.reason.message : "Your Google session could not be checked."));
      }
    });
    return () => { active = false; };
  }, [vaultMode, demoSignedIn]);

  const handleUnlock = useCallback(async (passphrase: string) => {
    setVaultBusy(true);
    setVaultError(null);
    try {
      await unlockVault(passphrase);
      const status = await refreshVault();
      if (status && !(status.initialized && status.unlocked)) {
        setVaultError("East could not finish the one-time key migration. Try again.");
      }
    } catch (error) {
      if (error instanceof VaultRequestError && error.kind === "unavailable") {
        setVaultMode("unavailable");
        setVaultNotice(error.message);
      } else {
        setVaultError(error instanceof VaultRequestError ? error.message : "The local key migration failed. Check the current passphrase and try again.");
      }
    } finally {
      setVaultBusy(false);
    }
  }, [refreshVault]);

  const handleReset = useCallback(async (confirmation: string): Promise<boolean> => {
    setVaultBusy(true);
    setVaultError(null);
    try {
      await resetVault(confirmation);
      const status = await refreshVault();
      setGoogleUser(null);
      setDemoSignedIn(false);
      setConversationSourceNavigation(null);
      setSettingsOpen(false);
      setPage("dashboard");
      if (!status?.initialized || !status.unlocked) {
        setVaultError("The local reset completed, but East could not reopen its empty workspace.");
        return false;
      }
      return true;
    } catch (error) {
      if (error instanceof VaultRequestError && error.kind === "unavailable") {
        setVaultMode("unavailable");
        setVaultNotice(error.message);
      } else {
        setVaultError(error instanceof VaultRequestError ? error.message : "The local data could not be reset. Try again.");
      }
      return false;
    } finally {
      setVaultBusy(false);
    }
  }, [refreshVault]);

  const handleConnectMailbox = useCallback(async () => {
    if (!googleUser || authBusy) return;
    setAuthBusy(true);
    setAuthNotice(null);
    try {
      await connectGoogleMailbox();
    } catch (error) {
      setAuthNotice(error instanceof AuthApiError ? error.message : "The mailbox connection could not start. Try again.");
      setAuthBusy(false);
    }
  }, [googleUser, authBusy]);

  const handleSignOut = useCallback(async () => {
    if (authBusy) return;
    signingOut.current = true;
    setAuthBusy(true);
    setAuthNotice(null);
    try {
      await logoutGoogleUser();
      googleUserRef.current = null;
      setGoogleUser(null);
      setDemoSignedIn(false);
      setConversationSourceNavigation(null);
      setSettingsOpen(false);
      setPage("dashboard");
      setSyncNotice(null);
      setMailboxesMissingRequiredPermission(new Set());
      attemptedInitialSyncs.current.clear();
    } catch (error) {
      setAuthNotice(error instanceof AuthApiError ? error.message : "Sign-out could not be completed. Try again.");
    } finally {
      signingOut.current = false;
      setAuthBusy(false);
    }
  }, [authBusy]);

  const runMailboxSync = useCallback(async (mailboxId: string) => {
    if (activeMailboxSyncs.current.has(mailboxId)) return;
    activeMailboxSyncs.current.add(mailboxId);
    setSyncingMailboxIds((current) => new Set(current).add(mailboxId));
    setSyncNotice("Syncing the latest messages for this mailbox…");
    try {
      const result = await syncGoogleMailbox(mailboxId);
      const status = result.status;
      if (status === "SYNCED") {
        setMailboxesMissingRequiredPermission((current) => {
          if (!current.has(mailboxId)) return current;
          const next = new Set(current);
          next.delete(mailboxId);
          return next;
        });
        setSyncNotice("Mailbox sync is up to date.");
      } else if (status === "IN_PROGRESS") {
        setSyncNotice("A sync is already running for this mailbox.");
      } else if (status === "LOCKED") {
        setSyncNotice("Unlock East before syncing this mailbox.");
      } else if (status === "NOT_FOUND") {
        setSyncNotice("This mailbox is no longer available. Reconnect it to sync again.");
      } else {
        if (result.failureCode === "auth_http_403") {
          setMailboxesMissingRequiredPermission((current) => new Set(current).add(mailboxId));
        }
        setSyncNotice(mailboxSyncFailureNotice(result.failureCode));
      }
    } catch (error) {
      setSyncNotice(error instanceof AuthApiError ? error.message : "East could not sync this mailbox. Try again.");
    } finally {
      try {
        await refreshGoogleSession();
      } catch {
        // Keep the most recent mailbox status if the local service is temporarily unavailable.
      }
      activeMailboxSyncs.current.delete(mailboxId);
      setSyncingMailboxIds((current) => {
        const next = new Set(current);
        next.delete(mailboxId);
        return next;
      });
    }
  }, [refreshGoogleSession]);

  const runMailboxDisconnect = useCallback(async (mailboxId: string) => {
    setDisconnectingMailboxIds((current) => new Set(current).add(mailboxId));
    setSyncNotice("Removing this mailbox and its local cache…");
    try {
      await disconnectGoogleMailbox(mailboxId);
      setGoogleUser((current) => current ? {
        ...current,
        mailboxes: current.mailboxes.filter((mailbox) => mailbox.id !== mailboxId),
      } : current);
      setPage("dashboard");
      setSyncNotice("Mailbox disconnected from East. Its Gmail account and messages were not changed.");
    } catch (error) {
      setSyncNotice(error instanceof AuthApiError ? error.message : "East could not remove this mailbox. Try again.");
    } finally {
      setDisconnectingMailboxIds((current) => {
        const next = new Set(current);
        next.delete(mailboxId);
        return next;
      });
    }
    try {
      await refreshGoogleSession();
    } catch {
      // The local disconnect succeeded; keep the immediately updated mailbox list if refresh is unavailable.
    }
  }, [refreshGoogleSession]);

  useEffect(() => {
    if (vaultMode !== "unlocked" || demoSignedIn || !googleUser) return;
    const accountsToInitialize = googleUser.mailboxes.filter(
      (mailbox) => mailbox.syncStatus === "pending" && !attemptedInitialSyncs.current.has(mailbox.id),
    );
    for (const mailbox of accountsToInitialize) {
      attemptedInitialSyncs.current.add(mailbox.id);
      void runMailboxSync(mailbox.id);
    }
  }, [vaultMode, demoSignedIn, googleUser, runMailboxSync]);

  const handleDemoSignIn = useCallback(() => {
    setGoogleUser(null);
    setDemoSignedIn(true);
    setAuthNotice(null);
  }, []);

  useEffect(() => {
    if (vaultMode !== "unlocked") return;

    const refreshMailboxStatusIfDue = () => {
      if (vaultMode !== "unlocked" || demoSignedIn || !googleUserRef.current) return;
      const now = Date.now();
      if (now - lastMailboxStatusRefreshAt.current >= 30_000) {
        lastMailboxStatusRefreshAt.current = now;
        void refreshGoogleSession().catch(() => {
          // Keep the last known account status until the local service is reachable again.
        });
      }
    };
    const recheckWhenVisible = () => {
      if (document.visibilityState !== "visible") return;
      void refreshVault();
      refreshMailboxStatusIfDue();
    };
    const pollVisibleSession = () => {
      if (document.visibilityState === "visible") refreshMailboxStatusIfDue();
    };
    const intervalId = window.setInterval(pollVisibleSession, 30_000);
    window.addEventListener("focus", recheckWhenVisible);
    document.addEventListener("visibilitychange", recheckWhenVisible);

    return () => {
      window.removeEventListener("focus", recheckWhenVisible);
      document.removeEventListener("visibilitychange", recheckWhenVisible);
      window.clearInterval(intervalId);
    };
  }, [vaultMode, demoSignedIn, refreshVault, refreshGoogleSession]);

  if (vaultMode === "checking") {
    return <main className="vault-checking-page"><div className="vault-checking-card"><span className="vault-checking-spinner" aria-hidden="true" />Checking local vault…</div></main>;
  }

  if (vaultMode === "migration") {
    return (
      <VaultGate
        busy={vaultBusy}
        error={vaultError}
        onClearError={() => setVaultError(null)}
        onUnlock={handleUnlock}
        onReset={handleReset}
      />
    );
  }

  if (!demoSignedIn && !googleUser) {
    return (
      <>
        <LoginPage
          onDemoSignIn={handleDemoSignIn}
          googleOAuthConfigured={googleOAuthConfigured}
          onGoogleSignIn={startGoogleSignIn}
        />
        <div className="vault-login-notice" role="status">
          <AlertCircle size={16} />
          <span className="vault-login-notice-copy">
            {authNotice ?? (vaultMode === "unavailable"
              ? vaultNotice ?? "Local vault unavailable. Continue with fictional demo data."
              : googleOAuthConfigured
                ? "Use Google sign-in for East, then connect one or more Gmail accounts. Their credentials and cached content are encrypted in East's local storage."
                : "East's encrypted local storage is ready. Sign in with Google when configured, or explore the fictional demo." )}
          </span>
          {vaultMode === "unavailable" ? <button type="button" onClick={() => { void refreshVault(); }}>Retry</button> : null}
        </div>
      </>
    );
  }

  const isDemoSession = demoSignedIn || !googleUser;
  const liveMailboxes = googleUser?.mailboxes ?? null;
  const displayName = googleUser?.displayName || googleUser?.email || "Demo user";
  const sessionMessage = googleUser
    ? googleUser.mailboxes.length > 0
      ? `Signed in as ${googleUser.email}. ${googleUser.mailboxes.length} Gmail ${googleUser.mailboxes.length === 1 ? "account is" : "accounts are"} connected.`
      : `Signed in as ${googleUser.email}. Connect a mailbox to load mail in Conversations.`
    : vaultMode === "unavailable"
      ? "Local vault unavailable. This workspace is showing fictional demo data only."
      : googleOAuthConfigured
        ? "Fictional demo data is shown here. Sign in with Google to load a connected mailbox."
        : "Fictional demo data is shown here. Configure Google OAuth to connect a real mailbox.";
  const mailboxesNeedingReauthorization = googleUser?.mailboxes.filter(
    (mailbox) => mailbox.reauthorizationRequired
      || mailbox.syncStatus === "needs_reauthorization"
      || mailboxesMissingRequiredPermission.has(mailbox.id),
  ) ?? [];
  const mailboxesMissingPermission = googleUser?.mailboxes.filter(
    (mailbox) => mailboxesMissingRequiredPermission.has(mailbox.id),
  ) ?? [];

  return (
    <div className="app-shell" data-page={page}>
      <AppSidebar
        page={page}
        mailboxes={liveMailboxes}
        currentUser={googleUser}
        onNavigate={setPage}
        onOpenSettings={() => setSettingsOpen(true)}
        onConnectMailbox={() => { void handleConnectMailbox(); }}
        onSignOut={() => { void handleSignOut(); }}
      />
      <div className="app-main-column">
        <AppHeader
          page={page}
          isDemo={isDemoSession}
          displayName={displayName}
          onConnectMailbox={() => { void handleConnectMailbox(); }}
          onOpenSettings={() => setSettingsOpen(true)}
          onSignOut={() => { void handleSignOut(); }}
        />
        <div className="vault-session-notice" role="status">
          <AlertCircle size={15} />
          <span className="vault-session-notice-copy">{authNotice ?? syncNotice ?? sessionMessage}</span>
          {vaultMode === "unavailable" ? <button type="button" onClick={() => { void refreshVault(); }}>Retry</button> : null}
        </div>
        {mailboxesNeedingReauthorization.length > 0 ? (
          <div className="mailbox-reauthorization-notice" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>
              {mailboxesMissingPermission.length > 0
                ? `East needs Gmail permission for ${mailboxesMissingPermission.map((mailbox) => mailbox.email).join(", ")}. Reconnect each affected account and approve the requested permissions in Google.`
                : `Google access needs reconnection for ${mailboxesNeedingReauthorization.map((mailbox) => mailbox.email).join(", ")}. Choose the affected account when Google asks.`}
            </span>
            <Button variant="outline" size="sm" disabled={authBusy} onClick={() => { void handleConnectMailbox(); }}>
              Reconnect account
            </Button>
          </div>
        ) : null}
        <main className="app-content" id="main-content">
          {page === "dashboard"
            ? googleUser
              ? <ConnectedDashboard
                  displayName={googleUser.displayName}
                  mailboxes={googleUser.mailboxes}
                  mailboxesMissingRequiredPermission={mailboxesMissingRequiredPermission}
                  syncingMailboxIds={syncingMailboxIds}
                  disconnectingMailboxIds={disconnectingMailboxIds}
                  onSyncMailbox={(mailboxId) => { void runMailboxSync(mailboxId); }}
                  onDisconnectMailbox={(mailboxId) => { void runMailboxDisconnect(mailboxId); }}
                  onConnectMailbox={() => { void handleConnectMailbox(); }}
                  onOpenSettings={() => setSettingsOpen(true)}
                />
              : <Dashboard />
            : <Conversations
                realMailboxMode={!!googleUser}
                sourceNavigation={conversationSourceNavigation}
                onSourceNavigationHandled={clearConversationSourceNavigation}
              />}
        </main>
      </div>
      {settingsOpen ? googleUser
        ? <SettingsDialog onClose={() => setSettingsOpen(false)} onResetLocalData={handleReset} />
        : <DemoSettingsDialog
            onClose={() => setSettingsOpen(false)}
            onReturnToSignIn={() => {
              setSettingsOpen(false);
              setDemoSignedIn(false);
            }}
          />
        : null}
    </div>
  );
}
