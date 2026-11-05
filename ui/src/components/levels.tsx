import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";

/** A price level and the minute (IST "HH:mm") it became knowable: never drawn before then. */
export interface Level { name: string; group: "prior" | "today" | "period" | "months"; price: number; from: string }
/** A tested support / resistance zone: from its second confirmed touch until a minute close beyond it. */
/** {@code steps}: [time, lo, hi, touches] from the forming touch and after each later one (each as confirmed). */
export interface Zone { kind: "support" | "resistance"; lo: number; hi: number; touches: number; from: string; until: string | null;
  steps?: [string, number, number, number][] }
export interface LevelsData {
  date: string; underlying: string; levels: Level[]; zones: Zone[];
  lines: { vwap: [string, number][]; ema20: [string, number][] }; tolerancePct: number;
  /** "bars": a day without the own tick capture, levels from the stored one-minute bars. */
  source?: "ticks" | "bars";
}

export interface LevelToggles {
  prior: boolean; today: boolean; period: boolean; months: boolean; lines: boolean; maxPain: boolean; zones: "strong" | "all" | "off";
  /** 15:10-15:30: the options' synthetic index and the closing auction's indicative close */
  auction: boolean;
}
const KEY = "chart-level-toggles";
const DEFAULT: LevelToggles = { prior: true, today: true, period: true, months: true, lines: true, maxPain: true, zones: "strong", auction: true };

/** /api/oi-tape/max-pain: [time, max-pain strike, strikes with OI] where it changes, and the last step. */
export interface MaxPainData { date: string; underlying: string; expiry?: string; deferred?: boolean; series: [string, number, number][] }

/**
 * /api/close-auction (user, 5 Nov 2026): every 15 s from 15:10, [time, synthetic index from the options, the auction's
 * indicative close, % of the index weight priced in the auction]; the 15:15 frozen value and the official close.
 */
export interface CloseAuctionData {
  date: string; underlying: string; expiry?: string | null; freeze?: number | null; close?: number | null; closeAt?: string | null;
  basis?: number | null; weightsCovered?: number | null; deferred?: boolean; series: [string, number | null, number | null, number | null][];
}

/** Distinct from the price line (blue) and from each other; plain hex so both chart kinds can use them. */
export const LEVEL_COLORS = {
  prior: "#f59e0b", today: "#14b8a6", period: "#60a5fa", months: "#94a3b8", vwap: "#ec4899", ema20: "#a78bfa",
  maxPain: "#65a30d", support: "#22c55e", resistance: "#ef4444", synthetic: "#9333ea", auction: "#0891b2",
};

export const levelColor = (group: Level["group"]) => LEVEL_COLORS[group];

/** Short names for the price-axis tags: "ORH / Prior ORL" → "ORH/pORL". */
export function tagName(name: string) {
  return name.replace(/Prior /g, "p").replace("Prev close", "PC").replace("Day open", "Open")
    .replace("2-day high", "2dH").replace("2-day low", "2dL").replace(/ high\b/g, "H").replace(/ low\b/g, "L")
    .replace(/ \/ /g, "/");
}

export function useLevels(date: string | undefined, underlying: string, live?: boolean) {
  return useQuery({
    queryKey: ["levels", date ?? "today", underlying],
    queryFn: () => get<LevelsData>(`/api/levels?underlying=${underlying}${date ? `&date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
    enabled: !!underlying,
  });
}

/** The nearest expiry's max pain through the day (own capture's OI), for the price chart. */
export function useMaxPain(date: string | undefined, underlying: string, live: boolean | undefined, on: boolean) {
  return useQuery({
    queryKey: ["max-pain", date ?? "today", underlying],
    queryFn: () => get<MaxPainData>(`/api/oi-tape/max-pain?underlying=${underlying}${date ? `&date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
    enabled: !!underlying && on,
  });
}

/** The closing auction's two estimates for the price chart (asked for from 15:05 when live; a finished day once). */
export function useCloseAuction(date: string | undefined, underlying: string, live: boolean | undefined, on: boolean, until?: string) {
  // the day's clock: a replay's own (its day is finished: read once), else IST now
  const clock = until ?? new Date(Date.now() + 5.5 * 3600_000).toISOString().slice(11, 19);
  return useQuery({
    queryKey: ["close-auction", date ?? "today", underlying],
    queryFn: () => get<CloseAuctionData>(`/api/close-auction?underlying=${underlying}${date ? `&date=${date}` : ""}`),
    refetchInterval: live && !until ? 30_000 : false,
    enabled: !!underlying && on && (!live || clock >= "15:05"),
  });
}

export function useLevelToggles(): [LevelToggles, (t: LevelToggles) => void] {
  const [toggles, setToggles] = useState<LevelToggles>(() => {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) return { ...DEFAULT, ...(JSON.parse(raw) as Partial<LevelToggles>) };
    } catch { /* storage unavailable: defaults */ }
    return DEFAULT;
  });
  const update = (t: LevelToggles) => {
    setToggles(t);
    try { localStorage.setItem(KEY, JSON.stringify(t)); } catch { /* not persisted */ }
  };
  return [toggles, update];
}

