import { useQuery } from "@tanstack/react-query";
import { type ComponentType, lazy, Suspense } from "react";
import { Navigate, NavLink, Route, Routes, useLocation } from "react-router";
import { get } from "./api";
import { MobileNav, type NavItem } from "./components/MobileNav";
import { AppUpdate } from "./components/AppUpdate";
import { PageBoundary } from "./components/PageBoundary";
import { WorkspaceProvider, WorkspaceSwitcher } from "./workspace";

// Mobile phase 0 (17 Nov 2026): each page is its own chunk, so a phone downloads only the page it opens. A deploy
// renames the chunks, so a tab opened before it cannot load a page it has not opened yet: reload once (at most once a
// minute, so a real outage shows the error instead of looping) to get the new build.
const page = <K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) =>
  lazy(() => load().then((m) => ({ default: m[name] }), (error: unknown) => {
    let last = 0;
    try {
      last = Number(sessionStorage.getItem("autotrade.chunkReload") ?? 0);
      sessionStorage.setItem("autotrade.chunkReload", String(Date.now()));
    } catch {
      last = Date.now();   // no storage: never reload, show the error
    }
    // offline the chunk is simply not reachable: a reload would only blank the screen (the page boundary says so)
    if (navigator.onLine !== false && Date.now() - last > 60_000) {
      window.location.reload();
      return new Promise<never>(() => undefined);   // the page is reloading; render nothing meanwhile
    }
    throw error;
  }));
const AccountsPage = page(() => import("./pages/AccountsPage"), "AccountsPage");
const BrokersPage = page(() => import("./pages/BrokersPage"), "BrokersPage");
const ConfigPage = page(() => import("./pages/ConfigPage"), "ConfigPage");
const HouseholdPage = page(() => import("./pages/HouseholdPage"), "HouseholdPage");
const IndexPage = page(() => import("./pages/IndexPage"), "IndexPage");
const LivePage = page(() => import("./pages/LivePage"), "LivePage");
const PerformancePage = page(() => import("./pages/PerformancePage"), "PerformancePage");
const ReplayPage = page(() => import("./pages/ReplayPage"), "ReplayPage");
const ResearchPage = page(() => import("./pages/ResearchPage"), "ResearchPage");
const RunDetailPage = page(() => import("./pages/ResearchPage"), "RunDetailPage");
const SessionDetailPage = page(() => import("./pages/SessionsPage"), "SessionDetailPage");
const SessionsPage = page(() => import("./pages/SessionsPage"), "SessionsPage");
const SettingsPage = page(() => import("./pages/SettingsPage"), "SettingsPage");
const TodayPage = page(() => import("./pages/TodayPage"), "TodayPage");
// mobile phase 1: the phone's trading screens (also reachable on the desktop by their address)
const HomeScreen = page(() => import("./mobile/HomeScreen"), "HomeScreen");
const PositionsScreen = page(() => import("./mobile/PositionsScreen"), "PositionsScreen");
const LedgerScreen = page(() => import("./mobile/LedgerScreen"), "LedgerScreen");
const BrokerWizard = page(() => import("./mobile/BrokerWizard"), "BrokerWizard");
const StrategiesScreen = page(() => import("./mobile/StrategiesScreen"), "StrategiesScreen");
const NotificationsScreen = page(() => import("./mobile/NotificationsScreen"), "NotificationsScreen");

/** Below the md breakpoint "/" opens the phone's Home instead of the desktop's first page. */
const phone = () => typeof window !== "undefined" && window.matchMedia?.("(max-width: 767px)").matches;

const NAV: readonly NavItem[] = [
  ["/live", "Overview"],
  ["/index/NIFTY", "NIFTY"],
  ["/index/SENSEX", "SENSEX"],
  ["/index/BANKNIFTY", "BANKNIFTY"],   // recorded for context, never traded (A-108)
  // ["/strategies", "Strategies"],   hidden 2026-10-09 (user: "Hide the Strategies page for now"); StrategiesPage.tsx kept
  ["/sessions", "Sessions"],
  ["/performance", "Performance"],
  ["/research", "Research"],
  ["/replay", "Replay"],
  ["/accounts", "Accounts"],          // live execution: accounts, kill switches, flatten (feature/live-execution)
  ["/household", "Family"],           // trader workspaces (7 Nov 2026): every member's day at a glance, members
  ["/config", "Config"],
];

/**
 * A family member or viewer (signed in without the operator role) sees the index charts with the trades and nothing
 * more (user, 10 Nov 2026: "Other than Owner, all other accounts ... should only see the chart and trades, nothing
 * more"; the Overview they had from 5 Nov is the owner's again), and their own workspace to run their trading (trader
 * workspaces, 7 Nov 2026: Today's broker and PAPER/LIVE switch, their brokers and logins, settings, their accounts).
 */
const MEMBER_NAV: readonly NavItem[] = [
  ["/index/NIFTY", "NIFTY"],          // the index charts, read-only: the chart and the trades only (10 Nov 2026)
  ["/index/SENSEX", "SENSEX"],
  ["/index/BANKNIFTY", "BANKNIFTY"],
  ["/today", "Today"],
  ["/brokers", "Brokers"],
  ["/accounts", "My accounts"],
  ["/settings", "Settings"],
];

