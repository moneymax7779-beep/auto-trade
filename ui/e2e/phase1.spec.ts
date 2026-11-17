import { expect, type Page, type Request, test } from "@playwright/test";

// Mobile phase 1 (17 Nov 2026): Home, Positions and Ledger against stored API answers (no execution worker needed, and
// nothing reaches a broker). The answers mirror the shapes of /api/execution/** on main.
const ACCOUNTS = [
  { id: 1, tenantId: 1, name: "ZERODHA-1", broker: "ZERODHA", mode: "LIVE", role: "OWNER", enabled: true, allocatedCapital: 100000,
    riskFile: "x", connection: null, lastTurn: null, today: { net: -3150, trades: 2, open: 1, liveNet: -3150, liveTrades: 2, liveOpen: 1 } },
  { id: 5, tenantId: 1, name: "PAPER-40", broker: "PAPER", mode: "PAPER", role: "OWNER", enabled: true, allocatedCapital: 500000,
    riskFile: "x", connection: "ACTIVE", lastTurn: null, today: { net: 18420, trades: 3, open: 0, liveNet: 0, liveTrades: 0, liveOpen: 0 } },
  { id: 9, tenantId: 1, name: "OLD-1", broker: "PAPER", mode: "PAPER", role: "OWNER", enabled: false, allocatedCapital: 0,
    riskFile: "x", connection: null, lastTurn: null, today: { net: 0, trades: 0, open: 0, liveNet: 0, liveTrades: 0, liveOpen: 0 } },
];
const ME = { user: "owner", admin: true, tenants: [{ id: 1, name: "Owner", role: "OWNER", mayAct: true, manages: true, canStop: true }] };
const BOARD = { day: "2026-11-18", tradingDay: true, accounts: [
  { accountId: 1, tenant: "Owner", account: "ZERODHA-1", broker: "ZERODHA", mode: "LIVE", connection: null, sessionUntil: null,
    strategies: ["oi-burst-cross"], ready: false },
  { accountId: 5, tenant: "Owner", account: "PAPER-40", broker: "PAPER", mode: "PAPER", connection: "ACTIVE", sessionUntil: null,
    strategies: [], ready: true }] };
const OPEN = { id: 87, strategy: "oi-burst-cross", underlying: "NIFTY", side: "CE", symbol: "NIFTY 24650 CE 18 NOV 26", lotSize: 65,
  entry: 147.25, opened: "2026-11-18T09:49:00", quantity: 390, status: "OPEN", bid: 141.6, best: 152, stop: 118, stopStatus: "OPEN",
  stopWorking: true, pnl: -2203.5 };
const BOOK = { account: "ZERODHA-1", mode: "LIVE", canAct: true, killSwitches: [], bookMode: "LIVE", squareOff: "15:20",
  summary: { openPnl: -2203.5, atRisk: 11407, realisedToday: -946, sinceLiveNet: 0, sinceLiveTrades: 0, sinceLiveFrom: null },
  open: [OPEN], closed: [{ id: 80, strategy: "wall-break-rider", underlying: "NIFTY", side: "PE", symbol: "NIFTY 24600 PE 18 NOV 26",
    lotSize: 65, entry: 90, opened: "2026-11-18T09:20:00", quantity: 130, exit: 83, exitReason: "STOP", closed: "2026-11-18T09:41:00",
    gross: -910, charges: 36, net: -946, pct: -7.8 }] };
const EMPTY_BOOK = { ...BOOK, account: "PAPER-40", mode: "PAPER", bookMode: "PAPER", open: [], closed: [],
  summary: { ...BOOK.summary, openPnl: 0, realisedToday: 18420 } };
const preview = (qty: number | null, warnings: string[] = []) => ({ positionId: 87, symbol: OPEN.symbol, mode: "LIVE", open: 390,
  lotSize: 65, quantity: qty ?? 390, lots: (qty ?? 390) / 65, remaining: 390 - (qty ?? 390), entry: 147.25, bid: 141.6, price: 141.6,
  priceFrom: "bid", gross: ((qty ?? 390) * (141.6 - 147.25)), net: ((qty ?? 390) * (141.6 - 147.25)) - 40, orders: 1, how: "x",
  warnings, typeToConfirm: false });
