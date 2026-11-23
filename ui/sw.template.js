// auto-trade service worker (mobile phase 4, 17 Nov 2026). Written to dist/sw.js at build time with the build's id, so
// every deploy is a new worker. 23 Nov 2026: it takes over at once (skipWaiting): pages are network first and the code's
// file names carry a content hash, so nothing stale is served by doing so; the app shows "A new version is ready" only
// when the open page's own code is older than the deployed build (it asks the worker which build it is). Before, every
// deploy left a waiting worker and the banner came back on every load until Reload was tapped in that browser.
// - /api/** and anything not GET: never touched (trading data is never served from a cache)
// - /assets/** (file names carry a content hash): cache first, kept until the next build's worker clears old caches
// - pages: network first; the saved shell only when the network fails (the app then shows its own "not updated" times)
const BUILD = "__BUILD__";
const CACHE = `autotrade-${BUILD}`;
const PRECACHE = __PRECACHE__;   // the build's entry script and stylesheet (vite.config.ts writes them in)

// 23 Nov 2026: only a real file is kept (ok, same origin, not redirected): a fetch sent to sign in (a session gone after a
// deploy) answered with the sign-in page, which addAll kept under the code's name, and the app could open blank
const keep = (res) => res.ok && res.type === "basic" && !res.redirected;

self.addEventListener("install", (event) => {
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE).then((c) => Promise.all(["/icons/icon-192.png", ...PRECACHE].map((url) =>
    fetch(url, { cache: "no-cache" }).then((res) => (keep(res) ? c.put(url, res) : undefined)).catch(() => undefined)))));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k.startsWith("autotrade-") && k !== CACHE)
    .map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener("message", (event) => {
  if (event.data === "SKIP_WAITING") self.skipWaiting();
  // the build this worker serves: a page whose entry script is not in it runs older code than the deployed build
  if (event.data === "BUILD?" && event.source) event.source.postMessage({ type: "BUILD", build: BUILD, precache: PRECACHE });
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/") || url.pathname.startsWith("/auth/")
    || url.pathname.startsWith("/login") || url.pathname.startsWith("/oauth2") || url.pathname.startsWith("/logout")) return;
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(caches.open(CACHE).then(async (c) => {
      const hit = await c.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (keep(res)) c.put(req, res.clone());
      return res;
    }));
    return;
  }
  if (req.mode === "navigate") {
    event.respondWith(fetch(req).then((res) => {
      // only a real app page is kept as the offline shell (never a sign-in redirect or an error page)
      if (res.ok && res.type === "basic" && !res.redirected) {
        const copy = res.clone();                       // before the page reads the body
        caches.open(CACHE).then((c) => c.put("/__shell", copy));
      }
      return res;
    }).catch(async () => (await caches.match("/__shell")) ?? Response.error()));
  }
});

// web push (phase 4): a notification per message; a tap opens (or focuses) the app at the message's page
self.addEventListener("push", (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { body: event.data ? event.data.text() : "" }; }
  const title = data.title || "auto-trade";
  event.waitUntil(self.registration.showNotification(title, {
    body: data.body || "", tag: data.tag, renotify: !!data.tag, icon: "/icons/icon-192.png", badge: "/icons/icon-192.png",
    data: { url: data.url || "/home" },
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "/home";
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((wins) => {
    const open = wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (open) { open.navigate(url); return open.focus(); }
    return self.clients.openWindow(url);
  }));
});
