import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, useParams, useSearchParams } from "react-router";
import { get, parseSide, type LastDecisionRow, type PositionRow, type SessionRow, type Status, type TradePositionRow, isPrimaryLive } from "../api";
import { AlertBanner } from "../components/AlertBanner";
import { DayContextCard } from "../components/DayContextPanels";
import { LiveBooks } from "../components/LiveBookPanel";
import { LivePositions } from "../components/LivePositions";
import { MarketCard } from "../components/MarketCard";
import { splitDecisionKey, storedScores, StrategyMatrix, strategyShort } from "../components/strategies";
import { SurgePanel, type DayNav } from "../components/SurgePanel";
import { chartTrades, TradesTable } from "../components/TradesTable";
import { PcrPanel } from "../components/PcrPanel";
import { OiTapePanel, type TapeTrade } from "../components/OiTapePanel";
import { ErrorNote, Loading, Panel, Pnl } from "../components/ui";
import { ReplayControls } from "../components/TradeInspector";

export const INDICES = ["NIFTY", "SENSEX", "BANKNIFTY"] as const;
/** Indices recorded for context only (charts, volume, PCR); no strategy trades them. */
const CONTEXT_ONLY = new Set(["BANKNIFTY"]);

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
  // the days the own capture holds: a day can be shown (charts, OI tape, PCR) without a live session record of it
  const captured = useQuery({ queryKey: ["oi-tape-days"], queryFn: () => get<string[]>("/api/oi-tape/days") });
  // a session's trade data, minute inspector and replay controls are the operator's (the API refuses anyone else)
  const me = useQuery({ queryKey: ["me"], queryFn: () => get<{ mode: string; operator: boolean }>("/api/me"), staleTime: 60_000 });
  // off until /api/me answers (a viewer never sees operator controls, even for a moment or when the call fails)
  const inspect = !!me.data && (me.data.mode !== "oidc" || me.data.operator);
  if (me.isLoading) return <Loading what="sign-in" />;
  // everyone but the owner (operator role) sees the chart and the trades only (user, 10 Nov 2026: "Other than Owner, all
  // other accounts, Follower Demo and whoever it is should only see the chart and trades, nothing more")
  const plain = !inspect;
  if (status.isLoading) return <Loading what="live status" />;
  if (status.error) return <ErrorNote error={status.error} />;
  const s = status.data!;
  const running = s.status !== "NO_SESSION" && s.session != null;
  // every day with a live session or the own capture, newest first (the dates the charts can show)
  const liveSessions = (sessions.data ?? []).filter(isPrimaryLive);
  const days = [...new Set([...liveSessions.map((r) => r.session_date), ...(captured.data ?? [])])].sort().reverse();
  const showLive = running && (!picked || picked === s.date);
  const day = showLive ? s.date! : picked ?? days[0];
  const go = (d: string | null) => setParams(d && !(running && d === s.date) ? { date: d } : {});
  const i = day ? days.indexOf(day) : -1;
  // the chart's own ‹ › steps: every live day, today's running session first
  const chartDays = running && !days.includes(s.date!) ? [s.date!, ...days] : days;
  const ci = day ? chartDays.indexOf(day) : -1;
  const nav: DayNav = { prev: ci >= 0 ? chartDays[ci + 1] : undefined, next: ci > 0 ? chartDays[ci - 1] : undefined, go };
  const sessionOf = (d: string) => liveSessions.filter((r) => r.session_date === d).map((r) => r.id).sort((a, b) => b - a)[0];

  return (
    <div className="space-y-4">
      {!plain && <AlertBanner />}
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
        {!plain && <Link to="/live" className="ml-auto text-xs text-accent hover:underline">← overview</Link>}
      </div>
      {/* a member's own LIVE accounts (open and closed positions, Exit, booking a hand exit): on the Overview until
          10 Nov 2026, which is the owner's now; their real-money trades must stay in reach */}
      {plain && <LiveBooks />}
      {CONTEXT_ONLY.has(underlying) && (
        <p className="text-xs text-muted">{plain ? `${underlying} is shown for context; no strategy trades it.`
          : `${underlying} is recorded for context (chart, futures volume, PCR, levels); no strategy trades it.`}</p>
      )}

      {showLive ? (plain ? <PlainLiveDay underlying={underlying} s={s} nav={nav} />
        : <LiveDay underlying={underlying} s={s} nav={nav} inspect={inspect} />) : day
        ? (plain ? <PlainPastDay key={day} underlying={underlying} day={day} nav={nav} sessionId={sessionOf(day)} />
          : <PastDay key={day} underlying={underlying} day={day} nav={nav} inspect={inspect} sessionId={sessionOf(day)} />)
        : <p className="text-sm text-muted">No live session recorded yet.</p>}
    </div>
  );
}

