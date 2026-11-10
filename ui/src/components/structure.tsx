import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { get, type SessionStructure } from "../api";

/** ma-cross's own view of the index (4 Nov 2026): its EMAs of 3-minute closes, 3-minute candles, each trade's stop / target. */
export interface StructureToggles { emas: boolean; candles: boolean; stops: boolean }
const STRUCTURE_KEY = "chart-structure-toggles";
/** No candles, EMAs or stops (a member's plain chart, 10 Nov 2026). */
export const NO_STRUCTURE: StructureToggles = { emas: false, candles: false, stops: false };
const STRUCTURE_DEFAULT: StructureToggles = { emas: true, candles: false, stops: true };
export const EMA9_COLOR = "#0ea5e9", EMA20_3M_COLOR = "#6d28d9";
export const STOP_COLOR = "#dc2626", TARGET_COLOR = "#16a34a";

/** The toggles, shared by the session and index charts and kept in the browser. */
export function useStructureToggles(): [StructureToggles, (t: StructureToggles) => void] {
  const [t, setT] = useState<StructureToggles>(() => {
    try {
      const raw = localStorage.getItem(STRUCTURE_KEY);
      if (raw) return { ...STRUCTURE_DEFAULT, ...(JSON.parse(raw) as Partial<StructureToggles>) };
    } catch { /* storage unavailable: defaults */ }
    return STRUCTURE_DEFAULT;
  });
  const update = (next: StructureToggles) => {
    setT(next);
    try { localStorage.setItem(STRUCTURE_KEY, JSON.stringify(next)); } catch { /* not persisted */ }
  };
  return [t, update];
}

/** The ma-cross toggle row; {@code stops} off where no trades' stops are known (a viewer, or no session). */
export function StructureTogglesBar({ toggles, onChange, stops = true }:
  { toggles: StructureToggles; onChange: (t: StructureToggles) => void; stops?: boolean }) {
  const items = [
    ["emas", <><i className="mr-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: EMA9_COLOR }} />EMA9
      <i className="mx-1.5 inline-block h-0.5 w-3 align-middle" style={{ background: EMA20_3M_COLOR }} />EMA20 3m</>],
    ["candles", "3-min candles"],
    ...(stops ? [["stops", <><span style={{ color: STOP_COLOR }}>stop</span> / <span style={{ color: TARGET_COLOR }}>target</span></>]] : []),
  ] as [keyof StructureToggles, ReactNode][];
  return (
    <div className="flex flex-wrap items-center gap-1 text-xs" role="group" aria-label="Strategy view">
      <span className="text-muted">ma-cross</span>
      {items.map(([key, label]) => (
        <button key={key} type="button" aria-pressed={toggles[key]} onClick={() => onChange({ ...toggles, [key]: !toggles[key] })}
          className={`rounded-full border px-2.5 py-1 ${toggles[key] ? "border-line bg-panel-2" : "border-transparent text-muted opacity-60"}`}>
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * The index chart's structure: a session's (with its ma-cross trades' stops) when one is given, else the day's alone;
 * refreshed while live (a 3-minute bar is added once it has closed).
 */
export function useStructure(date: string | undefined, underlying: string, sessionId?: number, live?: boolean, on = true) {
  return useQuery({
    queryKey: ["structure", sessionId != null ? `session-${sessionId}` : date ?? "none", underlying],
    enabled: on && (sessionId != null || !!date),
    queryFn: () => get<SessionStructure>(sessionId != null
      ? `/api/sessions/${sessionId}/structure?underlying=${underlying}`
      : `/api/structure?date=${date}&underlying=${underlying}`),
    refetchInterval: live ? 30_000 : false,
  });
}
