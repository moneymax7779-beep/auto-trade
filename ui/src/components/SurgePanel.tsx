import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { ErrorNote, Loading, Panel } from "./ui";

/** One option side's flow around a surge: ATM ± 2 total OI change and the ATM mid before / after. */
interface SideFlow { oi: number; pct: number; mid0: number; mid1: number; bid: number; ask: number; label: string; score: number }
interface Surge {
  t: string; vol: number; x: number; spot: number | null; atm?: number; flow: "bullish" | "bearish" | "mixed";
  futures?: { oi: number; pct: number; px: number; label: string; score: number };
  CE?: SideFlow; PE?: SideFlow; move15: number | null; move30: number | null;
}
interface UnderlyingSurges {
  underlying: string; index: [string, number][]; surges: Surge[]; historyAvailable: boolean;
  futuresExpiry: string | null; optionsExpiry: string | null;
}
interface SurgeReport { date: string | null; underlyings: UnderlyingSurges[] }

const nf = (n: number | null | undefined, d = 0) =>
  n == null ? "–" : n.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const signed = (n: number | null | undefined, d = 1) =>
  n == null ? "–" : `${n > 0 ? "+" : n < 0 ? "−" : ""}${nf(Math.abs(n), d)}`;
const tone = (n: number | null | undefined) => (n == null || n === 0 ? "" : n > 0 ? "text-up" : "text-down");
const minutes = (t: string) => { const [h, m] = t.split(":").map(Number); return h * 60 + m; };
const flowColor = (f: Surge["flow"]) => (f === "bullish" ? "var(--color-up)" : f === "bearish" ? "var(--color-down)" : "var(--color-muted)");

/**
 * Futures-volume surges (≥ 5 × the same minute's normal) with futures and ATM ± 2 options OI flow,
 * refreshed while a session runs. Display only: no strategy uses it.
 */
