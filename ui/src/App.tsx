import { NavLink, Route, Routes } from "react-router";
import { ConfigPage } from "./pages/ConfigPage";
import { LivePage } from "./pages/LivePage";
import { ReplayPage } from "./pages/ReplayPage";
import { ResearchPage, RunDetailPage } from "./pages/ResearchPage";
import { SessionDetailPage, SessionsPage } from "./pages/SessionsPage";

const NAV = [
  ["/live", "Live"],
  ["/sessions", "Sessions"],
  ["/research", "Research"],
  ["/replay", "Replay"],
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
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-4">
        <Routes>
          <Route path="/" element={<LivePage />} />
          <Route path="/live" element={<LivePage />} />
          <Route path="/sessions" element={<SessionsPage />} />
          <Route path="/sessions/:id" element={<SessionDetailPage />} />
          <Route path="/research" element={<ResearchPage />} />
          <Route path="/research/:id" element={<RunDetailPage />} />
          <Route path="/replay" element={<ReplayPage />} />
          <Route path="/config" element={<ConfigPage />} />
        </Routes>
      </main>
    </div>
  );
}
