#!/usr/bin/env python3
"""A-213 weekly NIFTY short strangle at the range edges with stops just beyond (rules fixed in the ledger before this ran).

Cycle per weekly expiry E as A-196 (first trading day after the previous cycle, 09:50 close; qty 650 a leg; costs 1 % of the
premium per order, >= Rs 0.05 a unit; settle at the expiry day's 15:29 close), no adjustments.
RANGE  sell the CE at the lowest strike >= the previous 10 trading days' index high, the PE at the highest strike <= their low;
       skip the cycle if the 09:50 spot is outside that range.
MAXOI  sell the CE strike above spot with the most OI and the PE strike below spot with the most OI (latest OI by 09:50).
Stop   the first index 1-minute close above CE strike x 1.002 or below PE strike x 0.998: buy back both legs, cycle ends.
Usage: python3 2026-10-24-range-strangle.py                (first run: the next cycle starts the day after a stop)
       python3 2026-10-24-range-strangle.py after-expiry   (A-213 note: the day after the cycle's expiry; bars hold only
                                                           the nearest weekly series, so a mid-week start has no bars)
"""
import csv, io, math, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
QTY, STEP, LOOKBACK, BEYOND = 650, 50, 10, 0.002
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
AFTER_EXPIRY = sys.argv[1:] == ["after-expiry"]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mn(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


DAILY = {d: (float(h), float(l)) for d, h, l in q(
    f"select (bar_start {IST})::date::text, max(high), min(low) from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' "
    f"and bar_start >= '2024-09-01' group by 1 order by 1")}
DAYS = sorted(d for d in DAILY if d >= "2024-09-20")
EXPIRIES = [r[0] for r in q("select distinct expiry::text from hist.candle where underlying = 'NIFTY' and kind = 'OPTION' "
                            "and expiry between '2024-10-01' and '2026-09-30' order by 1")]
EXPIRIES = [e for e in EXPIRIES if e in DAYS]
ALL_DAYS = sorted(DAILY)


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


def index_closes(start, e):
    rows = q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = 'NIFTY' "
             f"and kind = 'INDEX' and bar_start >= ('{start} 09:15+05:30')::timestamptz and bar_start <= ('{e} 15:29+05:30')::timestamptz")
    return {(d, mn(t)): float(c) for d, t, c in rows}


def max_oi(e, start, spot):
    rows = q(f"select distinct on (strike, option_type) strike, option_type, oi from hist.candle where underlying = 'NIFTY' "
             f"and kind = 'OPTION' and expiry = '{e}' and oi > 0 and bar_start >= ('{start} 09:15+05:30')::timestamptz "
             f"and bar_start <= ('{start} 09:50+05:30')::timestamptz order by strike, option_type, bar_start desc")
    ce = [(float(oi), float(k)) for k, ty, oi in rows if ty == "CE" and float(k) > spot]
    pe = [(float(oi), float(k)) for k, ty, oi in rows if ty == "PE" and float(k) < spot]
    return (max(ce)[1] if ce else None), (max(pe)[1] if pe else None)


def run_cycle(arm, e, start):
    span = [d for d in DAYS if start <= d <= e]
    idx, px = index_closes(start, e), load(e, start)
    last, spot = {}, None
    for m in range(mn("09:15"), mn("09:50") + 1):
        last.update(px.get((start, m), {}))
        spot = idx.get((start, m), spot)
    if spot is None:
        return {"skip": "no index at 09:50"}
    if arm == "RANGE":
        prev = [d for d in ALL_DAYS if d < start][-LOOKBACK:]
        hi, lo = max(DAILY[d][0] for d in prev), min(DAILY[d][1] for d in prev)
        if not lo <= spot <= hi:
            return {"skip": "spot outside the 10-day range"}
        kc, kp = math.ceil(hi / STEP) * STEP, math.floor(lo / STEP) * STEP
    else:
        kc, kp = max_oi(e, start, spot)
        if kc is None or kp is None:
            return {"skip": "no OI"}
    # a strike without a price by 09:50: the nearest further-out strike that has one
    ce = sorted(k for (k, ty) in last if ty == "CE" and k >= kc)
    pe = sorted((k for (k, ty) in last if ty == "PE" and k <= kp), reverse=True)
    if not ce or not pe:
        return {"skip": "no option price"}
    kc, kp = ce[0], pe[0]
    sold = {"CE": last[(kc, "CE")], "PE": last[(kp, "PE")]}
    pnl = sum(p * QTY - cost(p) for p in sold.values())
    info = {"spot": spot, "kc": kc, "kp": kp, "ce": sold["CE"], "pe": sold["PE"], "orders": 2}
    for d in span:
        for m in range(mn("09:15"), mn("15:29") + 1):
            if d == start and m <= mn("09:50"):
                continue
            last.update(px.get((d, m), {}))
            c = idx.get((d, m))
            stop = c is not None and (c > kc * (1 + BEYOND) or c < kp * (1 - BEYOND))
            if stop or (d == e and m == mn("15:29")):
                for k, ty in ((kc, "CE"), (kp, "PE")):
                    p = last[(k, ty)]
                    pnl -= p * QTY + cost(p)
                info.update(pnl=pnl, end=d, stopped=stop, orders=4, when=f"{d} {m // 60:02d}:{m % 60:02d}")
                return info
    return {"skip": "no expiry close"}