const LEDGER = [
  { day: "2026-11-17", openingEquity: 100000, trades: 3, wins: 2, open: 0, gross: 21000, charges: 600, net: 20400, closingEquity: 120400, brokerNet: 20400, difference: 0 },
  { day: "2026-11-16", openingEquity: 110000, trades: 2, wins: 0, open: 0, gross: -9500, charges: 500, net: -10000, closingEquity: 100000, brokerNet: -9800, difference: 200 },
  { day: "2026-11-13", openingEquity: 105000, trades: 1, wins: 1, open: 0, gross: 5200, charges: 200, net: 5000, closingEquity: 110000, brokerNet: null, difference: null },
  { day: "2026-10-30", openingEquity: 100000, trades: 1, wins: 1, open: 0, gross: 5100, charges: 100, net: 5000, closingEquity: 105000, brokerNet: null, difference: null },
];
const DAY_TRADES = [{ id: 70, strategy_id: "ma-cross", underlying: "NIFTY", option_side: "CE", symbol: "NIFTY 24500 CE 18 NOV 26",
  quantity_open: 0, average_entry: 120, opened: "2026-11-17T04:10:00Z", closed: "2026-11-17T05:00:00Z", exit_reason: "TARGET",
  realised_gross: 21000, charges: 600, status: "CLOSED" }];

interface Mock { posts: { url: string; body: unknown }[]; switches: unknown[]; warnings: string[] }

async function mock(page: Page): Promise<Mock> {
  const m: Mock = { posts: [], switches: [], warnings: [] };
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req: Request = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      m.posts.push({ url: p, body });
      if (p === "/api/execution/kill-switches") {
        m.switches.push({ id: m.switches.length + 1, tenantId: 1, scope: "ACCOUNT", accountId: (body as { accountId: number }).accountId,
          strategyId: null, instrumentKey: null, reason: "x", engagedBy: "owner", engagedAt: "2026-11-18T10:00:00Z" });
        return route.fulfill(json({ id: m.switches.length }));
      }
      if (p.endsWith("/exit")) {
        await new Promise((r) => setTimeout(r, 400));     // a slow broker: a second tap must not send a second sale
        return route.fulfill(json({ result: ["sold 195 at 141.60"] }));
      }
      if (p.endsWith("/flatten")) return route.fulfill(json({ account: "ZERODHA-1", result: ["sold 390"] }));
      return route.fulfill(json({}));
    }
    if (p === "/api/me") return route.fulfill(json({ mode: "none", user: "local-owner", operator: true, logout: false }));
    if (p === "/api/execution/me") return route.fulfill(json(ME));
    if (p === "/api/execution/accounts") return route.fulfill(json(ACCOUNTS));
    if (p === "/api/execution/login-board") return route.fulfill(json(BOARD));
    if (p === "/api/execution/kill-switches") return route.fulfill(json(m.switches));
    if (p === "/api/execution/accounts/1/live-book") return route.fulfill(json(BOOK));
    if (p === "/api/execution/accounts/5/live-book") return route.fulfill(json(EMPTY_BOOK));
    if (p === "/api/execution/accounts/1/positions/87/exit-preview") {
      const q = url.searchParams.get("quantity");
      return route.fulfill(json(preview(q == null ? null : Number(q), m.warnings)));
    }
    if (p === "/api/execution/accounts/1/ledger") return route.fulfill(json(LEDGER));
    if (p === "/api/execution/accounts/1/positions") return route.fulfill(json(DAY_TRADES));
    if (p === "/api/status") return route.fulfill(json({ status: "RUNNING", feed: "LIVE", feedLagSeconds: 2, account: "PAPER-1",
      dayPnl: 5000, strategy: { "NIFTY · obc": { time: "2026-11-18T10:01:02", spot: 25291.1, CE: "", PE: "", state: {} },
        "SENSEX · obc": { time: "2026-11-18T10:01:02", spot: 82904.5, CE: "", PE: "", state: {} } } }));
    return route.fulfill(json([]));
  });
  return m;
}

const fits = async (page: Page) => {
  const width = page.viewportSize()!.width;
  expect(await page.evaluate(() => Math.max(document.documentElement.scrollWidth, window.innerWidth))).toBeLessThanOrEqual(width);
};

test("Home: checklist with the Kite login, day P&L, indices, and phone fit", async ({ page }) => {
  await mock(page);
  await page.goto("/home");
  await expect(page.getByText("ZERODHA-1 not logged in")).toBeVisible();
  await expect(page.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/api/execution/accounts/1/kite/login");
  await expect(page.getByText("Market data live")).toBeVisible();
  await expect(page.getByText("+₹15,270")).toBeVisible();                 // −3,150 + 18,420; OLD-1 (disabled, flat) left out
  await expect(page.getByText("OLD-1")).toHaveCount(0);
  await expect(page.getByText("25,291.1")).toBeVisible();
  await fits(page);
  await page.screenshot({ path: `e2e-out/p1-${test.info().project.name}-home.png`, fullPage: true });
});

test("Home: Stop new entries engages a kill switch per chosen account", async ({ page }) => {
  const m = await mock(page);
  await page.goto("/home");
  await page.getByRole("button", { name: "Stop new entries…" }).click();
  const sheet = page.getByRole("dialog", { name: "Stop new entries" });
  await sheet.getByRole("checkbox").nth(1).uncheck();                     // keep PAPER-40 trading
  await sheet.getByRole("button", { name: "Stop 1 account" }).click();
  await expect(sheet.getByText("ZERODHA-1: new entries stopped")).toBeVisible();
  expect(m.posts).toEqual([{ url: "/api/execution/kill-switches", body: { scope: "ACCOUNT", accountId: 1, reason: "stopped from the phone" } }]);
  await sheet.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("ZERODHA-1: new entries stopped")).toBeVisible();   // the checklist shows the stop
});

