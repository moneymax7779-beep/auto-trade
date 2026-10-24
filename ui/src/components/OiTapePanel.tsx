import { useEffect, useId, useMemo, useRef, useState, type PointerEvent, type ReactNode, type RefObject } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { useChartView, useMoment, type View } from "./chartView";
import { layout as surgeLayout } from "./SurgePanel";
import { ErrorNote, Panel } from "./ui";

/** /api/oi-tape: [time, spot, futures price, futures OI, ATM, band call OI, band put OI, call flow, put flow, band PCR]. */
type TapeRow = [string, number, number | null, number | null, number, number, number, number, number, number | null];
interface TapeData {
  date: string; underlying: string; expiry?: string; futuresExpiry?: string | null; strikeStep?: number; band?: number;
  stepSeconds?: number; complete?: boolean; deferred?: boolean; series: TapeRow[];
}
/** /api/oi-tape/strikes rows: [strike, call OI now, call OI then, put OI now, put OI then] (null = none recorded yet). */
interface StrikesData {
  date: string; underlying: string; at?: string; from?: string; spot?: number | null; atm?: number | null; strikeStep?: number;
  deferred?: boolean; rows: [number, number | null, number | null, number | null, number | null][];
}
/** /api/surges: per index the futures volume of each minute and its normal ([IST "HH:mm", volume, normal or null]). */
interface SurgeVolume { underlyings: { underlying: string; volume?: [string, number, number | null][] }[] }
/** A trade drawn on the tape: entry and exit in IST, "HH:MM[:SS]" or "YYYY-MM-DD HH:MM:SS" (as the API gives them). */
export interface TapeTrade { label: string; opened: string; closed: string | null; net: number }
/** The tape's plot margins for a width, so its time axis lines up with the price chart above it. */
export type TapeInsets = (width: number) => { L: number; R: number };