/** The phone's bottom tabs (mobile phase 0); every other menu item is under More. "/index" covers the three charts. */
// Phase 1: Home, Positions, Chart, Ledger for everyone; the rest of each menu (Overview, Accounts, Today, Brokers...) under More.
const TABS: readonly NavItem[] = [["/home", "Home"], ["/positions", "Positions"], ["/index/NIFTY", "Chart"], ["/ledger", "Ledger"]];
const MEMBER_TABS = TABS;
/** In the phone's More sheet only (phase 3): the desktop keeps these switches on Accounts. */
const PHONE_MORE: readonly NavItem[] = [["/strategies", "Strategies"], ["/notifications", "Notifications"]];

export function App() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => get<Me>("/api/me"), staleTime: 60_000 });
  const location = useLocation();
  // offline, React Query pauses the call (it never fails): show the shell then, and let each page say it is offline
  if (me.isPending && me.fetchStatus !== "paused") return null;
  // fails closed: if /api/me cannot be read, the member's menu (the server refuses the rest anyway)
  const member = !me.data || (me.data.mode === "oidc" && !me.data.operator);
  const nav = member ? MEMBER_NAV : NAV;
  return (
    <WorkspaceProvider>
    <div className="min-h-full">
      <header className="sticky top-0 z-10 border-b border-line bg-bg/90 pt-[env(safe-area-inset-top)] backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-3 py-2.5 pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))] sm:gap-6">
          <div className="flex shrink-0 items-center gap-2">
            <span className="font-semibold">auto-trade</span>
            <span className="rounded bg-warn/15 px-1.5 py-0.5 text-[10px] font-bold tracking-wider text-warn">PAPER</span>
          </div>
          {/* below md the menu is the bottom tab bar and its More sheet (MobileNav) */}
          <nav className="hidden min-w-0 gap-1 overflow-x-auto md:flex">
            {nav.map(([to, label]) => (
              <NavLink key={to} to={to}
                className={({ isActive }) => `rounded px-3 py-1.5 text-sm ${isActive ? "bg-panel-2 font-semibold" : "text-muted hover:text-text"}`}>
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="hidden items-center gap-3 md:ml-auto md:flex">
            <WorkspaceSwitcher />
            <SignedIn />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-7xl pb-[calc(5rem+env(safe-area-inset-bottom))] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))] pt-4 md:pb-4">
        <PageBoundary key={location.pathname}>
        <Suspense fallback={<p className="text-sm text-muted">Loading…</p>}>
        {member ? (
          <Routes>
            <Route path="/" element={<Navigate to={phone() ? "/home" : "/index/NIFTY"} replace />} />
            <Route path="/home" element={<HomeScreen />} />
            <Route path="/positions" element={<PositionsScreen />} />
            <Route path="/ledger" element={<LedgerScreen />} />
            <Route path="/strategies" element={<StrategiesScreen />} />
            <Route path="/notifications" element={<NotificationsScreen />} />
            <Route path="/live" element={<Navigate to="/index/NIFTY" replace />} />
            <Route path="/index/:underlying" element={<IndexPage />} />
            <Route path="/today" element={<TodayPage />} />
            <Route path="/brokers" element={<BrokersPage />} />
            <Route path="/brokers/add" element={<BrokerWizard />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<AccountsPage />} />
          </Routes>
        ) : (
        <Routes>
          <Route path="/" element={phone() ? <Navigate to="/home" replace /> : <LivePage />} />
          <Route path="/home" element={<HomeScreen />} />
          <Route path="/positions" element={<PositionsScreen />} />
          <Route path="/ledger" element={<LedgerScreen />} />
          <Route path="/strategies" element={<StrategiesScreen />} />
          <Route path="/notifications" element={<NotificationsScreen />} />
          <Route path="/live" element={<LivePage />} />
          <Route path="/index/:underlying" element={<IndexPage />} />
          <Route path="/sessions" element={<SessionsPage />} />
          <Route path="/sessions/:id" element={<SessionDetailPage />} />
          <Route path="/performance" element={<PerformancePage />} />
          <Route path="/research" element={<ResearchPage />} />
          <Route path="/research/:id" element={<RunDetailPage />} />
          <Route path="/replay" element={<ReplayPage />} />
          <Route path="/config" element={<ConfigPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/household" element={<HouseholdPage />} />
          <Route path="/today" element={<TodayPage />} />
          <Route path="/brokers" element={<BrokersPage />} />
            <Route path="/brokers/add" element={<BrokerWizard />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
        )}
        </Suspense>
        </PageBoundary>
      </main>
      <AppUpdate />
      <MobileNav tabs={member ? MEMBER_TABS : TABS} nav={nav} more={PHONE_MORE} extra={<><WorkspaceSwitcher /><SignedIn /></>} />
    </div>
    </WorkspaceProvider>
  );
}

interface Me {
  mode: "none" | "oidc";
  user: string;
  operator: boolean;
  logout: boolean;
}

/** Who is signed in (Keycloak, autotrade.auth.mode=oidc) and a sign-out; nothing with mode none. */
function SignedIn() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => get<Me>("/api/me"), staleTime: 60_000 });
  if (!me.data || me.data.mode !== "oidc") return null;
  return (
    <form method="post" action="/logout" className="flex shrink-0 items-center gap-2 text-sm">
      <span className="text-muted" title={me.data.operator ? "operator: may use kill, stop and exit-all" : "no operator role"}>
        {me.data.user}
        {me.data.operator && <span className="ml-1 text-[10px] font-bold tracking-wider text-warn">OP</span>}
      </span>
      <button type="submit" className="rounded px-2 py-1 text-muted hover:bg-panel-2 hover:text-text">Sign out</button>
    </form>
  );
}
