import { type ReactNode, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import {
  get, parseSide, post, rupees, type LastDecisionRow, type MarketState, type RejectionRow, type Scores,
  type SessionRow, type Status, type TradePositionRow,
} from "../api";
import { ConfirmButton, ErrorNote, Loading, Panel, Pnl, ScoreBar, SignedGauge, StageBadge, Stat } from "../components/ui";
import { groupTrades, TradesTable } from "../components/TradesTable";
import { LivePositions } from "../components/LivePositions";
import { SurgePanel } from "../components/SurgePanel";
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
      <SurgePanel />
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

      <div className="grid gap-4 lg:grid-cols-2">
        {Object.entries(s.strategy ?? {}).map(([underlying, view]) => (
          <UnderlyingCard key={underlying} title={underlying} spot={view.spot} time={view.time} state={view.state}
            ce={parseSide(view.CE)} pe={parseSide(view.PE)} volatility={view.volatility} cas={view.cas} />
        ))}
      </div>

      <SurgePanel live date={s.date} />

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

function UnderlyingCard({ title, spot, time, state, ce, pe, volatility, cas }: {
  title: ReactNode;
  spot: number | null;
  time: string;
  state: MarketState;
  ce: Scores;
  pe: Scores;
  volatility?: Record<string, number | string>;
  cas?: Record<string, number | string>;
}) {
  const st = state;
  return (
    <Panel
      title={<>{title} <span className="num ml-2 text-base">{spot != null && Number.isFinite(spot) ? spot.toFixed(2) : "–"}</span></>}
      right={<span className="text-xs text-muted">{time} IST · {st?.label ? st.label + " · " : ""}{st?.regime}</span>}
    >
      <div className="grid grid-cols-2 gap-x-6 gap-y-3">
        <SignedGauge label="Direction" value={st?.direction} />
        <SignedGauge label="Structure" value={st?.structure} />
        <SignedGauge label="Participation" value={st?.participation} signed={false} />
        <SignedGauge label="Continuation" value={st?.continuation} />
      </div>
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        {[["CE (bullish)", ce], ["PE (bearish)", pe]].map(([label, side]) => {
          const scores = side as ReturnType<typeof parseSide>;
          return (
            <div key={label as string} className="rounded border border-line p-3">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-semibold">{label as string}</span>
                <StageBadge stage={scores.stage} />
              </div>
              <div className="space-y-1.5">
                <ScoreBar label="Early" value={scores.early} threshold={100} />
                <ScoreBar label="Confirm" value={scores.confirm} />
                <ScoreBar label="Runner" value={scores.runner} threshold={70} />
              </div>
            </div>
          );
        })}
      </div>
      <FactPanel title="Volatility regime" facts={volatility} />
      {cas && (cas.indicative !== undefined || cas.iepPressurePct !== undefined)
        ? <FactPanel title="Closing auction" facts={cas} /> : null}
    </Panel>
  );
}

const FACT_LABELS: Record<string, string> = {
  dte: "DTE", minutesToExpiry: "Min to expiry", atmIvPct: "ATM IV %", ivPercentile: "IV pctl", ivTrend: "IV trend",
  vix: "India VIX", vixPercentile252d: "VIX pctl 252d", vixChange15m: "VIX Δ15m", realizedVolPct: "Realised vol %",
  realizedVolPercentile: "RV pctl", atrPercentile: "ATR pctl", futuresRvol: "Futures RVOL",
  expectedMoveRemaining: "Exp. move left", event: "Event", phase: "Phase", indicative: "Indicative",
  reference: "Reference", iepPressurePct: "IEP pressure %", imbalance: "Imbalance", breadthPct: "CAS breadth %",
  top3: "Top-3 share", futuresVsIndicative: "Fut − indicative", liquidityPercentile: "Liquidity pctl",
  coveragePct: "Coverage %",
};

function FactPanel({ title, facts }: { title: string; facts?: Record<string, number | string> }) {
  if (!facts || Object.keys(facts).length === 0) return null;
  return (
    <div className="mt-4 rounded border border-line p-3">
      <div className="mb-2 text-xs font-semibold">{title}</div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-3">
        {Object.entries(facts).map(([key, value]) => (
          <div key={key} className="flex min-w-0 justify-between gap-2">
            <dt className="truncate text-muted">{FACT_LABELS[key] ?? key}</dt>
            <dd className="num">{String(value)}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/** Stored scores use −1 for "not computed"; the bars show that as blank. */
function storedScores(scores: Scores | undefined, stage: string): Scores {
  const value = (v: number | undefined) => (v == null || v < 0 ? NaN : v);
  return { stage, early: value(scores?.early), confirm: value(scores?.confirm), runner: value(scores?.runner) };
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
        ) : (
          <div className="grid gap-4 lg:grid-cols-2">
            {last.data!.map((row) => (
              <UnderlyingCard key={row.underlying + row.strategy_id}
                title={<>{row.underlying}{several && <span className="ml-2 text-xs font-normal text-muted">{row.strategy_id}</span>}
                  <span className="ml-2 text-xs font-normal text-muted">at close</span></>}
                spot={row.spot} time={row.t} state={row.state}
                ce={storedScores(row.ce_scores, row.ce_stage)} pe={storedScores(row.pe_scores, row.pe_stage)} />
            ))}
          </div>
        )
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
