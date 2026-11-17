/**
 * Mobile phase 5 (17 Nov 2026): the app's own errors to this server's log (POST /api/client-errors); no third-party
 * tracker. Production builds only, at most 10 reports a page load, and never anything typed into a form.
 */
let sent = 0;

export function reportError(kind: string, error: unknown) {
  if (!import.meta.env.PROD || sent >= 10) return;
  sent++;
  const e = error instanceof Error ? error : new Error(String(error));
  try {
    void fetch("/api/client-errors", {
      method: "POST", headers: { "Content-Type": "application/json" }, keepalive: true,
      body: JSON.stringify({ kind, message: e.message.slice(0, 500), stack: (e.stack ?? "").slice(0, 2000),
        url: location.pathname, userAgent: navigator.userAgent }),
    }).catch(() => undefined);
  } catch {
    // never let reporting fail the page
  }
}

export function watchErrors() {
  window.addEventListener("error", (e) => reportError("error", e.error ?? e.message));
  window.addEventListener("unhandledrejection", (e) => reportError("promise", e.reason));
}
