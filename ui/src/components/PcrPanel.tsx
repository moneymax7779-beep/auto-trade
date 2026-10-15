import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { ErrorNote, Panel } from "./ui";

/** /api/pcr: [time, pcr, put OI, call OI] per minute, nearest expiry, OI carried forward. */
interface PcrData { date: string; underlying: string; expiry?: string | null; contracts?: number; deferred?: boolean; series: [string, number, number, number][] }

const num = (v: number) => v.toLocaleString("en-IN");

/** Put/call OI ratio through the day (display only): above 1 more put OI than call OI (writers below the market). */
export function PcrPanel({ underlying, date, live }: { underlying: string; date?: string; live?: boolean }) {
  const q = useQuery({
    queryKey: ["pcr", underlying, date ?? "today"],
    queryFn: () => get<PcrData>(`/api/pcr?underlying=${underlying}${date ? `&date=${date}` : ""}`),
    refetchInterval: live ? 60_000 : false,
  });
  if (q.error) return <ErrorNote error={q.error} />;
  const d = q.data;
  if (!d || d.series.length === 0) {
    return <Panel title={`PCR · ${underlying}`}><p className="text-sm text-muted">{q.isLoading ? "Loading…"
      : d?.deferred ? "An earlier day's PCR is computed after the close (15:50 IST), so it never slows the live session."
      : "No option OI recorded for this day."}</p></Panel>;
  }
  const s = d.series;
  const last = s[s.length - 1], first = s[0];
  const values = s.map((r) => r[1]);
  const lo = Math.min(...values, 1), hi = Math.max(...values, 1);
  const W = 640, H = 120, pad = 4;
  const x = (i: number) => pad + (i / Math.max(1, s.length - 1)) * (W - 2 * pad);
  const y = (v: number) => H - pad - ((v - lo) / Math.max(1e-9, hi - lo)) * (H - 2 * pad);
  const path = s.map((r, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(r[1]).toFixed(1)}`).join(" ");
  return (
    <Panel title={`PCR · ${underlying}`} right={<span className="text-xs text-muted">expiry {d.expiry} · {d.contracts} contracts · OI carried forward</span>}>
      <div className="flex flex-wrap items-baseline gap-6 text-sm">
        <span>now <b className="num text-lg">{last[1].toFixed(2)}</b> <span className="text-muted">at {last[0].slice(0, 5)}</span></span>
        <span className="text-muted">open {first[1].toFixed(2)} · low {Math.min(...values).toFixed(2)} · high {Math.max(...values).toFixed(2)}</span>
        <span className="text-muted num">put OI {num(last[2])} · call OI {num(last[3])}</span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} className="mt-2 h-28 w-full" role="img" aria-label={`PCR of ${underlying} through the day`}>
        {lo <= 1 && hi >= 1 && <line x1={pad} x2={W - pad} y1={y(1)} y2={y(1)} stroke="currentColor" strokeOpacity={0.25} strokeDasharray="4 4" />}
        <path d={path} fill="none" stroke="#6366f1" strokeWidth={1.8} />
      </svg>
      <p className="text-xs text-muted">Put OI ÷ call OI over the recorded strikes around the money (dashed line = 1). Context only; no strategy reads it.</p>
    </Panel>
  );
}
