import { expect, type Page, test } from "@playwright/test";

// Mobile phase 4 (17 Nov 2026): the Notifications screen against stored answers. Subscribing itself needs a real push
// service and the production service worker, so it is checked on a phone after a deploy, not here.
const STATE = (enabled: boolean) => ({ enabled, publicKey: enabled ? "B".padEnd(87, "A") : null,
  devices: enabled ? [{ id: 4, userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Safari/604.1",
    createdAt: "2026-11-17T10:00:00+05:30", lastOk: "2026-11-17T10:05:00+05:30", lastError: null, endpoint: "web.push.apple.com" }] : [] });

async function mock(page: Page, enabled: boolean) {
  const posts: { url: string; body: unknown }[] = [];
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (p === "/api/presence") return route.fulfill({ status: 204 });   // the tab's activity beat (presence.ts, 23 Nov 2026), not a user action
    if (req.method() === "POST") { posts.push({ url: p, body: req.postDataJSON() }); return route.fulfill(json({ removed: 1 })); }
    if (p === "/api/me") return route.fulfill(json({ mode: "none", user: "local-owner", operator: true, logout: false }));
    if (p === "/api/execution/push") return route.fulfill(json(STATE(enabled)));
    if (p === "/api/execution/login-board") return route.fulfill(json({ day: "2026-11-18", tradingDay: true, accounts: [] }));
    return route.fulfill(json([]));
  });
  return posts;
}

test("server off: says so, and Home has no notifications line", async ({ page }) => {
  await mock(page, false);
  await page.goto("/notifications");
  await expect(page.getByText(/not switched on for this server yet/)).toBeVisible();
  await page.goto("/home");
  await expect(page.getByText("No broker accounts: PAPER only.")).toBeVisible();
  await expect(page.getByText("Notifications off on this phone")).toHaveCount(0);
});

test("server on: Home offers to turn them on; the device list removes a device", async ({ page }) => {
  const posts = await mock(page, true);
  await page.goto("/home");
  await expect(page.getByText("Notifications off on this phone")).toBeVisible();
  await page.getByRole("link", { name: "Turn on" }).click();
  await expect(page).toHaveURL(/\/notifications$/);
  await expect(page.getByText("iPhone · Safari")).toBeVisible();
  if (test.info().project.name !== "iphone-13") {        // an iPhone not on the Home Screen is told to add it first
    await expect(page.getByRole("button", { name: "Turn on for this phone" })).toBeEnabled();
  }
  await page.getByRole("button", { name: "Remove" }).click();
  await expect.poll(() => posts.length).toBe(1);
  expect(posts[0]).toEqual({ url: "/api/execution/push/unsubscribe", body: { id: 4 } });
  await page.screenshot({ path: `e2e-out/p4-${test.info().project.name}-notifications.png` });
});

test("on an iPhone in Safari (not installed): add to the Home Screen first", async ({ page }) => {
  test.skip(test.info().project.name !== "iphone-13", "iPhone only");
  await mock(page, true);
  await page.goto("/notifications");
  await expect(page.getByText("On iPhone: add auto-trade to the Home Screen first.")).toBeVisible();
});
