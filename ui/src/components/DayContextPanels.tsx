import { useQuery } from "@tanstack/react-query";
import { type ReactNode, useMemo } from "react";
import { get, type DayContext, type TradePositionRow, type TradePremium } from "../api";
import { type ChartLine, TimelineChart, type MinuteView } from "./TimelineChart";
import { ErrorNote, Loading } from "./ui";

/** The day around a session (VIX, straddle, gap, expiry, participants, shape); finished days are kept server-side. */
export function useDayContext(date: string | undefined, underlying: string) {
  return useQuery({
    queryKey: ["day-context", date ?? "none", underlying],
    enabled: !!date,
    queryFn: () => get<DayContext>(`/api/day-context?date=${date}&underlying=${underlying}`),
  });
}

const signed = (v: number | null | undefined, digits = 2, unit = "") =>
  v == null || Number.isNaN(v) ? "–" : `${v > 0 ? "+" : v < 0 ? "−" : ""}${Math.abs(v).toFixed(digits)}${unit}`;
const plain = (v: number | null | undefined, digits = 2) => (v == null || Number.isNaN(v) ? "–" : v.toFixed(digits));
const points = (v: number | null | undefined) => (v == null ? "–" : Math.round(v).toLocaleString("en-IN"));

function Tile({ label, value, sub, title }: { label: string; value: ReactNode; sub?: ReactNode; title?: string }) {
  return (
    <div className="min-w-0 rounded border border-line px-3 py-2" title={title}>
      <div className="text-xs text-muted">{label}</div>
      <div className="num truncate text-sm font-semibold">{value}</div>
      {sub != null && <div className="text-xs text-muted">{sub}</div>}
    </div>
  );
}

/**
 * One row of the day's context above the price chart. Everything here was known at the open except the day's shape,
 * which is labelled as known only at the close.
 */
export function DayContextCard({ date, underlying }: { date: string | undefined; underlying: string }) {
  const ctx = useDayContext(date, underlying);
  if (ctx.isLoading) return <Loading what="day context" />;
  if (ctx.error) return <ErrorNote error={ctx.error} />;
  const c = ctx.data;
  if (!c) return null;
  const opening = c.straddle?.find((s) => s[0] >= "09:20");
  const fii = c.participants?.find((p) => p.who === "FII");
  const pro = c.participants?.find((p) => p.who === "Pro");
  const client = c.participants?.find((p) => p.who === "Client");
  const shape = c.shape;
  const range = c.index ? c.index.high - c.index.low : null;
  return (
    <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-7">
      <Tile label="Expiry"
        value={c.expiry ? (c.expiry.expiryDay ? "EXPIRY DAY" : `${c.expiry.calendarDays} day${c.expiry.calendarDays === 1 ? "" : "s"}`) : "–"}
        sub={c.expiry ? `nearest ${c.expiry.expiry}` : "no option data"} />
      <Tile label="Gap at the open" value={signed(c.index?.gapPct, 2, " %")}
        sub={c.index?.prevClose != null ? `prev close ${points(c.index.prevClose)} → open ${points(c.index.open)}` : "no previous close"} />
      <Tile label="India VIX" value={c.vixDay.open != null ? `${plain(c.vixDay.open)} → ${plain(c.vixDay.last)}` : "–"}
        sub={c.vixDay.percentile1y != null ? `${plain(c.vixDay.percentile1y, 0)}th pct of the past year at the open`
          : c.vixDay.prevClose != null ? `prev close ${plain(c.vixDay.prevClose)}` : "no VIX for this day"}
        title={`previous close ${plain(c.vixDay.prevClose)} (${c.vixDay.prevDay ?? "–"}); day ${plain(c.vixDay.low)}–${plain(c.vixDay.high)}; ${c.vixDay.historyDays} days of history`} />
      <Tile label="Expected move (VIX ÷ √252)" value={c.expectedMoveVix != null ? `±${points(c.expectedMoveVix)} pts` : "–"}
        sub={range != null ? `day range ${points(range)} pts${shape?.rangeVsExpected != null ? ` = ${plain(shape.rangeVsExpected)}×` : ""}` : undefined}
        title="one standard deviation for a day from VIX at the open" />
      <Tile label="ATM straddle at 09:20" value={opening ? `₹${plain(opening[1])}` : "–"}
        sub={opening ? `${points(opening[2])} strike${opening[3] != null ? ` · ≈ ${plain(opening[3], 1)} % IV` : ""}` : "no option prices"}
        title="the market's priced move to expiry; IV from the straddle (Brenner–Subrahmanyam), an approximation" />
      <Tile label="FII index futures long" value={fii ? `${plain(fii.indexFutLongPct, 1)} %` : "–"}
        sub={fii ? `${signed(fii.indexFutLongPctChange, 1, " pts")} · Pro ${plain(pro?.indexFutLongPct, 0)} % · Client ${plain(client?.indexFutLongPct, 0)} %` : "not published"}
        title={fii ? `as published after ${fii.asOf} (NSE participant-wise OI); index calls net ${fii.indexCallNet.toLocaleString("en-IN")}, puts net ${fii.indexPutNet.toLocaleString("en-IN")}` : undefined} />
      <Tile label={shape?.final === false ? "Day so far (hindsight)" : "Day shape (known at the close)"}
        value={shape ? `${shape.type} ${shape.direction}` : "–"}
        sub={shape ? `body ${plain(shape.bodyPct, 0)} % of the range` : undefined}
        title="trend: body ≥ 60 % of the range and range ≥ the VIX-expected move; range: body < 30 %. Not knowable during the day." />
    </div>
  );
}

