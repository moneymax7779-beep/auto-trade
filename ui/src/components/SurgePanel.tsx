import { Fragment, useEffect, useId, useMemo, useRef, useState, type PointerEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { ErrorNote, Loading, Panel } from "./ui";
import {
  LEVEL_COLORS, LevelTogglesBar, mergeLevels, tagName, useLevels, useLevelToggles, visibleLevels, visibleZones,
  type LevelToggles, type LevelsData,
} from "./levels";
import { presetView, useChartView, useLinkedTime, type View } from "./chartView";

/** One option side's flow around a surge: ATM ± 2 total OI change and the ATM mid before / after. */
interface SideFlow { oi: number; pct: number; mid0: number; mid1: number; bid: number; ask: number; label: string; score: number }
type Flow = "bullish" | "bearish" | "mixed";
interface Surge {
  t: string; vol: number; x: number; spot: number | null; atm?: number; flow: Flow;
  futures?: { oi: number; pct: number; px: number; label: string; score: number };
  CE?: SideFlow; PE?: SideFlow;
  /** Futures quantity in minute t bought at the ask / sold at the bid (estimate; boughtPct of the classified part). */
  aggressor?: { bought: number; sold: number; unclassified: number; boughtPct: number | null };
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
/** A change of direction inside a burst: one directional minute's flow, then the next directional minute's opposite. */
interface Flip { from: Flow; to: Flow; fromT: string; toT: string }
interface Burst { start: string; end: string; surges: Surge[]; peak: number; volume: number; flows: Record<Flow, number>; flips: Flip[] }
function bursts(surges: Surge[]): Burst[] {
  const out: Burst[] = [];
  for (const s of [...surges].sort((a, b) => minutes(a.t) - minutes(b.t))) {
    const last = out[out.length - 1];
    if (last && minutes(s.t) - minutes(last.end) <= 2) {
      last.end = s.t; last.surges.push(s); last.peak = Math.max(last.peak, s.x); last.volume += s.vol; last.flows[s.flow]++;
    } else {
      out.push({ start: s.t, end: s.t, surges: [s], peak: s.x, volume: s.vol, flips: [],
        flows: { bullish: s.flow === "bullish" ? 1 : 0, bearish: s.flow === "bearish" ? 1 : 0, mixed: s.flow === "mixed" ? 1 : 0 } });
    }
  }
  // consecutive minutes read with overlapping t-1 -> t+1 windows: a bearish minute then a bullish one is the turn
  // between them (e.g. put sellers covering into the low, then put holders selling out of it), not a contradiction
  for (const b of out) {
    let previous: Surge | undefined;
    for (const s of b.surges) {
      if (s.flow === "mixed") continue;
      if (previous && previous.flow !== s.flow) b.flips.push({ from: previous.flow, to: s.flow, fromT: previous.t, toT: s.t });
      previous = s;
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
/** Stepping between days from the chart: the neighbouring days with data, and how to open one. */
export interface DayNav { prev?: string; next?: string; go: (day: string) => void }
const shortDay = (d: string) => new Date(`${d}T00:00:00Z`).toLocaleDateString("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });

export function SurgePanel({ live, date, underlying, nav }: { live?: boolean; date?: string; underlying?: string; nav?: DayNav }) {
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
  if (underlying && !r.underlyings.some((u) => u.underlying === underlying)) {
    return <Panel title={`${underlying} · futures surges`}><p className="text-sm text-muted">No {underlying} surge data for {r.date}.</p></Panel>;
  }
  return (
    <div className="space-y-4">
      {r.underlyings.filter((u) => !underlying || u.underlying === underlying).map((u) => (
        <UnderlyingSurgesPanel key={u.underlying} date={r.date!} data={u} live={live} filters={filters} onFilters={update} nav={nav} />
      ))}
    </div>
  );
}

function UnderlyingSurgesPanel({ date, data, live, filters, onFilters, nav }:
  { date: string; data: UnderlyingSurges; live?: boolean; filters: Filters; onFilters: (f: Filters) => void; nav?: DayNav }) {
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
      <SurgeChart date={date} live={live} nav={nav} data={data} shown={shown} groups={groups} selected={selected} onSelect={setSelected}
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

const T = 10, GAP = 10, AXIS = 24;
const FULL_W = 1000;                                  // the viewBox width on a wide screen (scaled to fit)
/** Layout for a width: below FULL_W the chart is drawn 1:1 in pixels so text stays readable on a phone. */
function layout(boxWidth: number | null) {
  const W = boxWidth && boxWidth < FULL_W ? Math.max(280, Math.round(boxWidth)) : FULL_W;
  const narrow = W < 600;
  const L = narrow ? 48 : 60, R = narrow ? 84 : 104;   // the right gutter holds the price tags
  return { W, L, R, PW: W - L - R, narrow };
}
const X0 = minutes("09:15"), X1 = minutes("15:30");
const FULL: View = { a: X0, b: X1 };
const X_CAP = 30;                                     // the volume pane tops out at 30 × normal
const MIN_SPAN = 10;                                  // zoomed in no further than 10 minutes
const SIZES = { S: { price: 200, prem: 70, vol: 70 }, M: { price: 320, prem: 100, vol: 90 }, L: { price: 480, prem: 140, vol: 110 } } as const;
type Size = keyof typeof SIZES;
const SIZE_KEY = "surge-chart-size";
const PRESETS: { label: string; span: number | null }[] = [{ label: "day", span: null }, { label: "2h", span: 120 }, { label: "1h", span: 60 }, { label: "30m", span: 30 }];

interface YRange { lo: number; hi: number }           // a stretched price axis; null = fit the visible minutes
interface Drag { kind: "pan" | "yscale" | "xscale"; x: number; y: number; view: View; yr: YRange; manualY: boolean; moved: boolean; id: number }
interface Pinch { dist: number; mid: number; view: View }
interface Premium { strike: number; expiry: string; CE: [string, number, number][]; PE: [string, number, number][] }
const PREMIUM_KEY = "surge-chart-premium";
const PREMIUM_COLORS = { CE: "#22c55e", PE: "#ef4444" } as const;
const strikeStep = (u: string) => (u === "SENSEX" ? 100 : u === "BANKNIFTY" ? 100 : 50);

function clampView(a: number, b: number): View {
  const span = Math.min(X1 - X0, Math.max(MIN_SPAN, b - a));
  if (a < X0) return { a: X0, b: X0 + span };
  if (a + span > X1) return { a: X1 - span, b: X1 };
  return { a, b: a + span };
}

/** A round grid step giving at most about `n` lines over `range`. */
function niceStep(range: number, n: number) {
  const raw = range / n, p = 10 ** Math.floor(Math.log10(raw));
  return [1, 2, 2.5, 5, 10].map((k) => k * p).find((s) => s >= raw) ?? 10 * p;
}

function loadSize(): Size {
  try { const s = localStorage.getItem(SIZE_KEY); if (s && s in SIZES) return s as Size; } catch { /* default */ }
  return "S";
}

function SurgeChart({ date, live, nav, data, shown, groups, selected, onSelect, levels, toggles }:
  { date: string; live?: boolean; nav?: DayNav; data: UnderlyingSurges; shown: Surge[]; groups: Burst[]; selected: string | null;
    onSelect: (t: string | null) => void; levels?: LevelsData; toggles: LevelToggles }) {
  const svgRef = useRef<SVGSVGElement>(null);
  const clipId = `plot-${useId().replace(/:/g, "")}`;
  const [hover, setHover] = useState<{ m: number; y: number } | null>(null);
  const [view, setView] = useChartView(date, data.underlying, FULL);
  const [linked, setLinked] = useLinkedTime();
  const [showPremium, setShowPremium] = useState(() => { try { return localStorage.getItem(PREMIUM_KEY) !== "false"; } catch { return true; } });
  const [pickedStrike, setPickedStrike] = useState<number | null>(null);
  const pointers = useRef(new Map<number, number>());
  const pinch = useRef<Pinch | null>(null);
  const [manualY, setManualY] = useState<YRange | null>(null);
  const [size, setSizeState] = useState<Size>(loadSize);
  const drag = useRef<Drag | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const [boxWidth, setBoxWidth] = useState<number | null>(null);
  const { W, L, R, PW, narrow } = layout(boxWidth);
  const geo = useRef({ view, y0: 0, y1: 1, priceH: 200, W, L, PW });
  const pts = data.index;
  const prices = useMemo(() => new Map(pts.map(([t, p]) => [minutes(t), p])), [pts]);
  const vols = useMemo(() => new Map((data.volume ?? []).map(([t, v, n]) => [minutes(t), { v, n }])), [data.volume]);
  const byMinute = useMemo(() => new Map(shown.map((s) => [minutes(s.t), s])), [shown]);
  const lineMaps = useMemo(() => ({
    vwap: new Map((levels?.lines.vwap ?? []).map(([t, v]) => [minutes(t), v])),
    ema20: new Map((levels?.lines.ema20 ?? []).map(([t, v]) => [minutes(t), v])),
  }), [levels]);
  const lastMinute = pts.length ? minutes(pts[pts.length - 1][0]) : X1;
  const hasChart = pts.length >= 2;
  // the premium pane's strike: picked, else the selected surge's ATM, else the latest ATM
  const step = strikeStep(data.underlying);
  const selectedSurge = selected ? shown.find((s) => s.t === selected) : undefined;
  const lastSpot = pts.length ? pts[pts.length - 1][1] : null;
  const strike = pickedStrike ?? selectedSurge?.atm ?? (lastSpot != null ? Math.round(lastSpot / step) * step : null);
  const premium = useQuery({
    queryKey: ["premium", date, data.underlying, data.optionsExpiry, strike],
    queryFn: () => get<Premium>(`/api/premium?date=${date}&underlying=${data.underlying}&expiry=${data.optionsExpiry}&strike=${strike}`),
    enabled: showPremium && strike != null && !!data.optionsExpiry,
    refetchInterval: live ? 30_000 : false,
    placeholderData: (prev) => prev,
  });
  const prem = showPremium ? premium.data : undefined;
  const premMaps = useMemo(() => ({
    CE: new Map((prem?.CE ?? []).map(([t, mid]) => [minutes(t), mid])),
    PE: new Map((prem?.PE ?? []).map(([t, mid]) => [minutes(t), mid])),
  }), [prem]);

  const setSize = (s: Size) => { setSizeState(s); try { localStorage.setItem(SIZE_KEY, s); } catch { /* not kept */ } };
  const reset = () => { setView({ a: X0, b: X1 }); setManualY(null); };
  const preset = (span: number | null) => {
    setManualY(null);
    setView(span == null ? { a: X0, b: X1 } : clampView(Math.min(lastMinute, X1) - span + 5, Math.min(lastMinute, X1) + 5));
  };
  const zoomX = (f: number, at?: number) => setView((v) => {
    const m = at ?? (v.a + v.b) / 2;
    return clampView(m - (m - v.a) * f, m + (v.b - m) * f);
  });

  // a surge picked in the table is brought into view
  useEffect(() => {
    if (!selected) return;
    const m = minutes(selected);
    setView((v) => (m >= v.a && m <= v.b ? v : clampView(m - (v.b - v.a) / 2, m + (v.b - v.a) / 2)));
  }, [selected]);

  // pinch (trackpad) or ⌘/ctrl + scroll zooms; a sideways scroll pans; a plain scroll still scrolls the page
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const onWheel = (e: WheelEvent) => {
      const box = svg.getBoundingClientRect();
      const g = geo.current;
      const { W, L, PW } = g;
      const k = W / box.width;                          // the viewBox scales uniformly
      const x = (e.clientX - box.left) * k, y = (e.clientY - box.top) * k;
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault();
        const f = Math.exp(Math.max(-1, Math.min(1, e.deltaY * 0.01)));
        if (x < L && y <= T + g.priceH) {
          const v = g.y1 - ((y - T) / g.priceH) * (g.y1 - g.y0);
          setManualY({ lo: v - (v - g.y0) * f, hi: v + (g.y1 - v) * f });
        } else {
          const m = g.view.a + ((x - L) / PW) * (g.view.b - g.view.a);
          zoomX(f, Math.max(X0, Math.min(X1, m)));
        }
      } else if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        e.preventDefault();
        const shift = (e.deltaX / PW) * (g.view.b - g.view.a);
        setView((v) => clampView(v.a + shift, v.b + shift));
      }
    };
    svg.addEventListener("wheel", onWheel, { passive: false });
    return () => svg.removeEventListener("wheel", onWheel);
  }, [hasChart]);

  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    const observer = new ResizeObserver(([entry]) => setBoxWidth(entry.contentRect.width));
    observer.observe(box);
    return () => observer.disconnect();
  }, [hasChart]);

  if (!hasChart) {
    return <p className="text-sm text-muted">{live ? "No index data yet."
      : `No index data for ${date}: neither auto-trade's own capture (from 28 Sep 2026) nor zt-tiger-v2 has this day's ticks.`}</p>;
  }

  const { price: PRICE_H, prem: PREM_SIZE, vol: VOL_H } = SIZES[size];
  const PREM_H = showPremium ? PREM_SIZE : 0;
  const PY0 = T + PRICE_H + GAP;                       // premium pane top
  const VY0 = PY0 + (PREM_H ? PREM_H + GAP : 0);       // volume pane top
  const H = VY0 + VOL_H + AXIS;
  const { a, b } = view;
  const span = b - a;
  const X = (m: number) => L + ((m - a) / span) * PW;
  const visible = pts.filter(([t]) => { const m = minutes(t); return m >= a && m <= b; });
  const ys = (visible.length ? visible : pts).map((p) => p[1]);
  const pLo = Math.min(...ys), pHi = Math.max(...ys), near = Math.max((pHi - pLo) * 0.15, 1);
  const lv = levels ? mergeLevels(visibleLevels(levels.levels, toggles), levels.tolerancePct).filter((l) => minutes(l.from) <= b) : [];
  const zs = levels ? visibleZones(levels.zones, toggles).filter((z) => minutes(z.from) <= b && (!z.until || minutes(z.until) >= a)) : [];
  // auto-fit: the visible prices, widened a little for levels just outside them
  const inBand = (v: number) => v >= pLo - near && v <= pHi + near;
  const fitted = [pLo, pHi, ...lv.filter((l) => inBand(l.price)).map((l) => l.price),
    ...zs.filter((z) => inBand(z.lo) || inBand(z.hi)).flatMap((z) => [z.lo, z.hi])];
  const fLo = Math.min(...fitted), fHi = Math.max(...fitted), pad = Math.max((fHi - fLo) * 0.06, 1);
  const y0 = manualY?.lo ?? fLo - pad, y1 = manualY?.hi ?? fHi + pad;
  geo.current = { view, y0, y1, priceH: PRICE_H, W, L, PW };
  const Y = (v: number) => T + ((y1 - v) / (y1 - y0)) * PRICE_H;
  const inY = (v: number) => v >= y0 && v <= y1;
  const drawnLevels = lv.filter((l) => inY(l.price));
  const drawnZones = zs.filter((z) => z.hi >= y0 && z.lo <= y1);
  const above = lv.filter((l) => l.price > y1).sort((p, q) => p.price - q.price).slice(0, narrow ? 1 : 2);
  const below = lv.filter((l) => l.price < y0).sort((p, q) => q.price - p.price).slice(0, narrow ? 1 : 2);
  const VY = (x: number) => VY0 + VOL_H - (Math.min(x, X_CAP) / X_CAP) * VOL_H;
  const gridStep = niceStep(y1 - y0, PRICE_H / 40);
  const grid: number[] = [];
  for (let v = Math.ceil(y0 / gridStep) * gridStep; v <= y1; v += gridStep) grid.push(v);
  // premium pane: the strike's call and put mid, scaled to the visible minutes
  const premVisible = (["CE", "PE"] as const).flatMap((k) => (prem?.[k] ?? []).filter(([t]) => { const m = minutes(t); return m >= a && m <= b; }).map((r) => r[1]));
  const pmLo = premVisible.length ? Math.min(...premVisible) : 0, pmHi = premVisible.length ? Math.max(...premVisible) : 1;
  const pmPad = Math.max((pmHi - pmLo) * 0.08, 0.5);
  const PYv = (v: number) => PY0 + ((pmHi + pmPad - v) / (pmHi - pmLo + 2 * pmPad)) * PREM_H;
  const premAt = (k: "CE" | "PE", m: number) => { const map = premMaps[k]; for (let i = m; i >= m - 5; i--) { const v = map.get(i); if (v != null) return v; } return null; };
  const tStep = [1, 2, 5, 10, 15, 30, 60, 120].find((s) => span / s <= (narrow ? 4 : 8)) ?? 120;
  const tOffset = tStep >= 60 ? minutes("09:30") % tStep : 0;   // hourly ticks on the half hour: 09:30, 10:30 …
  const ticks: number[] = [];
  for (let m = Math.ceil((a - tOffset) / tStep) * tStep + tOffset; m <= b; m += tStep) ticks.push(m);
  const barW = Math.max(1, (PW / span) * 0.7);
  const lastShown = visible.length ? visible[visible.length - 1] : pts[pts.length - 1];
  const at = (m: number) => { for (let k = m; k >= m - 5; k--) { const v = prices.get(k); if (v != null) return v; } return null; };

  const toSvg = (e: { clientX: number; clientY: number }) => {
    const box = svgRef.current!.getBoundingClientRect();
    return { x: ((e.clientX - box.left) / box.width) * W, y: ((e.clientY - box.top) / box.height) * H };
  };
  const onPointerDown = (e: PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0) return;
    const p = toSvg(e);
    pointers.current.set(e.pointerId, p.x);
    if (pointers.current.size === 2) {                  // two fingers: pinch to zoom time
      const [x1, x2] = [...pointers.current.values()];
      pinch.current = { dist: Math.max(10, Math.abs(x2 - x1)), mid: a + (((x1 + x2) / 2 - L) / PW) * span, view };
      drag.current = null;
      return;
    }
    const kind = p.x < L && p.y <= T + PRICE_H ? "yscale" : p.y > VY0 + VOL_H ? "xscale" : "pan";
    drag.current = { kind, x: p.x, y: p.y, view, yr: { lo: y0, hi: y1 }, manualY: manualY != null, moved: false, id: e.pointerId };
  };
  const onPointerMove = (e: PointerEvent<SVGSVGElement>) => {
    const p = toSvg(e);
    if (pointers.current.has(e.pointerId)) pointers.current.set(e.pointerId, p.x);
    const pz = pinch.current;
    if (pz && pointers.current.size === 2) {
      const [x1, x2] = [...pointers.current.values()];
      const f = pz.dist / Math.max(10, Math.abs(x2 - x1));
      setView(clampView(pz.mid - (pz.mid - pz.view.a) * f, pz.mid + (pz.view.b - pz.mid) * f));
      setHover(null);
      return;
    }
    const d = drag.current;
    if (d) {
      const dx = p.x - d.x, dy = p.y - d.y;
      if (!d.moved && Math.abs(dx) + Math.abs(dy) > 3) {
        d.moved = true;
        svgRef.current?.setPointerCapture(d.id);         // only once it is a drag, so clicks still reach the markers
      }
      if (d.moved) {
        const s0 = d.view.b - d.view.a, r0 = d.yr.hi - d.yr.lo;
        if (d.kind === "pan") {
          const shift = (-dx / PW) * s0;
          setView(clampView(d.view.a + shift, d.view.b + shift));
          if (d.manualY) {
            const dv = (dy / PRICE_H) * r0;
            setManualY({ lo: d.yr.lo + dv, hi: d.yr.hi + dv });
          }
        } else if (d.kind === "yscale") {                // drag down compresses, up stretches
          const r = r0 * Math.exp(dy / 120), mid = (d.yr.lo + d.yr.hi) / 2;
          setManualY({ lo: mid - r / 2, hi: mid + r / 2 });
        } else {                                         // drag right zooms in about the middle
          const s = s0 * Math.exp(-dx / 200), mid = (d.view.a + d.view.b) / 2;
          setView(clampView(mid - s / 2, mid + s / 2));
        }
        setHover(null);
        return;
      }
    }
    const m = Math.round(a + ((p.x - L) / PW) * span);
    setHover(p.x >= L && p.x <= W - R && m >= X0 && m <= X1 ? { m, y: p.y } : null);
  };
  const onPointerUp = (e: PointerEvent<SVGSVGElement>) => {
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) pinch.current = null;
    drag.current = null;
  };

  const tip = hover == null ? null : (() => {
    const price = at(hover.m);
    // levels and zones known at that minute, nearest above and below
    const known = [
      ...lv.filter((l) => minutes(l.from) <= hover.m).map((l) => ({ name: l.name, lo: l.price, hi: l.price })),
      ...zs.filter((z) => minutes(z.from) <= hover.m && (!z.until || minutes(z.until) > hover.m))
        .map((z) => ({ name: `${z.kind === "support" ? "S" : "R"} ${z.touches}×`, lo: z.lo, hi: z.hi })),
    ];
    const inside = price == null ? [] : known.filter((k) => price >= k.lo - 0.01 && price <= k.hi + 0.01 && k.hi > k.lo);
    const up = price == null ? undefined : known.filter((k) => k.lo > price).sort((p, q) => p.lo - q.lo)[0];
    const down = price == null ? undefined : known.filter((k) => k.hi < price).sort((p, q) => q.hi - p.hi)[0];
    const burst = groups.find((g) => minutes(g.start) <= hover.m && hover.m <= minutes(g.end) && g.flips.length > 0);
    // a burst of several surge minutes: its futures bought / sold summed over those minutes
    const whole = groups.find((g) => minutes(g.start) <= hover.m && hover.m <= minutes(g.end) && g.surges.length > 1);
    const split = whole?.surges.reduce((a, x) => (x.aggressor ? { b: a.b + x.aggressor.bought, s: a.s + x.aggressor.sold } : a), { b: 0, s: 0 });
    const burstSplit = whole && split && split.b + split.s > 0
      ? { start: whole.start, end: whole.end, pct: Math.round((100 * split.b) / (split.b + split.s)) } : null;
    return { m: hover.m, price, vol: vols.get(hover.m), surge: byMinute.get(hover.m), inside, up, down, flips: burst?.flips ?? [], burstSplit,
      vwap: lineMaps.vwap.get(hover.m), ema: lineMaps.ema20.get(hover.m),
      ce: prem ? premAt("CE", hover.m) : null, pe: prem ? premAt("PE", hover.m) : null };
  })();
  const tipLeft = hover == null ? 0 : (X(hover.m) / W) * 100;
  const crossY = hover && hover.y >= T && hover.y <= T + PRICE_H ? hover.y : null;
  const zoomed = span < X1 - X0 || manualY != null;
  // to the previous day's close / the next day's open, keeping the zoom, so the two days read as one strip
  const stepDay = (dir: "prev" | "next") => {
    const target = dir === "prev" ? nav?.prev : nav?.next;
    if (!nav || !target) return;
    presetView(target, data.underlying, dir === "prev" ? { a: X1 - span, b: X1 } : { a: X0, b: X0 + span });
    nav.go(target);
  };

  const btn = (on: boolean) => `rounded px-2 py-0.5 ${on ? "bg-panel-2 font-semibold" : "text-muted hover:text-text"}`;
  return (
    <div>
      <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <div className="flex items-center gap-0.5" role="group" aria-label="Time zoom">
          <button type="button" className={btn(false)} aria-label="Zoom out" onClick={() => zoomX(1.5)}>−</button>
          <button type="button" className={btn(false)} aria-label="Zoom in" onClick={() => zoomX(1 / 1.5)}>+</button>
          {PRESETS.map((p) => (
            <button key={p.label} type="button" className={btn(p.span == null ? span >= X1 - X0 : Math.abs(span - p.span) < 1)}
              onClick={() => preset(p.span)} title={p.span ? `the last ${p.label} of data` : "the whole session"}>{p.label}</button>
          ))}
        </div>
        <button type="button" className={btn(manualY == null)} aria-pressed={manualY == null} onClick={() => setManualY(null)}
          title="fit the price axis to the visible minutes">price auto</button>
        <div className="flex items-center gap-0.5" role="group" aria-label="Chart height">
          <span className="text-muted">height</span>
          {(Object.keys(SIZES) as Size[]).map((s) => (
            <button key={s} type="button" className={btn(size === s)} aria-pressed={size === s} onClick={() => setSize(s)}>{s}</button>
          ))}
        </div>
        <div className="flex items-center gap-0.5" role="group" aria-label="Premium pane">
          <button type="button" className={btn(showPremium)} aria-pressed={showPremium}
            onClick={() => { setShowPremium(!showPremium); try { localStorage.setItem(PREMIUM_KEY, String(!showPremium)); } catch { /* not kept */ } }}
            title="the call and put of one strike, by minute">premium</button>
          {showPremium && strike != null && (
            <>
              <button type="button" className={btn(false)} aria-label="Lower strike" onClick={() => setPickedStrike(strike - step)}>‹</button>
              <span className="num">{nf(strike)}</span>
              <button type="button" className={btn(false)} aria-label="Higher strike" onClick={() => setPickedStrike(strike + step)}>›</button>
              {pickedStrike != null && <button type="button" className="text-accent hover:underline" onClick={() => setPickedStrike(null)}
                title="back to the selected surge's ATM, or the latest ATM">ATM</button>}
            </>
          )}
        </div>
        <button type="button" className={btn(linked)} aria-pressed={linked} onClick={() => setLinked(!linked)}
          title="NIFTY and SENSEX show the same minutes">link time</button>
        {zoomed && <button type="button" className="text-accent hover:underline" onClick={reset}>reset</button>}
        {nav && (nav.prev || nav.next) && (
          <div className="flex items-center gap-0.5" role="group" aria-label="Day">
            <button type="button" className={btn(false)} disabled={!nav.prev} onClick={() => stepDay("prev")}
              title="the previous day, at the same zoom (its close when zoomed in)">‹ {nav.prev ? shortDay(nav.prev) : ""}</button>
            <span className="num font-semibold">{shortDay(date)}</span>
            <button type="button" className={btn(false)} disabled={!nav.next} onClick={() => stepDay("next")}
              title="the next day, at the same zoom (its open when zoomed in)">{nav.next ? shortDay(nav.next) : ""} ›</button>
          </div>
        )}
        <span className="ml-auto hidden text-muted sm:inline">pinch or ⌘/ctrl + scroll to zoom · drag to pan · drag the price or time axis to stretch · double-click resets</span>
        <span className="text-muted sm:hidden">pinch to zoom · drag to pan · double-tap resets</span>
      </div>
      <div ref={boxRef} className="relative overflow-hidden rounded border border-line">
        {/* panned to the open (close): the previous (next) day is one click away */}
        {nav?.prev && a <= X0 + 0.5 && (
          <button type="button" onClick={() => stepDay("prev")}
            className="absolute left-1 top-1/3 z-10 rounded border border-line bg-panel/90 px-1.5 py-1 text-xs shadow hover:border-accent"
            title={`${shortDay(nav.prev)}: its close, at this zoom`}>‹ {shortDay(nav.prev)}</button>
        )}
        {nav?.next && b >= X1 - 0.5 && (
          <button type="button" onClick={() => stepDay("next")}
            className="absolute right-1 top-1/3 z-10 rounded border border-line bg-panel/90 px-1.5 py-1 text-xs shadow hover:border-accent"
            title={`${shortDay(nav.next)}: its open, at this zoom`}>{shortDay(nav.next)} ›</button>
        )}
        <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} className="block w-full select-none" role="img"
          style={{ touchAction: "pan-y", cursor: drag.current?.moved ? "grabbing" : "crosshair" }}
          aria-label={`${data.underlying} index and futures volume by minute with surges`}
          onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}
          onPointerLeave={() => { if (!drag.current?.moved) setHover(null); }} onDoubleClick={reset}>
          <defs>
            <clipPath id={clipId}><rect x={L} y={T} width={PW} height={VY0 + VOL_H - T} /></clipPath>
            <clipPath id={`${clipId}-price`}><rect x={L} y={T} width={PW} height={PRICE_H} /></clipPath>
          </defs>
          {/* axis strips: drag them to stretch */}
          <rect x={0} y={T} width={L} height={PRICE_H} fill="transparent" style={{ cursor: "ns-resize" }} />
          <rect x={L} y={VY0 + VOL_H} width={PW} height={AXIS} fill="transparent" style={{ cursor: "ew-resize" }} />
          {grid.map((v) => (
            <g key={v}>
              <line x1={L} x2={W - R} y1={Y(v)} y2={Y(v)} stroke="var(--color-line)" />
              <text x={L - (narrow ? 4 : 8)} y={Y(v) + 4} textAnchor="end" fontSize={narrow ? 9.5 : 11} fill="var(--color-muted)" className="num">{nf(v, gridStep < 1 ? 1 : 0)}</text>
            </g>
          ))}
          {ticks.map((m) => (
            <g key={m}>
              <line x1={X(m)} x2={X(m)} y1={T} y2={VY0 + VOL_H} stroke="var(--color-line)" strokeDasharray="2 4" />
              <text x={X(m)} y={H - 7} textAnchor="middle" fontSize="11" fill="var(--color-muted)" className="num">{hhmm(m)}</text>
            </g>
          ))}
          <g clipPath={`url(#${clipId})`}>
            {/* bursts: consecutive surge minutes shaded as one */}
            {groups.filter((g) => g.surges.length > 1).map((g) => (
              <rect key={g.start} x={X(minutes(g.start)) - 3} width={X(minutes(g.end)) - X(minutes(g.start)) + 6}
                y={T} height={VY0 + VOL_H - T} fill={g.flips.length ? "var(--color-muted)" : flowColor(dominant(g))} opacity={0.08} />
            ))}
          </g>
          <g clipPath={`url(#${clipId}-price)`}>
            {/* tested zones, from their second touch until broken; named only when zoomed in (the tooltip names them always) */}
            {drawnZones.map((z) => {
              const x0 = X(minutes(z.from)), x1 = z.until ? X(minutes(z.until)) : W - R;
              const top = Y(z.hi), h = Math.max(3, Y(z.lo) - Y(z.hi));
              const c = z.kind === "support" ? LEVEL_COLORS.support : LEVEL_COLORS.resistance;
              return (
                <g key={`${z.kind}-${z.from}-${z.lo}`}>
                  <rect x={x0} width={Math.max(1, x1 - x0)} y={top} height={h} fill={c} opacity={0.14} />
                  {span <= 120 && <text x={Math.max(x0, L) + 3} y={top - 2} fontSize="9" fill={c} opacity={0.9}>
                    {z.kind === "support" ? "support" : "resistance"} {z.touches}×</text>}
                </g>
              );
            })}
            {/* prior-day and today levels, each from the minute it was known */}
            {drawnLevels.map((l) => (
              <line key={`${l.name}-${l.price}`} x1={X(minutes(l.from))} x2={W - R} y1={Y(l.price)} y2={Y(l.price)}
                stroke={l.group === "prior" ? LEVEL_COLORS.prior : LEVEL_COLORS.today} strokeWidth="1" strokeDasharray="5 4" opacity={0.85} />
            ))}
            {toggles.lines && levels && (["vwap", "ema20"] as const).map((k) => {
              const line = levels.lines[k];
              return line.length > 1 && (
                <polyline key={k} fill="none" stroke={LEVEL_COLORS[k]} strokeWidth="1.2" opacity={0.85}
                  points={line.map(([t, v]) => `${X(minutes(t)).toFixed(1)},${Y(v).toFixed(1)}`).join(" ")} />
              );
            })}
            <polyline fill="none" stroke="var(--color-accent)" strokeWidth="1.6"
              points={pts.map((p) => `${X(minutes(p[0])).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(" ")} />
            {shown.filter((s) => s.spot != null && minutes(s.t) >= a - 1 && minutes(s.t) <= b + 1).map((s) => {
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
          </g>

          {/* price-axis tags in the right gutter, nudged apart: levels, VWAP / EMA20 and the last visible price */}
          {nudge([
            ...drawnLevels.map((l) => ({ key: `l-${l.name}`, y: Y(l.price), text: `${tagName(l.name)} ${nf(l.price, 0)}`, full: l.name,
              color: l.group === "prior" ? LEVEL_COLORS.prior : LEVEL_COLORS.today, fill: false })),
            ...(toggles.lines ? (["vwap", "ema20"] as const).flatMap((k) => {
              const v = lineMaps[k].get(minutes(lastShown[0]));
              return v != null && inY(v) ? [{ key: k, y: Y(v), text: `${k === "vwap" ? "VWAP" : "EMA"} ${nf(v, 0)}`, full: k, color: LEVEL_COLORS[k], fill: false }] : [];
            }) : []),
            { key: "last", y: Y(lastShown[1]), text: nf(lastShown[1], 1), full: `${data.underlying} at ${lastShown[0]}`, color: "var(--color-accent)", fill: true },
          ]).map((t) => (
            <g key={t.key}>
              <title>{t.full}</title>
              {t.fill && <rect x={W - R + 2} y={t.y - 12} width={R - 4} height={14} rx={2} fill={t.color} />}
              <text x={W - R + 6} y={t.y - 1.5} fontSize={narrow ? 9 : 10} fill={t.fill ? "var(--color-panel)" : t.color} className="num">{t.text}</text>
            </g>
          ))}
          {above.length > 0 && (
            <text x={L + 4} y={T + 10} fontSize="9" fill="var(--color-muted)">
              ↑ {above.map((l) => `${l.name} ${nf(l.price, 0)}`).join(" · ")}
            </text>
          )}
          {below.length > 0 && (
            <text x={L + 4} y={T + PRICE_H - 4} fontSize="9" fill="var(--color-muted)">
              ↓ {below.map((l) => `${l.name} ${nf(l.price, 0)}`).join(" · ")}
            </text>
          )}
          {/* premium pane: one strike's call and put mid by minute (own capture) */}
          {PREM_H > 0 && (
            <g>
              <line x1={L} x2={W - R} y1={PY0 + PREM_H} y2={PY0 + PREM_H} stroke="var(--color-line)" />
              {prem && premVisible.length > 0 ? (
                <>
                  {[pmLo, pmHi].map((v, i) => (
                    <text key={i} x={L - 8} y={PYv(v) + 4} textAnchor="end" fontSize="10" fill="var(--color-muted)" className="num">{nf(v, v < 100 ? 1 : 0)}</text>
                  ))}
                  <g clipPath={`url(#${clipId})`}>
                    {(["CE", "PE"] as const).map((k) => (
                      <polyline key={k} fill="none" stroke={k === "CE" ? PREMIUM_COLORS.CE : PREMIUM_COLORS.PE} strokeWidth="1.3"
                        points={prem[k].map(([t, v]) => `${X(minutes(t)).toFixed(1)},${PYv(v).toFixed(1)}`).join(" ")} />
                    ))}
                  </g>
                  {nudge((["CE", "PE"] as const).flatMap((k) => {
                    const v = premAt(k, Math.min(b, lastMinute));
                    return v == null ? [] : [{ k, y: PYv(v), v }];
                  })).map(({ k, y, v }) => (
                    <text key={k} x={W - R + 6} y={y + 3} fontSize="10" fill={PREMIUM_COLORS[k]} className="num">{k} {nf(v, 1)}</text>
                  ))}
                  <text x={L + 4} y={PY0 + 10} fontSize="9" fill="var(--color-muted)">{nf(prem.strike)} CE / PE mid · expiry {prem.expiry}</text>
                </>
              ) : (
                <text x={L + 4} y={PY0 + PREM_H / 2} fontSize="10" fill="var(--color-muted)">
                  {premium.isFetching ? "loading premium…" : `no ${strike != null ? nf(strike) + " " : ""}option quotes in the own capture for this day (it began 28 Sep)`}
                </text>
              )}
            </g>
          )}
          {/* bursts whose flow changed direction: named above the volume pane */}
          <g clipPath={`url(#${clipId})`}>
            {groups.filter((g) => g.flips.length > 0).map((g) => (
              <text key={`flip-${g.start}`} x={X(minutes(g.start)) - 3} y={VY0 - 3} fontSize={narrow ? 9 : 10}
                fill="var(--color-text)" stroke="var(--color-panel)" strokeWidth="3" style={{ paintOrder: "stroke" }}>
                ⇄ flow flipped {g.flips.map((f) => `${f.from === "bullish" ? "▲" : "▼"}→${f.to === "bullish" ? "▲" : "▼"}`).join(" ")}
              </text>
            ))}
          </g>
          {/* volume pane: futures volume as a multiple of the minute's normal, surge threshold dashed */}
          <line x1={L} x2={W - R} y1={VY0 + VOL_H} y2={VY0 + VOL_H} stroke="var(--color-line)" />
          {[5, 15, 30].map((x) => (
            <g key={x}>
              <line x1={L} x2={W - R} y1={VY(x)} y2={VY(x)} stroke={x === 5 ? "var(--color-muted)" : "var(--color-line)"}
                strokeDasharray={x === 5 ? "4 3" : "2 4"} opacity={x === 5 ? 0.7 : 1} />
              <text x={L - 8} y={VY(x) + 4} textAnchor="end" fontSize="10" fill="var(--color-muted)" className="num">{x === 30 ? "30×+" : `${x}×`}</text>
            </g>
          ))}
          <g clipPath={`url(#${clipId})`}>
            {[...vols.entries()].map(([m, { v, n }]) => {
              if (n == null || n <= 0 || m < a - 1 || m > b + 1) return null;
              const x = v / n;
              const s = byMinute.get(m);
              return (
                <rect key={m} x={X(m) - barW / 2} width={barW} y={VY(x)} height={VY0 + VOL_H - VY(x)}
                  fill={s ? flowColor(s.flow) : "var(--color-muted)"} opacity={s ? 0.9 : 0.35} />
              );
            })}
          </g>
          <text x={W - R - 4} y={VY0 + 9} textAnchor="end" fontSize="10" fill="var(--color-muted)">futures volume × normal</text>

          {hover != null && (
            <line x1={X(hover.m)} x2={X(hover.m)} y1={T} y2={VY0 + VOL_H} stroke="var(--color-text)" strokeOpacity={0.35} pointerEvents="none" />
          )}
          {crossY != null && (
            <g pointerEvents="none">
              <line x1={L} x2={W - R} y1={crossY} y2={crossY} stroke="var(--color-text)" strokeOpacity={0.25} strokeDasharray="3 3" />
              <rect x={2} y={crossY - 8} width={L - 6} height={16} rx={2} fill="var(--color-text)" />
              <text x={L - 8} y={crossY + 4} textAnchor="end" fontSize="10" fill="var(--color-panel)" className="num">
                {nf(y1 - ((crossY - T) / PRICE_H) * (y1 - y0), 0)}
              </text>
            </g>
          )}
        </svg>
        {tip && (
          <div className="pointer-events-none absolute top-2 z-10 w-72 rounded border border-line bg-panel px-3 py-2 text-xs shadow-lg"
            style={{ left: `calc(${tipLeft}% + ${tipLeft > 55 ? "-18.5rem" : "0.75rem"})` }}>
            <div className="num font-semibold">{hhmm(tip.m)} · {nf(tip.price, 2)}</div>
            {tip.vol && (
              <div className="num text-muted">futures {nf(tip.vol.v)}{tip.vol.n ? ` · ${nf(tip.vol.v / tip.vol.n, 1)}× normal` : ""}</div>
            )}
            {(tip.vwap != null || tip.ema != null) && tip.price != null && (
              <div className="num text-muted">
                {tip.vwap != null && <>VWAP {nf(tip.vwap, 0)} ({signed(tip.price - tip.vwap, 0)})</>}
                {tip.vwap != null && tip.ema != null && " · "}
                {tip.ema != null && <>EMA20 {nf(tip.ema, 0)} ({signed(tip.price - tip.ema, 0)})</>}
              </div>
            )}
            {(tip.ce != null || tip.pe != null) && prem && (
              <div className="num text-muted">{nf(prem.strike)} CE {nf(tip.ce, 1)} · PE {nf(tip.pe, 1)}</div>
            )}
            {tip.price != null && (tip.up || tip.down || tip.inside.length > 0) && (
              <div className="num text-muted">
                {tip.inside.length > 0 && <div>in {tip.inside.map((k) => `${k.name} ${nf(k.lo, 0)}–${nf(k.hi, 0)}`).join(", ")}</div>}
                {tip.up && <div>above: {tip.up.name} {nf(tip.up.lo, 0)} ({signed(tip.up.lo - tip.price, 0)})</div>}
                {tip.down && <div>below: {tip.down.name} {nf(tip.down.hi, 0)} ({signed(tip.down.hi - tip.price, 0)})</div>}
              </div>
            )}
            {tip.surge && (
              <div className="mt-1 space-y-0.5 border-t border-line pt-1">
                <div style={{ color: flowColor(tip.surge.flow) }} className="font-semibold">{tip.surge.flow} surge · {nf(tip.surge.x, 1)}×</div>
                {tip.surge.aggressor && tip.surge.aggressor.boughtPct != null && (
                  <div className="num" title="futures trades at or above the ask count as bought, at or below the bid as sold (estimate)">
                    <span className={tip.surge.aggressor.boughtPct >= 60 ? "text-up" : tip.surge.aggressor.boughtPct <= 40 ? "text-down" : ""}>
                      {tip.surge.aggressor.boughtPct >= 60 ? "buyers hit the ask" : tip.surge.aggressor.boughtPct <= 40 ? "sellers hit the bid" : "two-sided"}
                    </span>{" "}
                    · bought {tip.surge.aggressor.boughtPct} % · sold {100 - tip.surge.aggressor.boughtPct} %
                    <div className="text-muted">{nf(tip.surge.aggressor.bought)} bought · {nf(tip.surge.aggressor.sold)} sold
                      {tip.surge.aggressor.unclassified > 0 && <> · {nf(tip.surge.aggressor.unclassified)} unclear</>}</div>
                  </div>
                )}
                {tip.burstSplit && (
                  <div className="num text-muted">burst {tip.burstSplit.start}–{tip.burstSplit.end}: bought {tip.burstSplit.pct} % · sold {100 - tip.burstSplit.pct} %</div>
                )}
                <div>futures OI {signed(tip.surge.futures?.pct, 2)} % {short(tip.surge.futures?.label)}</div>
                <div>calls {signed(tip.surge.CE?.pct, 1)} % {short(tip.surge.CE?.label)} · puts {signed(tip.surge.PE?.pct, 1)} % {short(tip.surge.PE?.label)}</div>
                <div className="num">from {tip.surge.from ?? "t+1"}: +5 {signed(tip.surge.move5)} · +15 {signed(tip.surge.move15)} · +30 {signed(tip.surge.move30)}</div>
                <div className="num text-muted">within 15 min: best {signed(tip.surge.best15)} · worst {signed(tip.surge.worst15)}</div>
              </div>
            )}
            {tip.flips.length > 0 && (
              <div className="mt-1 border-t border-line pt-1">
                {tip.flips.map((f) => (
                  <div key={f.toT}>⇄ flow flipped: <span style={{ color: flowColor(f.from) }}>{f.from} {f.fromT}</span> →{" "}
                    <span style={{ color: flowColor(f.to) }}>{f.to} {f.toT}</span></div>
                ))}
                <div className="text-muted">windows t−1 → t+1 overlap: the turn falls between the two minutes</div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** Label positions at least 12 px apart (sorted top to bottom). */
function nudge<T extends { y: number }>(items: T[]): T[] {
  const sorted = [...items].sort((a, b) => a.y - b.y);
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].y - sorted[i - 1].y < 12) sorted[i] = { ...sorted[i], y: sorted[i - 1].y + 12 };
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

/** A burst whose flow changed direction (bearish then bullish, or the reverse). */
function FlipChip({ flips }: { flips: Flip[] }) {
  const title = flips.map((f) => `${f.from} ${f.fromT} → ${f.to} ${f.toT}`).join("; ");
  return (
    <span title={title} className="inline-flex items-center gap-1 rounded-full border border-line px-2 py-0.5 text-[11px] font-sans">
      ⇄ <span style={{ color: flowColor(flips[0].from) }}>{flips[0].from === "bullish" ? "bull" : "bear"}</span>→
      <span style={{ color: flowColor(flips[flips.length - 1].to) }}>{flips[flips.length - 1].to === "bullish" ? "bull" : "bear"}</span>
      {flips.length > 1 && <span className="text-muted">×{flips.length}</span>}
    </span>
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
                    <td className={cell}>{g.flips.length > 0 ? <FlipChip flips={g.flips} /> : <FlowChip flow={dominant(g)} />}</td>
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
