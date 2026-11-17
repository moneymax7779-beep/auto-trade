import { expect, type Page, test } from "@playwright/test";

// Mobile phase 2 (17 Nov 2026): the broker wizard against stored API answers. Nothing reaches a broker: the Kite login is
// "done" by flipping the stored account to logged in, as the server would after Kite's callback.
const ME = { user: "ravi", admin: false, tenants: [{ id: 7, name: "Ravi", role: "OWNER", mayAct: true, manages: true,
  canStop: true, control: "DAILY_SWITCH" }] };
const broker = (over: Record<string, unknown> = {}) => ({ id: 31, name: "Zerodha", broker: "ZERODHA", clientId: "AB1234",
  brokerApp: null, connection: "PENDING_CONNECT", sessionUntil: null, enabled: true, liveCapable: true, liveEligible: false,
  paperTwinId: 32, allocation: 300000, equityMode: "FIXED", sizeStepPct: 100, strategies: [], liveStrategies: [], chosen: false,
  paper: { net: 0, trades: 0, open: 0 }, live: { net: 0, trades: 0, open: 0 }, ...over });
const today = (brokers: unknown[]) => ({ tenantId: 7, name: "Ravi", control: "DAILY_SWITCH", role: "OWNER", day: "2026-11-18",
  members: [], state: null, brokers, totals: { paperNet: 0, liveNet: 0, paperOpen: 0, liveOpen: 0 },
  lossLimit: { dailyLossLimit: null, liveNetToday: 0, reached: false }, killSwitches: [], support: [], pending: [], events: [],
  canSwitch: true, canStop: true, canManage: true, platformAdmin: false });
const APPS = { apps: [], canManage: true, register: {
  ZERODHA: { redirectUrl: "https://autotrade.example/api/execution/kite/callback", staticIps: ["34.1.2.3"], liveCapable: true },
  UPSTOX: { redirectUrl: "https://autotrade.example/api/execution/upstox/callback", staticIps: ["34.1.2.3"], liveCapable: false },
  KOTAK_NEO: { redirectUrl: null, staticIps: ["34.1.2.3"], liveCapable: false },
  GROWW: { redirectUrl: null, staticIps: ["34.1.2.3"], liveCapable: false } } };

async function mock(page: Page) {
  const state = { brokers: [] as unknown[], posts: [] as { url: string; body: unknown }[] };
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      state.posts.push({ url: p, body });
      if (p === "/api/execution/accounts") { state.brokers = [broker()]; return route.fulfill(json({ id: 31, name: "Zerodha" })); }
      if (p === "/api/execution/accounts/31/broker-app/setup") {
        state.brokers = [broker({ brokerApp: "Zerodha app", connection: "DISCONNECTED" })];
        return route.fulfill(json({ name: "Zerodha app", keyHint: "…7f3a", created: true }));
      }
      return route.fulfill(json({}));
    }
    if (p === "/api/me") return route.fulfill(json({ mode: "oidc", user: "ravi", operator: false, logout: true }));
    if (p === "/api/execution/me") return route.fulfill(json(ME));
    if (p === "/api/execution/workspaces/7/today") return route.fulfill(json(today(state.brokers)));
    if (p === "/api/execution/workspaces/7/broker-apps") return route.fulfill(json(APPS));
    return route.fulfill(json([]));
  });
  return state;
}

test("a Zerodha account from pick to ready on the phone", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]).catch(() => undefined);
  const state = await mock(page);
  await page.goto("/brokers/add");
  await expect(page.getByRole("heading", { name: "Add a broker" })).toBeVisible();
  await page.getByLabel(/Client id at Zerodha/).fill("ab1234");
  await page.getByLabel(/Capital for auto-trade/).fill("3,00,000");
  await page.screenshot({ path: `e2e-out/p2-${test.info().project.name}-pick.png` });
  await page.getByRole("button", { name: "Add and continue" }).click();
  expect(state.posts[0]).toEqual({ url: "/api/execution/accounts", body: { name: "Zerodha", broker: "ZERODHA", clientId: "AB1234",
    allocation: 300000, equityMode: "FIXED", strategies: [], enabled: true, tenantId: 7 } });

  await expect(page.getByText("Create your app at developers.kite.trade")).toBeVisible();
  await expect(page).toHaveURL(/account=31&step=1/);
  await expect(page.getByText("https://autotrade.example/api/execution/kite/callback")).toBeVisible();
  await page.getByRole("button", { name: "Copy" }).first().click();
  await expect(page.getByRole("button", { name: "Copied" })).toBeVisible();
  await page.screenshot({ path: `e2e-out/p2-${test.info().project.name}-app.png` });
  await page.getByRole("button", { name: "I created the app" }).click();

  const save = page.getByRole("button", { name: "Save keys" });
  await expect(save).toBeDisabled();
  await page.getByLabel("API key").fill("kite-key-123456");
  await page.getByLabel("API secret").fill("kite-secret-abcdef");
  await save.click();
  expect(state.posts[1]).toEqual({ url: "/api/execution/accounts/31/broker-app/setup",
    body: { apiKey: "kite-key-123456", apiSecret: "kite-secret-abcdef", mobileNumber: null } });

  const login = page.getByRole("link", { name: "Log in at Zerodha" });
  await expect(login).toHaveAttribute("href", "/api/execution/accounts/31/kite/login");
  await page.screenshot({ path: `e2e-out/p2-${test.info().project.name}-login.png` });
  // the trader logs in on Kite's page; the server marks the account logged in; this tab notices within 5 s
  state.brokers = [broker({ brokerApp: "Zerodha app", connection: "ACTIVE", sessionUntil: "2099-01-01T00:30:00Z" })];
  await expect(page.getByText(/Logged in until/)).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("heading", { name: "Zerodha is ready" })).toBeVisible();
  const width = page.viewportSize()!.width;
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});

test("a resumed wizard keeps its step; Kotak takes TOTP and MPIN, never stores them", async ({ page }) => {
  const state = await mock(page);
  state.brokers = [broker({ id: 31, broker: "KOTAK_NEO", name: "Kotak", brokerApp: "Kotak app", connection: "DISCONNECTED" })];
  await page.goto("/brokers/add?account=31&step=3");
  await expect(page.getByRole("heading", { name: "Test the login" })).toBeVisible();
  await page.getByLabel("TOTP").fill("123 456");          // as an authenticator app copies it
  await expect(page.getByLabel("TOTP")).toHaveValue("123456");
  await page.getByLabel("MPIN").fill("4321");
  await page.getByRole("button", { name: "Log in to Kotak" }).click();
  await expect.poll(() => state.posts.length).toBe(1);
  expect(state.posts[0]).toEqual({ url: "/api/execution/accounts/31/kotak/login", body: { totp: "123456", mpin: "4321" } });
  await expect(page.getByLabel("TOTP")).toHaveValue("");                 // cleared after the attempt
  await expect(page.getByLabel("MPIN")).toHaveValue("");
});

test("a member who cannot manage is told who can", async ({ page }) => {
  await mock(page);
  await page.route("**/api/execution/workspaces/7/today", (r) => r.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ ...today([]), canManage: false }) }));
  await page.goto("/brokers/add");
  await expect(page.getByText(/Only the workspace's owner or admin can add a broker/)).toBeVisible();
});
