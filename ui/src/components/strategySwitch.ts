import type { GlobalLiveStrategies } from "../api";

/**
 * The owner's Off / Paper / Live switch per strategy for every account (V33 global LIVE list, V35 OFF list), as one plan
 * shared by the desktop's Accounts panel and the phone's Strategies screen (mobile phase 3, 17 Nov 2026: moved here from
 * AccountsPage unchanged, so both send exactly the same request).
 */
export type StrategyState = "OFF" | "PAPER" | "LIVE";
export const STATES: StrategyState[] = ["OFF", "PAPER", "LIVE"];
export const sameSet = (a: string[], b: string[]) => [...a].sort().join() === [...b].sort().join();

export interface GlobalPlan {
  names: string[];
  running: string[];
  saved: (s: string) => StrategyState;
  state: (s: string) => StrategyState;
  changed: boolean;
  /** the POST body that makes the switches true (only the lists that changed) */
  body: { strategies?: string[]; off?: string[] };
  toLive: string[];
  fromLive: string[];
  toOff: string[];
  backOn: string[];
  /** LIVE accounts that follow the global list (they trade a newly LIVE strategy with real money) */
  liveAccounts: string[];
}

export function planGlobal(g: GlobalLiveStrategies, picked: Record<string, StrategyState> | null): GlobalPlan {
  const running = g.running ?? [];
  const offNow = g.off ?? [];
  const names = [...new Set([...running, ...g.available, ...offNow])].sort();
  // not set: what the accounts trade now by their defaults (the union), so the switches start where things are
  const liveNow = g.global ?? [...new Set(g.accounts.filter((a) => a.source === "DEFAULT").flatMap((a) => a.effective))];
  const saved = (s: string): StrategyState => offNow.includes(s) ? "OFF" : liveNow.includes(s) ? "LIVE" : "PAPER";
  const state = (s: string): StrategyState => picked?.[s] ?? saved(s);
  const offNext = names.filter((s) => state(s) === "OFF");
  const liveNext = names.filter((s) => state(s) === "LIVE");
  const offChanged = !sameSet(offNext, offNow);
  // OFF takes a strategy out of the LIVE list on the server, so a newly OFF one is not a LIVE change by itself. With no
  // global list (each account on its risk-file default) the LIVE list is always sent, so what the switches show is what
  // trades: otherwise a default strategy switched Off and back to Paper would trade LIVE again on those accounts.
  const liveChanged = !sameSet(liveNext, liveNow.filter((s) => !offNext.includes(s)))
    || (g.global == null && offChanged);
  return {
    names, running, saved, state,
    changed: offChanged || liveChanged,
    body: { ...(liveChanged ? { strategies: liveNext } : {}), ...(offChanged ? { off: offNext } : {}) },
    toLive: liveNext.filter((s) => saved(s) !== "LIVE"),
    fromLive: names.filter((s) => saved(s) === "LIVE" && state(s) !== "LIVE"),
    toOff: offNext.filter((s) => !offNow.includes(s)),
    backOn: offNow.filter((s) => !offNext.includes(s)),
    liveAccounts: g.accounts.filter((a) => a.mode === "LIVE" && a.source !== "ACCOUNT").map((a) => a.account),
  };
}
