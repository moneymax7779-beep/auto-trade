/**
 * The strike ladder's huge changes and when they blink (user, 11 Nov 2026: "highlighting/blinking background effect when
 * there is huge changes"). Pure functions, so they can be checked without a browser.
 */

type N = number | null;
interface SideChange { d: N; pct: N; oi0: N }
export interface LadderRow { k: number; c: SideChange; p: SideChange }
export type Size = "units" | "pct";

export const NO_CELLS: ReadonlySet<string> = new Set();
export const cellKey = (k: number, side: "c" | "p") => `${k}:${side}`;

const medianOf = (v: number[]) => { const s = [...v].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : 0; };

/**
 * The huge change cells ("strike:c" / "strike:p"), relative so NIFTY, SENSEX and BANKNIFTY read alike: on each side the
 * two largest |changes|, kept only when at least 5 × the median non-zero |change| of all the ladder's cells and at least
 * half the ladder's largest. On 9 and 6 Nov ladders (NIFTY, SENSEX; 50 windows from 1 min to since the open) that picked
 * 1–4 cells, mostly 3. In "% of strike" the same rule on the percentages, among strikes that held at least a quarter of
 * their side's median OI at the window's start (a far strike going from 40 to 2,000 OI is +4,900 % and would otherwise
 * always win). Null when fewer than 6 cells changed: the window held no OI print, so there is nothing to judge.
 */
export function hugeCells(rows: LadderRow[], size: Size): ReadonlySet<string> | null {
  const floor = (side: "c" | "p") => (size === "pct" ? 0.25 * medianOf(rows.map((r) => r[side].oi0 ?? 0).filter((v) => v > 0)) : 0);
  const cells = (["c", "p"] as const).flatMap((side) => {
    const min = floor(side);
    return rows.map((r) => ({ key: cellKey(r.k, side), side, v: Math.abs((size === "pct" ? r[side].pct : r[side].d) ?? 0), oi0: r[side].oi0 ?? 0 }))
      .filter((c) => c.v > 0 && Number.isFinite(c.v) && c.oi0 >= min);
  });
  if (cells.length < 6) return null;
  const med = medianOf(cells.map((c) => c.v)), max = Math.max(...cells.map((c) => c.v));
  return new Set((["c", "p"] as const).flatMap((side) => cells.filter((c) => c.side === side).sort((a, b) => b.v - a.v).slice(0, 2)
    .filter((c) => c.v >= 5 * med && c.v >= 0.5 * max).map((c) => c.key)));
}

/** How many of the latest ladders that held an OI print a cell must be missing from to blink again (about 3 minutes on NSE). */
export const QUIET_LADDERS = 3;

/**
 * What the blink remembers between ladders: which view it was (underlying, day, window, unit), the moment, whether that
 * moment was a live step (live and not pinned), and the huge cells of the latest ladders that held an OI print.
 */
export interface PulseMemo { ctx: string; at: string; live: boolean; recent: ReadonlySet<string>[] }

/**
 * One ladder arriving. Returns the memo to keep and the cells to blink: a set (possibly empty) replaces the blinking
 * cells, null leaves them as they are (the same ladder again). A cell blinks only on a new live step of the same view,
 * and only when it was not huge in any of the last {@link QUIET_LADDERS} ladders that held a print, so a cell hovering at
 * the threshold, or a short window that alternates between a print and none, does not blink every minute. Opening the
 * page, changing the window, unit or day, pinning a moment or letting go of one never blinks.
 */
export function pulseStep(prev: PulseMemo | null, ctx: string, at: string, live: boolean, huge: ReadonlySet<string> | null):
    { memo: PulseMemo; fresh: ReadonlySet<string> | null } {
  const sameView = prev != null && prev.ctx === ctx;
  if (sameView && prev.at === at && prev.live === live) return { memo: prev, fresh: null };
  const recent = sameView ? prev.recent : [];
  const memo: PulseMemo = { ctx, at, live, recent: huge ? [...recent, huge].slice(-QUIET_LADDERS) : recent };
  if (!live || !sameView || !prev.live || !huge) return { memo, fresh: NO_CELLS };
  const fresh = new Set([...huge].filter((c) => !recent.some((s) => s.has(c))));
  return { memo, fresh };
}
