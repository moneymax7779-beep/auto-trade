import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { LEVEL_COLORS, LevelTogglesBar, mergeLevels, useLevels, useLevelToggles, visibleLevels, visibleZones } from "../components/levels";
import { Link, useParams } from "react-router";
import { fixed, get, type DecisionRow, type OrderRow, type SessionRow, type TradePositionRow } from "../api";
import { TimelineChart } from "../components/TimelineChart";
import { ErrorNote, Loading, Panel, Pnl, StageBadge, Table } from "../components/ui";
import { markersFromOrders } from "./markers";
import { exitMarkers, TradesTable } from "../components/TradesTable";

export function SessionsPage() {
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  if (sessions.isLoading) return <Loading what="sessions" />;
  if (sessions.error) return <ErrorNote error={sessions.error} />;
  return (
    <Panel title="Trading sessions (PAPER)">
      <Table
        rows={sessions.data!}
        empty="No sessions yet. Run bin/trading-core --mode=replay --session=YYYY-MM-DD or --mode=live."
        columns={[
          { key: "id", label: "#", render: (r) => <Link className="text-accent underline" to={`/sessions/${r.id}`}>{r.id}</Link> },
          { key: "d", label: "Date", render: (r) => r.session_date },
          { key: "m", label: "Mode", render: (r) => r.mode },
          { key: "f", label: "Feed", render: (r) => r.feed },
          { key: "st", label: "Status", render: (r) => r.status },
          { key: "p", label: "Positions", align: "right", render: (r) => r.summary?.positions ?? "–" },
          { key: "w", label: "Wins", align: "right", render: (r) => r.summary?.wins ?? "–" },
          { key: "n", label: "Net", align: "right", render: (r) => <Pnl value={r.summary?.net} /> },
          { key: "r", label: "Reconciled", render: (r) => (r.summary?.reconciled == null ? "–" : r.summary.reconciled ? "yes" : "NO") },
          { key: "c", label: "Code", render: (r) => <span className="num text-xs">{r.code_version}</span> },
          { key: "s", label: "Started", render: (r) => r.started_at },
        ]}
      />
    </Panel>
  );
}

