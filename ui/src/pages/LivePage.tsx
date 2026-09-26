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
    return (
      <Panel title="No trading session running">
        <p className="text-sm text-muted">
          Start one with <code className="num">bin/trading-core --mode=live</code> (or{" "}
          <code className="num">--mode=replay --session=YYYY-MM-DD</code>). Past sessions are under{" "}
          <Link to="/sessions" className="text-accent underline">Sessions</Link>.
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
      right={<span className="text-xs text-muted">{view.time} IST · {st?.regime}</span>}
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
    </Panel>
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
