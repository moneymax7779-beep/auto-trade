import { useQuery } from "@tanstack/react-query";
import { Navigate, NavLink, Route, Routes } from "react-router";
import { get } from "./api";
import { AccountsPage } from "./pages/AccountsPage";
import { BrokersPage } from "./pages/BrokersPage";
import { ConfigPage } from "./pages/ConfigPage";
import { HouseholdPage } from "./pages/HouseholdPage";
import { IndexPage } from "./pages/IndexPage";
import { LivePage } from "./pages/LivePage";
import { PerformancePage } from "./pages/PerformancePage";
import { ReplayPage } from "./pages/ReplayPage";
import { ResearchPage, RunDetailPage } from "./pages/ResearchPage";
import { SessionDetailPage, SessionsPage } from "./pages/SessionsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { TodayPage } from "./pages/TodayPage";
import { WorkspaceProvider, WorkspaceSwitcher } from "./workspace";

const NAV = [
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
] as const;

/**
 * A family member or viewer (signed in without the operator role) sees the index charts with the trades and nothing
 * more (user, 10 Nov 2026: "Other than Owner, all other accounts ... should only see the chart and trades, nothing
 * more"; the Overview they had from 5 Nov is the owner's again), and their own workspace to run their trading (trader
 * workspaces, 7 Nov 2026: Today's broker and PAPER/LIVE switch, their brokers and logins, settings, their accounts).
 */
const MEMBER_NAV = [
  ["/index/NIFTY", "NIFTY"],          // the index charts, read-only: the chart and the trades only (10 Nov 2026)
  ["/index/SENSEX", "SENSEX"],
  ["/index/BANKNIFTY", "BANKNIFTY"],
  ["/today", "Today"],
  ["/brokers", "Brokers"],
  ["/accounts", "My accounts"],
  ["/settings", "Settings"],
] as const;

export function App() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => get<Me>("/api/me"), staleTime: 60_000 });
  if (me.isLoading) return null;
  // fails closed: if /api/me cannot be read, the member's menu (the server refuses the rest anyway)
  const member = !me.data || (me.data.mode === "oidc" && !me.data.operator);
  const nav = member ? MEMBER_NAV : NAV;
  return (
    <WorkspaceProvider>
    <div className="min-h-full">
      <header className="sticky top-0 z-10 border-b border-line bg-bg/90 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-2.5 sm:gap-6">
          <div className="flex shrink-0 items-center gap-2">
            <span className="font-semibold">auto-trade</span>
            <span className="rounded bg-warn/15 px-1.5 py-0.5 text-[10px] font-bold tracking-wider text-warn">PAPER</span>
          </div>
          <nav className="flex min-w-0 gap-1 overflow-x-auto">
            {nav.map(([to, label]) => (
              <NavLink key={to} to={to}
                className={({ isActive }) => `rounded px-3 py-1.5 text-sm ${isActive ? "bg-panel-2 font-semibold" : "text-muted hover:text-text"}`}>
                {label}
              </NavLink>
            ))}
          </nav>
          <WorkspaceSwitcher />
          <SignedIn />
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-4">
        {member ? (
          <Routes>
            <Route path="/" element={<Navigate to="/index/NIFTY" replace />} />
            <Route path="/live" element={<Navigate to="/index/NIFTY" replace />} />
            <Route path="/index/:underlying" element={<IndexPage />} />
            <Route path="/today" element={<TodayPage />} />
            <Route path="/brokers" element={<BrokersPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<AccountsPage />} />
          </Routes>
        ) : (
        <Routes>
          <Route path="/" element={<LivePage />} />
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
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
        )}
      </main>
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
    <form method="post" action="/logout" className="ml-auto flex shrink-0 items-center gap-2 text-sm">
      <span className="text-muted" title={me.data.operator ? "operator: may use kill, stop and exit-all" : "no operator role"}>
        {me.data.user}
        {me.data.operator && <span className="ml-1 text-[10px] font-bold tracking-wider text-warn">OP</span>}
      </span>
      <button type="submit" className="rounded px-2 py-1 text-muted hover:bg-panel-2 hover:text-text">Sign out</button>
    </form>
  );
}
