import { useQuery } from "@tanstack/react-query";
import { NavLink, Route, Routes } from "react-router";
import { get } from "./api";
import { AccountsPage } from "./pages/AccountsPage";
import { ConfigPage } from "./pages/ConfigPage";
import { IndexPage } from "./pages/IndexPage";
import { LivePage } from "./pages/LivePage";
import { PerformancePage } from "./pages/PerformancePage";
import { ReplayPage } from "./pages/ReplayPage";
import { ResearchPage, RunDetailPage } from "./pages/ResearchPage";
import { SessionDetailPage, SessionsPage } from "./pages/SessionsPage";

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
  ["/config", "Config"],
] as const;

export function App() {
  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-10 border-b border-line bg-bg/90 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-2.5 sm:gap-6">
          <div className="flex shrink-0 items-center gap-2">
            <span className="font-semibold">auto-trade</span>
            <span className="rounded bg-warn/15 px-1.5 py-0.5 text-[10px] font-bold tracking-wider text-warn">PAPER</span>
          </div>
          <nav className="flex min-w-0 gap-1 overflow-x-auto">
            {NAV.map(([to, label]) => (
              <NavLink key={to} to={to}
                className={({ isActive }) => `rounded px-3 py-1.5 text-sm ${isActive ? "bg-panel-2 font-semibold" : "text-muted hover:text-text"}`}>
                {label}
              </NavLink>
            ))}
          </nav>
          <SignedIn />
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-4">
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
        </Routes>
      </main>
    </div>
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
