import { expect, type Page, test } from "@playwright/test";
import { ACCOUNTS } from "./mocks";

// The spot exit on an open position (27 Nov 2026, user: "I should exit from LIVE open position when it reaches the spot not
// just on the percentage target"): a level of the index; a CE is sold when the index is at or above it, a PE at or below.
// Phone Positions screen against stored API answers that keep what the screen sets; nothing reaches a broker.

const ME = { user: "owner", admin: true, tenants: [{ id: 1, name: "Owner", role: "OWNER", mayAct: true, manages: true, canStop: true }] };
const OPEN = { id: 87, strategy: "oi-burst-cross", underlying: "NIFTY", side: "CE", symbol: "NIFTY 24650 CE 18 NOV 26", lotSize: 65,
  entry: 147.25, opened: "2026-11-18T09:49:00", quantity: 390, status: "OPEN", bid: 141.6, best: 152, stop: 118, stopStatus: "OPEN",
  stopWorking: true, pnl: -2203.5, spot: 24_612.4 };
const SUMMARY = { openPnl: -2203.5, atRisk: 11407, realisedToday: 0, sinceLiveNet: 0, sinceLiveTrades: 0, sinceLiveFrom: null };

interface Spot { underlying: string; direction: string; level: number; status: string; firedSpot?: number | null;
  firedAt?: string | null; nextTryAt?: string | null; lastResult?: string | null; tries?: number }
interface State { posts: { url: string; body: unknown }[]; spot: Spot | null; canAct: boolean; refuse: string | null; side: string;
  spotAge?: number }

async function mock(page: Page, init: Partial<State> = {}): Promise<State> {
  const s: State = { posts: [], spot: null, canAct: true, refuse: null, side: "CE", ...init };
  const json = (body: unknown, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (p === "/api/presence") return route.fulfill({ status: 204 });
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      s.posts.push({ url: p, body });
      if (p === "/api/execution/accounts/1/positions/87/spot-target") {
        if (s.refuse) return route.fulfill(json({ message: s.refuse }, 409));
        s.spot = { underlying: "NIFTY", direction: s.side === "CE" ? "UP" : "DOWN", level: (body as { level: number }).level,
          status: "WAITING", tries: 0 };
        return route.fulfill(json({ account: "ZERODHA-1", positionId: 87, spotTarget: s.spot }));
      }
      if (p === "/api/execution/accounts/1/positions/87/spot-target/clear") {
        s.spot = null;
        return route.fulfill(json({ account: "ZERODHA-1", positionId: 87, cleared: true }));
      }
      return route.fulfill(json({}));
    }
    if (p === "/api/me") return route.fulfill(json({ mode: "none", user: "local-owner", operator: true, logout: false }));
    if (p === "/api/execution/me") return route.fulfill(json(ME));
    if (p === "/api/execution/accounts") return route.fulfill(json(ACCOUNTS));
    if (p === "/api/execution/accounts/1/live-book") {
      return route.fulfill(json({ account: "ZERODHA-1", mode: "LIVE", canAct: s.canAct, canStop: true, killSwitches: [],
        reasonRequired: false, bookMode: "LIVE", squareOff: "15:20", summary: SUMMARY, closed: [],
        open: [{ ...OPEN, spotAge: s.spotAge ?? 1, side: s.side, symbol: s.side === "CE" ? OPEN.symbol : "NIFTY 24600 PE 18 NOV 26",
          ...(s.spot ? { spotTarget: s.spot } : {}) }] }));
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

test("Positions: set a spot exit on a CE, see what it will do, save it", async ({ page }) => {
  const s = await mock(page);
  await page.goto("/positions");
  const card = page.getByTestId("spot-87");
  await card.getByRole("button", { name: "Set spot exit (NIFTY ≥)" }).click();
  const input = page.getByLabel("Spot exit level of NIFTY");
  const save = page.getByRole("button", { name: "Save spot exit" });
  await expect(save).toBeDisabled();
  await input.fill("24600");
  await expect(page.getByText(/already at or above it: use Exit to sell now/)).toBeVisible();
  await expect(save).toBeDisabled();
  await input.fill("27500");                                  // a slip of a digit: more than 10 % above
  await expect(page.getByText(/More than 10 % away from the index/)).toBeVisible();
  await expect(save).toBeDisabled();
  await input.fill("24,680");
  await expect(page.getByText("now 24612.40 · 67.6 pts away")).toBeVisible();
  await expect(page.getByText(/When NIFTY trades at 24680\.00 or above, all 6 lots are sold at once/)).toBeVisible();
  await expect(page.getByText(/LIVE: a real sell order at the broker for all 6 lots \(390 qty\)/)).toBeVisible();
  await fits(page);
  await page.screenshot({ path: `e2e-out/spot-${test.info().project.name}-edit.png`, fullPage: true });
  await save.click();
  await expect(page.getByTestId("spot-87").getByText("Spot exit at NIFTY ≥ 24680.00: waiting · NIFTY 24612.40, 67.6 pts to go"))
    .toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("spot-target"))).toEqual([
    { url: "/api/execution/accounts/1/positions/87/spot-target", body: { level: 24680 } }]);
  // the % target is still there beside it
  await expect(page.getByRole("button", { name: "Set target %" })).toBeVisible();
  await page.getByRole("button", { name: "Change spot exit" }).click();
  await expect(page.getByLabel("Spot exit level of NIFTY")).toHaveValue("24680");
  await page.getByRole("button", { name: "Remove spot exit" }).click();
  await expect(page.getByRole("button", { name: "Set spot exit (NIFTY ≥)" })).toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("spot-target")).map((x) => x.url)).toEqual([
    "/api/execution/accounts/1/positions/87/spot-target", "/api/execution/accounts/1/positions/87/spot-target/clear"]);
  await fits(page);
});

