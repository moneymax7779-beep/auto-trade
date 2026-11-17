import { expect, type Page, test } from "@playwright/test";
import { mock } from "./mocks";

// Mobile phase 1 (17 Nov 2026): Home, Positions and Ledger against stored API answers (no execution worker needed, and
// nothing reaches a broker). The answers mirror the shapes of /api/execution/** on main.
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
  // real money and paper apart, never added together; the signal source and a disabled flat account are not listed
  await expect(page.getByText("Real money (LIVE)")).toBeVisible();
  await expect(page.getByText("−₹3,150").first()).toBeVisible();
  await expect(page.getByText("+₹18,420").first()).toBeVisible();
  await expect(page.getByText("+₹15,270")).toHaveCount(0);
  await expect(page.getByText("OLD-1")).toHaveCount(0);
  await expect(page.getByText("SIGNAL-1")).toHaveCount(0);
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

test("Positions: Exit waits for the preview (no risky sale before its warnings are known)", async ({ page }) => {
  await mock(page);
  await page.route("**/api/execution/accounts/1/positions/87/exit-preview*", async (route) => {
    await new Promise((r) => setTimeout(r, 1_500));
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ positionId: 87, symbol: "x",
      mode: "LIVE", open: 390, lotSize: 65, quantity: 390, lots: 6, remaining: 0, entry: 147.25, price: 141.6, priceFrom: "bid",
      gross: -2200, net: -2240, orders: 1, how: "x", warnings: ["Spread 6 %"], typeToConfirm: false }) });
  });
  await page.goto("/positions");
  await page.getByRole("button", { name: "Exit…" }).click();
  const sheet = page.getByRole("dialog", { name: /Exit NIFTY 24650 CE/ });
  await expect(sheet.getByText("Getting the price…")).toBeVisible();
  await expect(sheet.getByRole("button", { name: /^Exit all/ })).toBeDisabled();
  await expect(sheet.getByText("Spread 6 %")).toBeVisible();
  await expect(sheet.getByRole("button", { name: /^Exit all/ })).toBeDisabled();     // now it needs EXIT typed
});

test("Positions: after an unknown outcome the lots are locked and Retry resends the same key", async ({ page }) => {
  const m = await mock(page);
  let calls = 0;
  await page.route("**/api/execution/accounts/1/positions/87/exit", async (route) => {
    m.posts.push({ url: "/exit", body: route.request().postDataJSON() });
    calls++;
    await route.fulfill(calls === 1 ? { status: 502, contentType: "text/plain", body: "bad gateway" }
      : { status: 200, contentType: "application/json", body: JSON.stringify({ result: ["sold 390"], repeat: true }) });
  });
  await page.goto("/positions");
  await page.getByRole("button", { name: "Exit…" }).click();
  const sheet = page.getByRole("dialog", { name: /Exit NIFTY 24650 CE/ });
  await sheet.getByRole("button", { name: /^Exit all/ }).click();
  await expect(sheet.getByText(/The broker may have sold already/)).toBeVisible();
  await sheet.getByRole("button", { name: "Half" }).click();                          // locked: still all 6
  await expect(sheet.getByText("390 qty (6 lots)")).toBeVisible();
  await sheet.getByRole("button", { name: "Retry the same exit" }).click();
  await expect(sheet.getByText("Already sent: sold 390")).toBeVisible();
  const keys = m.posts.filter((x) => x.url === "/exit").map((x) => (x.body as { key: string; quantity: unknown }));
  expect(keys).toHaveLength(2);
  expect(keys[1].key).toBe(keys[0].key);
  expect(keys[1].quantity).toBeNull();
});

test("Home with the execution worker down after the close: one plain line, no fake ₹0, no raw session state", async ({ page }) => {
  await page.route("**/api/**", async (route) => {
    const p = new URL(route.request().url()).pathname;
    if (p === "/api/me") return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ mode: "none", user: "o", operator: true, logout: false }) });
    if (p === "/api/status") return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      status: "NO_SESSION", schedule: { mode: "LIVE", nextStart: "2099-01-05T09:00+05:30[Asia/Kolkata]" } }) });
    if (p.startsWith("/api/execution/")) return route.fulfill({ status: 502, contentType: "application/json",
      body: JSON.stringify({ message: "the execution worker at http://execution:8096 is not reachable (ConnectException)" }) });
    return route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await page.goto("/home");
  await expect(page.getByText("The trading service is not answering.")).toHaveCount(1, { timeout: 10_000 });
  await expect(page.getByText(/execution:8096/)).toHaveCount(0);
  await expect(page.getByText("No trading session now")).toBeVisible();
  await expect(page.getByText(/next starts 09:00/)).toBeVisible();
  await expect(page.getByText(/no_session/i)).toHaveCount(0);
  await expect(page.getByText("Day P&L (closed, after charges)")).toHaveCount(0);
  await page.screenshot({ path: `e2e-out/p5-${test.info().project.name}-home-down.png` });
});

test("Home after the close shows PAPER-1's last session, kept out of both totals", async ({ page }) => {
  await mock(page);
  const json = (b: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(b) });
  await page.route("**/api/status", (r) => r.fulfill(json({ status: "NO_SESSION", schedule: { mode: "LIVE", nextStart: "2099-01-05T09:00+05:30" } })));
  await page.route("**/api/sessions", (r) => r.fulfill(json([
    { id: 1290, account: "PAPER-1", mode: "PAPER_REPLAY", session_date: "2026-11-18", status: "DONE", summary: { net: 999 } },
    { id: 1273, account: "SIGNAL-1", mode: "PAPER_LIVE", session_date: "2026-11-17", status: "DONE", summary: { net: 5 } },
    { id: 1272, account: "PAPER-1", mode: "PAPER_LIVE", session_date: "2026-11-17", status: "DONE", summary: { net: 182995 } },
    { id: 1260, account: "PAPER-1", mode: "PAPER_LIVE", session_date: "2026-11-16", status: "DONE", summary: { net: -5000 } },
  ])));
  await page.goto("/home");
  await expect(page.getByText("strategy session · last session 17 Nov · not in the totals")).toBeVisible();
  await expect(page.getByText("+₹1,82,995")).toBeVisible();
  await expect(page.getByText("+₹18,420").first()).toBeVisible();          // the paper total is still PAPER-40's alone
  await expect(page.getByText("+₹999")).toHaveCount(0);                    // a replay is not a live session
});
