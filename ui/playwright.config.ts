import { defineConfig, devices } from "@playwright/test";

// Mobile phase 0 (17 Nov 2026): phone-width checks of every page. Runs against a served UI (default: `npx vite --port
// 5174`, which proxies /api to trading-core on 8095). Uses the installed Google Chrome, so no browser download.
//   npm run test:mobile                      (UI_BASE=http://127.0.0.1:5174 by default)
export default defineConfig({
  testDir: "e2e",
  outputDir: "e2e-out/results",
  timeout: 60_000,
  globalTimeout: 20 * 60_000,   // a hung run (e.g. the dev server restarting) fails instead of waiting forever
  workers: 2,
  reporter: [["list"]],
  use: { baseURL: process.env.UI_BASE ?? "http://127.0.0.1:5174", channel: "chrome", trace: "off" },
  projects: [
    { name: "iphone-13", use: { ...devices["iPhone 13"], browserName: "chromium", channel: "chrome" } },
    { name: "pixel-7", use: { ...devices["Pixel 7"], channel: "chrome" } },
    { name: "small-375", use: { viewport: { width: 375, height: 667 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, channel: "chrome" } },
  ],
});
