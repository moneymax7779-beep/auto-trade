import { Fragment, useMemo, useRef, useState, type MouseEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { ErrorNote, Loading, Panel } from "./ui";
import {
  LEVEL_COLORS, LevelTogglesBar, mergeLevels, useLevels, useLevelToggles, visibleLevels, visibleZones,
  type LevelToggles, type LevelsData,
} from "./levels";

/** One option side's flow around a surge: ATM ± 2 total OI change and the ATM mid before / after. */
interface SideFlow { oi: number; pct: number; mid0: number; mid1: number; bid: number; ask: number; label: string; score: number }
type Flow = "bullish" | "bearish" | "mixed";
interface Surge {
  t: string; vol: number; x: number; spot: number | null; atm?: number; flow: Flow;
  futures?: { oi: number; pct: number; px: number; label: string; score: number };
  CE?: SideFlow; PE?: SideFlow;
  /** Index points after the flow is known (from the close of t+1): +5 / +15 / +30 min, best / worst close within 15 min. */
  from?: string; move5?: number | null; move15: number | null; move30: number | null; best15?: number | null; worst15?: number | null;
}
interface UnderlyingSurges {
  underlying: string; index: [string, number][]; volume?: [string, number, number | null][]; surges: Surge[];
  historyAvailable: boolean; futuresExpiry: string | null; optionsExpiry: string | null;
}
interface SurgeReport { date: string | null; underlyings: UnderlyingSurges[] }

interface Filters { flows: Flow[]; minX: number }
const FILTER_KEY = "surge-panel-filters";
const DEFAULT_FILTERS: Filters = { flows: ["bullish", "bearish", "mixed"], minX: 5 };
const THRESHOLDS = [5, 10, 20];
const FLOWS: Flow[] = ["bullish", "bearish", "mixed"];

const nf = (n: number | null | undefined, d = 0) =>
  n == null ? "–" : n.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const signed = (n: number | null | undefined, d = 1) =>
  n == null ? "–" : `${n > 0 ? "+" : n < 0 ? "−" : ""}${nf(Math.abs(n), d)}`;
const tone = (n: number | null | undefined) => (n == null || n === 0 ? "" : n > 0 ? "text-up" : "text-down");
const minutes = (t: string) => { const [h, m] = t.split(":").map(Number); return h * 60 + m; };
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const flowColor = (f: Flow) => (f === "bullish" ? "var(--color-up)" : f === "bearish" ? "var(--color-down)" : "var(--color-muted)");
/** Short labels so one minute fits one table row. */
const SHORT: Record<string, string> = {
  "short covering": "short cover", "long unwinding": "long unwind", "long build": "long build",
  "short build": "short build", writing: "writing", buying: "buying", flat: "flat",
};
const short = (label?: string) => (label ? SHORT[label] ?? label : "–");

function loadFilters(): Filters {
  try {
    const raw = localStorage.getItem(FILTER_KEY);
    if (raw) {
      const f = JSON.parse(raw) as Filters;
      if (Array.isArray(f.flows) && typeof f.minX === "number") return f;
    }
  } catch { /* private window or blocked storage: defaults */ }
  return DEFAULT_FILTERS;
}
function saveFilters(f: Filters) {
  try { localStorage.setItem(FILTER_KEY, JSON.stringify(f)); } catch { /* not persisted */ }
}

/** Consecutive surge minutes (gaps of at most 2 minutes) read as one burst. */
interface Burst { start: string; end: string; surges: Surge[]; peak: number; volume: number; flows: Record<Flow, number> }
function bursts(surges: Surge[]): Burst[] {
  const out: Burst[] = [];
  for (const s of [...surges].sort((a, b) => minutes(a.t) - minutes(b.t))) {
    const last = out[out.length - 1];
    if (last && minutes(s.t) - minutes(last.end) <= 2) {
      last.end = s.t; last.surges.push(s); last.peak = Math.max(last.peak, s.x); last.volume += s.vol; last.flows[s.flow]++;
    } else {
      out.push({ start: s.t, end: s.t, surges: [s], peak: s.x, volume: s.vol,
        flows: { bullish: s.flow === "bullish" ? 1 : 0, bearish: s.flow === "bearish" ? 1 : 0, mixed: s.flow === "mixed" ? 1 : 0 } });
    }
  }
  return out;
}
const dominant = (b: Burst): Flow =>
  b.flows.bullish > b.flows.bearish && b.flows.bullish >= b.flows.mixed ? "bullish"
    : b.flows.bearish > b.flows.bullish && b.flows.bearish >= b.flows.mixed ? "bearish" : "mixed";

/**
 * Futures-volume surges (≥ 5 × the same minute's normal) with futures and ATM ± 2 options OI flow, refreshed
 * while a session runs. Display only: no strategy uses it.
 */
export function SurgePanel({ live, date }: { live?: boolean; date?: string }) {
  const q = useQuery({
    queryKey: ["surges", date ?? "latest"],
    queryFn: () => get<SurgeReport>(`/api/surges${date ? `?date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
  });
  const [filters, setFilters] = useState<Filters>(loadFilters);
  const update = (f: Filters) => { setFilters(f); saveFilters(f); };
  if (q.isLoading) return <Loading what="futures surges" />;
  if (q.error) return <ErrorNote error={q.error} />;
  const r = q.data!;
  if (!r.date) return null;
  return (
    <div className="space-y-4">
      {r.underlyings.map((u) => (
        <UnderlyingSurgesPanel key={u.underlying} date={r.date!} data={u} live={live} filters={filters} onFilters={update} />
      ))}
    </div>
  );
}

function UnderlyingSurgesPanel({ date, data, live, filters, onFilters }:
  { date: string; data: UnderlyingSurges; live?: boolean; filters: Filters; onFilters: (f: Filters) => void }) {
  const shown = useMemo(
    () => data.surges.filter((s) => filters.flows.includes(s.flow) && s.x >= filters.minX),
    [data.surges, filters],
  );
  const groups = useMemo(() => bursts(shown), [shown]);
  const [selected, setSelected] = useState<string | null>(null);
  const levels = useLevels(date, data.underlying, live);
  const [toggles, setToggles] = useLevelToggles();
  const count = (f: Flow) => data.surges.filter((s) => s.flow === f).length;
  return (
    <Panel
      title={<>{data.underlying} · futures surges and OI flow · {date}
        {live && <span className="ml-2 text-xs font-normal text-muted">updates every 30 s; a surge shows once the next minute closes</span>}</>}
      right={<span className="text-xs text-muted num">{data.surges.length} surges · {count("bullish")} bullish · {count("bearish")} bearish · {count("mixed")} mixed</span>}
    >
      {!data.historyAvailable && <p className="mb-2 text-xs text-warn">Volume history from zt-tiger-v2 is unavailable, so surges cannot be ranked.</p>}
      <Toolbar filters={filters} onFilters={onFilters} data={data} />
      <div className="mb-2"><LevelTogglesBar toggles={toggles} onChange={setToggles} /></div>
      <HitRate surges={data.surges} />
      {data.underlying === "SENSEX" && (
        <p className="mb-2 text-xs text-warn">SENSEX futures trade thinly (hundreds to a few thousand contracts a minute), so its surges rest on small volumes.</p>
      )}
      <SurgeChart data={data} shown={shown} groups={groups} selected={selected} onSelect={setSelected}
        levels={levels.data} toggles={toggles} />
      <BurstTable groups={groups} index={data.index} live={live} selected={selected} onSelect={setSelected} />
      <p className="mt-2 text-xs text-muted">
        Surge: futures volume ≥ 5 × the median of the same minute over the previous 10 sessions. Flow compares the last
        quote of minute t−1 with minute t+1: futures OI ≥ 0.2 %; calls / puts: the ATM ± 2 strikes' OI ≥ 1 %, read with
        the ATM option's mid. Index moves are measured from the close of t+1, when the flow is first known. OI shows
        positions opening or closing, not who bought or sold. Trading this signal lost
        money over 18 sessions (docs/studies/2026-09-29-surge-oi-flow.md); today's hit rate is a description, not evidence.
      </p>
    </Panel>
  );
}

function Toolbar({ filters, onFilters, data }: { filters: Filters; onFilters: (f: Filters) => void; data: UnderlyingSurges }) {
  const toggle = (f: Flow) => {
    const flows = filters.flows.includes(f) ? filters.flows.filter((x) => x !== f) : [...filters.flows, f];
    onFilters({ ...filters, flows: flows.length ? flows : [f] });
  };
  return (
    <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs">
      <div className="flex items-center gap-1" role="group" aria-label="Flow filter">
        {FLOWS.map((f) => {
          const on = filters.flows.includes(f);
          return (
            <button key={f} type="button" aria-pressed={on} onClick={() => toggle(f)}
              className={`flex items-center gap-1.5 rounded-full border px-2.5 py-1 ${on ? "border-line bg-panel-2" : "border-transparent text-muted opacity-60"}`}>
              <i className="inline-block h-2.5 w-2.5 rounded-full"
                style={f === "mixed" ? { border: `1.5px solid ${flowColor(f)}` } : { background: flowColor(f) }} />
              {f}
            </button>
          );
        })}
      </div>
      <div className="flex items-center gap-1" role="group" aria-label="Minimum volume multiple">
        <span className="text-muted">min</span>
        {THRESHOLDS.map((x) => (
          <button key={x} type="button" aria-pressed={filters.minX === x} onClick={() => onFilters({ ...filters, minX: x })}
            className={`rounded px-2 py-1 num ${filters.minX === x ? "bg-panel-2 font-semibold" : "text-muted"}`}>≥{x}×</button>
        ))}
      </div>
      <span className="text-muted">futures {data.futuresExpiry ?? "–"} · options {data.optionsExpiry ?? "–"}</span>
    </div>
  );
}

/** Today's description: after bullish (bearish) flow, was the index higher (lower) 15 minutes later? */
function HitRate({ surges }: { surges: Surge[] }) {
  const rate = (f: Flow) => {
    const v = surges.filter((s) => s.flow === f && s.move15 != null);
    const right = v.filter((s) => (f === "bullish" ? s.move15! > 0 : s.move15! < 0)).length;
    return { n: v.length, right };
  };
  const b = rate("bullish"), r = rate("bearish");
  if (b.n + r.n === 0) return null;
  return (
    <p className="mb-2 text-xs text-muted">
      Today, index 15 min after the flow was known (close of t+1): bullish flow right <span className="num text-text">{b.right}/{b.n}</span>
      {" · "}bearish flow right <span className="num text-text">{r.right}/{r.n}</span>
    </p>
  );
}

const W = 1000, L = 60, R = 14, T = 10, PRICE_H = 200, GAP = 10, VOL_H = 70, AXIS = 24;
const H = T + PRICE_H + GAP + VOL_H + AXIS;
const X0 = minutes("09:15"), X1 = minutes("15:30");
const X_CAP = 30;                                     // the volume pane tops out at 30 × normal

function SurgeChart({ data, shown, groups, selected, onSelect, levels, toggles }:
  { data: UnderlyingSurges; shown: Surge[]; groups: Burst[]; selected: string | null; onSelect: (t: string | null) => void;
    levels?: LevelsData; toggles: LevelToggles }) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const pts = data.index;
  const prices = useMemo(() => new Map(pts.map(([t, p]) => [minutes(t), p])), [pts]);
  const vols = useMemo(() => new Map((data.volume ?? []).map(([t, v, n]) => [minutes(t), { v, n }])), [data.volume]);
  const byMinute = useMemo(() => new Map(shown.map((s) => [minutes(s.t), s])), [shown]);
  if (pts.length < 2) return <p className="text-sm text-muted">No index data yet.</p>;

  const X = (m: number) => L + ((m - X0) / (X1 - X0)) * (W - L - R);
  const ys = pts.map((p) => p[1]);
  const pLo = Math.min(...ys), pHi = Math.max(...ys), near = (pHi - pLo) * 0.15;
  // levels near the day's range widen it a little; far ones (e.g. a PDH 300 points away) stay off the chart
  const lv = levels ? mergeLevels(visibleLevels(levels.levels, toggles), levels.tolerancePct) : [];
  const zs = levels ? visibleZones(levels.zones, toggles) : [];
  const inRange = (v: number) => v >= pLo - near && v <= pHi + near;
  const drawnLevels = lv.filter((l) => inRange(l.price));
  const drawnZones = zs.filter((z) => inRange(z.lo) || inRange(z.hi));
  const all = [pLo, pHi, ...drawnLevels.map((l) => l.price), ...drawnZones.flatMap((z) => [z.lo, z.hi])];
  const lo = Math.min(...all), hi = Math.max(...all), pad = Math.max((hi - lo) * 0.06, 1);
  const y0 = lo - pad, y1 = hi + pad;
  const offChart = lv.filter((l) => !inRange(l.price));
  const Y = (v: number) => T + ((y1 - v) / (y1 - y0)) * PRICE_H;
  const VY0 = T + PRICE_H + GAP;
  const VY = (x: number) => VY0 + VOL_H - (Math.min(x, X_CAP) / X_CAP) * VOL_H;
  const step = data.underlying === "NIFTY" ? 50 : 200;
  const grid: number[] = [];
  for (let v = Math.ceil(y0 / step) * step; v <= y1; v += step) grid.push(v);
  const barW = Math.max(1, ((W - L - R) / (X1 - X0)) * 0.7);

  const onMove = (e: MouseEvent<SVGSVGElement>) => {
    const svg = svgRef.current;
    if (!svg) return;
    const box = svg.getBoundingClientRect();
    const x = ((e.clientX - box.left) / box.width) * W;
    const m = Math.round(X0 + ((x - L) / (W - L - R)) * (X1 - X0));
    setHover(m >= X0 && m <= X1 ? m : null);
  };
  const tip = hover == null ? null : { m: hover, price: prices.get(hover), vol: vols.get(hover), surge: byMinute.get(hover) };
  const tipLeft = hover == null ? 0 : (X(hover) / W) * 100;

  return (
    <div className="relative overflow-x-auto rounded border border-line">
      <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} className="block w-full min-w-[640px]" role="img"
        aria-label={`${data.underlying} index and futures volume by minute with surges`}
        onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
        {/* bursts: consecutive surge minutes shaded as one */}
        {groups.filter((g) => g.surges.length > 1).map((g) => (
          <rect key={g.start} x={X(minutes(g.start)) - 3} width={X(minutes(g.end)) - X(minutes(g.start)) + 6}
            y={T} height={PRICE_H + GAP + VOL_H} fill={flowColor(dominant(g))} opacity={0.08} />
        ))}
        {grid.map((v) => (
          <g key={v}>
            <line x1={L} x2={W - R} y1={Y(v)} y2={Y(v)} stroke="var(--color-line)" />
            <text x={L - 8} y={Y(v) + 4} textAnchor="end" fontSize="11" fill="var(--color-muted)" className="num">{nf(v)}</text>
          </g>
        ))}
        {["09:30", "10:30", "11:30", "12:30", "13:30", "14:30", "15:30"].map((t) => (
          <g key={t}>
            <line x1={X(minutes(t))} x2={X(minutes(t))} y1={T} y2={VY0 + VOL_H} stroke="var(--color-line)" strokeDasharray="2 4" />
            <text x={X(minutes(t))} y={H - 7} textAnchor="middle" fontSize="11" fill="var(--color-muted)" className="num">{t}</text>
          </g>
        ))}
        {/* tested zones, from their second touch until broken */}
        {drawnZones.map((z) => {
          const x0 = X(minutes(z.from)), x1 = z.until ? X(minutes(z.until)) : W - R;
          const top = Y(z.hi), h = Math.max(3, Y(z.lo) - Y(z.hi));
          const c = z.kind === "support" ? LEVEL_COLORS.support : LEVEL_COLORS.resistance;
          return (
            <g key={`${z.kind}-${z.from}-${z.lo}`}>
              <rect x={x0} width={Math.max(1, x1 - x0)} y={top} height={h} fill={c} opacity={0.14} />
              <text x={x0 + 3} y={top - 2} fontSize="9" fill={c} opacity={0.9}>{z.kind === "support" ? "S" : "R"} {z.touches}×</text>
            </g>
          );
        })}
        {/* prior-day and today levels, each from the minute it was known */}
        {drawnLevels.map((l) => (
          <line key={`${l.name}-${l.price}`} x1={X(minutes(l.from))} x2={W - R} y1={Y(l.price)} y2={Y(l.price)}
            stroke={l.group === "prior" ? LEVEL_COLORS.prior : LEVEL_COLORS.today} strokeWidth="1" strokeDasharray="5 4" opacity={0.85} />
        ))}
        {toggles.lines && levels && (["vwap", "ema20"] as const).map((k) => {
          const line = levels.lines[k].filter(([, v]) => v >= y0 && v <= y1);
          return line.length > 1 && (
            <polyline key={k} fill="none" stroke={LEVEL_COLORS[k]} strokeWidth="1.1" opacity={0.85}
              points={line.map(([t, v]) => `${X(minutes(t)).toFixed(1)},${Y(v).toFixed(1)}`).join(" ")} />
          );
        })}
        <polyline fill="none" stroke="var(--color-accent)" strokeWidth="1.6"
          points={pts.map((p) => `${X(minutes(p[0])).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(" ")} />
        {shown.filter((s) => s.spot != null).map((s) => {
          const r = Math.min(6.5, 2.5 + Math.sqrt(s.x) * 0.8);
          const on = selected === s.t;
          return s.flow === "mixed" ? (
            <circle key={s.t} cx={X(minutes(s.t))} cy={Y(s.spot!)} r={on ? r + 2 : r} fill="none"
              stroke="var(--color-muted)" strokeWidth={on ? 2.5 : 1.2} opacity={0.8}
              className="cursor-pointer" onClick={() => onSelect(on ? null : s.t)} />
          ) : (
            <circle key={s.t} cx={X(minutes(s.t))} cy={Y(s.spot!)} r={on ? r + 2 : r} fill={flowColor(s.flow)}
              stroke={on ? "var(--color-text)" : "var(--color-panel)"} strokeWidth={on ? 2 : 1}
              className="cursor-pointer" onClick={() => onSelect(on ? null : s.t)} />
          );
        })}

        {/* level labels at the right edge, nudged apart */}
        {nudge(drawnLevels.map((l) => ({ l, y: Y(l.price) }))).map(({ l, y }) => (
          <text key={`label-${l.name}`} x={W - R - 4} y={y - 3} textAnchor="end" fontSize="10"
            fill={l.group === "prior" ? LEVEL_COLORS.prior : LEVEL_COLORS.today}
            stroke="var(--color-panel)" strokeWidth="3" style={{ paintOrder: "stroke" }} className="num">
            {l.name} {nf(l.price, 0)}
          </text>
        ))}
        {offChart.length > 0 && (
          <text x={L + 4} y={T + 10} fontSize="9" fill="var(--color-muted)">
            off chart: {offChart.map((l) => `${l.name} ${nf(l.price, 0)}`).join(" · ")}
          </text>
        )}
        {/* volume pane: futures volume as a multiple of the minute's normal, surge threshold dashed */}
        <line x1={L} x2={W - R} y1={VY0 + VOL_H} y2={VY0 + VOL_H} stroke="var(--color-line)" />
        {[5, 15, 30].map((x) => (
          <g key={x}>
            <line x1={L} x2={W - R} y1={VY(x)} y2={VY(x)} stroke={x === 5 ? "var(--color-warn)" : "var(--color-line)"}
              strokeDasharray={x === 5 ? "4 3" : "2 4"} opacity={x === 5 ? 0.8 : 1} />
            <text x={L - 8} y={VY(x) + 4} textAnchor="end" fontSize="10" fill="var(--color-muted)" className="num">{x === 30 ? "30×+" : `${x}×`}</text>
          </g>
        ))}
        {[...vols.entries()].map(([m, { v, n }]) => {
          if (n == null || n <= 0) return null;
          const x = v / n;
          const s = byMinute.get(m);
          return (
            <rect key={m} x={X(m) - barW / 2} width={barW} y={VY(x)} height={VY0 + VOL_H - VY(x)}
              fill={s ? flowColor(s.flow) : "var(--color-muted)"} opacity={s ? 0.9 : 0.35} />
          );
        })}
        <text x={W - R} y={VY0 + 9} textAnchor="end" fontSize="10" fill="var(--color-muted)">futures volume × normal</text>

        {hover != null && (
          <line x1={X(hover)} x2={X(hover)} y1={T} y2={VY0 + VOL_H} stroke="var(--color-text)" strokeOpacity={0.35} />
        )}
      </svg>
      {tip && (
        <div className="pointer-events-none absolute top-2 z-10 w-60 rounded border border-line bg-panel px-3 py-2 text-xs shadow-lg"
          style={{ left: `calc(${tipLeft}% + ${tipLeft > 60 ? "-15.5rem" : "0.75rem"})` }}>
          <div className="num font-semibold">{hhmm(tip.m)} · {nf(tip.price, 2)}</div>
          {tip.vol && (
            <div className="num text-muted">futures {nf(tip.vol.v)}{tip.vol.n ? ` · ${nf(tip.vol.v / tip.vol.n, 1)}× normal` : ""}</div>
          )}
          {tip.surge && (
            <div className="mt-1 space-y-0.5">
              <div style={{ color: flowColor(tip.surge.flow) }} className="font-semibold">{tip.surge.flow} surge</div>
              <div>futures OI {signed(tip.surge.futures?.pct, 2)} % {short(tip.surge.futures?.label)}</div>
              <div>calls {signed(tip.surge.CE?.pct, 1)} % {short(tip.surge.CE?.label)} · puts {signed(tip.surge.PE?.pct, 1)} % {short(tip.surge.PE?.label)}</div>
              <div className="num">from {tip.surge.from ?? "t+1"}: +5 {signed(tip.surge.move5)} · +15 {signed(tip.surge.move15)} · +30 {signed(tip.surge.move30)}</div>
              <div className="num text-muted">within 15 min: best {signed(tip.surge.best15)} · worst {signed(tip.surge.worst15)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Label positions at least 11 px apart (sorted top to bottom). */
function nudge<T extends { y: number }>(items: T[]): T[] {
  const sorted = [...items].sort((a, b) => a.y - b.y);
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].y - sorted[i - 1].y < 11) sorted[i] = { ...sorted[i], y: sorted[i - 1].y + 11 };
  }
  return sorted;
}

function SideCell({ side }: { side?: SideFlow }) {
  if (!side) return <td className="px-1.5 py-1.5 text-right text-muted">–</td>;
  return (
    <td className="px-1.5 py-1.5 text-right" title={`ATM mid ${nf(side.mid0, 2)} → ${nf(side.mid1, 2)} · bid/ask ${nf(side.bid, 2)}/${nf(side.ask, 2)} · OI ${signed(side.oi, 0)}`}>
      <span className={tone(side.score)}>{signed(side.pct, 1)}%</span> <span className="text-muted">{short(side.label)}</span>
    </td>
  );
}

/** +5 / +15 / +30 and the 15-minute best / worst, all from the close of t+1. */
function Moves({ s, cell }: { s: Surge; cell: string }) {
  return (
    <>
      <td className={`${cell} text-right ${tone(s.move5)}`}>{signed(s.move5)}</td>
      <td className={`${cell} text-right ${tone(s.move15)}`}>{signed(s.move15)}</td>
      <td className={`${cell} text-right ${tone(s.move30)}`}>{signed(s.move30)}</td>
      <td className={`${cell} text-right`}>
        <span className={tone(s.best15)}>{signed(s.best15)}</span><span className="text-muted"> / </span>
        <span className={tone(s.worst15)}>{signed(s.worst15)}</span>
      </td>
    </>
  );
}

function FlowChip({ flow }: { flow: Flow }) {
  return <span className="rounded-full border px-2 py-0.5 text-xs" style={{ color: flowColor(flow), borderColor: flowColor(flow) }}>{flow}</span>;
}

function BurstTable({ groups, index, live, selected, onSelect }:
  { groups: Burst[]; index: [string, number][]; live?: boolean; selected: string | null; onSelect: (t: string | null) => void }) {
  const [open, setOpen] = useState<Set<string>>(new Set());
  const price = useMemo(() => new Map(index.map(([t, p]) => [t, p])), [index]);
  if (groups.length === 0) return <p className="mt-2 text-sm text-muted">No surge minutes match the filters.</p>;
  const ordered = live ? [...groups].reverse() : groups;
  const toggle = (k: string) => setOpen((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n; });
  const cell = "px-1.5 py-1.5";
  return (
    <div className="mt-3 max-h-[30rem] overflow-auto rounded border border-line">
      <table className="w-full text-[13px]">
        <thead className="sticky top-0 z-10 whitespace-nowrap bg-panel text-[11px] uppercase text-muted">
          <tr>
            <th className={`${cell} sticky left-0 bg-panel text-left`}>Minute</th>
            <th className={`${cell} text-right`} title="futures volume in the minute">Fut vol</th>
            <th className={`${cell} text-right`} title="multiple of the same minute's normal volume">×</th>
            <th className={`${cell} text-right`}>Index</th>
            <th className={`${cell} text-right`} title="futures OI change t−1 → t+1">Fut OI</th>
            <th className={`${cell} text-right`} title="calls, ATM ± 2 strikes: OI change and its reading">Calls ±2</th>
            <th className={`${cell} text-right`} title="puts, ATM ± 2 strikes: OI change and its reading">Puts ±2</th>
            <th className={`${cell} text-left`}>Flow</th>
            <th className={`${cell} text-right`} title="index points 5 minutes after the close of t+1, when the flow is known">+5m</th>
            <th className={`${cell} text-right`} title="index points 15 minutes after the close of t+1">+15m</th>
            <th className={`${cell} text-right`} title="index points 30 minutes after the close of t+1">+30m</th>
            <th className={`${cell} text-right`} title="best / worst minute close within 15 minutes of t+1 (index points)">15m range</th>
          </tr>
        </thead>
        <tbody className="num">
          {ordered.map((g) => {
            const multi = g.surges.length > 1;
            const isOpen = open.has(g.start) || !multi || g.surges.some((s) => s.t === selected);
            const last = g.surges[g.surges.length - 1];
            const move = (price.get(g.end) ?? NaN) - (price.get(g.start) ?? NaN);
            const minutesRows = live ? [...g.surges].reverse() : g.surges;
            return (
              <Fragment key={g.start}>
                {multi && (
                  <tr className="cursor-pointer border-t border-line bg-panel-2 whitespace-nowrap" onClick={() => toggle(g.start)}>
                    <td className={`${cell} sticky left-0 bg-panel-2 font-semibold`}>
                      <span className="mr-1 text-muted">{isOpen ? "▾" : "▸"}</span>{g.start}–{g.end}
                      <span className="ml-1 text-[11px] font-normal text-muted">{g.surges.length}m</span>
                    </td>
                    <td className={`${cell} text-right`} title="total futures volume of the burst's surge minutes">Σ {nf(g.volume)}</td>
                    <td className={`${cell} text-right`} title="the burst's highest multiple of normal volume">{nf(g.peak, 1)}×</td>
                    <td className={`${cell} text-right ${tone(move)}`} title="index change over the burst">{Number.isFinite(move) ? `Δ ${signed(move, 1)}` : "–"}</td>
                    <td className={`${cell} text-right text-muted`} colSpan={3}>
                      <span style={{ color: flowColor("bullish") }}>{g.flows.bullish} bullish</span> ·{" "}
                      <span style={{ color: flowColor("bearish") }}>{g.flows.bearish} bearish</span> · {g.flows.mixed} mixed
                    </td>
                    <td className={cell}><FlowChip flow={dominant(g)} /></td>
                    <Moves s={last} cell={cell} />
                  </tr>
                )}
                {isOpen && minutesRows.map((s) => (
                  <tr key={s.t} onClick={() => onSelect(selected === s.t ? null : s.t)}
                    className={`cursor-pointer border-t border-line whitespace-nowrap ${selected === s.t ? "bg-panel-2" : ""}`}>
                    <td className={`${cell} sticky left-0 bg-panel ${multi ? "pl-6" : ""}`}>{s.t}</td>
                    <td className={`${cell} text-right`}>{nf(s.vol)}</td>
                    <td className={`${cell} text-right`}>{nf(s.x, 1)}×</td>
                    <td className={`${cell} text-right`}>{nf(s.spot, 2)}</td>
                    <td className={`${cell} text-right`} title={s.futures ? `OI ${signed(s.futures.oi, 0)} · futures price ${signed(s.futures.px, 1)}` : ""}>
                      {s.futures ? <><span className={tone(s.futures.score)}>{signed(s.futures.pct, 2)}%</span> <span className="text-muted">{short(s.futures.label)}</span></> : <span className="text-muted">–</span>}
                    </td>
                    <SideCell side={s.CE} />
                    <SideCell side={s.PE} />
                    <td className={cell}><FlowChip flow={s.flow} /></td>
                    <Moves s={s} cell={cell} />
                  </tr>
                ))}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
