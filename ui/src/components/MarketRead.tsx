import { useQuery } from "@tanstack/react-query";
import { get, type PositionRow, type Scores, type StrategyView } from "../api";
import { mergeLevels, useLevels, useMaxPain, type Level, type LevelsData, type MaxPainData } from "./levels";
import { QUIET, strategyShort } from "./strategies";
import { Panel } from "./ui";

/**
 * The market read (user, 5 Nov 2026: "build the market read panel for the Overview"): what our own data says about each
 * index right now, in sentences: price against the previous close and the open, where it sits in the day's range and the
 * opening range, VWAP / EMA20, the nearest levels above and below (prior day, week / month, the last 12 months), max pain,
 * the near-the-money option OI and the futures over the last 15 minutes, and what our strategies are doing. A description
 * of the data, not a forecast: no strategy reads it, and OI build-up readings (A-233) and support / resistance levels (A-315)
 * showed no edge in our tests.
 */

/** /api/oi-tape: [time, spot, futures price, futures OI, ATM, band call OI, band put OI, call flow, put flow, band PCR]. */
type TapeRow = [string, number, number | null, number | null, number, number, number, number, number, number | null];
interface TapeData { expiry?: string; deferred?: boolean; series: TapeRow[] }

const WINDOW = 30;   // 30-second steps: the last 15 minutes (30 changes, from 31 steps)
const BAND = 5;      // the OI tape's default band, ATM ± 5 strikes

export interface ReadLine { label: string; text: string }
export interface ReadStrategy { strategy: string; ce: Scores; pe: Scores }

const nf = (n: number, d = 0) => n.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const signed = (n: number, d = 0) => `${n > 0 ? "+" : n < 0 ? "−" : ""}${nf(Math.abs(n), d)}`;
/** Option and futures OI in units: "+1.2M", "−32K". */
const oi = (n: number) => `${n > 0 ? "+" : n < 0 ? "−" : ""}${Math.abs(n) >= 1e5 ? `${(Math.abs(n) / 1e6).toFixed(1)}M` : `${Math.round(Math.abs(n) / 1e3)}K`}`;
const fact = (v: number | string) => (typeof v === "number" ? nf(v, Math.abs(v) < 100 ? 1 : 0) : v);
const level = (levels: Level[], name: string) => levels.find((l) => l.name === name)?.price;
const lastOf = (line: [string, number][] | undefined) => (line && line.length ? line[line.length - 1][1] : undefined);
/** Seconds of an IST "HH:mm[:ss]" time. */
const secs = (t: string) => { const [h, m, s] = t.split(":").map(Number); return h * 3600 + m * 60 + (s || 0); };

/**
 * The sentences for one index as of {@code at} ("HH:mm[:ss]", the session's latest view: a replay is read at its own
 * clock, never with later data): a level from the moment it is known, a minute line (VWAP, EMA20) once its minute has
 * closed, a 30-second OI step or max-pain step once it has ended. A line is left out when its data is missing.
 */