def main():
    results, skips = defaultdict(list), defaultdict(lambda: defaultdict(int))
    for arm in ("RANGE", "MAXOI"):
        start, i = DAYS[DAYS.index(EXPIRIES[0]) + 1], 1
        while i < len(EXPIRIES) and start:
            e = EXPIRIES[i]
            if start > e:
                i += 1
                continue
            nxt_day = DAYS[DAYS.index(e) + 1] if DAYS.index(e) + 1 < len(DAYS) else None
            if HELD_OUT & {d for d in DAYS if start <= d <= e}:
                start, i = nxt_day, i + 1
                continue
            print(f"\r{arm} cycle {e} from {start}   ", end="", file=sys.stderr)
            r = run_cycle(arm, e, start)
            if "skip" in r:
                skips[arm][r["skip"]] += 1
                start = nxt_day
            else:
                r.update(expiry=e, start=start)
                results[arm].append(r)
                j = DAYS.index(e if AFTER_EXPIRY else r["end"]) + 1
                start = DAYS[j] if j < len(DAYS) else None
            i += 1
    print(file=sys.stderr)
    print(("[rerun: next cycle from the day after each cycle's expiry] " if AFTER_EXPIRY else "[first run: next cycle from the day after a stop] ")
          + "A-213 short strangle at the range edges, stops 0.2 % beyond the strikes; qty 650 a leg; Rs net of costs per cycle.")
    print("A-196 (Rs 30 picks with adjustments) for comparison: design +Rs 5,50,364 on 46 cycles, validation +Rs 9,95,896 on 50 "
          "(CI95 [-4,577, +40,515]), worst -3,90,548.")
    for arm in ("RANGE", "MAXOI"):
        print(f"\n######## {arm}   skipped: " + (", ".join(f"{k} {v}" for k, v in skips[arm].items()) or "none"))
        for name, lo, hi in (("design Oct 2024-Sep 2025", "2024-10-01", "2025-09-30"),
                             ("validation Oct 2025-Sep 2026", "2025-10-01", "2026-09-30")):
            sel = [r for r in results[arm] if lo <= r["expiry"] <= hi]
            if not sel:
                print(f"== {name}: no cycles")
                continue
            p = [r["pnl"] for r in sel]
            eq, peak, dd = 0.0, 0.0, 0.0
            for v in p:
                eq += v; peak = max(peak, eq); dd = max(dd, peak - eq)
            rng = random.Random(213)
            means = sorted(statistics.mean(rng.choice(p) for _ in p) for _ in range(10000)) if len(p) > 1 else []
            print(f"== {name}: {len(sel)} cycles, total Rs {sum(p):+,.0f}, mean {statistics.mean(p):+,.0f}/cycle, "
                  f"winning {sum(v > 0 for v in p)}/{len(p)}, worst {min(p):+,.0f}, best {max(p):+,.0f}, max drawdown {dd:,.0f}, "
                  f"stopped {sum(r['stopped'] for r in sel)}" + (f", CI97.5 of mean [{means[125]:+,.0f}, {means[9874]:+,.0f}]" if means else ""))
            dist_c = [(r["kc"] - r["spot"]) / r["spot"] * 100 for r in sel]
            dist_p = [(r["spot"] - r["kp"]) / r["spot"] * 100 for r in sel]
            print(f"   strikes from spot: CE median {statistics.median(dist_c):.2f} %, PE median {statistics.median(dist_p):.2f} %; "
                  f"premiums: CE median Rs {statistics.median(r['ce'] for r in sel):.2f}, PE median Rs {statistics.median(r['pe'] for r in sel):.2f}; "
                  f"credit median Rs {statistics.median((r['ce'] + r['pe']) * QTY for r in sel):,.0f}")
            for r in sorted(sel, key=lambda r: r["pnl"])[:3]:
                print(f"   worst: expiry {r['expiry']} from {r['start']}: Rs {r['pnl']:+,.0f} (spot {r['spot']:.0f}, sold {r['kc']:.0f} CE "
                      f"{r['ce']:.2f} / {r['kp']:.0f} PE {r['pe']:.2f}, {'stop' if r['stopped'] else 'expiry'} {r['when']})")


if __name__ == "__main__":
    main()