const DAY0 = 9 * 60 + 15, DAY1 = 15 * 60 + 30;
const FULL: View = { a: DAY0, b: DAY1 };
const CALL = "var(--color-accent)", PUT = "var(--color-warn)", SURGE = "rgba(245,158,11,0.9)", VOL = "rgba(148,163,184,0.55)";
/** The surge chart's margins in pixels at a width (its drawing is W units wide, scaled to the panel). */
export const surgeInsets: TapeInsets = (w) => {
  const { W, L, R } = surgeLayout(w);
  return { L: (L * w) / W, R: (R * w) / W };
};
const secs = (t: string) => { const [h, m, s] = t.split(":").map(Number); return h * 3600 + m * 60 + (s || 0); };
const istMin = (t: string) => { const m = t.match(/(\d{2}):(\d{2})(?::(\d{2}))?(?!.*\d{2}:\d{2})/); return m ? Number(m[1]) * 60 + Number(m[2]) + Number(m[3] ?? 0) / 60 : NaN; };
const hm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(Math.floor(m % 60)).padStart(2, "0")}`;
const big = (v: number | null | undefined, signed = false) => {
  if (v == null || !Number.isFinite(v)) return "–";
  const a = Math.abs(v), s = signed ? (v > 0 ? "+" : v < 0 ? "−" : "") : v < 0 ? "−" : "";
  return a >= 1e6 ? `${s}${(a / 1e6).toFixed(a >= 1e8 ? 0 : 1)}M` : a >= 1e3 ? `${s}${(a / 1e3).toFixed(0)}k` : `${s}${a.toFixed(0)}`;
};
const px = (v: number | null | undefined) => (v == null ? "–" : v.toLocaleString("en-IN", { maximumFractionDigits: 2 }));

function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [w, setW] = useState(800);
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth || 800)); ro.observe(el); setW(el.clientWidth || 800);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/**
 * The OI tape of one index and day (display only), drawn under the page's price chart and sharing its time window
 * (zoom either one and the other follows): the call and put OI added or removed near the money since 09:15, the flow
 * per step, the PCR of the opening chain, the futures OI and the futures volume per minute (surges ≥ 5 × normal in
 * amber), every 30 seconds from the own capture, with the day's trades as bands; and, under it, every strike's OI at
 * any moment you click. Live, it refreshes every 30 seconds. The index itself is the chart above.
 */
export function OiTapePanel({ underlying, date, live, trades = [], title, insets = surgeInsets, bare }:
  { underlying: string; date: string; live?: boolean; trades?: TapeTrade[]; title?: string; insets?: TapeInsets;
    /** drawn inside the price chart's card (no card of its own), right under the chart */
    bare?: boolean }) {
  // the strikes around the money the OI lines and flow bars count (an experiment, 23 Oct: ATM ± 3 against the usual ± 5)
  const [band, setBand] = useState<3 | 5>(5);
  const q = useQuery({
    queryKey: ["oi-tape", underlying, date, band],
    queryFn: () => get<TapeData>(`/api/oi-tape?underlying=${underlying}&date=${date}&band=${band}`),
    placeholderData: (prev) => prev,
    refetchInterval: live ? 30_000 : false,
  });
  // the same per-minute futures volume the price charts draw (shared cache with the surge chart and the session chart)
  const surges = useQuery({
    queryKey: ["surges", date],
    queryFn: () => get<SurgeVolume>(`/api/surges?date=${date}`),
    refetchInterval: live ? 30_000 : false,
  });
  const [view, setView] = useChartView(date, underlying, FULL);
  // the moment clicked here or on the price chart above (shared): the strike ladder shows it
  const [bucket, setBucket] = useState<"auto" | 30 | 60 | 300>("auto");
  const [pinM, setPinM] = useMoment("pin", date, underlying);
  const volume = useMemo(() => surges.data?.underlyings.find((u) => u.underlying === underlying)?.volume ?? [], [surges.data, underlying]);
  const head = title ?? `${underlying} · OI tape · ${date}`;
  const d = q.data;
  const right = d?.expiry ? <span className="text-xs text-muted">expiry {d.expiry} · ATM ± {d.band} strikes · every {d.stepSeconds} s{d.complete ? "" : " · live"}</span> : null;
  const frame = (body: ReactNode) => bare ? (
    <div className="mt-4 border-t border-line pt-3">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2"><span className="text-sm font-semibold">{head}</span>{right}</div>
      {body}
    </div>
  ) : <Panel title={head} right={right}>{body}</Panel>;
  if (q.error) return frame(<ErrorNote error={q.error} />);
  if (!d || d.series.length === 0) {
    return frame(<p className="text-sm text-muted">{q.isLoading ? "Loading…"
      : d?.deferred ? "An earlier day's OI tape is built after the close (15:50 IST), so it never slows the live session."
      : "No option OI in the own capture for this day (the capture starts on 28 Sep 2026; earlier days were recorded by zt-tiger-v2 only). The price chart above still shows the day."}</p>);
  }
  // the step holding the pinned moment (the last one that started at or before it), else the latest step
  const pinRow = pinM == null ? null : [...d.series].reverse().find((r) => secs(r[0]) / 60 <= pinM + 1e-9) ?? d.series[0];
  const at = pinRow ? pinRow[0] : d.series[d.series.length - 1][0];
  return frame(
    <>
      <Tape d={d} date={date} underlying={underlying} volume={volume} view={view} setView={setView} trades={trades}
        pinned={pinRow ? pinRow[0] : null} setPinned={(t) => setPinM(t == null ? null : secs(t) / 60)}
        bucket={bucket} setBucket={setBucket} band={band} setBand={setBand} insets={insets} />
      <Strikes underlying={underlying} date={date} at={at} pinned={pinRow != null} live={live} />
    </>
  );
}

function Tape({ d, date, underlying, volume, view, setView, trades, pinned, setPinned, bucket, setBucket, band, setBand, insets }: {
  d: TapeData; date: string; underlying: string; volume: [string, number, number | null][]; view: View; setView: (v: View) => void; trades: TapeTrade[];
  pinned: string | null; setPinned: (t: string | null) => void;
  bucket: "auto" | 30 | 60 | 300; setBucket: (b: "auto" | 30 | 60 | 300) => void; band: 3 | 5; setBand: (b: 3 | 5) => void; insets: TapeInsets;
}) {
  const [ref, W] = useWidth<HTMLDivElement>();
  const clipId = `oi-clip-${useId().replace(/:/g, "")}`;
  // the moment under the mouse, here or on the price chart above (shared)
  const [hover, setHover] = useMoment("cursor", date, underlying);
  const [drag, setDrag] = useState<{ a: number; b: number } | null>(null);
  // the day's cumulative flow, computed once over every step (so a zoomed window still starts from the day's total)
  const rows = useMemo(() => {
    let cc = 0, cp = 0;
    return d.series.map((r) => { cc += r[7]; cp += r[8]; return { t: secs(r[0]) / 60, time: r[0], spot: r[1], fpx: r[2], foi: r[3], atm: r[4],
      ce: r[5], pe: r[6], dce: r[7], dpe: r[8], pcr: r[9], cumCe: cc, cumPe: cp }; });
  }, [d.series]);
  const vols = useMemo(() => volume.map(([t, v, normal]) => ({ t: secs(t) / 60, v, surge: normal != null && v >= 5 * normal, normal })), [volume]);
  const vis = useMemo(() => rows.filter((r) => r.t >= view.a && r.t <= view.b), [rows, view.a, view.b]);
  const visVol = vols.filter((r) => r.t >= view.a && r.t <= view.b);
  const span = view.b - view.a;
  const step = bucket === "auto" ? (span > 150 ? 300 : span > 40 ? 60 : 30) : bucket;
  const bars = useMemo(() => {
    const m = new Map<number, { t: number; ce: number; pe: number }>();
    vis.forEach((r) => { const k = Math.floor((r.t * 60) / step) * step / 60; const b = m.get(k) ?? { t: k, ce: 0, pe: 0 }; b.ce += r.dce; b.pe += r.dpe; m.set(k, b); });
    return [...m.values()];
  }, [vis, step]);

  const { L, R } = insets(W);
  const gap = 22;
  const panels = [
    { key: "cum", label: "OI added since 09:15, ATM ± band (calls · puts)", h: 120 },
    { key: "flow", label: `OI change per ${step >= 60 ? `${step / 60} min` : `${step} s`} (calls left, puts right)`, h: 104 },
    { key: "pcr", label: "PCR of the 09:15 chain, a fixed set of strikes (dashed = 1)", h: 64 },
    { key: "fut", label: `Futures OI${d.futuresExpiry ? ` (${d.futuresExpiry})` : ""}`, h: 64 },
    { key: "vol", label: "Futures volume per minute (amber: a surge, ≥ 5 × the minute's normal)", h: 64 },
  ];
  let yy = 18; const top: Record<string, number> = {};
  panels.forEach((p) => { top[p.key] = yy; yy += p.h + gap; });
  const H = yy - gap + 22;
  const yEnd = top.vol + panels[panels.length - 1].h;
  const x = (m: number) => L + ((m - view.a) / Math.max(1, span)) * (W - L - R);
  const inv = (px_: number) => view.a + ((px_ - L) / Math.max(1, W - L - R)) * span;
  const scale = (key: string, lo: number, hi: number) => {
    const p = panels.find((q) => q.key === key)!, t = top[key];
    const pad = (hi - lo) * 0.08 || Math.abs(hi) * 0.01 || 1;
    const a = lo - pad, b = hi + pad;
    return { y: (v: number) => t + p.h - ((v - a) / (b - a)) * p.h, a, b, t, h: p.h };
  };
  const ext = (vals: (number | null)[], incl?: number) => {
    const v = vals.filter((z): z is number => z != null && Number.isFinite(z));
    if (incl != null) v.push(incl);
    return v.length ? [Math.min(...v), Math.max(...v)] : [0, 1];
  };
  const sCum = scale("cum", ...(ext([...vis.map((r) => r.cumCe), ...vis.map((r) => r.cumPe)], 0) as [number, number]));
  const mxFlow = Math.max(1, ...bars.map((b) => Math.max(Math.abs(b.ce), Math.abs(b.pe))));
  const sFlow = scale("flow", -mxFlow, mxFlow);
  const sPcr = scale("pcr", ...(ext(vis.map((r) => r.pcr), 1) as [number, number]));
  const sFut = scale("fut", ...(ext(vis.map((r) => r.foi)) as [number, number]));
  const mxVol = Math.max(1, ...visVol.map((r) => r.v));
  const volY = (v: number) => top.vol + panels[4].h - (v / mxVol) * panels[4].h;
  const path = (f: (r: (typeof rows)[number]) => number | null, s: { y: (v: number) => number }) =>
    vis.reduce((acc, r) => { const v = f(r); if (v == null || !Number.isFinite(v)) return acc; return acc + `${acc ? "L" : "M"}${x(r.t).toFixed(1)},${s.y(v).toFixed(1)}`; }, "");
  const ticks = (() => { const st = span > 240 ? 60 : span > 120 ? 30 : span > 45 ? 15 : span > 15 ? 5 : 1; const out: number[] = [];
    for (let m = Math.ceil(view.a / st) * st; m <= view.b; m += st) out.push(m); return out; })();
  const shown = trades.map((t) => ({ ...t, a: istMin(t.opened), b: t.closed ? istMin(t.closed) : rows[rows.length - 1]?.t ?? DAY1 }))
    .filter((t) => Number.isFinite(t.a) && t.b >= view.a && t.a <= view.b);
  const nearest = (m: number) => vis.reduce((best, r) => (Math.abs(r.t - m) < Math.abs(best.t - m) ? r : best), vis[0]);
  const hv = hover != null && hover >= view.a && hover <= view.b && vis.length ? nearest(hover) : null;
  const hvVol = hv ? vols.find((r) => r.t === Math.floor(hv.t)) : null;
  const pin = pinned ? rows.find((r) => r.time === pinned) : null;

  const pos = (e: PointerEvent<SVGRectElement>) => { const b = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
    return ((e.clientX - b.left) / b.width) * W; };
  const down = (e: PointerEvent<SVGRectElement>) => { if (e.button !== 0) return; e.currentTarget.setPointerCapture(e.pointerId); const m = inv(pos(e)); setDrag({ a: m, b: m }); };
  const move = (e: PointerEvent<SVGRectElement>) => { const m = inv(pos(e)); setHover(m); if (drag) setDrag({ a: drag.a, b: m }); };
  const up = () => {
    if (!drag) return;
    const a = Math.max(DAY0, Math.min(drag.a, drag.b)), b = Math.min(DAY1, Math.max(drag.a, drag.b));
    if (b - a >= 2) setView({ a, b });
    else if (vis.length) { const r = nearest(drag.a); setPinned(pinned === r.time ? null : r.time); }
    setDrag(null);
  };
  const tipLeft = hv ? Math.min(Math.max(0, (x(hv.t) / W) * 100), 70) : 0;

  return (
    <div>
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="text-muted">Bars per</span>
        {(["auto", 30, 60, 300] as const).map((b) => (
          <button key={String(b)} type="button" onClick={() => setBucket(b)}
            className={`rounded border px-2 py-0.5 ${bucket === b ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>
            {b === "auto" ? `auto (${step >= 60 ? `${step / 60} min` : `${step} s`})` : b >= 60 ? `${b / 60} min` : `${b} s`}
          </button>
        ))}
        <span className="ml-3 text-muted">Strikes</span>
        {([3, 5] as const).map((b) => (
          <button key={b} type="button" onClick={() => setBand(b)}
            className={`rounded border px-2 py-0.5 ${band === b ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>
            ATM ± {b}
          </button>
        ))}
        <button type="button" onClick={() => setView(FULL)} disabled={view.a === DAY0 && view.b === DAY1}
          className="ml-auto rounded border border-line px-2 py-0.5 text-muted hover:text-text disabled:opacity-40">Whole day</button>
      </div>
      <div ref={ref} className="relative mt-2">
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" className="select-none"
          aria-label={`${d.underlying} OI tape ${hm(view.a)}-${hm(view.b)}`}>
          {/* the day's trades as bands across every panel (the price chart above draws their entries and exits) */}
          <defs>
            <clipPath id={clipId}><rect x={L} y={0} width={Math.max(0, W - L - R)} height={H} /></clipPath>
          </defs>
          {shown.map((t, i) => {
            const col = t.net >= 0 ? "var(--color-up)" : "var(--color-down)";
            const a = x(Math.max(view.a, t.a)), b = x(Math.min(view.b, t.b));
            return (
              <g key={i} clipPath={`url(#${clipId})`}>
                <rect x={a} y={top.cum} width={Math.max(2, b - a)} height={yEnd - top.cum} fill={col} opacity={0.07} />
                <line x1={x(t.a)} x2={x(t.a)} y1={top.cum} y2={yEnd} stroke={col} strokeDasharray="3 3" opacity={0.5} />
                <text x={a + 3} y={top.cum + 11 + (i % 3) * 12} className="fill-current text-[10px] opacity-80">{t.label}</text>
              </g>
            );
          })}
          {panels.map((p) => (
            <g key={p.key}>
              <text x={L} y={top[p.key] - 6} className="fill-current text-[11px] font-semibold">{p.label}</text>
              <line x1={L} x2={W - R} y1={top[p.key] + p.h} y2={top[p.key] + p.h} stroke="var(--color-line)" />
            </g>
          ))}
          {/* each panel's top and bottom values, in the right gutter like the price axis above */}
          {[["cum", sCum, (v: number) => big(v, true)], ["flow", sFlow, (v: number) => big(v, true)],
            ["pcr", sPcr, (v: number) => v.toFixed(2)], ["fut", sFut, (v: number) => big(v)]].map(([k, s, f]) => {
            const sc = s as ReturnType<typeof scale>, fmt = f as (v: number) => string;
            const hi = sc.b - (sc.b - sc.a) * 0.08, lo = sc.a + (sc.b - sc.a) * 0.08;
            return (
              <g key={k as string} className="fill-current text-[10px] opacity-60">
                <text x={W - R + 6} y={sc.y(hi) + 4}>{fmt(hi)}</text>
                <text x={W - R + 6} y={sc.y(lo) + 4}>{fmt(lo)}</text>
              </g>
            );
          })}
          <text x={W - R + 6} y={top.vol + 10} className="fill-current text-[10px] opacity-60">{big(mxVol)}</text>
          <line x1={L} x2={W - R} y1={sCum.y(0)} y2={sCum.y(0)} stroke="var(--color-line)" />
          <path d={path((r) => r.cumCe, sCum)} fill="none" stroke={CALL} strokeWidth={2} />
          <path d={path((r) => r.cumPe, sCum)} fill="none" stroke={PUT} strokeWidth={2} />
          <line x1={L} x2={W - R} y1={sFlow.y(0)} y2={sFlow.y(0)} stroke="var(--color-line)" />
          {bars.map((b) => {
            const w = Math.max(0.8, (x(b.t + step / 60) - x(b.t)) / 2 - 0.4);
            return (
              <g key={b.t} clipPath={`url(#${clipId})`}>
                {b.ce !== 0 && <rect x={x(b.t)} width={w} y={Math.min(sFlow.y(0), sFlow.y(b.ce))} height={Math.abs(sFlow.y(b.ce) - sFlow.y(0))} fill={CALL} />}
                {b.pe !== 0 && <rect x={x(b.t) + w} width={w} y={Math.min(sFlow.y(0), sFlow.y(b.pe))} height={Math.abs(sFlow.y(b.pe) - sFlow.y(0))} fill={PUT} />}
              </g>
            );
          })}
          {sPcr.a <= 1 && sPcr.b >= 1 && <line x1={L} x2={W - R} y1={sPcr.y(1)} y2={sPcr.y(1)} stroke="currentColor" strokeOpacity={0.3} strokeDasharray="4 4" />}
          <path d={path((r) => r.pcr, sPcr)} fill="none" stroke="#6366f1" strokeWidth={1.6} />
          <path d={path((r) => r.foi, sFut)} fill="none" stroke="var(--color-muted)" strokeWidth={1.6} />
          {visVol.map((r) => {
            const w = Math.max(0.6, x(r.t + 1) - x(r.t) - 0.6);
            return <rect key={r.t} clipPath={`url(#${clipId})`} x={x(r.t)} width={w} y={volY(r.v)} height={top.vol + panels[4].h - volY(r.v)} fill={r.surge ? SURGE : VOL} />;
          })}
          {visVol.length === 0 && <text x={L + 4} y={top.vol + panels[4].h / 2} className="fill-current text-[10px] opacity-60">no futures volume for this day</text>}
          {ticks.map((m) => (
            <text key={m} x={x(m)} y={H - 6} textAnchor="middle" className="fill-current text-[10px] opacity-60">{hm(m)}</text>
          ))}
          {pin && pin.t >= view.a && pin.t <= view.b && <line x1={x(pin.t)} x2={x(pin.t)} y1={top.cum} y2={yEnd} stroke="var(--color-accent)" strokeWidth={1.5} />}
          {hv && <line x1={x(hv.t)} x2={x(hv.t)} y1={top.cum} y2={yEnd} stroke="currentColor" strokeOpacity={0.35} />}
          {drag && Math.abs(drag.b - drag.a) > 0.5 && (
            <rect x={x(Math.min(drag.a, drag.b))} width={Math.abs(x(drag.b) - x(drag.a))} y={top.cum} height={yEnd - top.cum}
              fill="var(--color-accent)" opacity={0.12} />
          )}
          <rect x={L} y={top.cum} width={Math.max(0, W - L - R)} height={yEnd - top.cum} fill="transparent" style={{ cursor: "crosshair", touchAction: "pan-y" }}
            onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerLeave={() => { setHover(null); }}
            onPointerCancel={() => setDrag(null)}
            onDoubleClick={() => setView(FULL)} />
        </svg>
        {hv && (
          <div className="pointer-events-none absolute z-10 rounded border border-line bg-panel px-2 py-1 text-xs shadow num"
            style={{ left: `${tipLeft}%`, top: top.flow }}>
            <div className="text-muted">{hv.time} IST</div>
            <div>spot {px(hv.spot)} · ATM {px(hv.atm)}</div>
            <div><span style={{ color: CALL }}>calls</span> {big(hv.ce)} ({big(hv.dce, true)}) · since 09:15 {big(hv.cumCe, true)}</div>
            <div><span style={{ color: PUT }}>puts</span> {big(hv.pe)} ({big(hv.dpe, true)}) · since 09:15 {big(hv.cumPe, true)}</div>
            <div>PCR {hv.pcr?.toFixed(3) ?? "–"} · futures OI {big(hv.foi)} @ {px(hv.fpx)}</div>
            {hvVol && <div>futures volume {big(hvVol.v)} in {hm(hvVol.t)}{hvVol.normal ? ` (${(hvVol.v / hvVol.normal).toFixed(1)} × normal)` : ""}</div>}
          </div>
        )}
      </div>
      <p className="mt-1 text-xs text-muted">
        Drag across the panels to zoom (the price chart above follows, and the other way round), double-click for the whole day; click a
        moment to see its strikes below. Flow counts only strikes within ATM ± {d.band} that had OI in both steps, so a strike the capture
        starts recording mid-day adds no false jump. OI is what the exchange had published by the end of each step (NIFTY about once a
        minute, BSE every 2½–3 minutes).
      </p>
    </div>
  );
}

