import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { get } from "../api";

/** A price level and the minute (IST "HH:mm") it became knowable: never drawn before then. */
export interface Level { name: string; group: "prior" | "today"; price: number; from: string }
/** A tested support / resistance zone: from its second confirmed touch until a minute close beyond it. */
export interface Zone { kind: "support" | "resistance"; lo: number; hi: number; touches: number; from: string; until: string | null }
export interface LevelsData {
  date: string; underlying: string; levels: Level[]; zones: Zone[];
  lines: { vwap: [string, number][]; ema20: [string, number][] }; tolerancePct: number;
}

export interface LevelToggles { prior: boolean; today: boolean; lines: boolean; zones: "strong" | "all" | "off" }
const KEY = "chart-level-toggles";
const DEFAULT: LevelToggles = { prior: true, today: true, lines: true, zones: "strong" };

export const LEVEL_COLORS = {
  prior: "var(--color-warn)", today: "var(--color-accent)", vwap: "#f59e0b", ema20: "#a78bfa",
  support: "var(--color-up)", resistance: "var(--color-down)",
};

export function useLevels(date: string | undefined, underlying: string, live?: boolean) {
  return useQuery({
    queryKey: ["levels", date ?? "today", underlying],
    queryFn: () => get<LevelsData>(`/api/levels?underlying=${underlying}${date ? `&date=${date}` : ""}`),
    refetchInterval: live ? 30_000 : false,
    enabled: !!underlying,
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
  return levels.filter((l) => (l.group === "prior" ? t.prior : t.today));
}

/** Levels closer than the tolerance read as one: "ORL / Prior ORL 22,508". */
export function mergeLevels(levels: Level[], tolerancePct: number): Level[] {
  const sorted = [...levels].sort((a, b) => a.price - b.price);
  const out: Level[] = [];
  for (const l of sorted) {
    const last = out[out.length - 1];
    if (last && Math.abs(l.price - last.price) <= (last.price * tolerancePct) / 100) {
      last.name = `${last.name} / ${l.name}`;
      if (l.from < last.from) last.from = l.from;
      if (l.group === "today") last.group = "today";
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
      <button type="button" aria-pressed={toggles.lines} className={chip(toggles.lines)}
        onClick={() => onChange({ ...toggles, lines: !toggles.lines })}>
        <i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.vwap }} />VWAP
        <i className="mx-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: LEVEL_COLORS.ema20 }} />EMA20
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
