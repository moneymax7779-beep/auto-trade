import { expect, test } from "@playwright/test";
import type { GlobalLiveStrategies } from "../src/api";
import { planGlobal } from "../src/components/strategySwitch";

// The shared Off / Paper / Live plan (desktop Accounts panel and phone Strategies screen): the request it sends.
const g = (over: Partial<GlobalLiveStrategies> = {}): GlobalLiveStrategies => ({
  available: ["obc", "wbr", "mac"], running: ["obc", "wbr", "mac", "ecr"], global: ["obc"], off: [],
  accounts: [{ id: 1, account: "ZERODHA-1", mode: "LIVE", source: "GLOBAL", effective: ["obc"] },
    { id: 2, account: "PAPER-40", mode: "PAPER", source: "DEFAULT", effective: [] }], canEdit: true, ...over });

test("no change sends nothing", () => {
  const p = planGlobal(g(), null);
  expect(p.changed).toBe(false);
  expect(p.body).toEqual({});
  expect(p.names).toEqual(["ecr", "mac", "obc", "wbr"]);
});

test("Off on a LIVE strategy sends only the OFF list (the server takes it out of LIVE)", () => {
  const p = planGlobal(g(), { obc: "OFF" });
  expect(p.body).toEqual({ off: ["obc"] });
  expect(p.fromLive).toEqual(["obc"]);
  expect(p.toOff).toEqual(["obc"]);
});

test("to Live lists the real-money accounts that follow the global list", () => {
  const p = planGlobal(g(), { wbr: "LIVE" });
  expect(p.body).toEqual({ strategies: ["obc", "wbr"] });
  expect(p.toLive).toEqual(["wbr"]);
  expect(p.liveAccounts).toEqual(["ZERODHA-1"]);
});

test("without a global list the LIVE list is always sent with an OFF change", () => {
  const p = planGlobal(g({ global: null, accounts: [{ id: 1, account: "ZERODHA-1", mode: "LIVE", source: "DEFAULT",
    effective: ["obc", "mac"] }] }), { mac: "OFF" });
  expect(p.body).toEqual({ strategies: ["obc"], off: ["mac"] });
});

test("back on from Off returns as Paper", () => {
  const p = planGlobal(g({ off: ["mac"] }), { mac: "PAPER" });
  expect(p.saved("mac")).toBe("OFF");
  expect(p.body).toEqual({ off: [] });
  expect(p.backOn).toEqual(["mac"]);
});