function Strikes({ underlying, date, at, pinned, live }: { underlying: string; date: string; at: string; pinned: boolean; live?: boolean }) {
  const [back, setBack] = useState(300);
  // live and unpinned, "at" follows the tape's latest step, so a new step is a new query: no timer of its own
  const q = useQuery({
    queryKey: ["oi-strikes", underlying, date, at, back],
    queryFn: () => get<StrikesData>(`/api/oi-tape/strikes?underlying=${underlying}&date=${date}&at=${at}&back=${back}`),
    placeholderData: (prev) => prev,          // keep the last ladder while the next loads (no 700 px collapse every 30 s)
  });
  const [ref, W] = useWidth<HTMLDivElement>();
  const d = q.data;
  const rows = (d?.rows ?? []).map(([k, c, c0, p, p0]) => ({ k, c, c0, p, p0, dc: c != null && c0 != null ? c - c0 : null, dp: p != null && p0 != null ? p - p0 : null }));
  const mx = Math.max(1, ...rows.flatMap((r) => [r.c ?? 0, r.p ?? 0, r.c0 ?? 0, r.p0 ?? 0]));
  const rowH = 22, labW = 64, deltaW = 112, top = 6;
  const barW = Math.max(120, W - labW - deltaW - 8);
  const H = top + rows.length * rowH + 6;
  const xs = (v: number) => labW + (v / mx) * barW;
  const spotY = (() => {
    if (!d?.spot || !d.strikeStep || rows.length < 2) return null;
    const i = (rows[0].k - d.spot) / d.strikeStep;
    return top + (i + 0.5) * rowH;
  })();
  const movers = (key: "dc" | "dp", sign: 1 | -1) => rows.filter((r) => r[key] != null && sign * (r[key] as number) > 0)
    .sort((a, b) => sign * ((b[key] as number) - (a[key] as number))).slice(0, 2);
  const mv = (key: "dc" | "dp", sign: 1 | -1) => movers(key, sign).map((r) => `${r.k.toLocaleString("en-IN")} ${big(r[key], true)}`).join(", ") || "–";
  return (
    <div ref={ref} className="mt-4 border-t border-line pt-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="font-semibold">Strikes at {d?.at ?? at}{pinned ? "" : live ? " (latest)" : " (close)"}</span>
        <span className="text-muted">· change since {d?.from ?? "…"}</span>
        <span className="ml-2 text-muted">over</span>
        {[30, 60, 300, 900, 1800].map((b) => (
          <button key={b} type="button" onClick={() => setBack(b)}
            className={`rounded border px-2 py-0.5 ${back === b ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>{b < 60 ? `${b} s` : `${b / 60} min`}</button>
        ))}
        {d?.spot != null && <span className="ml-auto num text-muted">spot {px(d.spot)} · ATM {px(d.atm)}</span>}
      </div>
      {q.error ? <ErrorNote error={q.error} /> : d?.deferred ? <p className="mt-2 text-sm text-muted">Built after the close.</p> : (
        <>
        {/* a short window often holds no OI print at all: the exchange publishes NIFTY option OI once a minute, BSE every ~2.7 min */}
        {/* judged near the money (ATM ± 5): deep in-the-money strikes also tick off the exchange's clock */}
        {d && rows.length > 0 && rows.filter((r) => d.atm == null || !d.strikeStep || Math.abs(r.k - d.atm) <= 5 * d.strikeStep + 1e-6)
          .every((r) => (r.dc ?? 0) === 0 && (r.dp ?? 0) === 0) && (
          <p className="mt-2 text-xs text-warn">No OI print between {d.from} and {d.at}: the exchange publishes {underlying === "NIFTY" || underlying === "BANKNIFTY"
            ? "NSE option OI about once a minute" : "BSE option OI every 2½–3 minutes"}, so a {back < 60 ? `${back}-second` : `${back / 60}-minute`} window can fall between two prints. Try a longer window or another moment.</p>
        )}
          <div className="mt-2 grid gap-x-6 gap-y-1 text-xs sm:grid-cols-2 num">
            <span><span style={{ color: CALL }}>Calls built</span> {mv("dc", 1)}</span>
            <span><span style={{ color: PUT }}>Puts built</span> {mv("dp", 1)}</span>
            <span><span style={{ color: CALL }}>Calls unwound</span> {mv("dc", -1)}</span>
            <span><span style={{ color: PUT }}>Puts unwound</span> {mv("dp", -1)}</span>
          </div>
          <div className="mt-2">
            <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={`${underlying} OI by strike at ${d?.at ?? at}`}>
              {rows.map((r, i) => {
                const y0 = top + i * rowH, atm = d?.atm === r.k, bh = (rowH - 6) / 2;
                return (
                  <g key={r.k}>
                    {atm && <rect x={0} y={y0} width={W} height={rowH} fill="var(--color-accent)" opacity={0.08} />}
                    <text x={labW - 8} y={y0 + rowH / 2 + 4} textAnchor="end" className={`fill-current text-[11px] num ${atm ? "font-semibold" : "opacity-70"}`}>
                      {r.k.toLocaleString("en-IN")}</text>
                    {r.c != null && <rect x={labW} y={y0 + 2} width={Math.max(0, xs(r.c) - labW)} height={bh} rx={2} fill={CALL} opacity={0.85} />}
                    {r.c0 != null && <line x1={xs(r.c0)} x2={xs(r.c0)} y1={y0 + 1} y2={y0 + 3 + bh} stroke="var(--color-text)" strokeWidth={1.5} />}
                    {r.p != null && <rect x={labW} y={y0 + 4 + bh} width={Math.max(0, xs(r.p) - labW)} height={bh} rx={2} fill={PUT} opacity={0.85} />}
                    {r.p0 != null && <line x1={xs(r.p0)} x2={xs(r.p0)} y1={y0 + 3 + bh} y2={y0 + 5 + 2 * bh} stroke="var(--color-text)" strokeWidth={1.5} />}
                    <text x={W - deltaW / 2 - 4} y={y0 + rowH / 2 + 4} textAnchor="end" className="fill-current text-[10px] num" style={{ fill: CALL }}>
                      {r.dc != null ? big(r.dc, true) : "–"}</text>
                    <text x={W - 4} y={y0 + rowH / 2 + 4} textAnchor="end" className="fill-current text-[10px] num" style={{ fill: PUT }}>
                      {r.dp != null ? big(r.dp, true) : "–"}</text>
                    <title>{`${r.k.toLocaleString("en-IN")}: calls ${big(r.c)} (${big(r.dc, true)}), puts ${big(r.p)} (${big(r.dp, true)})`}</title>
                  </g>
                );
              })}
              {spotY != null && spotY > 0 && spotY < H && (
                <g>
                  <line x1={labW} x2={W - deltaW} y1={spotY} y2={spotY} stroke="var(--color-text)" strokeDasharray="5 4" strokeWidth={1.2} />
                  <text x={W - deltaW - 4} y={spotY - 3} textAnchor="end" className="fill-current text-[10px] opacity-70">spot {px(d?.spot)}</text>
                </g>
              )}
            </svg>
          </div>
          <p className="text-xs text-muted">Bars: call (top) and put (bottom) OI at {d?.at ?? at}; the black tick is where it stood at {d?.from ?? "…"}. Right: the change in calls and puts (hover a row for the totals). The highlighted row is the ATM strike.</p>
        </>
      )}
    </div>
  );
}
