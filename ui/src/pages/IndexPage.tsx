import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, useParams, useSearchParams } from "react-router";
import { get, parseSide, type LastDecisionRow, type SessionRow, type Status, type TradePositionRow } from "../api";
import { AlertBanner } from "../components/AlertBanner";
import { LivePositions } from "../components/LivePositions";
import { MarketCard } from "../components/MarketCard";
import { splitDecisionKey, storedScores, StrategyMatrix } from "../components/strategies";
import { SurgePanel } from "../components/SurgePanel";
import { TradesTable } from "../components/TradesTable";
import { ErrorNote, Loading, Panel, Pnl } from "../components/ui";

export const INDICES = ["NIFTY", "SENSEX"] as const;

/**
 * One index's dashboard: its market state, the surge chart (levels, premium, volume) and table, every
 * strategy's stage on it, and its positions. {@code ?date=} shows an earlier live day the same way (read
 * from the database). The chart's time window is shared with the other index.
 */
export function IndexPage() {
  const underlying = (useParams().underlying ?? "NIFTY").toUpperCase();
  const [params, setParams] = useSearchParams();
  const picked = params.get("date");
  const status = useQuery({ queryKey: ["status"], queryFn: () => get<Status>("/api/status"), refetchInterval: 2000 });
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  if (status.isLoading) return <Loading what="live status" />;
  if (status.error) return <ErrorNote error={status.error} />;
  const s = status.data!;
  const running = s.status !== "NO_SESSION" && s.session != null;
  // every day with a live session, newest first (the dates the charts can show)
  const liveSessions = (sessions.data ?? []).filter((r) => r.mode === "PAPER_LIVE");
  const days = [...new Set(liveSessions.map((r) => r.session_date))].sort().reverse();
  const showLive = running && (!picked || picked === s.date);
  const day = showLive ? s.date! : picked ?? days[0];
  const go = (d: string | null) => setParams(d && !(running && d === s.date) ? { date: d } : {});
  const i = day ? days.indexOf(day) : -1;

  return (
    <div className="space-y-4">
      <AlertBanner />
      <div className="flex flex-wrap items-center gap-2">
        {INDICES.map((u) => (
          <NavLink key={u} to={`/index/${u}${picked ? `?date=${picked}` : ""}`}
            className={({ isActive }) => `rounded border px-3 py-1 text-sm ${isActive ? "border-accent bg-accent/10 font-semibold" : "border-line text-muted hover:text-text"}`}>
            {u}
          </NavLink>
        ))}
        <div className="ml-2 flex items-center gap-1 text-sm" role="group" aria-label="Day">
          <button type="button" className="rounded px-2 py-1 text-muted hover:text-text disabled:opacity-30" aria-label="Earlier day"
            disabled={i < 0 || i >= days.length - 1} onClick={() => go(days[i + 1])}>‹</button>
          <select aria-label="Day" value={day ?? ""} onChange={(e) => go(e.target.value)}
            className="num rounded border border-line bg-panel px-2 py-1">
            {running && !days.includes(s.date!) && <option value={s.date}>{s.date} · live</option>}
            {days.map((d) => <option key={d} value={d}>{d}{running && d === s.date ? " · live" : ""}</option>)}
          </select>
          <button type="button" className="rounded px-2 py-1 text-muted hover:text-text disabled:opacity-30" aria-label="Later day"
            disabled={i <= 0} onClick={() => go(days[i - 1])}>›</button>
          {running && !showLive && (
            <button type="button" className="ml-1 rounded border border-accent px-2 py-0.5 text-xs text-accent" onClick={() => go(null)}>live</button>
          )}
        </div>
        <Link to="/live" className="ml-auto text-xs text-accent hover:underline">← overview</Link>
      </div>

      {showLive ? <LiveDay underlying={underlying} s={s} /> : day
        ? <PastDay key={day} underlying={underlying} day={day}
            sessionId={liveSessions.filter((r) => r.session_date === day).map((r) => r.id).sort((a, b) => b - a)[0]} />
        : <p className="text-sm text-muted">No live session recorded yet.</p>}
    </div>
  );
}

/** The running session on this index. */
function LiveDay({ underlying, s }: { underlying: string; s: Status }) {
  const entries = Object.entries(s.strategy ?? {}).map(([key, view]) => ({ ...splitDecisionKey(key), view }))
    .filter((e) => e.underlying === underlying);
  const market = entries[0]?.view;
  const mine = <T extends { underlying: string }>(rows: T[] | undefined) => (rows ?? []).filter((r) => r.underlying === underlying);
  const open = mine(s.openPositions), closed = mine(s.closedPositions);
  const pnl = [...open, ...closed].reduce((sum, p) => sum + p.net, 0);   // net of costs, as the day P&L
  return (
    <>
      <p className="text-xs text-muted">session {s.session} · {s.date} · {underlying} P&L <Pnl value={Math.round(pnl)} /></p>
      {market && (
        <MarketCard title={underlying} spot={market.spot} time={market.time} state={market.state}
          volatility={market.volatility} cas={market.cas} />
      )}
      <SurgePanel live date={s.date} underlying={underlying} />
      {entries.length > 0 && (
        <Panel title={`Strategies on ${underlying}`}>
          <StrategyMatrix underlyings={[underlying]} positions={open}
            cells={entries.map((e) => ({ strategy: e.strategy, underlying, time: e.view.time, ce: parseSide(e.view.CE), pe: parseSide(e.view.PE) }))} />
        </Panel>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="Open positions"><LivePositions rows={open} closed={false} empty="Flat." /></Panel>
        <Panel title="Closed today"><LivePositions rows={closed} closed empty="No closed positions yet." /></Panel>
      </div>
    </>
  );
}

/** An earlier live day on this index, from the database: how it closed, the chart, every strategy's end state, the trades. */
function PastDay({ underlying, day, sessionId }: { underlying: string; day: string; sessionId?: number }) {
  const last = useQuery({
    queryKey: ["last-decisions", sessionId], enabled: sessionId != null,
    queryFn: () => get<LastDecisionRow[]>(`/api/sessions/${sessionId}/last-decisions`),
  });
  const positions = useQuery({
    queryKey: ["positions", sessionId], enabled: sessionId != null,
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${sessionId}/positions`),
  });
  const rows = (last.data ?? []).filter((r) => r.underlying === underlying);
  const trades = (positions.data ?? []).filter((p) => p.underlying === underlying);
  const r = rows[0];
  return (
    <>
      {r && (
        <MarketCard spot={r.spot} time={r.t} state={r.state}
          title={<>{underlying} <span className="ml-1 text-xs font-normal text-muted">{day} at close ·{" "}
            <Link to={`/sessions/${sessionId}`} className="hover:underline">session #{sessionId}</Link></span></>} />
      )}
      <SurgePanel date={day} underlying={underlying} />
      {rows.length > 0 && (
        <Panel title={`How each strategy ended on ${underlying}`}>
          <StrategyMatrix underlyings={[underlying]} cells={rows.map((x) => ({ strategy: x.strategy_id, underlying, time: x.t,
            ce: storedScores(x.ce_scores, x.ce_stage), pe: storedScores(x.pe_scores, x.pe_stage) }))} />
        </Panel>
      )}
      <Panel title={`Trades on ${underlying} · ${day}`}>
        <TradesTable rows={trades} showStrategy empty={positions.isLoading ? "Loading…" : "No trades on this index that day."} />
      </Panel>
    </>
  );
}
