import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import {
  get, parseSide, post, rupees, type Equity, type LastDecisionRow, type MarketState, type RejectionRow,
  type SessionRow, type Status, type StrategyView, type TradePositionRow,
} from "../api";
import { ConfirmButton, ErrorNote, Loading, Panel, Pnl, Stat } from "../components/ui";
import { groupTrades, TradesTable } from "../components/TradesTable";
import { LivePositions } from "../components/LivePositions";
import { MarketCard } from "../components/MarketCard";
import { splitDecisionKey, storedScores, StrategyMatrix, type MatrixCell } from "../components/strategies";
import { AlertBanner } from "../components/AlertBanner";

export function LivePage() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ["status"], queryFn: () => get<Status>("/api/status"), refetchInterval: 2000 });
  const action = useMutation({
    mutationFn: (path: string) => post<Status>(path),
    onSuccess: (data) => queryClient.setQueryData(["status"], data),
  });

  if (status.isLoading) return <Loading what="live status" />;
  if (status.error) return <ErrorNote error={status.error} />;
  const s = status.data!;
  if (s.status === "NO_SESSION" || s.session == null) {
    const next = s.schedule?.nextStart;
    return (
      <div className="space-y-4">
      <AlertBanner />
      <EquityPanel />
      <Panel title={s.schedule?.mode === "auto" ? "Waiting for the next trading session" : "No trading session running"}>
        {s.schedule?.mode === "auto" && next ? (
          <p className="text-sm">
            The live PAPER session starts automatically at{" "}
            <span className="num font-semibold">{formatStart(next)}</span> IST ({s.schedule.dailyWindow} on trading days).
          </p>
        ) : (
          <p className="text-sm text-muted">
            This service is not scheduling sessions (mode {s.schedule?.mode ?? "?"}). Run it with{" "}
            <code className="num">--mode=auto</code> to start sessions automatically.
          </p>
        )}
        <p className="mt-2 text-sm text-muted">
          Every past session is under <Link to="/sessions" className="text-accent underline">Sessions</Link>; the
          most recent one is below.
        </p>
      </Panel>
      <LastSession />
      </div>
    );
  }
  const killed = (s.killSwitches ?? []).length > 0;
  const lag = s.feedLagSeconds;

  return (
    <div className="space-y-4">
      <AlertBanner />
      <Panel
        title={<>Session {s.session} · {s.date} · <span className="text-warn">{s.mode}</span></>}
        right={
          <div className="flex flex-wrap gap-2">
            {killed ? (
              <ConfirmButton tone="neutral" label="Release kill switch" confirmLabel="Confirm release"
                onConfirm={() => action.mutate("/api/kill/release?scope=GLOBAL")} />
            ) : (
              <ConfirmButton label="Kill switch" confirmLabel="Block new entries?"
                onConfirm={() => action.mutate("/api/kill?scope=GLOBAL&reason=operator%20UI")} />
            )}
            <ConfirmButton label="Exit all" confirmLabel="Close every position?" onConfirm={() => action.mutate("/api/exit-all")} />
            <ConfirmButton label="Stop session" confirmLabel="Exit all and stop?" onConfirm={() => action.mutate("/api/stop")} />
          </div>
        }
      >
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
          <Stat label="Status" value={s.status} tone={s.status === "RUNNING" ? "up" : "warn"} />
          <Stat label="Day P&L (net of costs)" value={<Pnl value={s.dayPnl} />} />
          <Stat label="Feed" value={s.feed} />
          <Stat label="Last event (IST)" value={s.lastEvent?.slice(0, 8) ?? "–"} />
          <Stat label="Feed lag" value={lag == null ? "replay" : `${lag.toFixed(1)} s`} tone={lag != null && lag > 5 ? "down" : undefined} />
          <Stat label="Kill switches" value={killed ? s.killSwitches!.join(", ") : "none"} tone={killed ? "down" : "muted"} />
        </div>
        {action.error && <div className="mt-3"><ErrorNote error={action.error} /></div>}
      </Panel>

      <EquityPanel today={s} />

      <LiveOverview views={s.strategy ?? {}} positions={s.openPositions ?? []} />

      <Panel title="Open positions">
        <LivePositions rows={s.openPositions ?? []} closed={false} empty="Flat." />
      </Panel>
      <Panel title="Closed today">
        <LivePositions rows={s.closedPositions ?? []} closed empty="No closed positions yet." />
      </Panel>
      <Panel title="Recent refusals (risk, stale data, missing quotes)">
        {(s.recentRejections ?? []).length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="num space-y-1 text-xs">{s.recentRejections!.map((r, i) => <li key={i}>{r}</li>)}</ul>
        )}
      </Panel>
    </div>
  );
}

