/**
 * User activity (plan docs/plans/2026-11-23-user-activity-logging-plan.md; user 23 Nov 2026): this tab tells the server,
 * once a minute and when it is hidden or closed, how many seconds it was on screen and in use since it last said so.
 * Nothing else: no keys, no clicks, no page content, the route without its query string. A tab that is hidden adds
 * nothing (it still says it is open, so the open-tab count is right). The server clamps what it is told.
 */
const BEAT_MS = 60_000;
const IDLE_MS = 120_000;            // in use: a key, pointer, touch or scroll in the last 2 minutes
const TAB_KEY = "autotrade-tab-id";

let started = false;

function tabId(): string {
  try {
    const kept = sessionStorage.getItem(TAB_KEY);
    if (kept) return kept;
    const id = crypto.randomUUID();
    sessionStorage.setItem(TAB_KEY, id);
    return id;
  } catch {
    return crypto.randomUUID();     // storage blocked: a new id each load
  }
}

function standalone(): boolean {
  try {
    return window.matchMedia("(display-mode: standalone)").matches
      || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  } catch {
    return false;
  }
}

/**
 * {@code send}: the browser's own fetch, not the app's wrapper (code review 23 Nov 2026): the wrapper reloads the page on a
 * sign-in 401, and a hidden tab must not reload itself when the sign-in ends; its next use does that.
 */
export function watchPresence(send: typeof fetch = fetch) {
  if (started || typeof document === "undefined") return;
  started = true;
  const id = tabId();
  let visible = document.visibilityState === "visible";
  let last = Date.now();
  let lastInput = Date.now();
  let visibleMs = 0;
  let activeMs = 0;
  let lastInputMark = 0;

  /** Adds the time since the last count to the totals, as it was (visible, in use) during that time. */
  const count = (now: number) => {
    const from = last;
    last = now;
    if (!visible || now <= from) return;
    visibleMs += now - from;
    activeMs += Math.max(0, Math.min(now, lastInput + IDLE_MS) - from);
  };

  const beat = (closing: boolean) => {
    count(Date.now());
    const v = Math.floor(visibleMs / 1000), a = Math.min(v, Math.floor(activeMs / 1000));
    visibleMs -= v * 1000;
    activeMs = Math.max(0, activeMs - a * 1000);
    try {
      void send("/api/presence", {
        method: "POST", headers: { "Content-Type": "application/json" }, keepalive: true, credentials: "same-origin",
        body: JSON.stringify({ tabId: id, visible: v, active: a, path: location.pathname, standalone: standalone(), closing }),
      }).catch(() => undefined);    // lost: one minute of activity, never retried
    } catch {
      // never let this fail the page
    }
  };

  const input = () => {
    const now = Date.now();
    if (now - lastInputMark < 1000) return;   // at most once a second
    lastInputMark = now;
    count(now);
    lastInput = now;
  };
  for (const e of ["keydown", "pointerdown", "pointermove", "wheel", "touchstart", "scroll"]) {
    window.addEventListener(e, input, { passive: true, capture: true });
  }

  document.addEventListener("visibilitychange", () => {
    const now = Date.now();
    count(now);
    visible = document.visibilityState === "visible";
    if (visible) lastInput = now;   // coming back to the tab is using it
    beat(false);
  });
  window.addEventListener("pagehide", () => beat(true));
  window.setInterval(() => beat(false), BEAT_MS);
  beat(false);                      // this tab is open
}