test("Positions: half exit with a preview, one sale per sheet even on a double tap", async ({ page }) => {
  const m = await mock(page);
  await page.goto("/positions");
  await expect(page.getByText("NIFTY 24650 CE 18 NOV 26")).toBeVisible();
  await expect(page.getByText("6 lots")).toBeVisible();
  await page.screenshot({ path: `e2e-out/p1-${test.info().project.name}-positions.png`, fullPage: true });
  await page.getByRole("button", { name: "Exit…" }).click();
  const sheet = page.getByRole("dialog", { name: /Exit NIFTY 24650 CE/ });
  await sheet.getByRole("button", { name: "Half" }).click();
  await expect(sheet.getByText("195 qty (3 lots)")).toBeVisible();
  await expect(sheet.getByText("3 of 6 lots; 3 stay open with their stop.")).toBeVisible();
  await page.screenshot({ path: `e2e-out/p1-${test.info().project.name}-exit-sheet.png` });
  const button = sheet.getByRole("button", { name: "Exit 3 lots" });
  await button.click();
  await button.click({ force: true, timeout: 1_000 }).catch(() => undefined);   // second tap while the first is in flight
  await expect(sheet.getByText("sold 195 at 141.60")).toBeVisible();
  const exits = m.posts.filter((x) => x.url.endsWith("/exit"));
  expect(exits).toHaveLength(1);
  expect(exits[0].body).toMatchObject({ confirm: "EXIT", quantity: 195 });
  expect((exits[0].body as { key: string }).key).toMatch(/.{8,}/);
});

test("Positions: a risky sale needs EXIT typed; All sends no quantity", async ({ page }) => {
  const m = await mock(page);
  m.warnings.push("Spread 6 % of the bid: the sale may fill well below it.");
  await page.goto("/positions");
  await page.getByRole("button", { name: "Exit…" }).click();
  const sheet = page.getByRole("dialog", { name: /Exit NIFTY 24650 CE/ });
  await expect(sheet.getByText("Spread 6 %")).toBeVisible();
  const go = sheet.getByRole("button", { name: /^Exit all/ });
  await expect(go).toBeDisabled();
  await sheet.getByLabel("Type EXIT to confirm").fill("exit");
  await expect(go).toBeEnabled();
  await go.click();
  await expect(sheet.getByText("sold 195 at 141.60")).toBeVisible();
  expect(m.posts.filter((x) => x.url.endsWith("/exit"))[0].body).toMatchObject({ quantity: null });
});

test("Positions: Flatten needs FLATTEN typed", async ({ page }) => {
  const m = await mock(page);
  await page.goto("/positions");
  await page.getByRole("button", { name: "Flatten ZERODHA-1…" }).click();
  const sheet = page.getByRole("dialog", { name: "Flatten ZERODHA-1" });
  await expect(sheet.getByRole("button", { name: "Flatten now" })).toBeDisabled();
  await sheet.getByLabel("Type FLATTEN to confirm").fill("FLATTEN");
  await sheet.getByRole("button", { name: "Flatten now" }).click();
  await expect(sheet.getByText("sold 390")).toBeVisible();
  expect(m.posts.filter((x) => x.url.endsWith("/flatten"))).toHaveLength(1);
  await fits(page);
});

test("Ledger: days with the broker match, weeks, a day's trades, CSV", async ({ page }) => {
  await mock(page);
  await page.goto("/ledger");
  await expect(page.getByText("+₹20,400").first()).toBeVisible();
  await expect(page.getByText("matches broker")).toBeVisible();
  await expect(page.getByText("broker differs +₹200")).toBeVisible();
  await expect(page.getByText("3 green · 1 red", { exact: false })).toBeVisible();
  await page.screenshot({ path: `e2e-out/p1-${test.info().project.name}-ledger.png`, fullPage: true });
  await page.getByRole("button", { name: /Tue, 17 Nov/ }).click();
  await expect(page.getByText("NIFTY 24500 CE 18 NOV 26")).toBeVisible();
  await expect(page.getByText("ma-cross · 09:40–10:30 · TARGET")).toBeVisible();
  await page.getByRole("tab", { name: "month" }).click();
  await expect(page.getByText("November 2026")).toBeVisible();
  await expect(page.getByText("October 2026")).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download CSV" }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("ledger-ZERODHA-1-2026-10-30-to-2026-11-17.csv");
  await fits(page);
});
