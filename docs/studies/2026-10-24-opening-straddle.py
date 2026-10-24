#!/usr/bin/env python3
"""A-195 opening ATM straddle with a +10 % target (definitions and criteria fixed in the ledger before this ran).

09:16:00 buy the ATM call + put (nearest weekly expiry); sell both when the combined bid >= 1.10 x cost; else at 10:00
(rule), 11:00 / 15:15 (descriptive). No stop. Bars: 09:15 close + 0.75 % per leg, minute closes, - 0.75 % out.
Ticks (own capture): asks at 09:16:00, combined bid each second.
Usage: python3 2026-10-24-opening-straddle.py
"""
import csv, io, random, statistics, subprocess
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST, TARGET = 0.0075, 0.10
LIMITS = {"10:00": 10 * 60, "11:00": 11 * 60, "15:15": 15 * 60 + 15}
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
TICK_DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
             "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21",
             "2026-10-22", "2026-10-23"]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mn(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def bar_trade(u, d):
    ix = q(f"select close from hist.candle where underlying = '{u}' and kind = 'INDEX' and bar_start = ('{d} 09:15+05:30')::timestamptz")
    if not ix:
        return None
    k = round(float(ix[0][0]) / STEP[u]) * STEP[u]
    exp = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
            f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:20+05:30')::timestamptz")
    if not exp or not exp[0] or not exp[0][0]:
        return None
    exp = exp[0][0]
    legs = defaultdict(dict)
    for ty, t, c in q(f"select option_type, to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' "
                      f"and kind = 'OPTION' and expiry = '{exp}' and strike = {k} and bar_start >= ('{d} 09:15+05:30')::timestamptz "
                      f"and bar_start <= ('{d} 15:15+05:30')::timestamptz"):
        if c and float(c) > 0:
            legs[ty][mn(t)] = float(c)
    if mn("09:15") not in legs["CE"] or mn("09:15") not in legs["PE"]:
        return None
    cost = (legs["CE"][mn("09:15")] + legs["PE"][mn("09:15")]) * (1 + COST)
    out, last = {}, {"CE": legs["CE"][mn("09:15")], "PE": legs["PE"][mn("09:15")]}
    hit = None
    for m in range(mn("09:16"), LIMITS["15:15"] + 1):
        for ty in ("CE", "PE"):
            if m in legs[ty]:
                last[ty] = legs[ty][m]
        v = (last["CE"] + last["PE"]) * (1 - COST)
        if hit is None and v >= cost * (1 + TARGET):
            hit = (v / cost - 1, m)
        for lab, lim in LIMITS.items():
            if m == lim and lab not in out:
                out[lab] = hit if hit and hit[1] <= lim else (v / cost - 1, None)
    return {"exp_day": exp == d, "k": k, "cost": cost / (1 + COST), **out} if len(out) == 3 else None


def tick_trade(u, d):
    ix = q(f"select price from md.index_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
           f"and recv_ts < '{d} 09:16+05:30' order by recv_ts desc limit 1")
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")
    if not ix or not exp or not exp[0][0]:
        return None
    k, exp = round(float(ix[0][0]) / STEP[u]) * STEP[u], exp[0][0]
    rows = q(f"select option_type, extract(epoch from time_bucket('1 second', recv_ts) - '{d} 00:00+05:30'::timestamptz)::int, "
             f"last(bid_px[1], recv_ts), last(ask_px[1], recv_ts) from md.option_tick where session_date = '{d}' and underlying = '{u}' "
             f"and expiry = '{exp}' and strike = {k} and recv_ts >= '{d} 09:16+05:30' and recv_ts <= '{d} 15:15+05:30' group by 1, 2")
    qs = defaultdict(dict)
    for ty, s, bd, ak in rows:
        if bd and ak and float(bd) > 0 and float(ak) > 0:
            qs[ty][int(s)] = (float(bd), float(ak))
    secs = range(9 * 3600 + 16 * 60, 15 * 3600 + 15 * 60 + 1)
    first = {ty: next((s for s in secs if s in qs[ty]), None) for ty in ("CE", "PE")}
    if None in first.values():
        return None
    s0 = max(first.values())
    last = {ty: qs[ty][first[ty]] for ty in ("CE", "PE")}
    cost = last["CE"][1] + last["PE"][1]
    out, hit = {}, None
    for s in range(s0, 15 * 3600 + 15 * 60 + 1):
        for ty in ("CE", "PE"):
            if s in qs[ty]:
                last[ty] = qs[ty][s]
        v = last["CE"][0] + last["PE"][0]
        if hit is None and s > s0 and v >= cost * (1 + TARGET):
            hit = (v / cost - 1, s // 60)
        for lab, lim in LIMITS.items():
            if s == lim * 60 and lab not in out:
                out[lab] = hit if hit and hit[1] <= lim else (v / cost - 1, None)
    return {"exp_day": exp == d, "k": k, "cost": cost, **out} if len(out) == 3 else None


def bar_days(lo, hi):
    ds = [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle where underlying = 'NIFTY' "
                          f"and kind = 'INDEX' and bar_start >= '{lo}' and bar_start < ('{hi}'::date + 1)) x order by d")]
    return [d for d in ds if d not in HELD_OUT]


def boot(xs, days, n=10000, seed=195):
    by = defaultdict(list)
    for x_, d in zip(xs, days):
        by[d].append(x_)
    keys = list(by)
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def report(name, rows):
    print(f"\n== {name}: {len(rows)} trades")
    for lab in LIMITS:
        for sub, f in (("all", lambda r: True), ("expiry day", lambda r: r["exp_day"]), ("other days", lambda r: not r["exp_day"])):
            sel = [r for r in rows if f(r)]
            if not sel:
                continue
            xs = [r[lab][0] for r in sel]
            hits = sum(r[lab][1] is not None for r in sel)
            ci = boot(xs, [r["d"] for r in sel]) if sub == "all" and len(sel) > 2 else None
            print(f"  give up {lab} {sub:10s} n={len(xs):4d} mean {statistics.mean(xs):+6.2%} median {statistics.median(xs):+6.2%} "
                  f"target hit {hits / len(xs):.0%} worst {min(xs):+.0%}" + (f"  CI95 [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))


if __name__ == "__main__":
    rows = []
    for d in TICK_DAYS:
        for u in ("NIFTY", "SENSEX"):
            r = tick_trade(u, d)
            if r:
                r.update(d=d, u=u)
                rows.append(r)
    report("tick days 28 Sep-23 Oct (own capture, bid/ask)", rows)
    for name, lo, hi in (("bars Oct 2024-Jun 2025", "2024-10-01", "2025-06-30"), ("bars Jul-Dec 2025", "2025-07-01", "2025-12-31"),
                         ("bars Jan-Sep 2026", "2026-01-01", "2026-09-30")):
        rows = []
        for d in bar_days(lo, hi):
            for u in ("NIFTY", "SENSEX"):
                r = bar_trade(u, d)
                if r:
                    r.update(d=d, u=u)
                    rows.append(r)
        report(name, rows)
