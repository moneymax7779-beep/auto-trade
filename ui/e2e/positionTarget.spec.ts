import { expect, type Page, test } from "@playwright/test";
import { ACCOUNTS } from "./mocks";

// The manual first target on an open position (24 Nov 2026, user: "I should be able to manually enter the 1st target in %
// once the position is opened ... so in case it reverses should close at the target entered manually"; answers: a lock on
// the whole position, beside the strategy's exits, PAPER and LIVE). Phone Positions screen against stored API answers that
// keep the target the screen sets; nothing reaches a broker.

const ME = { user: "owner", admin: true, tenants: [{ id: 1, name: "Owner", role: "OWNER", mayAct: true, manages: true, canStop: true }] };
const OPEN = { id: 87, strategy: "oi-burst-cross", underlying: "NIFTY", side: "CE", symbol: "NIFTY 24650 CE 18 NOV 26", lotSize: 65,
  entry: 147.25, opened: "2026-11-18T09:49:00", quantity: 390, status: "OPEN", bid: 141.6, best: 152, stop: 118, stopStatus: "OPEN",
  stopWorking: true, pnl: -2203.5 };
const SUMMARY = { openPnl: -2203.5, atRisk: 11407, realisedToday: 0, sinceLiveNet: 0, sinceLiveTrades: 0, sinceLiveFrom: null };

interface Target { pct: number; price: number; status: string; armedAt?: string | null; armedBid?: number | null; tries?: number;
  lastResult?: string | null; firedBid?: number | null; firedAt?: string | null; nextTryAt?: string | null }
interface State { posts: { url: string; body: unknown }[]; target: Target | null; canAct: boolean; refuse: string | null;
  reasonRequired?: boolean }

async function mock(page: Page, init: Partial<State> = {}): Promise<State> {
  const s: State = { posts: [], target: null, canAct: true, refuse: null, ...init };
  const json = (body: unknown, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (p === "/api/presence") return route.fulfill({ status: 204 });   // the tab's activity beat, not a user action
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      s.posts.push({ url: p, body });
      if (p === "/api/execution/accounts/1/positions/87/target") {
        if (s.refuse) return route.fulfill(json({ message: s.refuse }, 409));
        const pct = (body as { pct: number }).pct;
        s.target = { pct, price: Math.round(147.25 * (1 + pct / 100) * 20) / 20, status: "WAITING", tries: 0 };
        return route.fulfill(json({ account: "ZERODHA-1", positionId: 87, target: s.target }));
      }
      if (p === "/api/execution/accounts/1/positions/87/target/clear") {
        s.target = null;
        return route.fulfill(json({ account: "ZERODHA-1", positionId: 87, cleared: true }));
      }
      return route.fulfill(json({}));
    }
    if (p === "/api/me") return route.fulfill(json({ mode: "none", user: "local-owner", operator: true, logout: false }));
    if (p === "/api/execution/me") return route.fulfill(json(ME));
    if (p === "/api/execution/accounts") return route.fulfill(json(ACCOUNTS));
    if (p === "/api/execution/accounts/1/live-book") {
      return route.fulfill(json({ account: "ZERODHA-1", mode: "LIVE", canAct: s.canAct, canStop: true, killSwitches: [],
        reasonRequired: s.reasonRequired ?? false,
        bookMode: "LIVE", squareOff: "15:20", summary: SUMMARY, closed: [],
        open: [{ ...OPEN, ...(s.target ? { manualTarget: s.target } : {}) }] }));
    }
    if (p === "/api/execution/accounts/5/live-book") {
      return route.fulfill(json({ account: "PAPER-40", mode: "PAPER", canAct: true, killSwitches: [], bookMode: "PAPER",
        summary: { ...SUMMARY, openPnl: 0 }, open: [], closed: [] }));
    }
    return route.fulfill(json([]));
  });
  return s;
}

const fits = async (page: Page) => {
  const width = page.viewportSize()!.width;
  expect(await page.evaluate(() => Math.max(document.documentElement.scrollWidth, window.innerWidth))).toBeLessThanOrEqual(width);
};

