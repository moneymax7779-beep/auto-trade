import { useEffect, useState } from "react";

// set by the Reload button: the first install also changes the controller (clients.claim), and must not reload the page
// under the trader's thumb (review 17 Nov: e2e/pwa.spec.ts caught it)
let reloadAsked = false;

/**
 * Mobile phase 4 (17 Nov 2026): registers the service worker (production builds only) and, when a deploy brings a new
 * one, shows "A new version is ready" with Reload; the reload waits for the trader's tap so it never interrupts an exit.
 */
export function AppUpdate() {
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  useEffect(() => {
    if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
    let reloading = false;
    const onController = () => { if (reloadAsked && !reloading) { reloading = true; window.location.reload(); } };
    navigator.serviceWorker.addEventListener("controllerchange", onController);
    navigator.serviceWorker.register("/sw.js").then((reg) => {
      const watch = (w: ServiceWorker | null) => {
        if (!w) return;
        w.addEventListener("statechange", () => { if (w.state === "installed" && navigator.serviceWorker.controller) setWaiting(w); });
      };
      if (reg.waiting && navigator.serviceWorker.controller) setWaiting(reg.waiting);
      watch(reg.installing);
      reg.addEventListener("updatefound", () => watch(reg.installing));
      // a phone keeps the app open for days: look for a new build when it comes back to the foreground
      const check = () => { if (document.visibilityState === "visible") reg.update().catch(() => undefined); };
      document.addEventListener("visibilitychange", check);
    }).catch(() => undefined);
    return () => navigator.serviceWorker.removeEventListener("controllerchange", onController);
  }, []);
  if (!waiting) return null;
  return (
    <div role="status" className="fixed inset-x-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-40 flex items-center justify-between gap-3 border-t border-sky-700 bg-sky-50 px-4 py-2 text-sm md:bottom-0">
      <span>A new version of auto-trade is ready.</span>
      <button type="button" className="min-h-11 rounded-lg bg-sky-700 px-4 font-semibold text-white"
        onClick={() => { reloadAsked = true; waiting.postMessage("SKIP_WAITING"); }}>Reload</button>
    </div>
  );
}
