import { expect, type Page, test } from "@playwright/test";

// Mobile phase 3 (17 Nov 2026): the Strategies screen against stored answers (the POST bodies must equal what the desktop
// panel sends: components/strategySwitch.ts), and the phone chart page against the Mac's own trading-core (8095).
const GLOBAL = (canEdit = true) => ({
  available: ["oi-burst-cross", "wall-break-rider", "expiry-trend-rider", "ma-cross", "session-break-retest"],
  running: ["early-confirm-runner", "expiry-trend-rider", "ma-cross", "oi-burst-cross", "wall-break-rider", "session-break-retest"],
  global: ["oi-burst-cross", "wall-break-rider"], off: [],
  accounts: [{ id: 1, account: "ZERODHA-1", mode: "LIVE", source: "GLOBAL", effective: ["oi-burst-cross", "wall-break-rider"] }],
  canEdit });
const day = (d: string, strategy: string, net: number) => ({ session_id: 1, session_date: d, strategy_id: strategy, underlying: "NIFTY",
  option_side: "CE", symbol: "x", opened_at: `${d}T10:00:00`, closed_at: `${d}T11:00:00`, exit_reason: "STOP", realised: net,
  costs: 0, net, quantity: 65, lot_size: 65, average_cost: 100, average_exit: 100 });
const PERF = [
  ...Array.from({ length: 12 }, (_, i) => day(`2026-10-${String(i + 1).padStart(2, "0")}`, "oi-burst-cross", i % 3 === 0 ? -20000 : 50000)),
  day("2026-11-16", "session-break-retest", 31563), day("2026-11-17", "session-break-retest", -4000),
];

async function mock(page: Page, canEdit = true) {
  const posts: unknown[] = [];
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (p === "/api/presence") return route.fulfill({ status: 204 });   // the tab's activity beat (presence.ts, 23 Nov 2026), not a user action
    if (req.method() === "POST") { posts.push(req.postDataJSON()); return route.fulfill(json(GLOBAL(canEdit))); }
    if (p === "/api/me") return route.fulfill(json({ mode: "none", user: "local-owner", operator: true, logout: false }));
    if (p === "/api/execution/live-strategies") return route.fulfill(json(GLOBAL(canEdit)));
    if (p === "/api/strategies") return route.fulfill(json({ live: [{ strategy: "oi-burst-cross", version: "obc-v31" },
      { strategy: "session-break-retest", version: "sbr-v27" }] }));
    if (p === "/api/performance") return route.fulfill(json(PERF));
    return route.fulfill(json([]));
  });
  return posts;
}

const group = (page: Page, s: string) => page.getByRole("group", { name: `${s}: off, paper or live` });

test("Strategies: cards carry the live record; a thin one needs LIVE typed; the body matches the desktop's", async ({ page }) => {
  const posts = await mock(page);
  await page.goto("/strategies");
  await expect(page.getByText("obc-v31")).toBeVisible();
  await expect(page.getByText("+₹3,20,000").first()).toBeVisible();                // 8 × 50K − 4 × 20K
  await expect(page.getByText("Thin record: fewer than 10 live sessions.")).toHaveCount(1);   // sbr: 2 sessions
  await expect(page.getByText("No live PAPER record yet.")).toHaveCount(4);                   // wbr, etr, mac, ecr
  await page.screenshot({ path: `e2e-out/p3-${test.info().project.name}-strategies.png`, fullPage: true });
  await group(page, "session-break-retest").getByRole("button", { name: "Live" }).click();
  await group(page, "ma-cross").getByRole("button", { name: "Off" }).click();
  await page.getByRole("button", { name: "Review 2 changes" }).click();
  const sheet = page.getByRole("dialog", { name: "Save strategy changes" });
  await expect(sheet.getByText("Real money: session-break-retest will trade LIVE on ZERODHA-1 from its next entry.")).toBeVisible();
  await expect(sheet.getByText("Off everywhere (no new entry, paper or real): ma-cross.")).toBeVisible();
  const save = sheet.getByRole("button", { name: "Save for all accounts" });
  await expect(save).toBeDisabled();
  await sheet.getByLabel("Type LIVE to confirm").fill("live");
  await save.click();
  await expect(sheet).toBeHidden();
  expect(posts).toEqual([{ strategies: ["oi-burst-cross", "session-break-retest", "wall-break-rider"], off: ["ma-cross"] }]);
});

test("Strategies: Off alone sends only the OFF list; Undo clears", async ({ page }) => {
  const posts = await mock(page);
  await page.goto("/strategies");
  await group(page, "ma-cross").getByRole("button", { name: "Off" }).click();
  await expect(page.getByRole("button", { name: "Review 1 change" })).toBeVisible();
  await page.getByRole("button", { name: "Undo" }).click();
  await expect(page.getByRole("button", { name: /Review/ })).toHaveCount(0);
  await group(page, "ma-cross").getByRole("button", { name: "Off" }).click();
  await page.getByRole("button", { name: "Review 1 change" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save for all accounts" }).click();
  await expect.poll(() => posts.length).toBe(1);
  expect(posts[0]).toEqual({ off: ["ma-cross"] });
});

test("Strategies: a viewer sees the cards without the switch", async ({ page }) => {
  await mock(page, false);
  await page.goto("/strategies");
  await expect(group(page, "oi-burst-cross").getByRole("button", { name: "Paper" })).toBeDisabled();
  await expect(page.getByText(/Only the platform owner changes these/)).toBeVisible();
});

test("Chart page on a phone: chart first, OI tape folded until tapped, fits", async ({ page }) => {
  await page.goto("/index/NIFTY");
  const fold = page.getByRole("button", { name: /OI tape and strikes/ });
  await expect(fold).toBeVisible({ timeout: 20_000 });
  await expect(fold).toHaveAttribute("aria-expanded", "false");
  const chart = page.getByText(/futures surges and OI flow/).first();
  const context = page.getByText("Gap at the open").first();
  if (await context.count() > 0 && await chart.count() > 0) {
    expect((await chart.boundingBox())!.y).toBeLessThan((await context.boundingBox())!.y);
  }
  await page.screenshot({ path: `e2e-out/p3-${test.info().project.name}-chart.png` });
  await fold.click();
  await expect(fold).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText(/OI tape ·/).first()).toBeVisible({ timeout: 20_000 });
  const width = page.viewportSize()!.width;
  expect(await page.evaluate(() => Math.max(document.documentElement.scrollWidth, window.innerWidth))).toBeLessThanOrEqual(width);
});