export function readMarket({ at, spot, view, levels, tape, maxPain, strategies, positions }: {
  at: string; spot: number; view?: StrategyView; levels?: LevelsData; tape?: TapeData; maxPain?: MaxPainData;
  strategies: ReadStrategy[]; positions: PositionRow[];
}): ReadLine[] {
  const out: ReadLine[] = [];
  const now = secs(at);
  const known = (t: string) => secs(t) <= now, minuteDone = (t: string) => secs(t) + 60 <= now, stepDone = (t: string) => secs(t) + 30 <= now;
  const lv = (levels?.levels ?? []).filter((l) => known(l.from));
  const prevClose = level(lv, "Prev close"), dayOpen = level(lv, "Day open");
  if (prevClose) {
    const parts = [`${nf(spot, 1)}, ${signed(spot - prevClose, 1)} (${signed((100 * (spot - prevClose)) / prevClose, 2)} %) from the previous close ${nf(prevClose, 1)}`];
    if (dayOpen) parts.push(`opened ${nf(dayOpen, 1)} (${dayOpen >= prevClose ? "gap up" : "gap down"} ${signed((100 * (dayOpen - prevClose)) / prevClose, 2)} %), ${signed(spot - dayOpen)} since`);
    out.push({ label: "Price", text: parts.join("; ") });
  }

  const rows = (tape?.series ?? []).filter((r) => stepDone(r[0]));
  const spots = rows.map((r) => r[1]).filter((v) => Number.isFinite(v));
  const orh = level(lv, "ORH"), orl = level(lv, "ORL");
  if (spots.length) {
    // the tape's 30-second closes miss the extremes inside a step; the opening range is from every tick
    const hi = Math.max(...spots, spot, orh ?? -Infinity), lo = Math.min(...spots, spot, orl ?? Infinity);
    const pct = hi > lo ? (100 * (spot - lo)) / (hi - lo) : 50;
    const where = pct >= 75 ? "upper quarter" : pct >= 50 ? "upper half" : pct >= 25 ? "lower half" : "lower quarter";
    let text = `day ${nf(lo)}–${nf(hi)} (${nf(hi - lo)} points), price in the ${where} (${nf(pct)} %)`;
    if (orh && orl) {
      text += spot > orh ? `; above the opening range (ORH ${nf(orh)}, ${signed(spot - orh)})`
        : spot < orl ? `; below the opening range (ORL ${nf(orl)}, ${signed(spot - orl)})`
        : `; inside the opening range ${nf(orl)}–${nf(orh)}`;
    }
    out.push({ label: "Range", text });
  }

  const vwap = lastOf(levels?.lines.vwap.filter((r) => minuteDone(r[0]))), ema = lastOf(levels?.lines.ema20.filter((r) => minuteDone(r[0])));
  if (vwap != null || ema != null) {
    const side = (v: number, name: string) => `${signed(spot - v)} ${spot >= v ? "above" : "below"} ${name} ${nf(v)}`;
    out.push({ label: "Lines", text: [vwap != null && side(vwap, "VWAP"), ema != null && side(ema, "EMA20")].filter(Boolean).join(" · ") });
  }

  const merged = levels ? mergeLevels(lv, levels.tolerancePct) : [];
  const above = merged.filter((l) => l.price > spot).sort((a, b) => a.price - b.price).slice(0, 2);
  const below = merged.filter((l) => l.price < spot).sort((a, b) => b.price - a.price).slice(0, 2);
  const named = (ls: Level[]) => ls.map((l) => `${l.name} ${nf(l.price)} (${signed(l.price - spot)})`).join(" · ");
  if (above.length || below.length) {
    out.push({ label: "Levels", text: [above.length && `above: ${named(above)}`, below.length && `below: ${named(below)}`]
      .filter(Boolean).join("; ") + (above.length === 0 ? "; nothing above in the last 12 months" : "") });
  }

  const mpRows = (maxPain?.series ?? []).filter((r) => stepDone(r[0]));
  const mp = mpRows.length ? mpRows[mpRows.length - 1][1] : undefined;
  if (mp != null) {
    out.push({ label: "Max pain", text: `${nf(mp)} for the ${maxPain?.expiry ?? ""} expiry; price ${nf(Math.abs(spot - mp))} ${spot >= mp ? "above" : "below"} it` });
  }

  if (rows.length > 1) {
    const recent = rows.slice(-(WINDOW + 1)), first = recent[0], last = recent[recent.length - 1];
    const ce = recent.slice(1).reduce((a, r) => a + r[7], 0), pe = recent.slice(1).reduce((a, r) => a + r[8], 0);
    const quiet = Math.max(Math.abs(ce), Math.abs(pe)) < 0.01 * (last[5] + last[6]);
    const reading = quiet ? "little change"
      : ce > 0 && pe <= 0 ? "calls written, puts cut: writers lean bearish (resistance above)"
      : pe > 0 && ce <= 0 ? "puts written, calls cut: writers lean bullish (support below)"
      : ce > 0 && pe > 0 ? `both sides written, ${pe > ce ? "more puts" : "more calls"}: writers expect a range`
      : "both sides cut: positions closing";
    const minutes = Math.round((recent.length - 1) / 2);
    let text = `last ${minutes} min, ATM ± ${BAND}: calls ${oi(ce)}, puts ${oi(pe)}, ${reading}`;
    const pcr = last[9], pcrOpen = rows[0][9];
    if (pcr != null && pcrOpen != null) text += `; PCR ${pcr.toFixed(2)} (${pcrOpen.toFixed(2)} at the open)`;
    out.push({ label: "Option OI", text });

    if (first[2] != null && last[2] != null && first[3] != null && last[3] != null) {
      const dp = last[2] - first[2], doi = last[3] - first[3];
      const flat = Math.abs(doi) < 0.001 * last[3];   // under 0.1 % of the open interest: no build-up either way
      const kind = flat ? "OI about flat" : dp >= 0 ? (doi > 0 ? "long build-up" : "short covering")
        : (doi > 0 ? "short build-up" : "long unwinding");
      out.push({ label: "Futures", text: `${nf(last[2], 1)} (${signed(dp, 1)} in ${minutes} min), OI ${oi(doi)}: ${kind}; `
        + `${signed(last[2] - last[1], 1)} over the index` });
    }
  }

  const vol = view?.volatility;
  if (vol && (vol.expectedMoveRemaining != null || vol.vix != null)) {
    out.push({ label: "Volatility", text: [vol.expectedMoveRemaining != null && `expected move left ±${fact(vol.expectedMoveRemaining)}`,
      vol.atmIvPct != null && `ATM IV ${fact(vol.atmIvPct)} %`, vol.vix != null && `India VIX ${fact(vol.vix)}`].filter(Boolean).join(" · ") });
  }

  const busy = strategies.flatMap((s) => (["ce", "pe"] as const)
    .filter((k) => s[k].stage && !QUIET.has(s[k].stage))
    .map((k) => `${strategyShort(s.strategy)} ${k.toUpperCase()} ${s[k].stage.toLowerCase()}`));
  const held = positions.map((p) => `${p.strategy ? strategyShort(p.strategy) + " " : ""}${p.symbol} ${signed(p.net)}`);
  out.push({ label: "Our strategies", text: [busy.length ? busy.join(" · ") : "all idle", held.length && `open: ${held.join(" · ")}`]
    .filter(Boolean).join("; ") });
  return out;
}

