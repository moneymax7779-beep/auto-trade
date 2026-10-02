import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, useParams } from "react-router";
import { get, parseSide, type LastDecisionRow, type SessionRow, type Status } from "../api";
import { AlertBanner } from "../components/AlertBanner";
import { LivePositions } from "../components/LivePositions";
import { MarketCard } from "../components/MarketCard";
import { splitDecisionKey, storedScores, StrategyMatrix } from "../components/strategies";
import { SurgePanel } from "../components/SurgePanel";
import { ErrorNote, Loading, Panel, Pnl } from "../components/ui";

export const INDICES = ["NIFTY", "SENSEX"] as const;

/**
 * One index's dashboard: its market state, the surge chart (levels, premium, volume) and table, every
 * strategy's stage on it, and its positions. The chart's time window is shared with the other index.
 */
export function IndexPage() {
  const underlying = (useParams().underlying ?? "NIFTY").toUpperCase();
  const status = useQuery({ queryKey: ["status"], queryFn: () => get<Status>("/api/status"), refetchInterval: 2000 });
  if (status.isLoading) return <Loading what="live status" />;
  if (status.error) return <ErrorNote error={status.error} />;
  const s = status.data!;
  const running = s.status !== "NO_SESSION" && s.session != null;
  const entries = Object.entries(s.strategy ?? {}).map(([key, view]) => ({ ...splitDecisionKey(key), view }))
    .filter((e) => e.underlying === underlying);
  const market = entries[0]?.view;
  const mine = <T extends { underlying: string }>(rows: T[] | undefined) => (rows ?? []).filter((r) => r.underlying === underlying);
  const open = mine(s.openPositions), closed = mine(s.closedPositions);
  const pnl = [...open, ...closed].reduce((sum, p) => sum + p.net, 0);   // net of costs, as the day P&L

  return (
    <div className="space-y-4">
      <AlertBanner />
      <div className="flex flex-wrap items-center gap-2">
        {INDICES.map((u) => (
          <NavLink key={u} to={`/index/${u}`}
            className={({ isActive }) => `rounded border px-3 py-1 text-sm ${isActive ? "border-accent bg-accent/10 font-semibold" : "border-line text-muted hover:text-text"}`}>
            {u}
          </NavLink>
        ))}
        <span className="text-xs text-muted">
          {running ? <>session {s.session} · {s.date} · {underlying} P&L <Pnl value={Math.round(pnl)} /></> : "no live session: the latest recorded day"}
        </span>
        <Link to="/live" className="ml-auto text-xs text-accent hover:underline">← overview</Link>
      </div>

      {running && market && (
        <MarketCard title={underlying} spot={market.spot} time={market.time} state={market.state}
          volatility={market.volatility} cas={market.cas} />
      )}

      {!running && <LastOnIndex underlying={underlying} />}

      <SurgePanel live={running} date={running ? s.date : undefined} underlying={underlying} />

      {running && entries.length > 0 && (
        <Panel title={`Strategies on ${underlying}`}>
          <StrategyMatrix underlyings={[underlying]} positions={open}
            cells={entries.map((e) => ({ strategy: e.strategy, underlying, time: e.view.time, ce: parseSide(e.view.CE), pe: parseSide(e.view.PE) }))} />
        </Panel>
      )}
      {running && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title="Open positions"><LivePositions rows={open} closed={false} empty="Flat." /></Panel>
          <Panel title="Closed today"><LivePositions rows={closed} closed empty="No closed positions yet." /></Panel>
        </div>
      )}
    </div>
  );
}

/** No session running: how the last live session ended on this index (read from the database). */
function LastOnIndex({ underlying }: { underlying: string }) {
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  const id = (sessions.data ?? []).find((r) => r.mode === "PAPER_LIVE")?.id;
  const last = useQuery({
    queryKey: ["last-decisions", id], enabled: id != null,
    queryFn: () => get<LastDecisionRow[]>(`/api/sessions/${id}/last-decisions`),
  });
  const rows = (last.data ?? []).filter((r) => r.underlying === underlying);
  if (rows.length === 0) return null;
  const r = rows[0];
  return (
    <>
      <MarketCard spot={r.spot} time={r.t} state={r.state}
        title={<>{underlying} <span className="ml-1 text-xs font-normal text-muted">at close · <Link to={`/sessions/${id}`} className="hover:underline">session #{id}</Link></span></>} />
      <Panel title={`How each strategy ended on ${underlying}`}>
        <StrategyMatrix underlyings={[underlying]} cells={rows.map((x) => ({ strategy: x.strategy_id, underlying, time: x.t,
          ce: storedScores(x.ce_scores, x.ce_stage), pe: storedScores(x.pe_scores, x.pe_stage) }))} />
      </Panel>
    </>
  );
}