export function SurgePanel({ live, date }: { live?: boolean; date?: string }) {
  const q = useQuery({
    queryKey: ["surges", date ?? "latest"],
    queryFn: () => get<SurgeReport>(`/api/surges${date ? `?date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
  });
  if (q.isLoading) return <Loading what="futures surges" />;
  if (q.error) return <ErrorNote error={q.error} />;
  const r = q.data!;
  if (!r.date) return null;
  return (
    <div className="space-y-4">
      {r.underlyings.map((u) => <UnderlyingSurgesPanel key={u.underlying} date={r.date!} data={u} live={live} />)}
    </div>
  );
}

function UnderlyingSurgesPanel({ date, data, live }: { date: string; data: UnderlyingSurges; live?: boolean }) {
  const n = (f: Surge["flow"]) => data.surges.filter((s) => s.flow === f).length;
  return (
    <Panel
      title={<>{data.underlying} · futures surges and OI flow · {date}{live && <span className="ml-2 text-xs font-normal text-muted">updates every 30 s; a surge shows once the next minute closes</span>}</>}
      right={<span className="text-xs text-muted num">{data.surges.length} surges · {n("bullish")} bullish · {n("bearish")} bearish · {n("mixed")} mixed</span>}
    >
      {!data.historyAvailable && <p className="mb-2 text-xs text-warn">Volume history from zt-tiger-v2 is unavailable, so surges cannot be ranked.</p>}
      <div className="mb-2 flex flex-wrap gap-4 text-xs text-muted">
        <span><i className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full" style={{ background: "var(--color-up)" }} />bullish flow</span>
        <span><i className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full" style={{ background: "var(--color-down)" }} />bearish flow</span>
        <span><i className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full" style={{ background: "var(--color-muted)" }} />mixed</span>
        <span>marker size = × normal volume</span>
        <span>futures {data.futuresExpiry ?? "–"} · options expiry {data.optionsExpiry ?? "–"}</span>
      </div>
      {data.underlying === "SENSEX" && (
        <p className="mb-2 text-xs text-warn">SENSEX futures trade thinly (hundreds to a few thousand contracts a minute), so its surges rest on small volumes.</p>
      )}
      <SurgeChart data={data} />
      <SurgeTable surges={data.surges} />
      <p className="mt-2 text-xs text-muted">
        Surge: futures volume ≥ 5 × the median of the same minute over the previous 10 sessions. Flow compares the last
        quote of minute t−1 with minute t+1: futures OI ≥ 0.2 %; calls / puts: the ATM ± 2 strikes' OI ≥ 1 %, read with
        the ATM option's mid. OI shows positions opening or closing, not who bought or sold. The 17-day test of trading
        this signal lost money (docs/studies/2026-09-29-surge-oi-flow.md).
      </p>
    </Panel>
  );
}

function SurgeChart({ data }: { data: UnderlyingSurges }) {
  const pts = data.index;
  if (pts.length < 2) return <p className="text-sm text-muted">No index data yet.</p>;
  const W = 1000, H = 300, L = 62, R = 14, T = 14, B = 30;
  const x0 = minutes("09:15"), x1 = minutes("15:30");
  const ys = pts.map((p) => p[1]);
  const lo = Math.min(...ys), hi = Math.max(...ys), pad = Math.max((hi - lo) * 0.08, 1);
  const y0 = lo - pad, y1 = hi + pad;
  const X = (m: number) => L + ((m - x0) / (x1 - x0)) * (W - L - R);
  const Y = (v: number) => T + ((y1 - v) / (y1 - y0)) * (H - T - B);
  const step = data.underlying === "NIFTY" ? 50 : 200;
  const grid: number[] = [];
  for (let v = Math.ceil(y0 / step) * step; v <= y1; v += step) grid.push(v);
  return (
    <div className="overflow-x-auto rounded border border-line">
      <svg viewBox={`0 0 ${W} ${H}`} className="block w-full min-w-[640px]" role="img" aria-label={`${data.underlying} index by minute with futures surges`}>
        {grid.map((v) => (
          <g key={v}>
            <line x1={L} x2={W - R} y1={Y(v)} y2={Y(v)} stroke="var(--color-line)" />
            <text x={L - 8} y={Y(v) + 4} textAnchor="end" fontSize="11" fill="var(--color-muted)" className="num">{nf(v)}</text>
          </g>
        ))}
        {["09:30", "10:30", "11:30", "12:30", "13:30", "14:30", "15:30"].map((t) => (
          <g key={t}>
            <line x1={X(minutes(t))} x2={X(minutes(t))} y1={T} y2={H - B} stroke="var(--color-line)" strokeDasharray="2 4" />
            <text x={X(minutes(t))} y={H - 8} textAnchor="middle" fontSize="11" fill="var(--color-muted)" className="num">{t}</text>
          </g>
        ))}
        <polyline fill="none" stroke="var(--color-accent)" strokeWidth="1.6"
          points={pts.map((p) => `${X(minutes(p[0])).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(" ")} />
        {data.surges.filter((s) => s.spot != null).map((s) => {
          const r = Math.min(14, 3 + Math.sqrt(s.x) * 1.4);
          return (
            <circle key={s.t} cx={X(minutes(s.t))} cy={Y(s.spot!)} r={r} fill={flowColor(s.flow)} fillOpacity={0.55} stroke={flowColor(s.flow)}>
              <title>{`${s.t} · ${nf(s.x, 1)}× · ${s.flow}`}</title>
            </circle>
          );
        })}
      </svg>
    </div>
  );
}

function SideCell({ side }: { side?: SideFlow }) {
  if (!side) return <td className="px-2 py-1.5 text-right text-muted">–</td>;
  return (
    <td className="px-2 py-1.5 text-right">
      <span className={tone(side.score)}>{signed(side.pct, 1)} %</span> {side.label}
      <div className="num text-xs text-muted">ATM mid {nf(side.mid0, 2)} → {nf(side.mid1, 2)} · {nf(side.bid, 2)}/{nf(side.ask, 2)}</div>
    </td>
  );
}

function SurgeTable({ surges }: { surges: Surge[] }) {
  if (surges.length === 0) return <p className="mt-2 text-sm text-muted">No surge minutes yet today.</p>;
  return (
    <div className="mt-3 max-h-[28rem] overflow-auto rounded border border-line">
      <table className="w-full text-sm">
        <thead className="sticky top-0 whitespace-nowrap bg-panel text-xs uppercase text-muted">
          <tr>
            <th className="px-2 py-1.5 text-left">Minute</th><th className="px-2 py-1.5 text-right">Futures vol</th>
            <th className="px-2 py-1.5 text-right">× normal</th><th className="px-2 py-1.5 text-right">Index</th>
            <th className="px-2 py-1.5 text-right">Futures OI (t−1→t+1)</th><th className="px-2 py-1.5 text-right">Calls ATM±2 OI</th>
            <th className="px-2 py-1.5 text-right">Puts ATM±2 OI</th><th className="px-2 py-1.5 text-left">Flow</th>
            <th className="px-2 py-1.5 text-right">+15 min</th><th className="px-2 py-1.5 text-right">+30 min</th>
          </tr>
        </thead>
        <tbody className="num">
          {surges.map((s) => (
            <tr key={s.t} className="border-t border-line whitespace-nowrap">
              <td className="px-2 py-1.5">{s.t}</td>
              <td className="px-2 py-1.5 text-right">{nf(s.vol)}</td>
              <td className="px-2 py-1.5 text-right">{nf(s.x, 1)}×</td>
              <td className="px-2 py-1.5 text-right">{nf(s.spot, 2)}</td>
              <td className="px-2 py-1.5 text-right">
                {s.futures ? (<>
                  <span className={tone(s.futures.score)}>{signed(s.futures.pct, 2)} %</span> {s.futures.label}
                  <div className="text-xs text-muted">{signed(s.futures.oi, 0)} · px {signed(s.futures.px, 1)}</div>
                </>) : <span className="text-muted">–</span>}
              </td>
              <SideCell side={s.CE} />
              <SideCell side={s.PE} />
              <td className="px-2 py-1.5">
                <span className="rounded-full border px-2 py-0.5 text-xs" style={{ color: flowColor(s.flow), borderColor: flowColor(s.flow) }}>{s.flow}</span>
              </td>
              <td className={`px-2 py-1.5 text-right ${tone(s.move15)}`}>{signed(s.move15, 1)}</td>
              <td className={`px-2 py-1.5 text-right ${tone(s.move30)}`}>{signed(s.move30, 1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