/** {@code date}: the session's day (today when live, the replayed day in a replay). */
export function MarketRead({ date, live, items, positions }: {
  date?: string; live: boolean; items: { underlying: string; view: StrategyView; strategies: ReadStrategy[] }[]; positions: PositionRow[];
}) {
  return (
    <Panel title="Market read" right={<span className="text-xs text-muted">what our data shows now · not a forecast</span>}>
      <div className="grid gap-6 lg:grid-cols-2">
        {items.map((i) => (
          <IndexRead key={i.underlying} date={date} live={live} underlying={i.underlying} view={i.view} strategies={i.strategies}
            positions={positions.filter((p) => p.underlying === i.underlying)} />
        ))}
      </div>
      <p className="mt-3 text-xs text-muted">
        Read from our own capture and the strategies' state, refreshed every 30 s. No strategy uses it, and in our tests OI
        build-up readings (A-233) and support / resistance levels (A-315) did not predict the next move.
      </p>
    </Panel>
  );
}

function IndexRead({ date, live, underlying, view, strategies, positions }: {
  date?: string; live: boolean; underlying: string; view: StrategyView; strategies: ReadStrategy[]; positions: PositionRow[];
}) {
  // refreshed only while the session runs
  const levels = useLevels(date, underlying, live);
  const maxPain = useMaxPain(date, underlying, live, true);
  const tape = useQuery({
    queryKey: ["oi-tape", underlying, date ?? "today", BAND],
    queryFn: () => get<TapeData>(`/api/oi-tape?underlying=${underlying}&band=${BAND}${date ? `&date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
  });
  const lines = readMarket({ at: view.time, spot: view.spot, view, levels: levels.data, tape: tape.data, maxPain: maxPain.data, strategies, positions });
  return (
    <div>
      <div className="mb-2 text-sm font-semibold">{underlying} <span className="font-normal text-muted">· {view.time} IST</span></div>
      <dl className="space-y-1.5 text-sm">
        {lines.map((l) => (
          <div key={l.label} className="grid grid-cols-[6.5rem_1fr] gap-2">
            <dt className="text-muted">{l.label}</dt>
            <dd className="num">{l.text}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
