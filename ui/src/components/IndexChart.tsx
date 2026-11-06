import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  CandlestickSeries, ColorType, createChart, createSeriesMarkers, CrosshairMode, HistogramSeries, type IChartApi,
  type ISeriesApi, type ISeriesMarkersPluginApi, LineSeries, type SeriesMarker, type Time, type UTCTimestamp,
} from "lightweight-charts";
import { get } from "../api";
import { ErrorNote } from "./ui";

/** GET /api/index-bars: per session the index minutes [time, o, h, l, c, futures volume] and the live PAPER trades. */
interface IndexBars {
  underlying: string;
  days: { date: string; source: "ticks" | "bars"; bars: [string, number, number, number, number, number][] }[];
  trades: { date: string; strategy: string; side: string; symbol: string; opened: string; closed: string | null; net: number }[];
}

interface Bar { key: string; time: UTCTimestamp; open: number; high: number; low: number; close: number; vol: number; tp: number }

type Overlay = "vwap" | "ema9" | "ema20" | "trades";
const OVERLAYS: { key: Overlay; label: string; color: string }[] = [
  { key: "vwap", label: "VWAP", color: "#db2777" }, { key: "ema9", label: "EMA9", color: "#0ea5e9" },
  { key: "ema20", label: "EMA20", color: "#6d28d9" }, { key: "trades", label: "Trades", color: "#16a34a" },
];
const STORE = "index-chart-options";
const UP = "#16a34a", DOWN = "#dc2626";

/** IST wall time on the chart's UTC axis, as the app's other charts. */
const ts = (dateTime: string) => (Date.parse(`${dateTime}:00Z`) / 1000) as UTCTimestamp;

function aggregate(data: IndexBars, tf: number): Bar[] {
  const out: Bar[] = [];
  for (const day of data.days) {
    for (const [t, o, h, l, c, v] of day.bars) {
      const mins = Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5)) - (9 * 60 + 15);
      const slot = Math.floor(mins / tf) * tf + 9 * 60 + 15;
      const key = `${day.date}T${String(Math.floor(slot / 60)).padStart(2, "0")}:${String(slot % 60).padStart(2, "0")}`;
      const last = out[out.length - 1];
      const tp = ((h + l + c) / 3) * v;
      if (last && last.key === key) {
        last.high = Math.max(last.high, h); last.low = Math.min(last.low, l); last.close = c; last.vol += v; last.tp += tp;
      } else {
        out.push({ key, time: ts(key), open: o, high: h, low: l, close: c, vol: v, tp });
      }
    }
  }
  return out;
}

/** Per-session EMA of closes (reset each day, as the strategies' features) and VWAP from the futures volume. */
function ema(bars: Bar[], n: number) {
  const k = 2 / (n + 1);
  let e: number | null = null, day = "";
  return bars.map((b) => {
    if (b.key.slice(0, 10) !== day) { day = b.key.slice(0, 10); e = null; }
    e = e == null ? b.close : b.close * k + e * (1 - k);
    return { time: b.time, value: e };
  });
}
function vwap(bars: Bar[]) {
  let pv = 0, v = 0, day = "";
  return bars.map((b) => {
    if (b.key.slice(0, 10) !== day) { day = b.key.slice(0, 10); pv = 0; v = 0; }
    pv += b.tp; v += b.vol;
    return { time: b.time, value: v > 0 ? pv / v : b.close };
  });
}

const price = (p: number) => p.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const volume = (v: number) => (v >= 1e5 ? `${(v / 1e5).toFixed(1)} L` : v >= 1e3 ? `${Math.round(v / 1e3)} K` : String(Math.round(v)));
const lakhs = (v: number) => `${v >= 0 ? "+" : "−"}₹${(Math.abs(v) / 1e5).toFixed(2)} L`;

function loadOptions(): { tf: number; days: number; on: Record<Overlay, boolean> } {
  const fallback = { tf: 3, days: 3, on: { vwap: true, ema9: true, ema20: true, trades: true } };
  try {
    const kept = JSON.parse(localStorage.getItem(STORE) ?? "null");
    return kept ? { ...fallback, ...kept, on: { ...fallback.on, ...(kept.on ?? {}) } } : fallback;
  } catch {
    return fallback;
  }
}

/**
 * The index pages' main chart (6 Nov 2026, user: "I want this zoomable expandable chart", a TradingView screenshot):
 * candles over the last 1, 3 or 5 sessions as one continuous series (nights closed up), 1/3/5/15-minute bars, futures
 * volume in its own pane, VWAP / EMA9 / EMA20 per session, the live PAPER account's trades; scroll to zoom, drag to pan,
 * Fit, and Expand to full screen (Esc leaves). The bar under the crosshair is read out in the legend.
 */
