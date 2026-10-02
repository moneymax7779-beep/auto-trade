import { useEffect, useRef } from "react";
import {
  ColorType,
  createChart,
  createSeriesMarkers,
  LineSeries,
  type IChartApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts";

export interface LinePoint { t: string; value: number | null }
export interface ChartMarker { t: string; text: string; tone: "up" | "down" | "info" }
/** {@code color} may be a CSS colour or a theme token name such as "--color-text". */
export interface ChartLine { name: string; color: string; points: LinePoint[]; width?: number; dashed?: boolean;
  /** show the line's name and value on the price axis (levels) */
  label?: boolean }

/**
 * Minute series for one session. Times are IST "HH:mm"; they are placed on the session date as if
 * UTC so the axis shows IST wall-clock time.
 */
export function TimelineChart({ date, lines, markers = [], height = 280 }: {
  date: string; lines: ChartLine[]; markers?: ChartMarker[]; height?: number;
}) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);

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
      if (index === 0 && markers.length > 0) {
        const colors = { up: "#22c55e", down: "#ef4444", info: "#60a5fa" };
        const items: SeriesMarker<Time>[] = markers
          .map((m) => ({
            time: toTime(m.t),
            // entries: calls below the line, puts above; exits (info) above so they never cover an entry
            position: m.tone === "up" ? ("belowBar" as const) : ("aboveBar" as const),
            color: colors[m.tone],
            shape: m.tone === "down" ? ("arrowDown" as const) : m.tone === "up" ? ("arrowUp" as const) : ("circle" as const),
            text: m.text,
          }))
          .sort((a, b) => (a.time as number) - (b.time as number));
        createSeriesMarkers(series, items);
      }
    });
    // Fit once the container has its real width, and again whenever it is resized.
    const fit = () => instance.timeScale().fitContent();
    const frame = requestAnimationFrame(fit);
    const observer = new ResizeObserver(fit);
    observer.observe(container.current);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      instance.remove();
      chart.current = null;
    };
  }, [date, lines, markers, height]);

  return <div ref={container} className="w-full" style={{ height }} />;
}