/** The running session on this index. */
function LiveDay({ underlying, s, nav, inspect }: { underlying: string; s: Status; nav: DayNav; inspect: boolean }) {
  const entries = Object.entries(s.strategy ?? {}).map(([key, view]) => ({ ...splitDecisionKey(key), view }))
    .filter((e) => e.underlying === underlying);
  const market = entries[0]?.view;
  const mine = <T extends { underlying: string }>(rows: T[] | undefined) => (rows ?? []).filter((r) => r.underlying === underlying);
  const open = mine(s.openPositions), closed = mine(s.closedPositions);
  const pnl = [...open, ...closed].reduce((sum, p) => sum + p.net, 0);   // net of costs, as the day P&L
  // a replay runs on its own clock: its day's charts are cut at its last event, so they move as it runs
  // "HH:mm:ss" (a whole minute comes from the server as "HH:mm")
  const until = s.mode === "PAPER_REPLAY" && s.lastEvent ? s.lastEvent.slice(0, 8).padEnd(8, ":00").slice(0, 8) : undefined;
  return (
    <>
      {inspect && <ReplayControls s={s} />}
      <p className="text-xs text-muted">session {s.session} · {s.date} · {underlying} P&L <Pnl value={Math.round(pnl)} /></p>
      {market && (
        <MarketCard title={underlying} spot={market.spot} time={market.time} state={market.state}
          volatility={market.volatility} cas={market.cas} />
      )}
      {s.date && <DayContextCard date={s.date} underlying={underlying} live />}
      <SurgePanel live date={s.date} underlying={underlying} nav={nav} until={until} sessionId={inspect ? s.session : undefined}
        trades={chartTrades([...open, ...closed].filter((p) => p.opened).map((p) => liveAsStored(p, s.date!)), underlying, null, strategyShort, noSpot)} />
      {s.date && <OiTapePanel underlying={underlying} date={s.date} live until={until}
        trades={[...open, ...closed].filter((p) => p.opened).map((p): TapeTrade => ({ label: `${p.strategy ?? ""} ${p.side}`, opened: p.opened!, closed: p.closed, net: p.net }))} />}
      <PcrPanel underlying={underlying} live />
      {entries.length > 0 && (
        <Panel title={`Strategies on ${underlying}`}>
          <StrategyMatrix underlyings={[underlying]} positions={open}
            cells={entries.map((e) => ({ strategy: e.strategy, underlying, time: e.view.time, ce: parseSide(e.view.CE), pe: parseSide(e.view.PE) }))} />
        </Panel>
      )}
      {/* one above the other (11 Nov 2026): side by side each 11-column table scrolled sideways in half the width */}
      <div className="grid gap-4">
        <Panel title="Open positions"><LivePositions rows={open} closed={false} empty="Flat." /></Panel>
        <Panel title="Closed today"><LivePositions rows={closed} closed empty="No closed positions yet." /></Panel>
      </div>
    </>
  );
}

