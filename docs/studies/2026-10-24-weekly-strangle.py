#!/usr/bin/env python3
"""A-196 weekly NIFTY short strangle with adjustments (rules and interpretations fixed in the ledger before this ran).

Per weekly expiry E: from the first trading day after the previous cycle, 09:50 close: sell the PE and CE closest to Rs 30.
Every 1-min close: a leg at <= 50 % of its sell price is bought back and its side re-sold at the strike nearest 87.5 % of
the remaining leg. 15:15 daily: if one leg < half the other, the same adjustment on the lower leg. PE strike >= CE strike =
straddle: baseline = combined close; above 1.10 x baseline -> close all, next weekly expiry from the next trading day
09:50. Expiry day 15:29 close settles. Costs 1 % of the premium per order (>= Rs 0.05 / unit); qty 650 per leg.
Usage: python3 2026-10-24-weekly-strangle.py
"""
import csv, io, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
QTY, ENTRY_PX, ADJ = 650, 30.0, 0.875
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mn(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


DAYS = [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle where underlying = 'NIFTY' "
                        f"and kind = 'INDEX' and bar_start >= '2024-09-20') x order by d")]
EXPIRIES = [r[0] for r in q("select distinct expiry::text from hist.candle where underlying = 'NIFTY' and kind = 'OPTION' "
                            "and expiry between '2024-10-01' and '2026-09-30' order by 1")]
EXPIRIES = [e for e in EXPIRIES if e in DAYS]                 # an expiry must be a trading day with bars


def cost(px):
    return max(0.01 * px, 0.05) * QTY


def load(e, start):
    rows = q(f"select strike, option_type, (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), close from hist.candle "
             f"where underlying = 'NIFTY' and kind = 'OPTION' and expiry = '{e}' and bar_start >= ('{start} 09:15+05:30')::timestamptz "
             f"and bar_start <= ('{e} 15:29+05:30')::timestamptz")
    px = defaultdict(dict)
    for k, ty, d, t, c in rows:
        if c and float(c) > 0:
            px[(d, mn(t))][(float(k), ty)] = float(c)
    return px


def run_cycle(e, start):
    """Simulate one cycle from day `start` (09:50) to expiry e or a 10 % straddle stop. Returns a result dict."""
    px = load(e, start)
    days = [d for d in DAYS if start <= d <= e]
    last = {}                                                  # carried closes per contract
    legs, pnl, orders, notes, base = {}, 0.0, 0, [], None
    picks = {}

    def nearest(side, target):
        cands = [(abs(v - target), k) for (k, ty), v in last.items() if ty == side]
        return min(cands)[1] if cands else None

    def sell(side, k, why, when):
        nonlocal pnl, orders
        p = last[(k, side)]
        pnl += p * QTY - cost(p)
        orders += 1
        legs[side] = {"k": k, "sell": p}
        notes.append(f"{when} SELL {side} {int(k)} @ {p:.2f} ({why})")

    def buy(side, when, why):
        nonlocal pnl, orders
        p = last[(legs[side]["k"], side)]
        pnl -= p * QTY + cost(p)
        orders += 1
        notes.append(f"{when} BUY  {side} {int(legs[side]['k'])} @ {p:.2f} ({why})")
        del legs[side]

    for d in days:
        for m in range(mn("09:15"), mn("15:29") + 1):
            last.update(px.get((d, m), {}))
            when = f"{d} {m // 60:02d}:{m % 60:02d}"
            if not legs and d == start and m == mn("09:50"):
                for side in ("PE", "CE"):
                    k = nearest(side, ENTRY_PX)
                    if k is None:
                        return None
                    picks[side] = last[(k, side)]
                    sell(side, k, "entry ~Rs 30", when)
                continue
            if not legs:
                continue
            cur = {s: last[(legs[s]["k"], s)] for s in legs}
            if base is not None:
                if cur["PE"] + cur["CE"] > 1.10 * base:
                    for s in ("PE", "CE"):
                        buy(s, when, f"straddle +10 % (base {base:.2f})")
                    return {"pnl": pnl, "end": d, "stopped": True, "orders": orders, "notes": notes, "picks": picks}
            else:
                for side in ("PE", "CE"):
                    other = "CE" if side == "PE" else "PE"
                    if cur[side] <= 0.5 * legs[side]["sell"] or (m == mn("15:15") and cur[side] < 0.5 * cur[other]):
                        why = "<= 50 % of sell" if cur[side] <= 0.5 * legs[side]["sell"] else "15:15 < half the other leg"
                        buy(side, when, why)
                        k = nearest(side, ADJ * cur[other])
                        sell(side, k, f"87.5 % of {other} {cur[other]:.2f}", when)
                        cur[side] = last[(k, side)]
                if legs["PE"]["k"] >= legs["CE"]["k"]:
                    base = last[(legs["PE"]["k"], "PE")] + last[(legs["CE"]["k"], "CE")]
                    notes.append(f"{when} straddle mode, base {base:.2f}")
            if d == e and m == mn("15:29"):
                for s in ("PE", "CE"):
                    buy(s, when, "expiry settle (15:29 close)")
                return {"pnl": pnl, "end": d, "stopped": False, "orders": orders, "notes": notes, "picks": picks}
    return None


