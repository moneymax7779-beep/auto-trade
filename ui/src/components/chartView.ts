import { useSyncExternalStore } from "react";

/** The visible minutes of a surge chart (minutes since midnight IST). */
export interface View { a: number; b: number }

const LINK_KEY = "chart-link-time";
const views = new Map<string, View>();
const listeners = new Set<() => void>();
let linked = (() => {
  try { return localStorage.getItem(LINK_KEY) !== "false"; } catch { return true; }
})();

const notify = () => listeners.forEach((l) => l());
const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };

/**
 * A chart's time window. Linked (the default), NIFTY and SENSEX of the same day share one window, so
 * switching index keeps 13:00-14:00 in view; unlinked, each index keeps its own.
 */
export function useChartView(date: string, underlying: string, full: View): [View, (v: View | ((v: View) => View)) => void] {
  const key = () => (linked ? date : `${date}|${underlying}`);
  const view = useSyncExternalStore(subscribe, () => views.get(key()) ?? full);
  const set = (v: View | ((v: View) => View)) => {
    const k = key();
    const next = typeof v === "function" ? v(views.get(k) ?? full) : v;
    views.set(k, next);
    if (linked) views.set(`${date}|${underlying}`, next);
    notify();
  };
  return [view, set];
}

/** Sets the window another day's chart opens with (stepping to the previous day lands on its close, and so on). */
export function presetView(date: string, underlying: string, view: View) {
  views.set(linked ? date : `${date}|${underlying}`, view);
  views.set(`${date}|${underlying}`, view);
  notify();
}

export function useLinkedTime(): [boolean, (on: boolean) => void] {
  const on = useSyncExternalStore(subscribe, () => linked);
  const set = (value: boolean) => {
    linked = value;
    try { localStorage.setItem(LINK_KEY, String(value)); } catch { /* not kept */ }
    notify();
  };
  return [on, set];
}