/** An earlier live day on this index, from the database: how it closed, the chart, every strategy's end state, the trades. */
function PastDay({ underlying, day, sessionId, nav, inspect }: { underlying: string; day: string; sessionId?: number; nav: DayNav; inspect: boolean }) {
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
      <DayContextCard date={day} underlying={underlying} />
      <SurgePanel date={day} underlying={underlying} nav={nav} sessionId={inspect ? sessionId : undefined} trades={chartTrades(trades, underlying, null, strategyShort, noSpot)} />
      <OiTapePanel underlying={underlying} date={day}
        trades={trades.map((p): TapeTrade => ({ label: `${p.strategy_id ?? ""} ${p.option_side}`, opened: p.opened_at, closed: p.closed_at, net: p.net }))} />
      <PcrPanel underlying={underlying} date={day} />
      {rows.length > 0 && (
        <Panel title={`How each strategy ended on ${underlying}`}>
          <StrategyMatrix underlyings={[underlying]} cells={rows.map((x) => ({ strategy: x.strategy_id, underlying, time: x.t,
            ce: storedScores(x.ce_scores, x.ce_stage), pe: storedScores(x.pe_scores, x.pe_stage) }))} />
        </Panel>
      )}
      <Panel title={`Trades on ${underlying} · ${day}`}>
        <TradesTable rows={trades} showStrategy empty={sessionId == null ? "No live session of this day is recorded on this server (its market data is)."
          : positions.isLoading ? "Loading…" : "No trades on this index that day."} />
      </Panel>
    </>
  );
}

/** A member's view of the running session on this index: the chart and the trades, nothing more (user, 10 Nov 2026). */
function PlainLiveDay({ underlying, s, nav }: { underlying: string; s: Status; nav: DayNav }) {
  const mine = <T extends { underlying: string }>(rows: T[] | undefined) => (rows ?? []).filter((r) => r.underlying === underlying);
  const open = mine(s.openPositions), closed = mine(s.closedPositions);
  const until = s.mode === "PAPER_REPLAY" && s.lastEvent ? s.lastEvent.slice(0, 8).padEnd(8, ":00").slice(0, 8) : undefined;
  return (
    <>
      <SurgePanel plain live date={s.date} underlying={underlying} nav={nav} until={until}
        trades={chartTrades([...open, ...closed].filter((p) => p.opened).map((p) => liveAsStored(p, s.date!)), underlying, null, strategyShort, noSpot)} />
      {/* one above the other (11 Nov 2026): side by side each 11-column table scrolled sideways in half the width */}
      <div className="grid gap-4">
        <Panel title="Open positions"><LivePositions rows={open} closed={false} empty="Flat." /></Panel>
        <Panel title="Closed today"><LivePositions rows={closed} closed empty="No closed positions yet." /></Panel>
      </div>
    </>
  );
}

/** A member's view of an earlier day on this index: the chart and the trades, nothing more (user, 10 Nov 2026). */
function PlainPastDay({ underlying, day, sessionId, nav }: { underlying: string; day: string; sessionId?: number; nav: DayNav }) {
  const positions = useQuery({
    queryKey: ["positions", sessionId], enabled: sessionId != null,
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${sessionId}/positions`),
  });
  const trades = (positions.data ?? []).filter((p) => p.underlying === underlying);
  return (
    <>
      <SurgePanel plain date={day} underlying={underlying} nav={nav} trades={chartTrades(trades, underlying, null, strategyShort, noSpot)} />
      <Panel title={`Trades on ${underlying} · ${day}`}>
        <TradesTable rows={trades} showStrategy empty={sessionId == null ? "No trades recorded for this day."
          : positions.isLoading ? "Loading…" : "No trades on this index that day."} />
      </Panel>
    </>
  );
}

const noSpot = () => null;                                // the surge chart places a trade on its own index line

/** A live position as a stored one, for the chart's trade list (live times are "HH:MM:SS": the day is added). */
function liveAsStored(p: PositionRow, date: string): TradePositionRow {
  const stamp = (t: string | null) => (t == null ? null : t.length <= 8 ? `${date} ${t}` : t);
  return {
    strategy_id: p.strategy ?? null, underlying: p.underlying, option_side: p.leg ? p.leg : p.side, symbol: p.symbol,
    opened_at: stamp(p.opened)!, closed_at: stamp(p.closed), stages: p.stages, exit_reason: p.exitReason,
    realised: p.realised ?? 0, costs: p.costs ?? 0, net: p.net, quantity: p.quantity, lot_size: p.lotSize ?? null,
    average_cost: p.averageCost, average_exit: p.exitPrice ?? null, direction: p.direction ?? null,
  };
}
