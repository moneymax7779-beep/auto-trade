import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { fixed, get, type DecisionRow, type OrderRow, type SessionRow, type TradePositionRow } from "../api";
import { TimelineChart } from "../components/TimelineChart";
import { ErrorNote, Loading, Panel, Pnl, StageBadge, Table } from "../components/ui";
import { markersFromOrders } from "./markers";

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
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  const decisions = useQuery({
    queryKey: ["decisions", id, underlying],
    queryFn: () => get<DecisionRow[]>(`/api/sessions/${id}/decisions?underlying=${underlying}`),
  });
  const orders = useQuery({ queryKey: ["orders", id], queryFn: () => get<OrderRow[]>(`/api/sessions/${id}/orders`) });
  const positions = useQuery({
    queryKey: ["positions", id],
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${id}/positions`),
  });
  const session = sessions.data?.find((s) => String(s.id) === id);
  const rows = decisions.data ?? [];

  const price = useMemo(() => [{ name: underlying, color: "--color-text", points: rows.map((r) => ({ t: r.t, value: r.spot })) }], [rows, underlying]);
  const scores = useMemo(() => [
    { name: "CE confirm", color: "#22c55e", points: rows.map((r) => ({ t: r.t, value: r.ce_scores?.confirm ?? null })) },
    { name: "PE confirm", color: "#ef4444", points: rows.map((r) => ({ t: r.t, value: r.pe_scores?.confirm ?? null })) },
    { name: "CE runner", color: "#86efac", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: r.ce_scores?.runner ?? null })) },
    { name: "PE runner", color: "#fca5a5", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: r.pe_scores?.runner ?? null })) },
  ], [rows]);
  const markers = useMemo(() => markersFromOrders(rows), [rows]);
  const changes = useMemo(() => rows.filter((r, i) => i === 0 || r.ce_stage !== rows[i - 1].ce_stage || r.pe_stage !== rows[i - 1].pe_stage), [rows]);

  return (
    <div className="space-y-4">
      <Panel
        title={<>Session {id} {session && <>· {session.session_date} · {session.mode} · net <Pnl value={session.summary?.net} /></>}</>}
        right={
          <div className="flex gap-1">
            {["NIFTY", "SENSEX"].map((u) => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`rounded px-3 py-1 text-sm ${u === underlying ? "bg-accent text-black" : "border border-line"}`}>{u}</button>
            ))}
          </div>
        }
      >
        {decisions.isLoading ? <Loading what="decisions" /> : decisions.error ? <ErrorNote error={decisions.error} /> :
          rows.length === 0 ? <p className="text-sm text-muted">No decisions for {underlying}.</p> : (
            <>
              <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={price} markers={markers} height={300} />
              <div className="mt-3 text-xs text-muted">Confirm and runner scores (0–100) over the day</div>
              <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={scores} height={180} />
            </>
          )}
      </Panel>

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="Stage changes">
          <Table rows={changes} columns={[
            { key: "t", label: "Time", render: (r) => r.t },
            { key: "s", label: "Spot", align: "right", render: (r) => fixed(r.spot) },
            { key: "ce", label: "CE", render: (r) => <StageBadge stage={r.ce_stage} /> },
            { key: "pe", label: "PE", render: (r) => <StageBadge stage={r.pe_stage} /> },
            { key: "o", label: "Orders", render: (r) => <span className="num text-xs">{r.orders ?? ""}</span> },
          ]} />
        </Panel>
        <Panel title="Positions">
          <Table rows={positions.data ?? []} empty="No positions." columns={[
            { key: "u", label: "Index", render: (r) => r.underlying },
            { key: "s", label: "Contract", render: (r) => r.symbol },
            { key: "st", label: "Stages", render: (r) => r.stages.join(" → ") },
            { key: "o", label: "Opened", render: (r) => r.opened_at?.slice(11) },
            { key: "c", label: "Closed", render: (r) => r.closed_at?.slice(11) ?? "–" },
            { key: "x", label: "Exit", render: (r) => r.exit_reason ?? "–" },
            { key: "k", label: "Costs", align: "right", render: (r) => fixed(r.costs, 0) },
            { key: "n", label: "Net", align: "right", render: (r) => <Pnl value={r.net} /> },
          ]} />
        </Panel>
      </div>

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
