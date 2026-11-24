import { expect, test } from "@playwright/test";

// Every menu page of the owner's view (autotrade.auth.mode=none) and the member-only pages. A page passes when, after
// its data has loaded, the document is no wider than the phone (nothing scrolls sideways; wide tables scroll inside
// themselves), the bottom tab bar is on screen with thumb-sized targets, and the desktop top menu is hidden.
const PAGES = ["/home", "/positions", "/ledger", "/live", "/index/NIFTY", "/index/SENSEX", "/sessions", "/performance", "/research", "/replay", "/accounts",
  "/household", "/config", "/today", "/brokers", "/settings"];

for (const path of PAGES) {
  test(`fits the phone: ${path}`, async ({ page }, info) => {
    await page.goto(path);
    await page.waitForTimeout(3_000);   // data loads; live pages keep polling, so never "network idle"
    await page.screenshot({ path: `e2e-out/${info.project.name}${path.replaceAll("/", "_")}.png`, fullPage: false });
    // on a phone a too-wide page widens the layout viewport itself (the browser zooms out), so compare with the
    // device width, not window.innerWidth
    const innerWidth = page.viewportSize()?.width ?? 0;
    const scrollWidth = await page.evaluate(() => Math.max(document.documentElement.scrollWidth, window.innerWidth));
    if (scrollWidth > innerWidth) {
      // name the widest offenders so the failure says what to fix
      const wide = await page.evaluate((w) => [...document.querySelectorAll<HTMLElement>("body *")]
        .filter((e) => e.getBoundingClientRect().right > w + 0.5)
        .filter((e) => ![...document.querySelectorAll<HTMLElement>("*")].some((p) => p !== e && p.contains(e)
          && getComputedStyle(p).overflowX !== "visible" && p.getBoundingClientRect().right <= w + 1))
        .slice(0, 5).map((e) => `${e.tagName.toLowerCase()}.${e.className.toString().slice(0, 60)} right=${Math.round(e.getBoundingClientRect().right)}`), innerWidth);
      throw new Error(`${path}: page is ${scrollWidth}px wide on a ${innerWidth}px phone; widest: ${wide.join(" | ")}`);
    }
    const tabs = page.getByRole("navigation", { name: "Main" });
    await expect(tabs).toBeVisible();
    const box = await tabs.boundingBox();
    expect(box && box.y + box.height).toBeLessThanOrEqual((page.viewportSize()?.height ?? 0) + 2)   // sub-pixel rounding;
    for (const link of await tabs.locator("a, button").all()) {
      expect((await link.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
  });
}

test("More opens every other menu item", async ({ page }) => {
  await page.goto("/live");
  await page.getByRole("button", { name: "More" }).click();
  const sheet = page.getByRole("dialog", { name: "More" });
  await expect(sheet).toBeVisible();
  for (const label of ["Overview", "SENSEX", "Sessions", "Research", "Replay", "Accounts", "Family", "Config"]) {
    await expect(sheet.getByRole("link", { name: label, exact: true })).toBeVisible();
  }
  await sheet.getByRole("link", { name: "Sessions", exact: true }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  await expect(sheet).toBeHidden();
});

test("the desktop menu is hidden on a phone", async ({ page }) => {
  await page.goto("/live");
  await expect(page.locator("header nav")).toBeHidden();
});

test.describe("desktop", () => {
  test.use({ viewport: { width: 1280, height: 800 }, isMobile: false, hasTouch: false });
  test("keeps the top menu and has no tab bar", async ({ page }) => {
    await page.goto("/live");
    await expect(page.locator("header nav")).toBeVisible();
    await expect(page.locator("header nav").getByRole("link", { name: "Overview", exact: true })).toBeVisible();
    // since 23 Nov 2026 (8383d69) the less-used pages, Config among them, sit under the More button beside the bar
    await page.locator("header").getByRole("button", { name: /^More/ }).click();
    await expect(page.locator("#more-pages").getByRole("link", { name: "Config", exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("navigation", { name: "Main" })).toBeHidden();
    await page.screenshot({ path: "e2e-out/desktop_live.png" });
  });
});
