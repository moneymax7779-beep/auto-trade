import { useEffect, useState } from "react";

/** The open page's own entry script ("/assets/index-<hash>.js"): which build its code is. */
function pageEntry(): string | null {
  const s = document.querySelector<HTMLScriptElement>('script[type="module"][src*="/assets/"]');
  return s ? new URL(s.src, window.location.href).pathname : null;
}

/**
 * Mobile phase 4 (17 Nov 2026): registers the service worker (production builds only). 23 Nov 2026: a new worker takes
 * over at once (it serves pages network first and code by content-hashed names, so nothing stale), and "A new version is
 * ready" shows only when THIS page runs older code than the deployed build: the page asks the worker for its build's
 * entry scripts and compares its own (a phone tab left open over a deploy). Before, every deploy left a waiting worker
 * and the banner came back on every load, over pages already on the new code, until Reload was tapped in that browser.
 * The reload waits for the trader's tap, so it never interrupts an exit.
 */
export function AppUpdate() {
  const [stale, setStale] = useState(false);
  useEffect(() => {
    if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
    const mine = pageEntry();
    const ask = () => navigator.serviceWorker.controller?.postMessage("BUILD?");
    const onMessage = (e: MessageEvent) => {
      const d = e.data as { type?: string; precache?: unknown } | null;
      if (d?.type === "BUILD" && mine && Array.isArray(d.precache)) setStale(!d.precache.includes(mine));
    };
    let check: (() => void) | null = null;
    navigator.serviceWorker.addEventListener("message", onMessage);
    // with addEventListener the worker's messages are held until this is called (onmessage would start them itself)
    navigator.serviceWorker.startMessages();
    navigator.serviceWorker.addEventListener("controllerchange", ask);   // a new build's worker took over
    navigator.serviceWorker.register("/sw.js").then((reg) => {
      // the worker calls skipWaiting while it installs, but Chrome drops that activation when another tab keeps the old
      // worker busy at that moment and never tries again (seen 23 Nov 2026 with two tabs): a waiting worker is told again
      const nudge = (w: ServiceWorker | null) => w?.postMessage("SKIP_WAITING");
      const watch = (w: ServiceWorker | null) => w?.addEventListener("statechange", () => { if (w.state === "installed") nudge(w); });
      nudge(reg.waiting);
      watch(reg.installing);
      reg.addEventListener("updatefound", () => watch(reg.installing));
      ask();
      // a phone keeps the app open for days: look for a new build when it comes back to the foreground
      check = () => { if (document.visibilityState === "visible") reg.update().then(() => { nudge(reg.waiting); ask(); }).catch(() => undefined); };
      document.addEventListener("visibilitychange", check);
    }).catch(() => undefined);
    return () => {
      navigator.serviceWorker.removeEventListener("message", onMessage);
      navigator.serviceWorker.removeEventListener("controllerchange", ask);
      if (check) document.removeEventListener("visibilitychange", check);
    };
  }, []);
  if (!stale) return null;
  return (
    <div role="status" className="fixed inset-x-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-40 flex items-center justify-between gap-3 border-t border-sky-700 bg-sky-50 px-4 py-2 text-sm md:bottom-0">
      <span>A new version of auto-trade is ready.</span>
      <button type="button" className="min-h-11 rounded-lg bg-sky-700 px-4 font-semibold text-white"
        onClick={() => window.location.reload()}>Reload</button>
    </div>
  );
}
