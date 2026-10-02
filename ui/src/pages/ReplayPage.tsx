import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { get } from "../api";
import { TimelineChart } from "../components/TimelineChart";
import { ErrorNote, Loading, Panel } from "../components/ui";

interface SnapshotSession { session_date: string; underlying: string; snapshots: number; run_id: number }
interface SnapshotPoint { t: string; phase: string; spot: number | null; or_high: number | null; or_low: number | null; vwap: number | null; ema20: number | null }
interface SnapshotAt { t: string; phase: string; spot: number; features: Record<string, unknown> }

/** Scrub through a saved feature run minute by minute (bin/autotrade features --save). */
export function ReplayPage() {
  const sessions = useQuery({ queryKey: ["snapshot-sessions"], queryFn: () => get<SnapshotSession[]>("/api/snapshots/sessions") });
  const [choice, setChoice] = useState<string | null>(null);
  const selected = sessions.data?.find((s) => `${s.session_date}|${s.underlying}` === choice) ?? sessions.data?.[0];
  const points = useQuery({
    queryKey: ["snapshots", selected?.session_date, selected?.underlying],
    enabled: selected != null,
    queryFn: () => get<SnapshotPoint[]>(`/api/snapshots?session=${selected!.session_date}&underlying=${selected!.underlying}`),
  });
  const [index, setIndex] = useState(0);
  useEffect(() => setIndex(0), [selected?.session_date, selected?.underlying]);
  const minute = points.data?.[index]?.t;
  const at = useQuery({
    queryKey: ["snapshot-at", selected?.session_date, selected?.underlying, minute],
    enabled: minute != null,
    queryFn: () => get<SnapshotAt>(`/api/snapshots/at?session=${selected!.session_date}&underlying=${selected!.underlying}&time=${minute}`),
  });
  const lines = useMemo(() => {
    const rows = points.data ?? [];
    return [
      { name: "Spot", color: "--color-text", points: rows.map((r) => ({ t: r.t, value: r.spot })) },
      { name: "ORH", color: "#22c55e", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: r.or_high })) },
      { name: "ORL", color: "#ef4444", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: r.or_low })) },
      // the feature as strategies read it: futures VWAP − the latest tick basis (noisy on SENSEX)
      { name: "VWAP proxy", color: "#ec4899", width: 1, points: rows.map((r) => ({ t: r.t, value: r.vwap })) },
      { name: "EMA20", color: "#a78bfa", width: 1, points: rows.map((r) => ({ t: r.t, value: r.ema20 })) },
    ];
  }, [points.data]);
  const markers = useMemo(() => (minute ? [{ t: minute, text: minute, tone: "info" as const }] : []), [minute]);
  const sections = useMemo(() => {
    const grouped = new Map<string, [string, unknown][]>();
    Object.entries(at.data?.features ?? {}).forEach(([key, value]) => {
      const [section, ...rest] = key.split(".");
      const name = rest.length ? rest.join(".") : section;
      const group = rest.length ? section : "snapshot";
      grouped.set(group, [...(grouped.get(group) ?? []), [name, value]]);
    });
    return [...grouped.entries()];
  }, [at.data]);

  if (sessions.isLoading) return <Loading what="saved feature runs" />;
  if (sessions.error) return <ErrorNote error={sessions.error} />;
  if (!sessions.data?.length) {
    return <Panel title="Replay"><p className="text-sm text-muted">No saved feature snapshots. Create them with <code className="num">bin/autotrade features --session YYYY-MM-DD --save</code>.</p></Panel>;
  }
  const count = points.data?.length ?? 0;
  return (
    <div className="space-y-4">
      <Panel
        title="Replay: features minute by minute"
        right={
          <select className="rounded border border-line bg-panel-2 px-2 py-1 text-sm" value={`${selected?.session_date}|${selected?.underlying}`}
            onChange={(e) => setChoice(e.target.value)}>
            {sessions.data.map((s) => (
              <option key={`${s.session_date}|${s.underlying}`} value={`${s.session_date}|${s.underlying}`}>
                {s.session_date} {s.underlying} ({s.snapshots})
              </option>
            ))}
          </select>
        }
      >
        {points.isLoading ? <Loading what="snapshots" /> : (
          <>
            <TimelineChart date={selected!.session_date} lines={lines} markers={markers} height={280} />
            <div className="mt-3 flex items-center gap-3">
              <button className="rounded border border-line px-2 py-1 text-sm" onClick={() => setIndex(Math.max(0, index - 1))}>◀</button>
              <input type="range" min={0} max={Math.max(0, count - 1)} value={index} className="flex-1"
                onChange={(e) => setIndex(Number(e.target.value))} aria-label="minute" />
              <button className="rounded border border-line px-2 py-1 text-sm" onClick={() => setIndex(Math.min(count - 1, index + 1))}>▶</button>
              <span className="num w-28 text-right text-sm">{minute ?? "–"} IST</span>
            </div>
          </>
        )}
      </Panel>
      {at.data && (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {sections.map(([section, entries]) => (
            <Panel key={section} title={section}>
              <dl className="grid grid-cols-[minmax(7rem,1fr)_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs">
                {entries.map(([name, value]) => (
                  <div key={name} className="contents">
                    <dt className="truncate text-muted" title={name}>{name}</dt>
                    <dd className="num truncate text-right" title={String(value ?? "")}>{format(value)}</dd>
                  </div>
                ))}
              </dl>
            </Panel>
          ))}
        </div>
      )}
    </div>
  );
}

function format(value: unknown): string {
  if (value == null) return "–";
  if (typeof value === "number") return Math.abs(value) >= 1000 ? value.toFixed(1) : value.toFixed(4).replace(/\.?0+$/, "");
  const text = String(value);
  return text.startsWith("sha256:") ? text.slice(0, 19) : text;
}
