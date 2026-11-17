import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import type { Plugin } from "vite";

// mobile phase 4: dist/sw.js from sw.template.js, stamped with a hash of the built index.html (which names every
// hashed chunk), so each build that changes the app is a new service worker and the app offers "Update ready"
function serviceWorker(): Plugin {
  return {
    name: "autotrade-sw",
    apply: "build",
    closeBundle() {
      const html = readFileSync("dist/index.html", "utf8");
      const build = createHash("sha256").update(html).digest("hex").slice(0, 12);
      // the entry script and stylesheet: cached when the worker installs, so the offline shell is never a blank page
      const entry = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((m) => m[1]);
      // and every small page chunk (the phone screens); the large chart / OI tape chunks load on demand
      const small = readdirSync("dist/assets").filter((f) => /\.(js|css)$/.test(f) && statSync(`dist/assets/${f}`).size < 60_000)
        .map((f) => `/assets/${f}`);
      const precache = [...new Set([...entry, ...small])];
      writeFileSync("dist/sw.js", readFileSync("sw.template.js", "utf8").replace("__BUILD__", build)
        .replace("__PRECACHE__", JSON.stringify(precache)));
    },
  };
}

// Dev: `npm run dev` on 5173 proxies /api to trading-core (bin/trading-core --mode=serve|live).
// Prod: `npm run build` writes ui/dist, which trading-core serves on 127.0.0.1:8095.
export default defineConfig({
  plugins: [react(), tailwindcss(), serviceWorker()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: { "/api": "http://127.0.0.1:8095" },
  },
  build: { outDir: "dist", sourcemap: true },
  // mobile phase 4: `vite preview` serves the production build (with its service worker) for e2e/pwa.spec.ts
  preview: { host: "127.0.0.1", port: 5175, proxy: { "/api": "http://127.0.0.1:8095" } },
});
