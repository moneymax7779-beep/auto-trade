// usage: node fullshots.mjs jobs.json [chrome-profile-dir]  — jobs: [[outPath, url], ...]; full-page PNGs via Chrome DevTools.
// Skips a job whose PNG already exists (resumable). Needs Node >= 22 (built-in WebSocket) and Google Chrome.
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
const jobs = JSON.parse(readFileSync(process.argv[2], "utf8"));
const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PROFILE = process.argv[3] || "/tmp/strategy-loop-chrome-profile";
import { existsSync } from "node:fs";
const chrome = spawn(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", `--user-data-dir=${PROFILE}`,
  "--remote-debugging-port=9333", "--window-size=1600,1200", "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((t) => t.type === "page"); } catch {}
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0; const pending = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
const send = (method, params = {}) => new Promise((r) => { const n = ++id; pending.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
await send("Page.enable"); await send("Runtime.enable");
const height = async () => (await send("Runtime.evaluate", { expression: "document.documentElement.scrollHeight" })).result.result.value;
for (const [out, url] of jobs) {
  if (existsSync(out)) continue;
  await send("Emulation.setDeviceMetricsOverride", { width: 1600, height: 1200, deviceScaleFactor: 1, mobile: false });
  await send("Page.navigate", { url });
  // let charts and queries settle: wait until the page height stops changing for 3 s (at most 40 s)
  let last = 0, stable = 0;
  for (let t = 0; t < 80 && stable < 6; t++) { await sleep(500); const h = await height(); stable = h === last && h > 1500 ? stable + 1 : 0; last = h; }
  await sleep(1500);
  const h = await height();
  await send("Emulation.setDeviceMetricsOverride", { width: 1600, height: h, deviceScaleFactor: 1, mobile: false });
  await sleep(2500);
  const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(out, Buffer.from(shot.result.data, "base64"));
  console.log(h, out.split("/").pop());
}
ws.close(); chrome.kill();
