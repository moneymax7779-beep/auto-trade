import { useEffect, useRef } from "react";
import {
  ColorType,
  createChart,
  createSeriesMarkers,
  HistogramSeries,
  LineSeries,
  type IChartApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts";

export interface LinePoint { t: string; value: number | null }
export interface ChartMarker { t: string; text: string; tone: "up" | "down" | "info" }
/**
 * One trade drawn on the price chart: an entry arrow ("#3 MAC"), an exit square coloured by the result ("#3 +₹86.1K")
 * and a thick segment from the index at entry to the index at exit, green for a profit and red for a loss.
 */
export interface ChartTrade {
  n: number;
  who: string;
  side: "CE" | "PE" | "STRADDLE";
  from: string;
  to: string | null;
  net: number;
  entrySpot: number | null;
  exitSpot: number | null;
}

const GREEN = "#16a34a", RED = "#dc2626", BLUE = "#2563eb";

/** ₹8.46L, ₹86.1K, ₹950 with a sign. */
export function compactRupees(v: number): string {
  const a = Math.abs(v), sign = v > 0 ? "+" : v < 0 ? "−" : "";
  if (a >= 100000) return `${sign}₹${(a / 100000).toFixed(2)}L`;
  if (a >= 1000) return `${sign}₹${(a / 1000).toFixed(1)}K`;
  return `${sign}₹${Math.round(a)}`;
}

const toTimeOn = (date: string, t: string, plusMinutes = 0) =>
  (Date.parse(`${date}T${t}:00Z`) / 1000 + plusMinutes * 60) as UTCTimestamp;
/** {@code color} may be a CSS colour or a theme token name such as "--color-text". */
export interface ChartLine { name: string; color: string; points: LinePoint[]; width?: number; dashed?: boolean;
  /** show the line's name and value on the price axis (levels) */
  label?: boolean }

/**
 * Minute series for one session. Times are IST "HH:mm"; they are placed on the session date as if
 * UTC so the axis shows IST wall-clock time.
 */
/** One minute's futures volume under the price; {@code color} marks surges. */
export interface VolumeBar { t: string; value: number; color: string }

// stable empty defaults: a fresh [] per render would rebuild the chart on every render
const NO_MARKERS: ChartMarker[] = [];
const NO_TRADES: ChartTrade[] = [];
const NO_VOLUME: VolumeBar[] = [];

export function TimelineChart({ date, lines, markers = NO_MARKERS, trades = NO_TRADES, volume = NO_VOLUME, focus = null, height = 280 }: {
  date: string; lines: ChartLine[]; markers?: ChartMarker[]; height?: number;
  trades?: ChartTrade[];
  /** futures volume per minute, drawn as bars in the bottom fifth of the chart */
  volume?: VolumeBar[];
  /** zoom to this window (IST "HH:mm"); null = the whole day */
  focus?: { from: string; to: string } | null;
}) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const focusRef = useRef(focus);
  focusRef.current = focus;

  useEffect(() => {
    if (!container.current) return;
    const style = getComputedStyle(document.documentElement);
    const text = style.getPropertyValue("--color-muted").trim() || "#8b98a9";
    const grid = style.getPropertyValue("--color-line").trim() || "#263041";
    const instance = createChart(container.current, {
      height,
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: "transparent" }, textColor: text, fontSize: 11 },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      timeScale: { timeVisible: true, secondsVisible: false, borderColor: grid },
      rightPriceScale: { borderColor: grid },
      crosshair: { mode: 1 },
    });
    chart.current = instance;
    const toTime = (t: string) => (Date.parse(`${date}T${t}:00Z`) / 1000) as UTCTimestamp;
    const resolve = (color: string) => (color.startsWith("--") ? style.getPropertyValue(color).trim() || "#888" : color);
    lines.forEach((line, index) => {
      const series = instance.addSeries(LineSeries, {
        color: resolve(line.color),
        lineWidth: (line.width ?? 2) as 1 | 2 | 3 | 4,
        lineStyle: line.dashed ? 2 : 0,
        priceLineVisible: false,
        lastValueVisible: index === 0 || !!line.label,
        title: line.name,
      });
      series.setData(line.points.filter((p) => p.value != null).map((p) => ({ time: toTime(p.t), value: p.value as number })));
      if (index === 0 && (markers.length > 0 || trades.length > 0)) {
        const colors = { up: "#22c55e", down: "#ef4444", info: "#60a5fa" };
        const items: SeriesMarker<Time>[] = markers.map((m) => ({
          time: toTime(m.t),
          // entries: calls below the line, puts above; exits (info) above so they never cover an entry
          position: m.tone === "up" ? ("belowBar" as const) : ("aboveBar" as const),
          color: colors[m.tone],
          shape: m.tone === "down" ? ("arrowDown" as const) : m.tone === "up" ? ("arrowUp" as const) : ("circle" as const),
          text: m.text,
        }));
        for (const tr of trades) {
          // entry: a call's arrow points up from below the price, a put's down from above; a straddle is a dot
          items.push({
            time: toTime(tr.from),
            position: tr.side === "PE" ? "aboveBar" : "belowBar",
            color: tr.side === "CE" ? GREEN : tr.side === "PE" ? RED : BLUE,
            shape: tr.side === "CE" ? "arrowUp" : tr.side === "PE" ? "arrowDown" : "circle",
            text: `#${tr.n} ${tr.who}`,
            size: 1.5,
          });
          if (tr.to) {
            // exit: a square on the other side of the price, coloured by the trade's result
            items.push({
              time: toTime(tr.to),
              position: tr.side === "PE" ? "belowBar" : "aboveBar",
              color: tr.net >= 0 ? GREEN : RED,
              shape: "square",
              text: `#${tr.n} ${compactRupees(tr.net)}`,
              size: 1.5,
            });
          }
        }
        items.sort((a, b) => (a.time as number) - (b.time as number));
        createSeriesMarkers(series, items);
      }
    });
    // futures volume: bars in the bottom fifth, on their own scale; the price keeps the top three quarters
    if (volume.length > 0) {
      instance.priceScale("right").applyOptions({ scaleMargins: { top: 0.05, bottom: 0.24 } });
      const bars = instance.addSeries(HistogramSeries, {
        priceScaleId: "volume", priceFormat: { type: "volume" }, priceLineVisible: false, lastValueVisible: false,
        title: "",
      });
      instance.priceScale("volume").applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
      bars.setData(volume.map((v) => ({ time: toTime(v.t), value: v.value, color: v.color })));
    }
    // each trade as a thick segment from the index at entry to the index at exit
    for (const tr of trades) {
      if (!tr.to || tr.to === tr.from || tr.entrySpot == null || tr.exitSpot == null) continue;
      const segment = instance.addSeries(LineSeries, {
        color: tr.net >= 0 ? GREEN : RED, lineWidth: 4, priceLineVisible: false, lastValueVisible: false,
        crosshairMarkerVisible: false, title: "",
      });
      segment.setData([{ time: toTime(tr.from), value: tr.entrySpot }, { time: toTime(tr.to), value: tr.exitSpot }]);
    }
    // Fit once the container has its real width, and again whenever it is resized (keeping a zoomed trade in view).
    const fit = () => {
      const f = focusRef.current;
      if (f) instance.timeScale().setVisibleRange({ from: toTimeOn(date, f.from, -15), to: toTimeOn(date, f.to, 15) });
      else instance.timeScale().fitContent();
    };
    const frame = requestAnimationFrame(fit);
    const observer = new ResizeObserver(fit);
    observer.observe(container.current);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      instance.remove();
      chart.current = null;
    };
  }, [date, lines, markers, trades, volume, height]);

  // zoom to one trade (15 minutes either side) or back to the whole day
  useEffect(() => {
    const instance = chart.current;
    if (!instance) return;
    if (focus) {
      instance.timeScale().setVisibleRange({ from: toTimeOn(date, focus.from, -15), to: toTimeOn(date, focus.to, 15) });
    } else {
      instance.timeScale().fitContent();
    }
  }, [focus, date]);

  return <div ref={container} className="w-full" style={{ height }} />;
}