def main():
    results = []
    i = 0
    start = DAYS[DAYS.index(EXPIRIES[0]) + 1]                  # the first cycle starts after the first expiry in range
    i = 1
    while i < len(EXPIRIES):
        e = EXPIRIES[i]
        if start > e:
            i += 1
            continue
        span = [d for d in DAYS if start <= d <= e]
        if HELD_OUT & set(span):                                 # skip cycles touching the held-out days entirely
            start = DAYS[DAYS.index(e) + 1] if DAYS.index(e) + 1 < len(DAYS) else None
            i += 1
            if start is None:
                break
            continue
        print(f"\rcycle {e} from {start}   ", end="", file=sys.stderr)
        r = run_cycle(e, start)
        if r:
            r.update(expiry=e, start=start)
            results.append(r)
            nxt = DAYS.index(r["end"]) + 1
        else:
            nxt = DAYS.index(e) + 1
        if nxt >= len(DAYS):
            break
        start = DAYS[nxt]
        i += 1                                                   # after expiry or a stop: the next weekly expiry
    print(file=sys.stderr)
    for name, lo, hi in (("design Oct 2024-Sep 2025", "2024-10-01", "2025-09-30"), ("validation Oct 2025-Sep 2026", "2025-10-01", "2026-09-30")):
        sel = [r for r in results if lo <= r["expiry"] <= hi]
        p = [r["pnl"] for r in sel]
        eq, peak, dd = 0.0, 0.0, 0.0
        for v in p:
            eq += v; peak = max(peak, eq); dd = max(dd, peak - eq)
        rng = random.Random(196)
        means = sorted(statistics.mean(rng.choice(p) for _ in p) for _ in range(10000)) if len(p) > 1 else []
        print(f"\n== {name}: {len(sel)} cycles, total Rs {sum(p):+,.0f}, mean {statistics.mean(p):+,.0f}/cycle, "
              f"winning {sum(v > 0 for v in p)}/{len(p)}, worst {min(p):+,.0f}, best {max(p):+,.0f}, max drawdown {dd:,.0f}, "
              f"stopped {sum(r['stopped'] for r in sel)}, mean orders {statistics.mean(r['orders'] for r in sel):.1f}"
              + (f", CI95 of mean [{means[250]:+,.0f}, {means[9750]:+,.0f}]" if means else ""))
        pk = [v for r in sel for v in r["picks"].values()]
        print(f"   entry picks: median Rs {statistics.median(pk):.2f}, {sum(20 <= v <= 40 for v in pk)}/{len(pk)} within Rs 20-40")
        for r in sorted(sel, key=lambda r: r["pnl"])[:3]:
            print(f"   worst: expiry {r['expiry']} from {r['start']}: Rs {r['pnl']:+,.0f}")
            for n in r["notes"][:12]:
                print(f"      {n}")


if __name__ == "__main__":
    main()