test("Positions: set a 20 % first target on an open position, see its price and what it will do", async ({ page }) => {
  const s = await mock(page);
  await page.goto("/positions");
  const card = page.getByTestId("target-87");
  await expect(card.getByText("No target of yours")).toBeVisible();
  await card.getByRole("button", { name: "Set target %" }).click();
  const input = page.getByLabel("Target % above entry");
  await input.fill("20");
  await expect(page.getByText("= 176.70")).toBeVisible();                   // 147.25 × 1.20
  await expect(page.getByText(/Bid 141\.60: nothing happens until the bid reaches 176\.70; then if the bid falls back to 176\.70, all 6 lots are sold/))
    .toBeVisible();
  await expect(page.getByText(/LIVE: a real sell order at the broker/)).toBeVisible();
  await fits(page);
  await page.screenshot({ path: `e2e-out/target-${test.info().project.name}-edit.png`, fullPage: true });
  await page.getByRole("button", { name: "Save target" }).click();
  await expect(page.getByTestId("target-87").getByText("Target +20 % = 176.70: waiting for the bid to reach it")).toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("/target"))).toEqual([
    { url: "/api/execution/accounts/1/positions/87/target", body: { pct: 20 } }]);
  await expect(page.getByRole("button", { name: "Change target" })).toBeVisible();
  await fits(page);
});

test("Positions: an armed target says the bid reached it; it can be removed", async ({ page }) => {
  const s = await mock(page, { target: { pct: 20, price: 176.7, status: "ARMED", armedAt: "2026-11-18T10:02:00Z", armedBid: 177.1,
    tries: 0 } });
  await page.goto("/positions");
  await expect(page.getByText("Target 176.70 reached at 15:32 (bid 177.10): if the bid falls back to 176.70 everything is sold"))
    .toBeVisible();
  await page.screenshot({ path: `e2e-out/target-${test.info().project.name}-armed.png`, fullPage: true });
  await page.getByRole("button", { name: "Change target" }).click();
  await expect(page.getByLabel("Target % above entry")).toHaveValue("20");
  await page.getByLabel("Target % above entry").fill("25");
  await expect(page.getByText("Your target 176.70 is armed now: a new % starts over and waits for the bid to reach the new price."))
    .toBeVisible();
  await page.getByRole("button", { name: "Remove target" }).click();
  await expect(page.getByTestId("target-87").getByText("No target of yours")).toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("/target")).map((x) => x.url)).toEqual([
    "/api/execution/accounts/1/positions/87/target/clear"]);
});

test("Positions: an invalid % cannot be saved; a refusal is shown and nothing changes", async ({ page }) => {
  const s = await mock(page, { refuse: "position 87 is not open on ZERODHA-1" });
  await page.goto("/positions");
  await page.getByRole("button", { name: "Set target %" }).click();
  const input = page.getByLabel("Target % above entry");
  const save = page.getByRole("button", { name: "Save target" });
  await expect(save).toBeDisabled();
  await input.fill("0");
  await expect(page.getByText("A % above 0 and at most 1000.")).toBeVisible();
  await expect(save).toBeDisabled();
  await input.fill("1500");
  await expect(save).toBeDisabled();
  await input.fill("12.5");
  await expect(page.getByText("= 165.65")).toBeVisible();                  // 147.25 × 1.125 = 165.656 -> 165.65
  // 7.25 is no multiple of the old step=0.5: the browser's own check blocked the submit with no message here
  await input.fill("7.25");
  await expect(page.getByText("= 157.95")).toBeVisible();                  // 147.25 × 1.0725 = 157.93 -> 157.95
  await save.click();
  await expect(page.getByText(/Not saved: 409: position 87 is not open on ZERODHA-1/)).toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("/target"))).toEqual([
    { url: "/api/execution/accounts/1/positions/87/target", body: { pct: 7.25 } }]);
  await page.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByTestId("target-87").getByText("No target of yours")).toBeVisible();
});

test("Positions: a role that may only stop trouble sees the target but cannot set it", async ({ page }) => {
  await mock(page, { canAct: false, target: { pct: 15, price: 169.35, status: "WAITING", tries: 0 } });
  await page.goto("/positions");
  await expect(page.getByText("NIFTY 24650 CE 18 NOV 26")).toBeVisible();
  await expect(page.getByText("Target +15 % = 169.35: waiting for the bid to reach it")).toBeVisible();
  await expect(page.getByRole("button", { name: /target/i })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Exit…" })).toBeVisible();     // Exit stays (SUPPORT may stop trouble)
});

test("Positions: SUPPORT exiting for a member sees the target but sets none (Exit with a reason instead)", async ({ page }) => {
  await mock(page, { reasonRequired: true, target: { pct: 15, price: 169.35, status: "SELLING", tries: 0,
    firedAt: "2026-11-18T10:02:00Z", nextTryAt: "2026-11-18T10:02:30Z",
    lastResult: "not sold, the broker cannot be reached (session expired): log in to the broker again" } });
  await page.goto("/positions");
  await expect(page.getByText(/Your target 169\.35 triggered at 15:32: the rest is sold at the next try \(15:32:30\)/)).toBeVisible();
  await expect(page.getByText(/log in to the broker again/)).toBeVisible();
  await expect(page.getByRole("button", { name: /target/i })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Exit…" })).toBeVisible();
});
