import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { fixed, get, type EpisodeRow, type FrameRow, type RunRow } from "../api";
import { TimelineChart } from "../components/TimelineChart";
import { ErrorNote, Loading, Panel, Pnl, Stat, Table } from "../components/ui";
import { markersFromOrders } from "./markers";

export function ResearchPage() {
  const runs = useQuery({ queryKey: ["runs"], queryFn: () => get<RunRow[]>("/api/runs") });
  if (runs.isLoading) return <Loading what="research runs" />;
  if (runs.error) return <ErrorNote error={runs.error} />;
  return (
    <Panel title="Research runs">
      <Table rows={runs.data!} columns={[
        { key: "id", label: "#", render: (r) => r.kind === "LIFECYCLE"
            ? <Link className="text-accent underline" to={`/research/${r.id}`}>{r.id}</Link> : r.id },
        { key: "k", label: "Kind", render: (r) => r.kind },
        { key: "st", label: "Status", render: (r) => r.status },
        { key: "s", label: "Sessions", render: (r) => <span className="num text-xs">{r.sessions}</span> },
        { key: "u", label: "Indices", render: (r) => r.underlyings },
        { key: "c", label: "Strategy / configs", render: (r) => <span className="num text-xs">{Object.keys(r.config ?? {})[0] ?? ""}</span> },
        { key: "n", label: "Notes", render: (r) => <span className="num text-xs">{JSON.stringify(r.notes)}</span> },
        { key: "cv", label: "Code", render: (r) => <span className="num text-xs">{r.code_version}</span> },
        { key: "t", label: "Started", render: (r) => r.started_at },
      ]} />
    </Panel>
  );
}

export function RunDetailPage() {
  const { id } = useParams();
  const episodes = useQuery({ queryKey: ["episodes", id], queryFn: () => get<EpisodeRow[]>(`/api/runs/${id}/episodes`) });
  const runs = useQuery({ queryKey: ["runs"], queryFn: () => get<RunRow[]>("/api/runs") });
  const run = runs.data?.find((r) => String(r.id) === id);
  const sessions = useMemo(() => (run?.sessions ?? "").replace(/[{}]/g, "").split(",").filter(Boolean), [run]);
  const [session, setSession] = useState<string | null>(null);
  const [underlying, setUnderlying] = useState("NIFTY");
  const chosen = session ?? sessions[sessions.length - 1] ?? null;
  const frames = useQuery({
    queryKey: ["frames", id, chosen, underlying],
    enabled: chosen != null,
    queryFn: () => get<FrameRow[]>(`/api/runs/${id}/frames?session=${chosen}&underlying=${underlying}`),
  });

  const lanes = useMemo(() => {
    const byLane = new Map<string, EpisodeRow[]>();
    (episodes.data ?? []).forEach((e) => byLane.set(e.lane, [...(byLane.get(e.lane) ?? []), e]));
    return [...byLane.entries()];
  }, [episodes.data]);
  const rows = frames.data ?? [];
  const price = useMemo(() => [{ name: underlying, color: "--color-text", points: rows.map((r) => ({ t: r.t, value: r.spot })) }], [rows, underlying]);
  const states = useMemo(() => [
    { name: "Direction", color: "#60a5fa", points: rows.map((r) => ({ t: r.t, value: r.direction })) },
    { name: "Structure", color: "#a78bfa", points: rows.map((r) => ({ t: r.t, value: r.structure })) },
    { name: "Participation", color: "#f59e0b", width: 1, points: rows.map((r) => ({ t: r.t, value: r.participation })) },
    { name: "Continuation", color: "#22c55e", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: r.continuation })) },
  ], [rows]);
  const markers = useMemo(() => markersFromOrders(rows), [rows]);

  return (
    <div className="space-y-4">
      <Panel title={`Run ${id} · lifecycle replay`}>
        {episodes.isLoading ? <Loading what="episodes" /> : episodes.error ? <ErrorNote error={episodes.error} /> : (
          <div className="grid gap-4 sm:grid-cols-2">
            {lanes.map(([lane, list]) => {
              const net = list.reduce((a, e) => a + e.net, 0);
              const rs = list.map((e) => e.r_multiple).filter((r): r is number => r != null);
              const wins = list.filter((e) => e.net > 0).length;
              return (
                <div key={lane} className="rounded border border-line p-3">
                  <div className="mb-2 text-sm font-semibold">{lane} fills</div>
                  <div className="grid grid-cols-4 gap-3">
                    <Stat label="Episodes" value={list.length} />
                    <Stat label="Wins" value={`${wins}/${list.length}`} />
                    <Stat label="Net" value={<Pnl value={net} />} />
                    <Stat label="Avg R" value={rs.length ? (rs.reduce((a, b) => a + b, 0) / rs.length).toFixed(2) : "–"} />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Panel>

      <Panel title="Episodes (traded campaigns, net of costs)">
        <Table rows={episodes.data ?? []} columns={[
          { key: "l", label: "Lane", render: (e) => e.lane },
          { key: "d", label: "Session", render: (e) => e.session_date },
          { key: "u", label: "Index", render: (e) => `${e.underlying} ${e.side}` },
          { key: "s", label: "Contract", render: (e) => e.symbol },
          { key: "st", label: "Stages", render: (e) => e.stages.join(" → ") },
          { key: "o", label: "Opened", render: (e) => e.opened_at?.slice(11) ?? "–" },
          { key: "c", label: "Closed", render: (e) => e.closed_at?.slice(11) ?? "–" },
          { key: "x", label: "Exit", render: (e) => e.exit_reason },
          { key: "n", label: "Net", align: "right", render: (e) => <Pnl value={e.net} /> },
          { key: "r", label: "R", align: "right", render: (e) => fixed(e.r_multiple) },
          { key: "mfe", label: "MFE/unit", align: "right", render: (e) => fixed(e.mfe_per_unit) },
        ]} />
      </Panel>

      <Panel
        title="Minute frames"
        right={
          <div className="flex flex-wrap gap-1">
            {sessions.map((d) => (
              <button key={d} onClick={() => setSession(d)}
                className={`rounded px-2 py-1 text-xs ${d === chosen ? "bg-accent text-black" : "border border-line"}`}>{d.slice(5)}</button>
            ))}
            {["NIFTY", "SENSEX"].map((u) => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`ml-1 rounded px-2 py-1 text-xs ${u === underlying ? "bg-accent text-black" : "border border-line"}`}>{u}</button>
            ))}
          </div>
        }
      >
        {chosen == null ? <p className="text-sm text-muted">No sessions.</p> : frames.isLoading ? <Loading what="frames" /> :
          rows.length === 0 ? <p className="text-sm text-muted">No frames for {underlying} on {chosen}.</p> : (
            <>
              <TimelineChart date={chosen} lines={price} markers={markers} height={280} />
              <div className="mt-3 text-xs text-muted">Direction, structure, continuation (−100…+100) and participation (0…100)</div>
              <TimelineChart date={chosen} lines={states} height={200} />
            </>
          )}
      </Panel>
    </div>
  );
}