/** Zones shown for the toggle: "strong" = 3 or more touches. */
export function visibleZones(zones: Zone[], t: LevelToggles) {
  return t.zones === "off" ? [] : zones.filter((z) => t.zones === "all" || z.touches >= 3);
}

export function visibleLevels(levels: Level[], t: LevelToggles) {
  return levels.filter((l) => t[l.group]);
}

/** Which colour a merged level takes: the most recent kind of level in it. */
const GROUP_RANK: Record<Level["group"], number> = { today: 3, prior: 2, period: 1, months: 0 };

/** Levels closer than the tolerance read as one: "ORL / Prior ORL 22,508". */
export function mergeLevels(levels: Level[], tolerancePct: number): Level[] {
  const sorted = [...levels].sort((a, b) => a.price - b.price);
  const out: Level[] = [];
  for (const l of sorted) {
    const last = out[out.length - 1];
    if (last && Math.abs(l.price - last.price) <= (last.price * tolerancePct) / 100) {
      last.name = `${last.name} / ${l.name}`;
      if (l.from < last.from) last.from = l.from;
      if (GROUP_RANK[l.group] > GROUP_RANK[last.group]) last.group = l.group;
    } else {
      out.push({ ...l });
    }
  }
  return out;
}

const chip = (on: boolean) =>
  `rounded-full border px-2.5 py-1 ${on ? "border-line bg-panel-2" : "border-transparent text-muted opacity-60"}`;

export function LevelTogglesBar({ toggles, onChange }: { toggles: LevelToggles; onChange: (t: LevelToggles) => void }) {
  const zoneNext = { strong: "all", all: "off", off: "strong" } as const;
  return (
    <div className="flex flex-wrap items-center gap-1 text-xs" role="group" aria-label="Chart levels">
      <span className="text-muted">levels</span>
      <button type="button" aria-pressed={toggles.prior} className={chip(toggles.prior)}
        onClick={() => onChange({ ...toggles, prior: !toggles.prior })}>
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.prior }} />prior day
      </button>
      <button type="button" aria-pressed={toggles.today} className={chip(toggles.today)}
        onClick={() => onChange({ ...toggles, today: !toggles.today })}>
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.today }} />today
      </button>
      <button type="button" aria-pressed={toggles.period} className={chip(toggles.period)}
        onClick={() => onChange({ ...toggles, period: !toggles.period })} title="previous week / month high and low">
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.period }} />week/month
      </button>
      <button type="button" aria-pressed={toggles.months} className={chip(toggles.months)}
        onClick={() => onChange({ ...toggles, months: !toggles.months })}
        title="each of the last 12 months' high and low, and the high / low over them (fewer where the bars do not reach back)">
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.months }} />months
      </button>
      <button type="button" aria-pressed={toggles.lines} className={chip(toggles.lines)}
        onClick={() => onChange({ ...toggles, lines: !toggles.lines })}>
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.vwap }} />VWAP
        <i className="mx-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.ema20 }} />EMA20 1m
      </button>
      <button type="button" aria-pressed={toggles.maxPain} className={chip(toggles.maxPain)}
        onClick={() => onChange({ ...toggles, maxPain: !toggles.maxPain })}
        title="max pain of the nearest expiry as the day goes: the strike where option buyers would be paid least at expiry (captured strikes, about ATM ± 16; context only)">
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.maxPain }} />max pain
      </button>
      <button type="button" aria-pressed={toggles.auction} className={chip(toggles.auction)}
        onClick={() => onChange({ ...toggles, auction: !toggles.auction })}
        title="15:10-15:30, while the index is frozen for the closing auction: the options' synthetic index (strike + call − put, dashed; where the options trade, not the close) and from 15:27 the auction's indicative close from each stock's indicative price (solid; usually within about 10 NIFTY points of the close at 15:28, far off on some expiry days)">
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.synthetic }} />synthetic
        <i className="mx-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.auction }} />auction
      </button>
      <button type="button" aria-pressed={toggles.zones !== "off"} className={chip(toggles.zones !== "off")}
        onClick={() => onChange({ ...toggles, zones: zoneNext[toggles.zones] })}
        title="tested zones: 3+ touches → all → off">
        <i className="mr-1.5 inline-block h-2 w-3 align-middle opacity-60" style={{ background: LEVEL_COLORS.support }} />
        zones {toggles.zones === "strong" ? "3×+" : toggles.zones}
      </button>
    </div>
  );
}