/** VIX and the ATM straddle's implied volatility through the day, on the shared time window. */
export function VolatilityStrip({ date, underlying, view, onView }: {
  date: string; underlying: string; view?: MinuteView; onView?: (v: MinuteView) => void;
}) {
  const ctx = useDayContext(date, underlying);
  const lines = useMemo<ChartLine[]>(() => {
    const c = ctx.data;
    if (!c) return [];
    return [
      { name: "VIX", color: "#f59e0b", points: c.vix.map(([t, v]) => ({ t, value: v })) },
      { name: "straddle IV %", color: "#3b82f6", width: 1, points: (c.straddle ?? []).map((s) => ({ t: s[0], value: s[3] })) },
    ];
  }, [ctx.data]);
  if (!ctx.data || lines.every((l) => l.points.length === 0)) return null;
  return (
    <div className="mt-3">
      <div className="text-xs text-muted">
        <span style={{ color: "#f59e0b" }}>India VIX</span> and the <span style={{ color: "#3b82f6" }}>ATM straddle's implied
        volatility</span> (%, approximate) — a falling line while holding an option is premium leaking out
      </div>
      <TimelineChart date={date} lines={lines} height={160} view={view} onView={onView} />
    </div>
  );
}

/**
 * One traded option through the day: its minute bars, the entry and exit, and the best and worst premium while held.
 * Answers whether a loss was the entry (never went our way) or the exit (went our way and was given back).
 */
