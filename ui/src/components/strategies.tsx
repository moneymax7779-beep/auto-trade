import { Link } from "react-router";
import { rupees, type PositionRow, type Scores } from "../api";
import { Pnl, StageBadge } from "./ui";

/** What each strategy does, in a line, and when it may trade (from its config file; the file is the authority). */
export const STRATEGY_INFO: Record<string, { short: string; name: string; when: string; what: string; scores?: boolean }> = {
  "expiry-breakout-straddle": {
    short: "EBS", name: "Expiry breakout straddle", when: "expiry day · 12:30–14:00 · flat 15:15",
    what: "A close through ORH / PDH or ORL / PDL buys the ATM straddle; combined +30 % target, −20 % stop.",
  },
  "early-confirm-runner": {
    short: "ECR", name: "Early · confirm · runner", when: "expiry days · staged through the day", scores: true,
    what: "Scores build a position in tranches: early near ORH / ORL, confirm on the breakout bar, runner on continuation.",
  },
  "opening-drive": {
    short: "ODB", name: "Opening drive", when: "every day · triggers 09:16–09:25 · flat 10:00",
    what: "A break of PDH / prior ORH (calls) or PDL / prior ORL (puts) from the untouched side; 25 % stop, trailing exit.",
  },
  "expiry-gamma-breakout": {
    short: "EGB", name: "Expiry gamma breakout", when: "expiry day · 09:30–14:45 · flat 15:15",
    what: "Compression, then a break of ORH / PDH or ORL / PDL, a retest and hold; tranches 25 / 45 / 30.",
  },
  "break-retest": {
    short: "BRT", name: "Break and retest", when: "non-expiring index · 09:31–14:30 · 30-min stop",
    what: "A close through ORH / ORL / PDH / PDL, a retest that holds, entry on the turn; stop at the retest extreme, target the next level.",
  },
  "ma-cross": {
    short: "MAC", name: "EMA9 / EMA20 cross", when: "non-expiring index · 09:30–14:30 · 60-min stop",
    what: "A cross of the fast average over the slow one buys the ATM option that way; stop at the last swing, target twice the stop distance.",
  },
  "expiry-trend-rider": {
    short: "ETR", name: "Expiry trend rider", when: "expiry index · 11:00–14:30 · flat 15:15",
    what: "Rides a trend with breadth and futures OI behind it, adding up to 3 times; exits on a swing break.",
  },
};

export const strategyShort = (id: string) => STRATEGY_INFO[id]?.short ?? id;

/** One strategy's latest decision on one index. */
export interface MatrixCell { strategy: string; underlying: string; time?: string; ce: Scores; pe: Scores }

const QUIET = new Set(["WATCH", "IDLE", "DONE", "?"]);

/**
 * Strategies (rows) by index (columns): each side's stage, the ECR scores where a strategy has them, and the
 * strategy's open position on that index. Quiet stages are muted so a live one stands out.
 */
export function StrategyMatrix({ cells, positions = [], underlyings }: {
  cells: MatrixCell[]; positions?: PositionRow[]; underlyings: string[];
}) {
  // the live decision order (the straddle first), unknown strategies last
  const rank = (id: string) => { const i = Object.keys(STRATEGY_INFO).indexOf(id); return i < 0 ? 99 : i; };
  const strategies = [...new Set(cells.map((c) => c.strategy))].sort((a, b) => rank(a) - rank(b));
  if (strategies.length === 0) return <p className="text-sm text-muted">No decisions yet.</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead className="text-left text-muted">
          <tr>
            <th className="py-1.5 pr-3 font-normal">Strategy</th>
            {underlyings.map((u) => (
              <th key={u} className="py-1.5 pr-3 font-normal">
                <Link to={`/index/${u}`} className="hover:text-text hover:underline">{u} →</Link>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {strategies.map((id) => {
            const info = STRATEGY_INFO[id];
            return (
              <tr key={id} className="border-t border-line align-top">
                <td className="py-2 pr-3">
                  <span className="font-semibold" title={info?.name ?? id}>{info?.short ?? id}</span>
                  <div className="text-muted">{info?.when ?? ""}</div>
                </td>
                {underlyings.map((u) => {
                  const cell = cells.find((c) => c.strategy === id && c.underlying === u);
                  const open = positions.filter((p) => p.underlying === u && (p.strategy ?? id) === id && p.quantity > 0);
                  return (
                    <td key={u} className="py-2 pr-3">
                      {cell ? <SideStages cell={cell} scores={!!info?.scores} /> : <span className="text-muted">not traded</span>}
                      {open.length > 0 && (
                        <div className="mt-1 num">
                          {open.map((p) => p.symbol.split(" ").slice(1, 3).join(" ")).join(" + ")} · <Pnl value={open.reduce((s, p) => s + p.net, 0)} />
                          <span className="text-muted"> on {rupees(open.reduce((s, p) => s + (p.premiumPaid ?? 0), 0))}</span>
                        </div>
                      )}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function SideStages({ cell, scores }: { cell: MatrixCell; scores: boolean }) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {(["ce", "pe"] as const).map((k) => {
        const side = cell[k];
        const quiet = QUIET.has(side.stage);
        return (
          <span key={k} className={`flex items-center gap-1 ${quiet ? "opacity-50" : ""}`}>
            <span className="text-muted">{k.toUpperCase()}</span>
            <StageBadge stage={side.stage} />
            {scores && Number.isFinite(side.confirm) && side.confirm >= 0 && (
              <span className="num text-muted" title="early / confirm / runner scores">
                {Math.round(side.early)}/{Math.round(side.confirm)}/{Math.round(side.runner)}
              </span>
            )}
          </span>
        );
      })}
      {cell.time && <span className="num text-muted">{cell.time}</span>}
    </div>
  );
}

/** "NIFTY · early-confirm-runner" (several strategies) or "NIFTY" (one). */
export function splitDecisionKey(key: string, only?: string): { underlying: string; strategy: string } {
  const i = key.indexOf(" · ");
  return i < 0 ? { underlying: key, strategy: only ?? "strategy" } : { underlying: key.slice(0, i), strategy: key.slice(i + 3) };
}

/** Stored scores use −1 for "not computed"; shown blank. */
export function storedScores(scores: Scores | undefined, stage: string): Scores {
  const v = (x: number | undefined) => (x == null || x < 0 ? NaN : x);
  return { stage, early: v(scores?.early), confirm: v(scores?.confirm), runner: v(scores?.runner) };
}
