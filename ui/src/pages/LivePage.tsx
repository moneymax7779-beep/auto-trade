import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { get, parseSide, post, type PositionRow, type Status, type StrategyView } from "../api";
import { ConfirmButton, ErrorNote, Loading, Panel, Pnl, ScoreBar, SignedGauge, StageBadge, Stat, Table } from "../components/ui";

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
          Past sessions are under <Link to="/sessions" className="text-accent underline">Sessions</Link>.
        </p>
      </Panel>
    );
  }
  const killed = (s.killSwitches ?? []).length > 0;
  const lag = s.feedLagSeconds;

  return (
    <div className="space-y-4">
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
          <UnderlyingCard key={underlying} underlying={underlying} view={view} />
        ))}
      </div>

      <Panel title="Open positions">
        <PositionsTable rows={s.openPositions ?? []} empty="Flat." />
      </Panel>
      <Panel title="Closed today">
        <PositionsTable rows={s.closedPositions ?? []} empty="No closed positions yet." />
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

function UnderlyingCard({ underlying, view }: { underlying: string; view: StrategyView }) {
  const ce = parseSide(view.CE);
  const pe = parseSide(view.PE);
  const st = view.state;
  return (
    <Panel
      title={<>{underlying} <span className="num ml-2 text-base">{Number.isFinite(view.spot) ? view.spot.toFixed(2) : "–"}</span></>}
      right={<span className="text-xs text-muted">{view.time} IST · {st?.label ? st.label + " · " : ""}{st?.regime}</span>}
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
      <FactPanel title="Volatility regime" facts={view.volatility} />
      {view.cas && (view.cas.indicative !== undefined || view.cas.iepPressurePct !== undefined)
        ? <FactPanel title="Closing auction" facts={view.cas} /> : null}
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

function PositionsTable({ rows, empty }: { rows: PositionRow[]; empty: string }) {
  return (
    <Table
      rows={rows}
      empty={empty}
      columns={[
        { key: "u", label: "Index", render: (r) => r.underlying },
        { key: "s", label: "Contract", render: (r) => `${r.symbol}` },
        { key: "st", label: "Stages", render: (r) => r.stages.join(" → ") },
        { key: "state", label: "State", render: (r) => r.state },
        { key: "q", label: "Qty", align: "right", render: (r) => r.quantity },
        { key: "a", label: "Avg cost", align: "right", render: (r) => r.averageCost.toFixed(2) },
        { key: "o", label: "Opened", render: (r) => r.opened ?? "–" },
        { key: "c", label: "Closed", render: (r) => r.closed ?? "–" },
        { key: "x", label: "Exit", render: (r) => r.exitReason ?? "–" },
        { key: "n", label: "Net", align: "right", render: (r) => <Pnl value={r.net} /> },
      ]}
    />
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