test("Positions: a PE's spot exit is below the index; a refusal is shown", async ({ page }) => {
  const s = await mock(page, { side: "PE", refuse: "position 87 is not open on ZERODHA-1" });
  await page.goto("/positions");
  await page.getByRole("button", { name: "Set spot exit (NIFTY ≤)" }).click();
  await page.getByLabel("Spot exit level of NIFTY").fill("24650");
  await expect(page.getByText(/already at or below it/)).toBeVisible();
  await page.getByLabel("Spot exit level of NIFTY").fill("24550");
  await expect(page.getByText(/When NIFTY trades at 24550\.00 or below/)).toBeVisible();
  await page.getByRole("button", { name: "Save spot exit" }).click();
  await expect(page.getByText(/Not saved: 409: position 87 is not open on ZERODHA-1/)).toBeVisible();
  expect(s.posts.filter((x) => x.url.includes("spot-target")).length).toBe(1);
});

test("Positions: a triggered spot exit says so; a read-only role sees it without buttons", async ({ page }) => {
  await mock(page, { canAct: false, spot: { underlying: "NIFTY", direction: "UP", level: 24680, status: "SELLING", tries: 0,
    firedAt: "2026-11-18T10:02:00Z", nextTryAt: "2026-11-18T10:02:30Z",
    lastResult: "not sold, the broker cannot be reached (session expired): log in to the broker again" } });
  await page.goto("/positions");
  await expect(page.getByText(/Spot exit NIFTY ≥ 24680\.00 triggered at 15:32: the rest is sold at the next try \(15:32:30\)/))
    .toBeVisible();
  await expect(page.getByRole("button", { name: /spot exit/i })).toHaveCount(0);
  await page.screenshot({ path: `e2e-out/spot-${test.info().project.name}-selling.png`, fullPage: true });
});

test("Positions: an index price that is not live says so; a triggered or failed spot exit can only be removed", async ({ page }) => {
  const s = await mock(page, { spotAge: 900, spot: { underlying: "NIFTY", direction: "UP", level: 24680, status: "FAILED", tries: 30,
    lastResult: "price band" } });
  await page.goto("/positions");
  await expect(page.getByText(/Spot exit NIFTY ≥ 24680\.00 could not sell after 30 tries \(price band\): use Exit/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Change spot exit" })).toHaveCount(0);
  await page.getByRole("button", { name: "Remove spot exit" }).click();
  await expect(page.getByRole("button", { name: "Set spot exit (NIFTY ≥)" })).toBeVisible();
  expect(s.posts.map((x) => x.url)).toContain("/api/execution/accounts/1/positions/87/spot-target/clear");
  await page.getByRole("button", { name: "Set spot exit (NIFTY ≥)" }).click();
  await expect(page.getByText("now 24612.40 (15 min old: not live)")).toBeVisible();
  await page.screenshot({ path: `e2e-out/spot-${test.info().project.name}-stale.png`, fullPage: true });
});