export function IndexChart({ underlying, until, live }: { underlying: string; until: string; live: boolean }) {
  const [opts, setOpts] = useState(loadOptions);
  const [full, setFull] = useState(false);
  const [legend, setLegend] = useState<Bar | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<{ candle: ISeriesApi<"Candlestick">; vol: ISeriesApi<"Histogram">; vwap: ISeriesApi<"Line">;
    ema9: ISeriesApi<"Line">; ema20: ISeriesApi<"Line">; marks: ISeriesMarkersPluginApi<Time> } | null>(null);
  const bars = useRef<Bar[]>([]);
  const fitted = useRef("");

  const q = useQuery({
    queryKey: ["index-bars", underlying, opts.days, until],
    queryFn: () => get<IndexBars>(`/api/index-bars?underlying=${underlying}&days=${opts.days}&until=${until}`),
    refetchInterval: live ? 30_000 : false,
  });

  const save = (next: typeof opts) => {
    setOpts(next);
    try { localStorage.setItem(STORE, JSON.stringify(next)); } catch { /* private window: options last for the page */ }
  };

  useEffect(() => {
    if (!box.current) return;
    const style = getComputedStyle(document.documentElement);
    const muted = style.getPropertyValue("--color-muted").trim() || "#59636e";
    const line = style.getPropertyValue("--color-line").trim() || "#d8dee4";
    const c = createChart(box.current, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: "transparent" }, textColor: muted, fontSize: 11, panes: { separatorColor: line } },
      grid: { vertLines: { color: "#eef1f4" }, horzLines: { color: "#eef1f4" } },
      rightPriceScale: { borderColor: line, scaleMargins: { top: 0.08, bottom: 0.04 } },
      timeScale: {
        borderColor: line, timeVisible: true, secondsVisible: false, rightOffset: 6, barSpacing: 7,
        tickMarkFormatter: (t: Time, type: number) => {
          const d = new Date((t as number) * 1000);
          return type <= 2 ? d.toLocaleDateString("en-IN", { day: "numeric", month: "short", timeZone: "UTC" }) : d.toISOString().slice(11, 16);
        },
      },
      localization: {
        timeFormatter: (t: Time) => {
          const d = new Date((t as number) * 1000);
          return `${d.toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" })}  ${d.toISOString().slice(11, 16)}`;
        },
      },
      crosshair: { mode: CrosshairMode.Normal },
      handleScale: { mouseWheel: true, pinch: true, axisPressedMouseMove: true },
      handleScroll: { mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true },
    });
    const candle = c.addSeries(CandlestickSeries, {
      upColor: UP, downColor: DOWN, borderVisible: false, wickUpColor: UP, wickDownColor: DOWN,
      priceFormat: { type: "custom", minMove: 0.05, formatter: price },
    });
    const overlay = (color: string, width: 1 | 2) => c.addSeries(LineSeries, {
      color, lineWidth: width, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
    });
    const vw = overlay("#db2777", 2), e9 = overlay("#0ea5e9", 1), e20 = overlay("#6d28d9", 1);
    const vol = c.addSeries(HistogramSeries, { priceFormat: { type: "custom", minMove: 1, formatter: volume }, priceLineVisible: false }, 1);
    c.panes()[1]?.setHeight(100);
    series.current = { candle, vol, vwap: vw, ema9: e9, ema20: e20, marks: createSeriesMarkers(candle, []) };
    c.subscribeCrosshairMove((p) => {
      const t = p?.time as number | undefined;
      setLegend(t == null ? null : bars.current.find((b) => b.time === t) ?? null);
    });
    chart.current = c;
    return () => { c.remove(); chart.current = null; series.current = null; };
  }, []);

  useEffect(() => {
    const s = series.current, c = chart.current;
    if (!s || !c || !q.data) return;
    const b = aggregate(q.data, opts.tf);
    bars.current = b;
    s.candle.setData(b.map(({ time, open, high, low, close }) => ({ time, open, high, low, close })));
    s.vol.setData(b.map((x) => ({ time: x.time, value: x.vol, color: x.close >= x.open ? "rgba(22,163,74,.45)" : "rgba(220,38,38,.45)" })));
    s.vwap.setData(opts.on.vwap ? vwap(b) : []);
    s.ema9.setData(opts.on.ema9 ? ema(b, 9) : []);
    s.ema20.setData(opts.on.ema20 ? ema(b, 20) : []);
    const snap = (key: string) => b.reduce<UTCTimestamp | null>((a, x) => (x.time <= ts(key) ? x.time : a), null);
    const marks: SeriesMarker<Time>[] = [];
    if (opts.on.trades) {
      for (const tr of q.data.trades) {
        const from = snap(`${tr.date}T${tr.opened}`);
        if (from != null) {
          marks.push({ time: from, position: tr.side === "PE" ? "aboveBar" : "belowBar", color: tr.side === "PE" ? DOWN : UP,
            shape: tr.side === "PE" ? "arrowDown" : "arrowUp", text: `${tr.strategy} ${tr.side}`, size: 1.3 });
        }
        const to = tr.closed ? snap(`${tr.date}T${tr.closed}`) : null;
        if (to != null) {
          marks.push({ time: to, position: tr.side === "PE" ? "belowBar" : "aboveBar", color: tr.net >= 0 ? UP : DOWN,
            shape: "square", text: lakhs(tr.net) });
        }
      }
    }
    s.marks.setMarkers(marks.sort((x, y) => (x.time as number) - (y.time as number)));
    // fit once per index, day range and bar size; a live refresh keeps the user's zoom
    const shape = `${underlying}|${opts.days}|${opts.tf}|${until}`;
    if (fitted.current !== shape) { c.timeScale().fitContent(); fitted.current = shape; }
  }, [q.data, opts, underlying, until]);

  useEffect(() => {
    if (!full) return;
    const close = (e: KeyboardEvent) => { if (e.key === "Escape") setFull(false); };
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [full]);

  const shown = legend ?? bars.current[bars.current.length - 1] ?? null;
  const i = shown ? bars.current.indexOf(shown) : -1;
  const prev = i > 0 ? bars.current[i - 1].close : shown?.open ?? 0;
  const change = shown ? shown.close - prev : 0;
  const tone = change >= 0 ? "text-up" : "text-down";
  const fromBars = (q.data?.days ?? []).filter((d) => d.source === "bars").map((d) => d.date);
  const seg = (on: boolean) => `px-2.5 py-1 text-xs ${on ? "bg-panel-2 font-semibold" : "text-muted hover:text-text"}`;

  return (
    <section className={full ? "fixed inset-0 z-50 flex flex-col bg-panel" : "min-w-0 rounded-lg border border-line bg-panel shadow-sm"}>
      <header className="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2">
        <h2 className="text-sm font-semibold">{underlying}</h2>
        <span className="text-xs text-muted">index · volume = near-month futures</span>
        <span className="inline-flex overflow-hidden rounded-md border border-line">
          {[1, 3, 5, 15].map((m) => <button key={m} className={seg(opts.tf === m)} onClick={() => save({ ...opts, tf: m })}>{m}m</button>)}
        </span>
        <span className="inline-flex overflow-hidden rounded-md border border-line">
          {[1, 3, 5].map((d) => <button key={d} className={seg(opts.days === d)} onClick={() => save({ ...opts, days: d })}>{d}D</button>)}
        </span>
        {OVERLAYS.map((o) => (
          <button key={o.key} onClick={() => save({ ...opts, on: { ...opts.on, [o.key]: !opts.on[o.key] } })}
            className={`inline-flex items-center gap-1.5 rounded-md border border-line px-2 py-1 text-xs ${opts.on[o.key] ? "" : "opacity-40"}`}>
            <span className="inline-block h-0.5 w-3 rounded" style={{ background: o.color }} />{o.label}
          </button>
        ))}
        <span className="ml-auto flex gap-2">
          <button className="rounded-md border border-line px-2.5 py-1 text-xs" onClick={() => chart.current?.timeScale().fitContent()}>Fit</button>
          <button className="rounded-md border border-line px-2.5 py-1 text-xs" onClick={() => setFull(!full)}>{full ? "✕ Close" : "⤢ Expand"}</button>
        </span>
      </header>
      <div className={full ? "relative min-h-0 flex-1" : "relative h-[560px]"}>
        {shown && (
          <div className="pointer-events-none absolute left-3 top-2 z-10 flex flex-wrap items-baseline gap-x-3 rounded bg-panel/85 px-2 py-1 text-xs">
            <b>{underlying} · {opts.tf}m</b>
            <span className="num">O <b className={tone}>{price(shown.open)}</b></span>
            <span className="num">H <b className={tone}>{price(shown.high)}</b></span>
            <span className="num">L <b className={tone}>{price(shown.low)}</b></span>
            <span className="num">C <b className={tone}>{price(shown.close)}</b></span>
            <span className={`num ${tone}`}>{change >= 0 ? "+" : ""}{price(change)} ({change >= 0 ? "+" : ""}{prev ? ((100 * change) / prev).toFixed(2) : "0.00"}%)</span>
            <span className="num text-muted">Vol {volume(shown.vol)}</span>
          </div>
        )}
        <div ref={box} className="absolute inset-0" />
        {q.error && <div className="absolute inset-x-3 top-12 z-10"><ErrorNote error={q.error} /></div>}
        {q.data && q.data.days.length === 0 && <p className="absolute left-3 top-12 text-sm text-muted">No minute bars for {underlying} up to {until}.</p>}
      </div>
      <footer className="flex flex-wrap justify-between gap-2 border-t border-line px-3 py-1.5 text-xs text-muted">
        <span>Scroll to zoom · drag to pan · drag the price axis to stretch · Fit shows all{full ? " · Esc closes" : ""}</span>
        <span>{fromBars.length > 0 ? `stored one-minute bars on ${fromBars.join(", ")} · ` : ""}{live ? "updates every 30 s" : "finished days"}</span>
      </footer>
    </section>
  );
}