const ORDER = ["NIFTY", "SENSEX", "BANKNIFTY"];
const rank = (u: string) => (ORDER.includes(u) ? ORDER.indexOf(u) : ORDER.length);
const byOrder = (a: string, b: string) => rank(a) - rank(b) || a.localeCompare(b);

/** Each index's market (one tile, linked to its dashboard) and every strategy's stage on it. */
function LiveOverview({ views, positions }: { views: Record<string, StrategyView>; positions: Status["openPositions"] }) {
  const entries = Object.entries(views).map(([key, view]) => ({ ...splitDecisionKey(key), view }));
  const underlyings = [...new Set(entries.map((e) => e.underlying))].sort(byOrder);
  const cells: MatrixCell[] = entries.map((e) => ({ strategy: e.strategy, underlying: e.underlying, time: e.view.time,
    ce: parseSide(e.view.CE), pe: parseSide(e.view.PE) }));
  return (
    <>
      <IndexTiles items={underlyings.map((u) => {
        const v = entries.find((e) => e.underlying === u)!.view;
        return { underlying: u, spot: v.spot, time: v.time, state: v.state };
      })} />
      <Panel title="Strategies" right={<Link to="/strategies" className="text-xs text-accent hover:underline">what each one does, and its record →</Link>}>
        <StrategyMatrix cells={cells} positions={positions ?? []} underlyings={underlyings} />
      </Panel>
    </>
  );
}

function IndexTiles({ items, note }: { items: { underlying: string; spot: number | null; time: string; state: MarketState }[]; note?: string }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {items.map((i) => (
        <MarketCard key={i.underlying} spot={i.spot} time={i.time} state={i.state}
          title={<Link to={`/index/${i.underlying}`} className="hover:underline">{i.underlying}{note && <span className="ml-2 text-xs font-normal text-muted">{note}</span>}</Link>}
          right={<span className="flex items-center gap-3 text-xs text-muted">
            <span>{i.time} IST · {i.state?.label ? i.state.label + " · " : ""}{i.state?.regime}</span>
            <Link to={`/index/${i.underlying}`} className="text-accent hover:underline">chart, surges →</Link>
          </span>} />
      ))}
    </div>
  );
}


const SESSION_KINDS = [
  { mode: "PAPER_LIVE", label: "Live" },
  { mode: "PAPER_REPLAY", label: "Replay" },
] as const;

/**
 * A finished session of the chosen kind (the most recent unless another is picked), read-only from the
 * database: how it ended (P&L, trades, the last stage of every index and strategy), never mistaken for
 * a running session.
 */