export function SessionDetailPage() {
  const { id } = useParams();
  const [underlying, setUnderlying] = useState("NIFTY");
  const [chosenStrategy, setChosenStrategy] = useState<string | null>(null);
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  const sessionRow = sessions.data?.find((s) => String(s.id) === id);
  // A session may run several strategies ("a+b"); the charts show one at a time.
  const strategyIds = (sessionRow?.strategy_id ?? "").split("+").filter(Boolean);
  const strategy = chosenStrategy ?? strategyIds[0] ?? null;
  const decisions = useQuery({
    queryKey: ["decisions", id, underlying, strategy],
    enabled: strategy !== null,
    queryFn: () => get<DecisionRow[]>(`/api/sessions/${id}/decisions?underlying=${underlying}`
      + `&strategy=${encodeURIComponent(strategy ?? "")}`),
  });
  const orders = useQuery({ queryKey: ["orders", id], queryFn: () => get<OrderRow[]>(`/api/sessions/${id}/orders`) });
  const positions = useQuery({
    queryKey: ["positions", id],
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${id}/positions`),
  });
  const session = sessionRow;
  const rows = decisions.data ?? [];

  const levels = useLevels(session?.session_date, underlying);
  const [toggles, setToggles] = useLevelToggles();
  const price = useMemo(() => {
    const main = { name: underlying, color: "--color-text", points: rows.map((r) => ({ t: r.t, value: r.spot })) };
    const lv = levels.data;
    const spots = rows.map((r) => r.spot).filter((v): v is number => v != null);
    if (!lv || spots.length === 0) return [main];
    const lo = Math.min(...spots), hi = Math.max(...spots), near = (hi - lo) * 0.15;
    const inRange = (v: number) => v >= lo - near && v <= hi + near;
    const end = rows[rows.length - 1].t;
    // every level starts at the minute it was knowable (no hindsight lines)
    const flat = mergeLevels(visibleLevels(lv.levels, toggles), lv.tolerancePct).filter((l) => inRange(l.price) && l.from <= end)
      .map((l) => ({ name: l.name, color: l.group === "prior" ? LEVEL_COLORS.prior : LEVEL_COLORS.today, width: 1 as const, dashed: true,
        label: true, points: [{ t: l.from, value: l.price }, { t: end, value: l.price }] }));
    const zones = visibleZones(lv.zones, toggles).filter((z) => inRange(z.lo) && z.from <= end).flatMap((z) => {
      const color = z.kind === "support" ? LEVEL_COLORS.support : LEVEL_COLORS.resistance;
      const until = z.until && z.until < end ? z.until : end;
      const name = `${z.kind === "support" ? "S" : "R"} ${z.touches}×`;
      return [z.lo, z.hi].map((v, i) => ({ name: i === 0 ? name : "", color, width: 1 as const, dashed: true,
        points: [{ t: z.from, value: v }, { t: until, value: v }] }));
    });
    const lines = toggles.lines ? [
      { name: "VWAP", color: LEVEL_COLORS.vwap, width: 1 as const, points: lv.lines.vwap.filter(([t]) => t <= end).map(([t, v]) => ({ t, value: v })) },
      { name: "EMA20", color: LEVEL_COLORS.ema20, width: 1 as const, points: lv.lines.ema20.filter(([t]) => t <= end).map(([t, v]) => ({ t, value: v })) },
    ] : [];
    return [main, ...zones, ...flat, ...lines];
  }, [rows, underlying, levels.data, toggles]);
  // Stored scores use −1 for "not computed" (e.g. the straddle has none): a gap, not a value.
  const score = (v: number | undefined) => (v == null || v < 0 ? null : v);
  const scores = useMemo(() => [
    { name: "CE confirm", color: "#22c55e", points: rows.map((r) => ({ t: r.t, value: score(r.ce_scores?.confirm) })) },
    { name: "PE confirm", color: "#ef4444", points: rows.map((r) => ({ t: r.t, value: score(r.pe_scores?.confirm) })) },
    { name: "CE runner", color: "#86efac", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: score(r.ce_scores?.runner) })) },
    { name: "PE runner", color: "#fca5a5", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: score(r.pe_scores?.runner) })) },
  ], [rows]);
  const hasScores = scores.some((line) => line.points.some((p) => p.value != null));
  // Entries from the strategy's orders; exits from the positions (they include the executor's stops
  // and a straddle's combined target or stop, which no strategy order announces).
  const markers = useMemo(() => [...markersFromOrders(rows, false),
    ...exitMarkers(positions.data ?? [], underlying, strategy)], [rows, positions.data, underlying, strategy]);
  const changes = useMemo(() => rows.filter((r, i) => i === 0 || r.ce_stage !== rows[i - 1].ce_stage || r.pe_stage !== rows[i - 1].pe_stage), [rows]);

  return (
    <div className="space-y-4">
      <Panel
        title={<>Session {id} {session && <>· {session.session_date} · {session.mode} · net <Pnl value={session.summary?.net} /></>}</>}
        right={
          <div className="flex flex-wrap gap-1">
            {strategyIds.length > 1 && strategyIds.map((sid) => (
              <button key={sid} onClick={() => setChosenStrategy(sid)}
                className={`rounded px-3 py-1 text-xs ${sid === strategy ? "bg-accent text-black" : "border border-line"}`}>{sid}</button>
            ))}
            {["NIFTY", "BANKNIFTY", "SENSEX"].map((u) => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`rounded px-3 py-1 text-sm ${u === underlying ? "bg-accent text-black" : "border border-line"}`}>{u}</button>
            ))}
          </div>
        }
      >
        {decisions.isLoading ? <Loading what="decisions" /> : decisions.error ? <ErrorNote error={decisions.error} /> :
          rows.length === 0 ? <p className="text-sm text-muted">No decisions for {underlying}.</p> : (
            <>
              <div className="mb-2"><LevelTogglesBar toggles={toggles} onChange={setToggles} /></div>
              <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={price} markers={markers} height={300} />
              {hasScores ? (
                <>
                  <div className="mt-3 text-xs text-muted">Confirm and runner scores (0–100) over the day</div>
                  <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={scores} height={180} />
                </>
              ) : (
                <p className="mt-3 text-xs text-muted">{strategy} has no confirm or runner scores; its conditions are in the stage changes below.</p>
              )}
            </>
          )}
      </Panel>

      <Panel title="Trades">
        <TradesTable rows={positions.data ?? []} showStrategy={strategyIds.length > 1} empty="No trades." />
      </Panel>

      <Panel title="Stage changes">
        <Table rows={changes} columns={[
          { key: "t", label: "Time", render: (r) => r.t },
          { key: "s", label: "Spot", align: "right", render: (r) => fixed(r.spot) },
          { key: "ce", label: "CE", render: (r) => <StageBadge stage={r.ce_stage} /> },
          { key: "pe", label: "PE", render: (r) => <StageBadge stage={r.pe_stage} /> },
          { key: "o", label: "Orders", render: (r) => <span className="num text-xs">{r.orders ?? ""}</span> },
        ]} />
      </Panel>


      <Panel title="Orders (last state of each)">
        <Table rows={orders.data ?? []} empty="No orders." columns={[
          { key: "id", label: "Client id", render: (r) => <span className="num text-xs">{r.client_order_id}</span> },
          { key: "r", label: "Role", render: (r) => r.role },
          { key: "sd", label: "Side", render: (r) => r.order_side },
          { key: "ty", label: "Type", render: (r) => r.order_type },
          { key: "sy", label: "Contract", render: (r) => r.symbol },
          { key: "q", label: "Qty", align: "right", render: (r) => r.quantity },
          { key: "l", label: "Limit", align: "right", render: (r) => fixed(r.limit_price) },
          { key: "tr", label: "Trigger", align: "right", render: (r) => fixed(r.trigger_price) },
          { key: "sn", label: "Sent", render: (r) => r.sent_at?.slice(11) },
          { key: "st", label: "Status", render: (r) => r.status ?? "–" },
          { key: "f", label: "Filled", align: "right", render: (r) => r.filled ?? 0 },
          { key: "a", label: "Avg", align: "right", render: (r) => fixed(r.average_price) },
        ]} />
      </Panel>
    </div>
  );
}
