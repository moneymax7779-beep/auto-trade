import { useSyncExternalStore } from "react";

/**
 * The index price pushed by the server as it moves (/api/live/ticks, server-sent events, every 250 ms from the running
 * session's memory; user, 5 Nov 2026: "build the full V2-style push for our chart"). One stream per page, shared by
 * every chart on it; each chart is told at most every 500 ms (2 s while the tab is hidden) and only when its own index
 * moved, and keeps the minute bars of the day it shows. The 30-second chart refresh stays as the backstop: a page whose
 * stream fails falls back to it while the stream reconnects (2 s, doubling to a minute). A hidden tab gives its stream
 * up (browsers allow few connections to one site) and opens it again when shown.
 */

/** One minute of pushed prices: last, high and low. */
export interface LiveBar { c: number; h: number; l: number }

/** /api/live/ticks events: per index that moved, its last price, the range since the last push, IST time. */
interface Tick { u: string; d: string; t: string; p: number; h: number; l: number; m: string }

const RENDER_MS = 500, HIDDEN_RENDER_MS = 2_000, CLOSE_AFTER_MS = 5_000, STALE_MS = 5_000, RETRY_MIN_MS = 2_000, RETRY_MAX_MS = 60_000;

interface Day { bars: Map<string, LiveBar>; maxMinute: string; arrived: number; version: number; stale: boolean }
/** `${date}|${index}` → that day's pushed bars */
const days = new Map<string, Day>();
const listeners = new Set<() => void>();
const changed = new Set<string>();
let pending: ReturnType<typeof setTimeout> | null = null;
let source: EventSource | null = null;
let closeTimer: ReturnType<typeof setTimeout> | null = null;
let retryTimer: ReturnType<typeof setTimeout> | null = null;
let retryMs = RETRY_MIN_MS;
let staleTimer: ReturnType<typeof setInterval> | null = null;

function notifySoon() {
  if (pending) return;
  const wait = typeof document !== "undefined" && document.hidden ? HIDDEN_RENDER_MS : RENDER_MS;
  pending = setTimeout(() => {
    pending = null;
    changed.forEach((key) => { const d = days.get(key); if (d) d.version++; });
    changed.clear();
    listeners.forEach((f) => f());
  }, wait);
}

/** {@code fresh}: ticks as they happen (counted for "streaming"); a stream's opening snapshot is not. */
function apply(ticks: Tick[], fresh: boolean) {
  for (const k of ticks) {
    const key = `${k.d}|${k.u}`, minute = k.t.slice(0, 5);
    let day = days.get(key);
    // a new replay of the same day starts again from the morning: its earlier run's bars no longer hold
    if (day && minute < day.maxMinute) day = undefined;
    if (!day) { day = { bars: new Map(), maxMinute: minute, arrived: 0, version: (days.get(key)?.version ?? 0) + 1, stale: true }; days.set(key, day); }
    const bar = day.bars.get(minute);
    day.bars.set(minute, bar ? { c: k.p, h: Math.max(bar.h, k.h), l: Math.min(bar.l, k.l) } : { c: k.p, h: k.h, l: k.l });
    if (minute > day.maxMinute) day.maxMinute = minute;
    if (fresh) { day.arrived = Date.now(); day.stale = false; }
    changed.add(key);
  }
  if (ticks.length) notifySoon();
}

/** "Streaming" ends 5 s after the last tick: the charts are told when it does (heartbeats carry no ticks). */
function checkStale() {
  const now = Date.now();
  days.forEach((d, key) => {
    if (!d.stale && now - d.arrived > STALE_MS) { d.stale = true; changed.add(key); }
  });
  if (changed.size) notifySoon();
}

function open() {
  if (source || retryTimer || typeof EventSource === "undefined") return;
  if (typeof document !== "undefined" && document.hidden) return;
  const s = new EventSource("/api/live/ticks");
  const read = (fresh: boolean) => (e: Event) => {
    try { apply(JSON.parse((e as MessageEvent<string>).data) as Tick[], fresh); } catch { /* a malformed event: skip it */ }
  };
  s.addEventListener("snapshot", read(false));
  s.addEventListener("ticks", read(true));
  s.onopen = () => { retryMs = RETRY_MIN_MS; };
  // a dropped stream reconnects by itself; a refused one (a deploy's 502, 503 at the stream limit, an expired sign-in)
  // closes for good, so it is opened again after a pause, longer each time
  s.onerror = () => {
    if (s.readyState !== EventSource.CLOSED || source !== s) return;
    source = null;
    if (listeners.size) {
      retryTimer = setTimeout(() => { retryTimer = null; if (listeners.size) open(); }, retryMs);
      retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
    }
  };
  source = s;
}

function close() {
  source?.close();
  source = null;
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) close();
    else if (listeners.size) open();
  });
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (closeTimer) { clearTimeout(closeTimer); closeTimer = null; }
  if (!staleTimer) staleTimer = setInterval(checkStale, 1_000);
  open();
  return () => {
    listeners.delete(listener);
    if (!listeners.size) {
      closeTimer = setTimeout(() => {
        close();
        if (staleTimer) { clearInterval(staleTimer); staleTimer = null; }
      }, CLOSE_AFTER_MS);
    }
  };
}

const none = () => () => {};

/**
 * The pushed minute bars of {@code underlying} on {@code date} (only when {@code on}: a live or paced-replay chart),
 * whether ticks are arriving (one in the last 5 s), and a version that changes only when this index's bars do.
 */
export function useLiveBars(underlying: string, date: string | undefined, on: boolean) {
  const key = `${date}|${underlying}`;
  const version = useSyncExternalStore(on ? subscribe : none, () => days.get(key)?.version ?? 0);
  const day = on && date ? days.get(key) : undefined;
  return { version, bars: day?.bars, streaming: !!day && !day.stale };
}

/**
 * The chart's minute closes with the pushed ones: the stored minutes stay as they are, the last stored minute (still
 * forming when it was read) and every later one take the pushed close; a replay's ({@code until}, "HH:mm:ss") never
 * beyond its clock.
 */
export function withLiveCloses(index: [string, number][], live: Map<string, LiveBar> | undefined, until?: string): [string, number][] {
  if (!live || !live.size) return index;
  const lastStored = index.length ? index[index.length - 1][0] : "00:00";
  const end = until ? until.slice(0, 5) : "15:30";
  const merged = new Map(index);
  live.forEach((bar, minute) => {
    if (minute >= lastStored && minute >= "09:15" && minute <= "15:30" && minute <= end) merged.set(minute, bar.c);
  });
  return [...merged.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
}
