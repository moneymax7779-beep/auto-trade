import AxeBuilder from "@axe-core/playwright";
import { expect, type Page, test } from "@playwright/test";
import { mock } from "./mocks";

// Mobile phase 5 (17 Nov 2026): WCAG 2 A/AA checks (axe) on every phone screen with stored answers. A serious or
// critical violation fails; the rest are printed.
const GLOBAL = { available: ["oi-burst-cross", "ma-cross"], running: ["oi-burst-cross", "ma-cross"], global: ["oi-burst-cross"],
  off: [], accounts: [{ id: 1, account: "ZERODHA-1", mode: "LIVE", source: "GLOBAL", effective: ["oi-burst-cross"] }], canEdit: true };
const PUSH = { enabled: true, publicKey: "B".padEnd(87, "A"), devices: [] };

async function audit(page: Page, what: string) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  for (const v of r.violations) {
    console.log(`A11Y ${what} [${v.impact}] ${v.id}: ${v.help} :: ${v.nodes.slice(0, 3).map((n) => n.target.join(" ")).join(" | ")}`);
  }
  expect(r.violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => `${v.id} (${v.nodes.length})`)).toEqual([]);
}

async function more(page: Page) {
  await mock(page);
  await page.route("**/api/execution/live-strategies", (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(GLOBAL) }));
  await page.route("**/api/execution/push", (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(PUSH) }));
}

for (const path of ["/home", "/positions", "/ledger", "/strategies", "/notifications"]) {
  test(`accessible: ${path}`, async ({ page }) => {
    await more(page);
    await page.goto(path);
    await page.waitForTimeout(1_200);
    await audit(page, path);
  });
}

test("accessible: the Exit sheet and the More sheet", async ({ page }) => {
  await more(page);
  await page.goto("/positions");
  await page.getByRole("button", { name: "Exit…" }).click();
  await page.waitForTimeout(800);
  await audit(page, "exit sheet");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "More" }).click();
  await audit(page, "more sheet");
});