export function TradePremiumPanel({ sessionId, date, positions, picked, onPick }: {
  sessionId: string; date: string; positions: TradePositionRow[]; picked: number | null; onPick: (id: number) => void;
}) {
  const own = positions.filter((p) => p.id != null);
  const id = picked ?? own[0]?.id ?? null;
  const premium = useQuery({
    queryKey: ["trade-premium", sessionId, id],
    enabled: id != null,
    queryFn: () => get<TradePremium>(`/api/sessions/${sessionId}/premium?position=${id}`),
  });
  const p = premium.data;
  const lines = useMemo<ChartLine[]>(() => {
    if (!p?.bars || !p.entry) return [];
    const close = { name: p.symbol, color: "--color-text", width: 1 as const, hidden: true,
      points: p.bars.map(([t, , , , c]) => ({ t, value: c })) };
    const until = p.exit?.t ?? p.bars[p.bars.length - 1]?.[0] ?? p.entry.t;
    const out: ChartLine[] = [close,
      { name: "entry", color: "#2563eb", width: 1, dashed: true, label: true,
        points: [{ t: p.entry.t, value: p.entry.price }, { t: until, value: p.entry.price }] }];
    if (p.exit) {
      out.push({ name: "exit", color: p.exit.price >= p.entry.price ? "#16a34a" : "#dc2626", width: 1, dashed: true, label: true,
        points: [{ t: p.entry.t, value: p.exit.price }, { t: p.exit.t, value: p.exit.price }] });
    }
    return out;
  }, [p]);
  const candles = useMemo(() => (p?.bars ?? []).map(([t, open, high, low, close]) => ({ t, open, high, low, close })), [p]);
  if (own.length === 0) return null;
  const ivAt = (t: string | undefined) => (t ? p?.iv?.filter(([m]) => m <= t).at(-1)?.[1] : undefined);
  const x = p?.excursion;
  const verdict = premiumVerdict(p);
  return (
    <div className="mt-3 rounded border border-line p-3">
      <div className="mb-2 flex flex-wrap items-center gap-1.5 text-xs">
        <span className="font-semibold">Traded option</span>
        {own.map((pos, i) => (
          <button key={pos.id} type="button" onClick={() => onPick(pos.id!)}
            className={`rounded border px-2 py-0.5 ${pos.id === id ? "border-accent bg-accent/10" : "border-line"}`}>
            #{i + 1} {pos.symbol} {pos.opened_at.slice(11, 16)}
          </button>
        ))}
      </div>
      {premium.isLoading ? <Loading what="premium" /> : premium.error ? <ErrorNote error={premium.error} /> :
        !p || p.error || !p.bars?.length ? <p className="text-xs text-muted">{p?.error ?? "No minute prices for this contract on this server."}</p> : (
          <>
            <TimelineChart date={date} lines={lines} candles={candles} height={220}
              focus={p.entry ? { from: p.entry.t, to: p.exit?.t ?? p.entry.t } : null} />
            <div className="mt-2 grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
              <div><span className="text-muted">entry</span> <span className="num">₹{plain(p.entry?.price)} at {p.entry?.t}</span>
                {ivAt(p.entry?.t) != null && <span className="text-muted"> · IV {plain(ivAt(p.entry?.t), 1)} %</span>}</div>
              <div><span className="text-muted">exit</span> <span className="num">{p.exit ? `₹${plain(p.exit.price)} at ${p.exit.t}` : "open"}</span>
                {ivAt(p.exit?.t) != null && <span className="text-muted"> · IV {plain(ivAt(p.exit?.t), 1)} %</span>}</div>
              <div title="the best premium while held (minute highs, entry to exit minute)">
                <span className="text-muted">best</span>{" "}
                <span className="num text-up">₹{plain(x?.bestPrice)} at {x?.bestAt ?? "–"} (+{plain(x?.mfe)} = +₹{(x?.mfeRupees ?? 0).toLocaleString("en-IN")})</span>
              </div>
              <div title="the worst premium while held (minute lows, entry to exit minute)">
                <span className="text-muted">worst</span>{" "}
                <span className="num text-down">₹{plain(x?.worstPrice)} at {x?.worstAt ?? "–"} (−{plain(x?.mae)} = −₹{(x?.maeRupees ?? 0).toLocaleString("en-IN")})</span>
              </div>
            </div>
            <p className="mt-1 text-xs text-muted">
              {verdict}{" "}Minute bars from the {p.source === "bars" ? "stored one-minute history (coarse)" : "own tick capture (last traded price)"}; a minute's range can include seconds before the entry or after the exit.
            </p>
          </>
        )}
    </div>
  );
}

/**
 * In words: did the trade ever go our way (best move under 2 % of the entry premium: the entry lost it), was a gain
 * given back (a loss after being up), or how much of the best move a winner kept.
 */
function premiumVerdict(p: TradePremium | undefined): string {
  const x = p?.excursion;
  if (!p?.entry || !p.exit || x?.mfe == null) return "";
  const gained = p.exit.price - p.entry.price;
  if (x.mfe < 0.02 * p.entry.price) {
    return `It barely went our way (best +${plain(x.mfe)}): the entry, not the exit, lost this one.`;
  }
  if (gained <= 0) {
    return `It was up +${plain(x.mfe)} (₹${(x.mfeRupees ?? 0).toLocaleString("en-IN")}) at ${x.bestAt} and closed at a loss: a gain given back.`;
  }
  return `The exit kept ${plain((gained / x.mfe) * 100, 0)} % of the best move.`;
}