function LastSession() {
  const [mode, setMode] = useState<string>("PAPER_LIVE");
  const [picked, setPicked] = useState<number | null>(null);
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  const ofMode = (sessions.data ?? []).filter((row) => row.mode === mode);
  const session = ofMode.find((row) => row.id === picked) ?? ofMode[0];
  const id = session?.id;
  const last = useQuery({
    queryKey: ["last-decisions", id], enabled: id != null,
    queryFn: () => get<LastDecisionRow[]>(`/api/sessions/${id}/last-decisions`),
  });
  const positions = useQuery({
    queryKey: ["positions", id], enabled: id != null,
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${id}/positions`),
  });
  const rejections = useQuery({
    queryKey: ["rejections", id], enabled: id != null,
    queryFn: () => get<RejectionRow[]>(`/api/sessions/${id}/rejections`),
  });

  const toggle = (
    <div className="flex gap-1 text-xs">
      {SESSION_KINDS.map((kind) => (
        <button key={kind.mode} type="button" onClick={() => { setMode(kind.mode); setPicked(null); }}
          className={`rounded border px-2 py-1 ${mode === kind.mode ? "border-accent bg-accent/10 font-semibold" : "border-line text-muted"}`}>
          {kind.label}
        </button>
      ))}
      {ofMode.length > 1 && (
        <select aria-label="Session" value={session?.id ?? ""} onChange={(e) => setPicked(Number(e.target.value))}
          className="num rounded border border-line bg-panel px-1 py-1">
          {ofMode.slice(0, 60).map((row) => (
            <option key={row.id} value={row.id}>
              #{row.id} · {row.session_date} · {row.code_version}
            </option>
          ))}
        </select>
      )}
    </div>
  );
  if (sessions.isLoading) return <Loading what="last session" />;
  if (sessions.error) return <ErrorNote error={sessions.error} />;
  if (!session) {
    return (
      <Panel title="Last session" right={toggle}>
        <p className="text-sm text-muted">No {mode === "PAPER_LIVE" ? "live" : "replay"} session recorded yet.</p>
      </Panel>
    );
  }
  const strategies = session.strategy_id.split("+").filter(Boolean);
  const several = strategies.length > 1;
  const summary = session.summary ?? {};
  const statusTone = session.status === "DONE" ? "muted" : session.status === "RUNNING" ? "up" : "warn";
  const closed = positions.data ?? [];
  const trades = groupTrades(closed);

  return (
    <div className="space-y-4">
      <Panel
        title={<>{session.id === ofMode[0]?.id ? "Last" : "Earlier"} {mode === "PAPER_LIVE" ? "live" : "replay"} session · #{session.id} · {session.session_date}{" "}
          <span className="ml-1 rounded bg-panel-2 px-1.5 py-0.5 text-xs font-normal text-muted">finished · not live</span></>}
        right={toggle}
      >
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
          <Stat label="Status" value={session.status} tone={statusTone} />
          <Stat label="Net P&L (after costs)" value={<Pnl value={summary.net ?? null} />} />
          {/* a straddle is one trade: count trades and wins over grouped legs, not positions */}
          <Stat label="Trades" value={trades.length} />
          <Stat label="Wins" value={positions.isLoading ? "–" : trades.filter((t) => t.net > 0).length} />
          <Stat label="Costs" value={rupees(summary.costs)} tone="muted" />
          <Stat label="Market events" value={summary.events != null ? summary.events.toLocaleString("en-IN") : "–"} tone="muted" />
        </div>
        <dl className="mt-4 grid gap-x-6 gap-y-1 text-xs sm:grid-cols-2">
          <div className="flex gap-2"><dt className="text-muted">Strategies</dt><dd className="num">{strategies.join(", ")}</dd></div>
          <div className="flex gap-2"><dt className="text-muted">Feed</dt><dd className="num">{session.feed}</dd></div>
          <div className="flex gap-2"><dt className="text-muted">Ran (IST)</dt>
            <dd className="num">{session.started_at?.slice(0, 16)} → {session.ended_at?.slice(11, 16) ?? "–"}</dd></div>
          <div className="flex gap-2"><dt className="text-muted">Code</dt><dd className="num">{session.code_version}</dd></div>
        </dl>
        {session.error && <p className="mt-3 text-xs text-warn">{session.error}</p>}
        <p className="mt-3 text-xs">
          <Link to={`/sessions/${session.id}`} className="text-accent underline">Charts, every decision and orders →</Link>
        </p>
      </Panel>

      {last.isLoading ? <Loading what="final stages" /> : last.error ? <ErrorNote error={last.error} /> : (
        (last.data ?? []).length === 0 ? (
          <Panel title="How each index ended"><p className="text-sm text-muted">No decisions were recorded.</p></Panel>
        ) : (() => {
          const rows = last.data!;
          const underlyings = [...new Set(rows.map((r) => r.underlying))].sort(byOrder);
          return (
            <>
              <IndexTiles note="at close" items={underlyings.map((u) => {
                const r = rows.find((x) => x.underlying === u)!;
                return { underlying: u, spot: r.spot, time: r.t, state: r.state };
              })} />
              <Panel title="How each strategy ended">
                <StrategyMatrix underlyings={underlyings} cells={rows.map((r) => ({ strategy: r.strategy_id, underlying: r.underlying,
                  time: r.t, ce: storedScores(r.ce_scores, r.ce_stage), pe: storedScores(r.pe_scores, r.pe_stage) }))} />
              </Panel>
            </>
          );
        })()
      )}

      <Panel title="Trades">
        <TradesTable rows={closed} showStrategy={several} empty="No trades in this session." />
      </Panel>
      {(rejections.data ?? []).length > 0 && (
        <Panel title={`Refusals (${rejections.data!.length})`}>
          <ul className="num max-h-48 space-y-1 overflow-y-auto text-xs">
            {rejections.data!.map((r, i) => (
              <li key={i}>{r.at.slice(11, 19)} {r.underlying}{several && r.strategy_id ? ` · ${r.strategy_id}` : ""} {r.intent}: {r.reason}</li>
            ))}
          </ul>
        </Panel>
      )}
    </div>
  );
}

/** "2026-09-28T09:00+05:30[Asia/Kolkata]" → "Mon 28 Sep 09:00". */
function formatStart(value: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2})/.exec(value);
  if (!m) return value;
  const date = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  const day = date.toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
  return `${day} ${m[4]}`;
}

/**
 * The account's equity: starting capital plus the realised net of every finished live session (risk v8/v9 size every
 * strategy from it at the start of each session). Today's P&L is added after the session ends.
 */
function EquityPanel({ today }: { today?: Status }) {
  const equity = useQuery({ queryKey: ["equity"], queryFn: () => get<Equity>("/api/equity"), refetchInterval: 60000 });
  const [open, setOpen] = useState(false);
  if (equity.isLoading) return <Loading what="equity" />;
  if (equity.error) return <ErrorNote error={equity.error} />;
  const e = equity.data!;
  const sessionCapital = today?.capital ?? e.equity;
  const live = today?.dayPnl ?? 0;
  const days = [...e.days].reverse();
  return (
    <Panel
      title={<>Account equity · {e.account}{e.riskVersion ? <> · <span className="text-muted">{e.riskVersion}</span></> : null}</>}
      right={
        <button className="text-sm text-accent underline" onClick={() => setOpen(!open)}>
          {open ? "Hide" : "Show"} day by day ({e.days.length})
        </button>
      }
    >
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Starting capital" value={rupees(e.startingCapital)} />
        <Stat label={e.equityFrom ? `Realised since ${e.equityFrom}` : "Realised so far (finished sessions)"} value={<Pnl value={e.realisedNet} />} />
        <Stat label={today ? "Capital this session" : "Capital next session"} value={rupees(sessionCapital)} />
        <Stat label="Size factor (× strategy budgets)" value={`${(today?.budgetScale ?? e.budgetScale).toFixed(2)}×`}
          tone={(today?.budgetScale ?? e.budgetScale) < 1 ? "down" : "up"} />
        <Stat label="Daily loss limit" value={rupees(today?.dailyLossLimit ?? e.dailyLossLimit)} />
        <Stat label={today ? "Equity if closed now" : "Equity"} value={rupees(sessionCapital + live)} />
      </div>
      {!e.equityMode && (
        <p className="mt-2 text-sm text-muted">The risk file uses a fixed capital: profits are not added to the size.</p>
      )}
      {open && (
        <table className="mt-3 w-full text-sm">
          <thead>
            <tr className="text-left text-muted">
              <th className="py-1">Session</th><th className="text-right">Equity before</th>
              <th className="text-right">Net</th><th className="text-right">Equity after</th>
            </tr>
          </thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.date} className="border-t border-line">
                <td className="py-1 num">{d.date}</td>
                <td className="text-right num">{rupees(d.equityBefore)}</td>
                <td className="text-right"><Pnl value={d.net} /></td>
                <td className="text-right num">{rupees(d.equityAfter)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
