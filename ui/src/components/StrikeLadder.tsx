import { useMemo, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { useMaxPain } from "./levels";
import { CALL, PUT, big, px, useWidth } from "./oiShared";
import { ErrorNote } from "./ui";

/**
 * The strike ladder under the OI tape: every strike's call and put OI at a moment and the change over a window. Shown as
 * it always was by default; the display options (user, 5 Nov 2026: "current panel don't change", the rest as options)
 * add a mirror layout, the change as a share of the strike's OI, who most likely traded (from the change in its IV),
 * the writers' walls and how they moved, one standard deviation to the close, gamma per strike, and a written
 * structure read. All of it describes the data; none of it is a signal our tests have found to work on its own.
 */

type Who = "WRITING" | "BUYING" | "COVERING" | "CLOSING" | null;
type N = number | null;
/**
 * /api/oi-tape/strikes rows: [strike, call OI, call OI then, put OI, put OI then, call IV, call IV then, put IV, put IV
 * then, call price, call price then, put price, put price then, call volume in the window, put volume in the window,
 * who traded the calls (by IV), the puts, the calls' gamma × OI, the puts' gamma × OI, [call OI change over 1, 5, 15
 * and 30 minutes, then put]] (null = not recorded).
 */
type Row = [number, N, N, N, N, N?, N?, N?, N?, N?, N?, N?, N?, N?, N?, Who?, Who?, N?, N?, N[]?];
interface Wall { strike: number; oi: N; change: N; since: string; from: number | null }
interface Structure { callWall: Wall | null; nearCeiling: Wall | null; nearFloor: Wall | null; putWall: Wall | null; atmIv: N; sigmaClose: N }
interface StrikesData {
  date: string; underlying: string; at?: string; from?: string; spot?: number | null; atm?: number | null; strikeStep?: number;
  since?: "open" | "close" | null; deferred?: boolean; rows: Row[]; extras?: boolean; rebuilding?: boolean; prints?: number; lastPrint?: string | null; structure?: Structure;
}

interface Side { oi: N; oi0: N; d: N; pct: N; iv: N; iv0: N; px: N; px0: N; vol: N; who: Who }
/** gc / gp: the calls' / puts' gamma × OI (index units of delta per point of the index). */
interface Strike { k: number; c: Side; p: Side; gc: N; gp: N; wc: N[]; wp: N[] }

/**
 * The ladder's window: seconds back, or since a fixed point of the day (user, 6 Nov 2026): "open", the first OI print
 * after 09:15, or "close", the pre-open OI, which is the exchange's closing OI of the day before (today's whole build-up).
 */
type Back = number | "open" | "close";
const BACKS: Back[] = [30, 60, 300, 900, 1800, 3600, 10800, "open", "close"];
const backText = (b: Back) => b === "open" ? "since open" : b === "close" ? "overall" : b < 60 ? `${b} s` : b < 3600 ? `${b / 60} min` : `${b / 3600} h`;
const BACK_HELP: Record<string, string> = {
  open: "Change since the day's first OI print after 09:15 (the exchange publishes the first intraday OI a minute or so into the session)",
  close: "Change since the previous close: the pre-open OI, which is the exchange's closing OI of the day before, so today's whole build-up",
};
/** A window that reaches back to the pre-open OI (the previous day's closing OI). */
const fromPreOpen = (d: StrikesData) => d.from === "pre-open" || d.from === "previous close";

/**
 * The windows of the "changes" column (1 h and since the open added 7 Nov 2026): their names and shades, the shortest
 * darkest; "since open" is an outline, so it shows apart from the 1-hour bar it often matches.
 */
const WINDOWS = ["1 min", "5 min", "15 min", "30 min", "1 h", "since open"];
const WINDOW_TAGS = ["1m", "5m", "15m", "30m", "1h", "open"];
const SHADES = [1, 0.62, 0.4, 0.25, 0.14, 1];
const OPEN_WINDOW = WINDOWS.length - 1;

const side = (r: Row, a: number, b: number, iv: number, ivb: number, pr: number, prb: number, vol: number, wi: number): Side => {
  const oi = (r[a] as N) ?? null, oi0 = (r[b] as N) ?? null;
  const d = oi != null && oi0 != null ? oi - oi0 : null;
  return { oi, oi0, d, pct: d != null && oi0 ? (100 * d) / oi0 : null, iv: (r[iv] as N) ?? null, iv0: (r[ivb] as N) ?? null,
    px: (r[pr] as N) ?? null, px0: (r[prb] as N) ?? null, vol: (r[vol] as N) ?? null, who: (r[wi] as Who) ?? null };
};

interface Opts {
  layout: "stacked" | "mirror"; size: "units" | "pct"; who: "off" | "iv";
  walls: boolean; sigma: boolean; gamma: boolean; read: boolean; panel: boolean; multi: boolean;
}
// the default view (user, 7 Nov 2026): puts left and calls right, units, who traded by IV, the 1 min–open changes, walls
// and gamma
const DEFAULTS: Opts = { layout: "mirror", size: "units", who: "iv", walls: true, sigma: false, gamma: true, read: false, panel: false, multi: true };
const KEY = "oi-strikes-view-v2";   // v2 (7 Nov 2026): the new default view replaces choices saved under v1

/** The display options, remembered in this browser (a private window or blocked storage just starts from the defaults). */
function useOpts(): [Opts, (o: Partial<Opts>) => void] {
  const [opts, setOpts] = useState<Opts>(() => {
    try {
      const saved = { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
      return saved.who === "premium" ? { ...saved, who: "iv" } : saved;   // the premium reading was dropped (5 Nov)
    } catch { return DEFAULTS; }
  });
  const set = (o: Partial<Opts>) => setOpts((prev) => {
    const next = { ...prev, ...o };
    try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* storage unavailable: kept for this page only */ }
    return next;
  });
  return [opts, set];
}

const WHO_TEXT: Record<Exclude<Who, null>, string> = { WRITING: "writing", BUYING: "buying", COVERING: "covering", CLOSING: "closing" };
const WHO_HELP: Record<Exclude<Who, null>, string> = {
  WRITING: "writers opening (OI up, IV down)", BUYING: "buyers opening (OI up, IV up)",
  COVERING: "writers buying back (OI down, IV up)", CLOSING: "buyers selling out (OI down, IV down)",
};
/** A label's colours: writing in the side's colour, buying a fainter version, covering red, closing grey. */
const whoStyle = (w: Exclude<Who, null>, color: string) => w === "WRITING" ? { fill: color, opacity: 0.22, text: color }
  : w === "BUYING" ? { fill: color, opacity: 0.09, text: color }
  : w === "COVERING" ? { fill: "var(--color-down)", opacity: 0.16, text: "var(--color-down)" }
  : { fill: "var(--color-muted)", opacity: 0.16, text: "var(--color-muted)" };

const strikeText = (k: number | null | undefined) => (k == null ? "–" : k.toLocaleString("en-IN"));
const pctText = (v: N) => (v == null ? "–" : `${v > 0 ? "+" : v < 0 ? "−" : ""}${Math.abs(v).toFixed(Math.abs(v) >= 10 ? 0 : 1)} %`);
const ivText = (v: N) => (v == null ? "–" : `${(100 * v).toFixed(1)} %`);
const hhmm = (t: string | null | undefined) => (t ? t.slice(0, 5) : "–");

export function Strikes({ underlying, date, at, pinned, live }: { underlying: string; date: string; at: string; pinned: boolean; live?: boolean }) {
  const [back, setBack] = useState<Back>(300);
  const [opts, setOpts] = useOpts();
  // live and unpinned, "at" follows the tape's latest step, so a new step is a new query: no timer of its own
  const q = useQuery({
    queryKey: ["oi-strikes", underlying, date, at, back],
    queryFn: () => get<StrikesData>(`/api/oi-tape/strikes?underlying=${underlying}&date=${date}&at=${at}&${typeof back === "number" ? `back=${back}` : `since=${back}`}`),
    placeholderData: (prev) => prev,          // keep the last ladder while the next loads (no 700 px collapse every 30 s)
    refetchInterval: (query) => (query.state.data?.rebuilding ? 15_000 : false),   // an old day's IV is being read again
  });
  const maxPain = useMaxPain(date, underlying, live, opts.read);
  const [ref, W] = useWidth<HTMLDivElement>();
  const d = q.data;
  const rows: Strike[] = useMemo(() => (d?.rows ?? []).map((r) => ({
    k: r[0], c: side(r, 1, 2, 5, 6, 9, 10, 13, 15), p: side(r, 3, 4, 7, 8, 11, 12, 14, 16),
    gc: (r[17] as N) ?? null, gp: (r[18] as N) ?? null,
    wc: ((r[19] as N[] | undefined) ?? []).slice(0, WINDOWS.length), wp: ((r[19] as N[] | undefined) ?? []).slice(WINDOWS.length, 2 * WINDOWS.length),
  })), [d]);
  const whoOf = (s: Side): Who => (opts.who === "iv" ? s.who : null);
  const movers = (key: "c" | "p", sign: 1 | -1) => rows.filter((r) => r[key].d != null && sign * (r[key].d as number) > 0)
    .sort((a, b) => sign * ((b[key].d as number) - (a[key].d as number))).slice(0, 2);
  const mv = (key: "c" | "p", sign: 1 | -1) => movers(key, sign).map((r) => `${strikeText(r.k)} ${big(r[key].d, true)}`).join(", ") || "–";
  const near = (r: Strike) => d?.atm == null || !d.strikeStep || Math.abs(r.k - d.atm) <= 5 * d.strikeStep + 1e-6;
  const mpAt = useMemo(() => {
    const s = (maxPain.data?.series ?? []).filter((r) => r[0] <= (d?.at ?? at));
    return s.length ? s[s.length - 1][1] : null;
  }, [maxPain.data, d?.at, at]);
  const printsText = d?.prints == null ? null : `${d.prints} OI print${d.prints === 1 ? "" : "s"}`;
  return (
    <div ref={ref} className="mt-4 border-t border-line pt-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="font-semibold">Strikes at {d?.at ?? at}{pinned ? "" : live ? " (latest)" : " (close)"}</span>
        <span className="text-muted">· change since {d?.from ?? "…"}{d?.since === "open" ? " (first OI print)" : ""}{printsText && <span title={`The exchange's OI updates near the money in this window (each contract's OI changes once per update; NSE about once a minute, BSE every 2½–3 minutes). Near-the-money OI last changed in the step from ${d?.lastPrint ?? "–"}.`}> · {printsText}</span>}</span>
        <span className="ml-2 text-muted">over</span>
        {BACKS.map((b) => (
          <button key={b} type="button" onClick={() => setBack(b)} title={BACK_HELP[b]}
            className={`rounded border px-2 py-0.5 ${back === b ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>{backText(b)}</button>
        ))}
        <button type="button" onClick={() => setOpts({ panel: !opts.panel })} aria-expanded={opts.panel}
          className={`rounded border px-2 py-0.5 ${opts.panel ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>
          Display options {opts.panel ? "▴" : "▾"}</button>
        {d?.spot != null && <span className="ml-auto num text-muted">spot {px(d.spot)} · ATM {px(d.atm)}</span>}
      </div>
      {opts.panel && <OptionsBar opts={opts} setOpts={setOpts} />}
      {q.error ? <ErrorNote error={q.error} /> : d?.deferred ? <p className="mt-2 text-sm text-muted">Built after the close.</p> : (
        <>
          {/* a short window often holds no OI print at all: the exchange publishes NIFTY option OI once a minute, BSE every ~2.7 min */}
          {/* judged near the money (ATM ± 5): deep in-the-money strikes also tick off the exchange's clock */}
          {d && rows.length > 0 && (d.prints != null ? d.prints === 0 : rows.filter(near).every((r) => (r.c.d ?? 0) === 0 && (r.p.d ?? 0) === 0)) && (
            <p className="mt-2 text-xs text-warn">No OI print between {d.from} and {d.at}: the exchange publishes {underlying === "NIFTY" || underlying === "BANKNIFTY"
              ? "NSE option OI about once a minute" : "BSE option OI every 2½–3 minutes"}, so {typeof back === "number" ? `a ${back < 60 ? `${back}-second` : `${back / 60}-minute`} window can fall between two prints. Try a longer window or another moment.` : "nothing has changed yet."}</p>
          )}
          {d && d.extras === false && (opts.who !== "off" || opts.gamma || opts.sigma || opts.read) && (
            <p className="mt-2 text-xs text-muted">{d.rebuilding ? "This day's IV, premium and volume are being read again from the ticks; the ladder fills them in by itself in a minute or two."
              : "This day was kept before IV, premium and volume were: who traded, gamma and ±1σ come back after it is rebuilt from the ticks (once, after 15:50)."}</p>
          )}
          {d?.structure && opts.read && <StructureRead d={d} rows={rows.filter(near)} allRows={rows} maxPain={mpAt} />}
          <div className="mt-2 grid gap-x-6 gap-y-1 text-xs sm:grid-cols-2 num">
            <span><span style={{ color: CALL }}>Calls built</span> {mv("c", 1)}</span>
            <span><span style={{ color: PUT }}>Puts built</span> {mv("p", 1)}</span>
            <span><span style={{ color: CALL }}>Calls unwound</span> {mv("c", -1)}</span>
            <span><span style={{ color: PUT }}>Puts unwound</span> {mv("p", -1)}</span>
          </div>
          <div className="mt-2">
            {d && rows.length > 0 && (opts.layout === "mirror"
              ? <Mirror d={d} rows={rows} W={W} opts={opts} whoOf={whoOf} underlying={underlying} />
              : <Stacked d={d} rows={rows} W={W} opts={opts} whoOf={whoOf} underlying={underlying} />)}
          </div>
          <Legend d={d} at={at} opts={opts} openIv={typeof back !== "number" || back > 1800} />
        </>
      )}
    </div>
  );
}

function Seg<T extends string>({ label, value, options, set }: { label: string; value: T; options: [T, string][]; set: (v: T) => void }) {
  return (
    <span className="inline-flex items-center gap-1">
      <span className="text-muted">{label}</span>
      {options.map(([v, text]) => (
        <button key={v} type="button" onClick={() => set(v)} aria-pressed={value === v}
          className={`rounded border px-2 py-0.5 ${value === v ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>{text}</button>
      ))}
    </span>
  );
}

function Check({ label, on, set, title }: { label: string; on: boolean; set: (v: boolean) => void; title: string }) {
  return (
    <button type="button" onClick={() => set(!on)} aria-pressed={on} title={title}
      className={`rounded border px-2 py-0.5 ${on ? "border-accent bg-accent/10" : "border-line text-muted hover:text-text"}`}>
      {on ? "✓ " : ""}{label}</button>
  );
}

function OptionsBar({ opts, setOpts }: { opts: Opts; setOpts: (o: Partial<Opts>) => void }) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2 rounded border border-line bg-panel-2 px-3 py-2 text-xs">
      <Seg label="Layout" value={opts.layout} options={[["stacked", "stacked"], ["mirror", "puts ← | → calls"]]} set={(v) => setOpts({ layout: v })} />
      <Seg label="Change" value={opts.size} options={[["units", "units"], ["pct", "% of strike"]]} set={(v) => setOpts({ size: v })} />
      <Seg label="Who traded" value={opts.who} options={[["off", "off"], ["iv", "on (by IV)"]]} set={(v) => setOpts({ who: v })} />
      <span className="inline-flex flex-wrap items-center gap-1">
        <span className="text-muted">Show</span>
        <Check label="changes 1 min–open" on={opts.multi} set={(v) => setOpts({ multi: v })} title="Each strike's OI change over 1, 5, 15, 30 minutes, 1 hour and since the open at once: nested bars, 1 min darkest, 1 h palest, since open dashed" />
        <Check label="structure read" on={opts.read} set={(v) => setOpts({ read: v })} title="A written read of the walls, who traded, volume, gamma, ±1σ and max pain" />
        <Check label="walls" on={opts.walls} set={(v) => setOpts({ walls: v })} title="The call wall, near ceiling, near floor and put wall, and where they moved from" />
        <Check label="±1σ to close" on={opts.sigma} set={(v) => setOpts({ sigma: v })} title="One standard deviation of the index to 15:30, from the ATM IV" />
        <Check label="gamma" on={opts.gamma} set={(v) => setOpts({ gamma: v })} title="Gamma × OI per strike: where the option writers' short gamma sits" />
      </span>
      <button type="button" onClick={() => setOpts({ ...DEFAULTS, panel: true })} className="ml-auto text-muted underline hover:text-text">reset</button>
    </div>
  );
}

interface Tag { text: string; short: string; color: string; title: string }

/**
 * The tags the walls put on strikes: "ceiling", "floor", "call wall", "put wall" ("ceiling = wall" when the near and far
 * wall are one strike), with ↑ / ↓ when it moved inside the window and "was …" on the strike it moved from.
 */
function wallTags(d: StrikesData): Map<number, Tag[]> {
  const out = new Map<number, Tag[]>();
  const st = d.structure;
  if (!st) return out;
  const add = (k: number, t: Tag) => out.set(k, [...(out.get(k) ?? []), t]);
  const inWindow = (w: Wall) => w.from != null && d.from != null && (fromPreOpen(d) || w.since > d.from);
  const one = (w: Wall, name: string, short: string, color: string) => {
    const arrow = inWindow(w) ? (w.strike > (w.from as number) ? " ↑" : " ↓") : "";
    add(w.strike, { text: `${name}${arrow}`, short: `${short}${arrow}`, color,
      title: `${name}: ${strikeText(w.strike)}, ${big(w.oi)} OI (${big(w.change, true)} in the window), here since ${hhmm(w.since)}${w.from != null ? `, before at ${strikeText(w.from)}` : ""}` });
    if (inWindow(w)) add(w.from as number, { text: `was ${name}`, short: "was", color: "var(--color-muted)", title: `${name} until ${hhmm(w.since)}` });
  };
  const pair = (near: Wall | null, far: Wall | null, nearName: string, farName: string, s1: string, s2: string, color: string) => {
    if (near && far && near.strike === far.strike && near.from === far.from) one(near, `${nearName} = wall`, `${s1}=W`, color);
    else {
      if (near) one(near, nearName, s1, color);
      if (far) one(far, farName, s2, color);
    }
  };
  pair(st.nearCeiling, st.callWall, "ceiling", "call wall", "ceil", "CW", CALL);
  pair(st.nearFloor, st.putWall, "floor", "put wall", "floor", "PW", PUT);
  return out;
}

/** Gamma × OI in rupees: the change in the contracts' delta, as index notional, for a 1 % move of the index (₹ crore). */
const gammaCrore = (g: number, spot: number) => (g * 0.01 * spot * spot) / 1e7;

/** One side's gamma for the tooltip: per contract unit, in delta units per point, and in ₹ crore per 1 % move. */
function gammaText(g: N, oi: N, spot: N | undefined) {
  if (g == null || !oi || spot == null) return "–";
  return `${(g / oi).toPrecision(2)} per unit · × OI ${big(oi)} = ${big(g)} units of delta per point · ₹${nfCr(gammaCrore(g, spot))} Cr per 1 % move (at today's gamma, linear: less near expiry, where gamma falls off within a few strikes)`;
}
const nfCr = (v: number) => v.toLocaleString("en-IN", { maximumFractionDigits: v >= 100 ? 0 : 1 });

function rowTitle(r: Strike, spot?: number | null) {
  const one = (name: string, s: Side) => `${name}: OI ${big(s.oi)} (${big(s.d, true)}, ${pctText(s.pct)})`
    + ` · IV ${ivText(s.iv0)} → ${ivText(s.iv)} · premium ${px(s.px0)} → ${px(s.px)} · traded ${big(s.vol)}`
    + (s.who ? ` · ${WHO_HELP[s.who]}` : "");
  const win = (w: N[]) => WINDOWS.map((m, n) => `${m} ${big(w[n] ?? null, true)}`).join(", ");
  return `${strikeText(r.k)}\n${one("calls", r.c)}\n${one("puts", r.p)}`
    + (r.wc.length ? `\nOI change · calls: ${win(r.wc)} · puts: ${win(r.wp)}` : "")
    + (r.gc != null || r.gp != null ? `\ngamma · calls: ${gammaText(r.gc, r.c.oi, spot)}\ngamma · puts: ${gammaText(r.gp, r.p.oi, spot)}` : "");
}

interface LadderProps { d: StrikesData; rows: Strike[]; W: number; opts: Opts; whoOf: (s: Side) => Who; underlying: string }

/** Below this width (a phone) the columns shrink: one-letter who-traded chips, short wall tags, a narrower gamma column. */
const COMPACT = 760;
const WHO_LETTER: Record<Exclude<Who, null>, string> = { WRITING: "W", BUYING: "B", COVERING: "C", CLOSING: "X" };

const changeText = (s: Side, opts: Opts) => (opts.size === "pct" ? pctText(s.pct) : s.d != null ? big(s.d, true) : "–");

function WhoChip({ x, y, w, who, color, compact }: { x: number; y: number; w: number; who: Who; color: string; compact?: boolean }) {
  if (!who) return null;
  const st = whoStyle(who, color);
  return (
    <g>
      <rect x={x} y={y} width={w} height={14} rx={3} fill={st.fill} opacity={st.opacity} />
      <text x={x + w / 2} y={y + 10.5} textAnchor="middle" className="text-[10px] font-semibold" style={{ fill: st.text }}>{compact ? WHO_LETTER[who] : WHO_TEXT[who]}</text>
      <title>{WHO_HELP[who]}</title>
    </g>
  );
}

/** A window's change in the ladder's unit: OI, or the share of the OI the strike had at the window's start. */
const windowValue = (change: N, now: N, opts: Opts): N => {
  if (change == null) return null;
  if (opts.size !== "pct") return change;
  const then = now != null ? now - change : null;
  return then && then > 0 ? (100 * change) / then : null;
};

/**
 * The window bars' full scale, one for every window so the bars compare: the largest change, or in "% of strike" the
 * 95th percentile (a far strike growing from 40 to 2,000 OI is +4,900 % and would flatten every other bar; it is clipped).
 */
const windowMax = (rows: Strike[], opts: Opts) => {
  const all = rows.flatMap((r) => [...r.wc.map((v) => windowValue(v, r.c.oi, opts)), ...r.wp.map((v) => windowValue(v, r.p.oi, opts))])
    .filter((v): v is number => v != null && v !== 0).map(Math.abs).sort((a, b) => a - b);
  if (!all.length) return 1;
  return opts.size === "pct" ? all[Math.min(all.length - 1, Math.floor(0.95 * all.length))] : all[all.length - 1];
};

/**
 * One side's changes over each window as nested bars from a zero line at {@code x0}: the longest bar drawn first, so a
 * shorter one is never hidden under it; "since open" as an outline on top.
 */
function WindowBars({ x0, half, y, h, w, now, max, color, opts }: { x0: number; half: number; y: number; h: number; w: N[]; now: N; max: number; color: string; opts: Opts }) {
  const bars = WINDOWS.map((_, n) => ({ n, v: windowValue(w[n] ?? null, now, opts) }))
    .filter((b): b is { n: number; v: number } => b.v != null && b.v !== 0)
    .map((b) => ({ ...b, len: Math.min(half, (Math.abs(b.v) / max) * half) }));
  const filled = bars.filter((b) => b.n !== OPEN_WINDOW).sort((a, b) => b.len - a.len), open = bars.find((b) => b.n === OPEN_WINDOW);
  return (
    <g>
      {filled.map(({ n, v, len }) => <rect key={n} x={v > 0 ? x0 : x0 - len} y={y} width={Math.max(1, len)} height={h} fill={color} opacity={SHADES[n]} />)}
      {open && <rect x={(open.v > 0 ? x0 : x0 - open.len) + 0.5} y={y + 0.5} width={Math.max(1, open.len - 1)} height={Math.max(1, h - 1)}
        fill="none" stroke={color} strokeWidth={1} strokeDasharray="2 1.5" />}
    </g>
  );
}

/** The column's header: the zero line's caption and a swatch per window. */
function WindowHeader({ x, y }: { x: number; y: number }) {
  // each entry a swatch, its tag (about 5.5 px a character at 9 px) and a gap
  const at = WINDOW_TAGS.map((_, n) => WINDOW_TAGS.slice(0, n).reduce((w, m) => w + 12 + m.length * 5.5 + 6, 0));
  return (
    <g className="text-[9px]">
      {WINDOW_TAGS.map((m, n) => (
        <g key={m}>
          {n === OPEN_WINDOW
            ? <rect x={x + at[n] + 0.5} y={y - 7.5} width={9} height={7} fill="none" stroke="var(--color-muted)" strokeDasharray="2 1.5" />
            : <rect x={x + at[n]} y={y - 8} width={10} height={8} fill="var(--color-muted)" opacity={SHADES[n]} />}
          <text x={x + at[n] + 12} y={y} className="fill-current opacity-70">{m}</text>
        </g>
      ))}
      <title>{`OI change over 1, 5, 15, 30 minutes, 1 hour and since the day's first OI print, from the zero line (right: added, left: cut); one scale for all, 1 h palest, since open dashed.`}</title>
    </g>
  );
}

/** The y of a price on a ladder whose first row is {@code first} (rows a strike step apart). */
const priceY = (first: number, step: number, top: number, rowH: number) => (v: number) => top + ((first - v) / step + 0.5) * rowH;

function SigmaBand({ d, rows, x0, x1, top, rowH }: { d: StrikesData; rows: Strike[]; x0: number; x1: number; top: number; rowH: number }) {
  const s = d.structure?.sigmaClose, spot = d.spot, step = d.strikeStep;
  if (s == null || spot == null || !step || !rows.length) return null;
  const y = priceY(rows[0].k, step, top, rowH), yHi = Math.max(top, y(spot + s)), yLo = Math.min(top + rows.length * rowH, y(spot - s));
  if (yLo <= yHi) return null;
  return (
    <g>
      <rect x={x0} y={yHi} width={Math.max(0, x1 - x0)} height={yLo - yHi} fill="var(--color-accent)" opacity={0.07} />
      <text x={x0 + 4} y={yHi + 10} className="fill-current text-[10px] opacity-70">±1σ to 15:30: {strikeText(Math.round(spot - s))}–{strikeText(Math.round(spot + s))}</text>
      <title>{`One standard deviation of the index from now to 15:30 (±${s.toFixed(0)} points), from the ATM IV ${ivText(d.structure?.atmIv ?? null)}, with the option's variance shared over the trading minutes left to expiry. About two days in three close inside it if the IV is right.`}</title>
    </g>
  );
}

/** The strike's call gamma × OI (top, call colour) and put gamma × OI (bottom, put colour), on one scale for the ladder. */
function GammaBar({ x, y, w, gc, gp, max }: { x: number; y: number; w: number; gc: N; gp: N; max: number }) {
  if (!(max > 0)) return null;
  const len = (g: N) => (g == null || g <= 0 ? 0 : Math.max(1, (g / max) * w));
  return (
    <g>
      {len(gc) > 0 && <rect x={x} y={y} width={len(gc)} height={5} rx={1} fill={CALL} opacity={0.85} />}
      {len(gp) > 0 && <rect x={x} y={y + 6} width={len(gp)} height={5} rx={1} fill={PUT} opacity={0.85} />}
    </g>
  );
}

/** The largest single side's gamma × OI on the ladder (the gamma column's full scale). */
const gammaMax = (rows: Strike[]) => Math.max(0, ...rows.flatMap((r) => [r.gc ?? 0, r.gp ?? 0]));

/**
 * An SVG {@code need} wide in a box {@code W} wide: as wide as the box when it fits, else at its own width with a
 * horizontal scroll (every column on, a phone), never overlapping columns.
 */
function Frame({ W, need, label, children }: { W: number; need: number; label: string; children: ReactNode }) {
  return need <= W ? <>{children}</> : <div className="overflow-x-auto" role="region" aria-label={`${label} (scroll sideways)`}>{children}</div>;
}

/** The ladder as it always was: call (top) and put (bottom) bars from the left, the changes on the right; options add columns. */
function Stacked({ d, rows, W: box, opts, whoOf, underlying }: LadderProps) {
  const tags = opts.walls ? wallTags(d) : new Map<number, Tag[]>();
  const mx = Math.max(1, ...rows.flatMap((r) => [r.c.oi ?? 0, r.p.oi ?? 0, r.c.oi0 ?? 0, r.p.oi0 ?? 0]));
  const gMax = gammaMax(rows);
  const compact = box < COMPACT, who = opts.who !== "off", chipW = compact ? 16 : 58, dW = compact ? 46 : 56;
  const rowH = 22, tagW = opts.walls ? (compact ? 44 : 84) : 0, labW = 64 + tagW, deltaW = who ? 2 * (dW + chipW + 6) : 112,
    gW = opts.gamma ? (compact ? 36 : 64) : 0, mW = opts.multi ? (compact ? 140 : 260) : 0, top = opts.multi ? 16 : 6;
  const minBar = compact ? 60 : 120, need = labW + minBar + gW + mW + deltaW + 8, W = Math.max(box, need);
  const barW = W - labW - deltaW - gW - mW - 8;
  const mX = labW + barW + gW + 8, mHalf = mW / 2 - 6, mMax = opts.multi ? windowMax(rows, opts) : 1;
  const H = top + rows.length * rowH + 6;
  const xs = (v: number) => labW + (v / mx) * barW;
  const y = d.strikeStep ? priceY(rows[0].k, d.strikeStep, top, rowH) : null;
  const spotY = d.spot != null && y && rows.length > 1 ? y(d.spot) : null;
  const cDx = who ? W - deltaW + dW : W - deltaW / 2 - 4, pDx = who ? W - deltaW / 2 + dW : W - 4;
  return (
    <Frame W={box} need={need} label="strike ladder">
    <svg viewBox={`0 0 ${W} ${H}`} width={W > box ? W : "100%"} role="img" aria-label={`${underlying} OI by strike at ${d.at}`}>
      {opts.multi && <WindowHeader x={mX + 4} y={11} />}
      {opts.sigma && <SigmaBand d={d} rows={rows} x0={labW} x1={labW + barW} top={top} rowH={rowH} />}
      {rows.map((r, i) => {
        const y0 = top + i * rowH, atm = d.atm === r.k, bh = (rowH - 6) / 2;
        return (
          <g key={r.k}>
            {atm && <rect x={0} y={y0} width={W} height={rowH} fill="var(--color-accent)" opacity={0.08} />}
            {(tags.get(r.k) ?? []).map((t, n) => (
              <text key={n} x={tagW - 4} y={y0 + rowH / 2 + 4 + (n - ((tags.get(r.k)?.length ?? 1) - 1) / 2) * 10} textAnchor="end"
                className="text-[10px] italic" style={{ fill: t.color }}>{compact ? t.short : t.text}<title>{t.title}</title></text>
            ))}
            <text x={labW - 8} y={y0 + rowH / 2 + 4} textAnchor="end" className={`fill-current text-[11px] num ${atm ? "font-semibold" : "opacity-70"}`}>
              {strikeText(r.k)}</text>
            {r.c.oi != null && <rect x={labW} y={y0 + 2} width={Math.max(0, xs(r.c.oi) - labW)} height={bh} rx={2} fill={CALL} opacity={0.85} />}
            {r.c.oi0 != null && <line x1={xs(r.c.oi0)} x2={xs(r.c.oi0)} y1={y0 + 1} y2={y0 + 3 + bh} stroke="var(--color-text)" strokeWidth={1.5} />}
            {r.p.oi != null && <rect x={labW} y={y0 + 4 + bh} width={Math.max(0, xs(r.p.oi) - labW)} height={bh} rx={2} fill={PUT} opacity={0.85} />}
            {r.p.oi0 != null && <line x1={xs(r.p.oi0)} x2={xs(r.p.oi0)} y1={y0 + 3 + bh} y2={y0 + 5 + 2 * bh} stroke="var(--color-text)" strokeWidth={1.5} />}
            {opts.gamma && <GammaBar x={labW + barW + 8} y={y0 + 5} w={gW - 14} gc={r.gc} gp={r.gp} max={gMax} />}
            {opts.multi && <>
              <line x1={mX + mW / 2} x2={mX + mW / 2} y1={y0} y2={y0 + rowH} stroke="var(--color-line)" />
              <WindowBars x0={mX + mW / 2} half={mHalf} y={y0 + 2} h={bh} w={r.wc} now={r.c.oi} max={mMax} color={CALL} opts={opts} />
              <WindowBars x0={mX + mW / 2} half={mHalf} y={y0 + 4 + bh} h={bh} w={r.wp} now={r.p.oi} max={mMax} color={PUT} opts={opts} />
            </>}
            <text x={cDx} y={y0 + rowH / 2 + 4} textAnchor="end" className="fill-current text-[10px] num" style={{ fill: CALL }}>{changeText(r.c, opts)}</text>
            <text x={pDx} y={y0 + rowH / 2 + 4} textAnchor="end" className="fill-current text-[10px] num" style={{ fill: PUT }}>{changeText(r.p, opts)}</text>
            {who && <WhoChip x={cDx + 4} y={y0 + 4} w={chipW} who={whoOf(r.c)} color={CALL} compact={compact} />}
            {who && <WhoChip x={pDx + 4} y={y0 + 4} w={chipW} who={whoOf(r.p)} color={PUT} compact={compact} />}
            <title>{rowTitle(r, d.spot)}</title>
          </g>
        );
      })}
      {spotY != null && spotY > 0 && spotY < H && (
        <g>
          <line x1={labW} x2={labW + barW} y1={spotY} y2={spotY} stroke="var(--color-text)" strokeDasharray="5 4" strokeWidth={1.2} />
          <text x={labW + barW - 4} y={spotY - 3} textAnchor="end" className="fill-current text-[10px] opacity-70">spot {px(d.spot)}</text>
        </g>
      )}
    </svg>
    </Frame>
  );
}

/** Puts to the left of the strike, calls to the right, the changes on the outside; wall tags sit at the bar ends. */
function Mirror({ d, rows, W: box, opts, whoOf, underlying }: LadderProps) {
  const tags = opts.walls ? wallTags(d) : new Map<number, Tag[]>();
  const mx = Math.max(1, ...rows.flatMap((r) => [r.c.oi ?? 0, r.p.oi ?? 0, r.c.oi0 ?? 0, r.p.oi0 ?? 0]));
  const gMax = gammaMax(rows);
  // compact (a phone): one-letter chips, and the walls colour the strike label instead of a tag beside the bars
  const compact = box < COMPACT, who = opts.who !== "off", chipW = compact ? 16 : 58;
  const rowH = 22, top = 20, dW = compact ? 46 : 52, cols = dW + (who ? chipW + 6 : 0), gW = opts.gamma ? (compact ? 36 : 64) : 0,
    tagW = opts.walls && !compact ? 84 : 0, cw = compact ? 56 : 66, mW = opts.multi ? (compact ? 90 : 180) : 0;
  const minHalf = compact ? 50 : 60, need = 2 * cols + 2 * mW + gW + cw + 2 * (tagW + 6 + minHalf), W = Math.max(box, need);
  const half = (W - 2 * cols - 2 * mW - gW - cw) / 2 - tagW - 6;
  const cx = cols + mW + tagW + half + cw / 2 + 4;
  const mMax = opts.multi ? windowMax(rows, opts) : 1, mHalf = mW / 2 - 5;
  const H = top + rows.length * rowH + 6;
  const len = (v: number) => (v / mx) * half;
  const y = d.strikeStep ? priceY(rows[0].k, d.strikeStep, top, rowH) : null;
  const spotY = d.spot != null && y && rows.length > 1 ? y(d.spot) : null;
  const left = cx - cw / 2, right = cx + cw / 2, outR = W - gW - cols, mL = cols + mW / 2, mR = outR - mW / 2;
  return (
    <Frame W={box} need={need} label="strike ladder">
    <svg viewBox={`0 0 ${W} ${H}`} width={W > box ? W : "100%"} role="img" aria-label={`${underlying} OI by strike at ${d.at}, puts left, calls right`}>
      <text x={left - 6} y={12} textAnchor="end" className="text-[10px] font-semibold" style={{ fill: PUT }}>puts ← OI</text>
      <text x={right + 6} y={12} className="text-[10px] font-semibold" style={{ fill: CALL }}>OI → calls</text>
      {opts.gamma && <text x={W - gW / 2} y={12} textAnchor="middle" className="fill-current text-[10px] opacity-60">gamma</text>}
      {opts.multi && <WindowHeader x={cols + 2} y={12} />}
      {opts.sigma && <SigmaBand d={d} rows={rows} x0={cols} x1={outR} top={top} rowH={rowH} />}
      {rows.map((r, i) => {
        const y0 = top + i * rowH, atm = d.atm === r.k, bh = 12, by = y0 + (rowH - bh) / 2;
        const pl = r.p.oi != null ? len(r.p.oi) : 0, cl = r.c.oi != null ? len(r.c.oi) : 0;
        const rowTags = tags.get(r.k) ?? [];
        const putTags = compact ? [] : rowTags.filter((t) => t.color === PUT || t.text.includes("floor") || t.text.includes("put wall"));
        const callTags = compact ? [] : rowTags.filter((t) => !putTags.includes(t));
        const labelColor = compact ? rowTags.find((t) => t.color !== "var(--color-muted)")?.color : undefined;
        return (
          <g key={r.k}>
            {atm && <rect x={0} y={y0} width={W} height={rowH} fill="var(--color-accent)" opacity={0.08} />}
            <text x={dW - 4} y={y0 + rowH / 2 + 4} textAnchor="end" className="text-[10px] num" style={{ fill: PUT }}>{changeText(r.p, opts)}</text>
            {who && <WhoChip x={dW + 2} y={y0 + 4} w={chipW} who={whoOf(r.p)} color={PUT} compact={compact} />}
            {opts.multi && <>
              <line x1={mL} x2={mL} y1={y0} y2={y0 + rowH} stroke="var(--color-line)" />
              <WindowBars x0={mL} half={mHalf} y={by} h={bh} w={r.wp} now={r.p.oi} max={mMax} color={PUT} opts={opts} />
              <line x1={mR} x2={mR} y1={y0} y2={y0 + rowH} stroke="var(--color-line)" />
              <WindowBars x0={mR} half={mHalf} y={by} h={bh} w={r.wc} now={r.c.oi} max={mMax} color={CALL} opts={opts} />
            </>}
            {r.p.oi != null && <rect x={left - pl} y={by} width={pl} height={bh} rx={2} fill={PUT} opacity={0.85} />}
            {r.p.oi0 != null && <line x1={left - len(r.p.oi0)} x2={left - len(r.p.oi0)} y1={by - 2} y2={by + bh + 2} stroke="var(--color-text)" strokeWidth={1.5} />}
            {putTags.length > 0 && <text x={left - Math.max(pl, r.p.oi0 != null ? len(r.p.oi0) : 0) - 6} y={y0 + rowH / 2 + 4} textAnchor="end"
              className="text-[10px] italic" style={{ fill: putTags[0].color }}>{putTags.map((t) => t.text).join(" · ")}<title>{putTags.map((t) => t.title).join("\n")}</title></text>}
            <text x={cx} y={y0 + rowH / 2 + 4} textAnchor="middle" className={`fill-current text-[11px] num ${atm || labelColor ? "font-semibold" : "opacity-80"}`}
              style={labelColor ? { fill: labelColor } : undefined}>{strikeText(r.k)}{rowTags.length > 0 && <title>{rowTags.map((t) => t.title).join("\n")}</title>}</text>
            {r.c.oi != null && <rect x={right} y={by} width={cl} height={bh} rx={2} fill={CALL} opacity={0.85} />}
            {r.c.oi0 != null && <line x1={right + len(r.c.oi0)} x2={right + len(r.c.oi0)} y1={by - 2} y2={by + bh + 2} stroke="var(--color-text)" strokeWidth={1.5} />}
            {callTags.length > 0 && <text x={right + Math.max(cl, r.c.oi0 != null ? len(r.c.oi0) : 0) + 6} y={y0 + rowH / 2 + 4}
              className="text-[10px] italic" style={{ fill: callTags[0].color }}>{callTags.map((t) => t.text).join(" · ")}<title>{callTags.map((t) => t.title).join("\n")}</title></text>}
            <text x={outR + dW - 4} y={y0 + rowH / 2 + 4} textAnchor="end" className="text-[10px] num" style={{ fill: CALL }}>{changeText(r.c, opts)}</text>
            {who && <WhoChip x={outR + dW + 2} y={y0 + 4} w={chipW} who={whoOf(r.c)} color={CALL} compact={compact} />}
            {opts.gamma && <GammaBar x={W - gW + 6} y={y0 + 5} w={gW - 10} gc={r.gc} gp={r.gp} max={gMax} />}
            <title>{rowTitle(r, d.spot)}</title>
          </g>
        );
      })}
      {spotY != null && spotY > top && spotY < H && (
        <g>
          {/* left and right of the strike labels, so the ATM label stays readable */}
          <line x1={cols + mW} x2={left - 2} y1={spotY} y2={spotY} stroke="var(--color-text)" strokeDasharray="5 4" strokeWidth={1.2} />
          <line x1={right + 2} x2={outR - mW} y1={spotY} y2={spotY} stroke="var(--color-text)" strokeDasharray="5 4" strokeWidth={1.2} />
          <text x={outR - mW - 4} y={spotY - 3} textAnchor="end" className="fill-current text-[10px] opacity-70">spot {px(d.spot)}</text>
        </g>
      )}
    </svg>
    </Frame>
  );
}

/** openIv: the window is one the server reads who traded for from the open's IV when it reaches the pre-open OI. */
function Legend({ d, at, opts, openIv }: { d?: StrikesData; at: string; opts: Opts; openIv: boolean }) {
  const parts: ReactNode[] = [];
  // each sentence is keyed when rendered (an array of fragments)
  parts.push(opts.layout === "mirror"
    ? <>Bars: put OI to the left, call OI to the right at {d?.at ?? at}; the tick is where it stood at {d?.from ?? "…"}. Outside: the change{opts.size === "pct" ? " as a share of the strike's earlier OI" : ""}.</>
    : <>Bars: call (top) and put (bottom) OI at {d?.at ?? at}; the tick is where it stood at {d?.from ?? "…"}. Right: the change in calls and puts{opts.size === "pct" ? " as a share of the strike's earlier OI" : ""}.</>);
  parts.push(<> Hover a row for its OI, IV, premium and volume. The highlighted row is the ATM strike.</>);
  if (opts.who !== "off") parts.push(<> Who traded is read from the OI change against the change in the strike's IV (OI up and IV down: writers opening; OI down and IV up: writers buying back); blank when either change is small (OI under 0.5 % of the strike, IV under 0.1 point). Not from the premium: it mostly follows the index, so a rally would read as call buying and put writing whoever traded. An inference, not a record of who traded. On a narrow screen the labels are W, B, C and X.{d && fromPreOpen(d) && openIv ? " Over a window from the previous close the IV then is the strike's first IV of today (none is recorded before the open), so the label weighs a day's net OI change against the IV move since the open: many traders' opening and closing netted into one word." : ""}</>);
  if (opts.multi) parts.push(<> Changes 1 min–open: each strike's OI change over the last 1, 5, 15, 30 minutes and 1 hour, and since the day's first OI print, at once, as nested bars from the zero line (right: added, left: cut), 1 min darkest, 1 h palest and since open a dashed outline, one scale for all; the longest bar is drawn first so none hides another; a window reaching back before the open starts from the pre-open OI (the previous close){opts.size === "pct" ? " (as a share of the strike's OI at each window's start)" : ""}; hover a row for the numbers. A 1-minute window holds about one exchange OI update.</>);
  if (opts.walls) parts.push(<> Walls: the largest call OI above spot (call wall: within ATM ± 15; ceiling: within 6 strikes) and the largest put OI below it (put wall, floor); a wall moves only when another strike holds 5 % more OI; ↑ / ↓ and "was" mark a move inside the window.</>);
  if (opts.gamma) parts.push(<> Gamma: Black-Scholes gamma from each side's IV times its OI, calls (top) and puts (bottom) on one scale; hover a row for the numbers: per contract unit, in units of delta per point of the index, and in ₹ crore of delta per 1 % move. Option writers are short this gamma, so it shows where their risk changes fastest as the price moves; whether they hedge it, and so whether it speeds moves up or pins them, it does not say (A-164: no usable regime filter).</>);
  return <p className="text-xs text-muted">{parts.map((p, n) => <span key={n}>{p}</span>)}</p>;
}

const fmtM = (v: number) => big(v, true);

/** The written read of the structure (shown when "structure read" is on): what the walls, flow, volume and gamma show. */
function StructureRead({ d, rows, allRows, maxPain }: { d: StrikesData; rows: Strike[]; allRows: Strike[]; maxPain: number | null }) {
  const st = d.structure as Structure;
  const inWindow = (w: Wall | null) => !!w && w.from != null && d.from != null && (fromPreOpen(d) || w.since > d.from);
  const wallLine = (w: Wall | null, what: string) => {
    if (!w) return `no ${what} in range`;
    const moved = w.from != null ? `${w.strike > w.from ? "moved up" : "moved down"} from ${strikeText(w.from)} at ${hhmm(w.since)}` : `since ${hhmm(w.since)}`;
    return `${strikeText(w.strike)} (${big(w.oi)} OI, ${big(w.change, true)} in the window), ${moved}`;
  };
  // flow by who traded, near the money (ATM ± 5)
  const flow = (k: "c" | "p") => {
    let writers = 0, buyers = 0, read = 0, all = 0, vol = 0, dOi = 0;
    rows.forEach((r) => {
      const s = r[k], w = s.who;
      if (s.d == null) return;
      all += Math.abs(s.d); dOi += s.d; vol += s.vol ?? 0;
      if (w === "WRITING" || w === "COVERING") { writers += s.d; read += Math.abs(s.d); }
      if (w === "BUYING" || w === "CLOSING") { buyers += s.d; read += Math.abs(s.d); }
    });
    return { writers, buyers, share: all > 0 ? (100 * read) / all : null, vol, dOi };
  };
  const fc = flow("c"), fp = flow("p");
  const gOf = (r: Strike) => (r.gc ?? 0) + (r.gp ?? 0);
  const gRows = allRows.filter((r) => gOf(r) > 0);
  const gTotal = gRows.reduce((a, r) => a + gOf(r), 0);
  const gTop = [...gRows].sort((a, b) => gOf(b) - gOf(a)).slice(0, 3);
  const gShare = gTotal > 0 ? (100 * gTop.reduce((a, r) => a + gOf(r), 0)) / gTotal : null;
  // near the price (±1σ to the close): call gamma above it against put gamma below it, in ₹ crore per 1 % move
  const nearBand = st.sigmaClose != null && d.spot != null ? st.sigmaClose : null;
  const near = (r: Strike) => nearBand != null && Math.abs(r.k - (d.spot as number)) <= nearBand + (d.strikeStep ?? 0) / 2;
  const callNear = gRows.filter((r) => near(r) && r.k >= (d.spot as number)).reduce((a, r) => a + (r.gc ?? 0), 0);
  const putNear = gRows.filter((r) => near(r) && r.k <= (d.spot as number)).reduce((a, r) => a + (r.gp ?? 0), 0);
  const sig = st.sigmaClose != null && d.spot != null ? `${strikeText(Math.round(d.spot - st.sigmaClose))}–${strikeText(Math.round(d.spot + st.sigmaClose))} (±${st.sigmaClose.toFixed(0)}, ATM IV ${ivText(st.atmIv)})` : null;
  const shifts: string[] = [];
  if (inWindow(st.nearCeiling)) shifts.push(`the ceiling ${(st.nearCeiling as Wall).strike > ((st.nearCeiling as Wall).from as number) ? "rose" : "fell"}`);
  if (inWindow(st.nearFloor)) shifts.push(`the floor ${(st.nearFloor as Wall).strike > ((st.nearFloor as Wall).from as number) ? "rose" : "fell"}`);
  const volText = (f: ReturnType<typeof flow>) => f.vol > 0 ? `${big(f.vol)} traded, OI ${fmtM(f.dOi)} (${f.dOi >= 0 ? "+" : "−"}${Math.abs((100 * f.dOi) / f.vol).toFixed(0)} % of it)` : "volume not recorded";
  // the far wall is named only when it is another strike than the near one
  const farWall = (near: Wall | null, far: Wall | null, what: string) =>
    !far ? "" : near && near.strike === far.strike ? ` · also the ${what}` : ` · ${what} ${wallLine(far, what)}`;
  const notKept = <span className="text-muted">not kept for this day yet</span>;
  const lines: [string, ReactNode][] = [
    ["Ceiling", <>{wallLine(st.nearCeiling, "ceiling")}{farWall(st.nearCeiling, st.callWall, "call wall")}</>],
    ["Floor", <>{wallLine(st.nearFloor, "floor")}{farWall(st.nearFloor, st.putWall, "put wall")}</>],
    ["Who traded", d.extras === false ? notKept : <>calls: writers {fmtM(fc.writers)}, buyers {fmtM(fc.buyers)}{fc.share != null ? ` (${fc.share.toFixed(0)} % of the change readable)` : ""} · puts: writers {fmtM(fp.writers)}, buyers {fmtM(fp.buyers)}{fp.share != null ? ` (${fp.share.toFixed(0)} % readable)` : ""} <span className="text-muted">· ATM ± 5, by IV</span></>],
    ["Volume", d.extras === false ? notKept : <>calls {volText(fc)} · puts {volText(fp)} <span className="text-muted">· ATM ± 5 in the window</span></>],
  ];
  if (sig) lines.push(["±1σ to 15:30", <>{sig}</>]);
  if (gShare != null) lines.push(["Gamma", <>heaviest at {gTop.map((r) => `${strikeText(r.k)} (${(r.gp ?? 0) > (r.gc ?? 0) ? "puts" : "calls"})`).join(", ")}: {gShare.toFixed(0)} % of the ladder's gamma × OI
    {nearBand != null && d.spot != null && callNear + putNear > 0 && <>; within ±1σ, call gamma above the price ₹{nfCr(gammaCrore(callNear, d.spot))} Cr and put gamma below it ₹{nfCr(gammaCrore(putNear, d.spot))} Cr per 1 % move{callNear > 0 && putNear > 0 ? ` (${putNear >= callNear ? `puts ${(putNear / callNear).toFixed(1)}×` : `calls ${(callNear / putNear).toFixed(1)}×`})` : ""}</>}</>]);
  if (maxPain != null && d.spot != null) lines.push(["Max pain", <>{strikeText(maxPain)}, price {px(Math.abs(d.spot - maxPain))} {d.spot >= maxPain ? "above" : "below"} it</>]);
  lines.push(["In this window", <>{shifts.length ? `${shifts.join(" and ")}` : "the ceiling and floor held their strikes"}{d.prints != null ? `; ${d.prints} OI print${d.prints === 1 ? "" : "s"}` : ""}</>]);
  return (
    <div className="mt-3 rounded border border-line bg-panel-2 px-3 py-2 text-xs">
      <dl className="grid gap-x-3 gap-y-1 sm:grid-cols-[7rem_1fr]">
        {lines.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted">{k}</dt>
            <dd className="num">{v}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-2 text-muted">Describes the data at this moment. In our tests near-the-money writer flow did not predict the next hour (A-068), the build-up reading gave no edge (A-233), and OI floors and ceilings held no better than chance at expiry (A-154).</p>
    </div>
  );
}
