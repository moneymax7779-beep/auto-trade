import { useQuery } from "@tanstack/react-query";
import { NavLink, Route, Routes } from "react-router";
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
 * A family member or viewer (signed in without the operator role) sees the Overview without its controls (user, 5 Nov
 * 2026), their own workspace (trader workspaces, 7 Nov 2026: Today's broker and PAPER/LIVE switch, their brokers and
 * logins, settings), their accounts and the index chart pages; the rest is the owner's.
 */
const MEMBER_NAV = [
  ["/live", "Overview"],
  ["/today", "Today"],
  ["/brokers", "Brokers"],
  ["/accounts", "My accounts"],
  ["/settings", "Settings"],
  ["/index/NIFTY", "NIFTY"],          // the same index chart pages as the owner's (user, 29 Oct 2026), read-only
  ["/index/SENSEX", "SENSEX"],
  ["/index/BANKNIFTY", "BANKNIFTY"],
] as const;

export function App() {
  const me = useQuery({ queryKey: ["me"], queryFn: () => get<Me>("/api/me"), staleTime: 60_000 });
  if (me.isLoading) return null;
  const member = me.data?.mode === "oidc" && !me.data.operator;
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
            <Route path="/" element={<LivePage canAct={false} />} />
            <Route path="/live" element={<LivePage canAct={false} />} />
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
