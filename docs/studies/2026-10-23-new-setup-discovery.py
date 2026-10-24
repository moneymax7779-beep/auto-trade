#!/usr/bin/env python3
"""A-176 new-setup discovery (definitions and criteria fixed in the ledger before this ran).

Five setups on NIFTY / SENSEX 1-min bars; trade = ATM option of the signal side, nearest expiry, bought at the trigger
bar's close, sold 60 min later or at 15:20, 1.5 % round-trip cost.
Each phase reads only its own date range from hist.candle, so validation / confirmation days are not loaded earlier.
Usage: python3 2026-10-23-new-setup-discovery.py discovery
       python3 2026-10-23-new-setup-discovery.py validation S1,S2      (at most 2 discovery survivors, read once)
       python3 2026-10-23-new-setup-discovery.py confirmation S1
"""
import csv, io, statistics, subprocess, sys
from collections import defaultdict

PHASES = {"discovery": ("2024-10-01", "2025-06-30"), "validation": ("2025-07-01", "2025-12-31"),
          "confirmation": ("2026-01-01", "2026-10-31")}
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
STEP = {"NIFTY": 50, "SENSEX": 100}
IST = "at time zone 'Asia/Kolkata'"
COST = 0.015
HOLD = 60
LAST = 365          # 15:20


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def minute(hhmm):
    h, m = map(int, hhmm.split(":"))
    return (h - 9) * 60 + m - 15


def load(lo, hi):
    """{(u, day): {'o','h','l','c': [375], 'v': [375]}} index bars + most-traded future's volume; VIX closes {day: [375]}."""
    # one trading day before lo, for the previous-day high / low of the first day
    span = f"bar_start >= ('{lo}'::date - 7) and bar_start < ('{hi}'::date + 1)"
    days = defaultdict(lambda: {k: [None] * 375 for k in "ohlcv"})
    for u, d, t, o, h, l, c in q(f"select underlying, (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), "
                                 f"open, high, low, close from hist.candle where kind = 'INDEX' "
                                 f"and underlying in ('NIFTY','SENSEX') and {span}"):
        i = minute(t)
        if 0 <= i < 375:
            b = days[(u, d)]
            b["o"][i], b["h"][i], b["l"][i], b["c"][i] = float(o), float(h), float(l), float(c)
    for u, d, t, v in q(f"with f as (select underlying, instrument_key, (bar_start {IST})::date d, bar_start, volume "
                        f"from hist.candle where kind = 'FUTURE' and underlying in ('NIFTY','SENSEX') and {span}), "
                        f"top as (select distinct on (underlying, d) underlying, d, instrument_key from f "
                        f"group by underlying, d, instrument_key order by underlying, d, sum(volume) desc nulls last) "
                        f"select f.underlying, f.d::text, to_char(f.bar_start {IST},'HH24:MI'), coalesce(f.volume, 0) "
                        f"from f join top using (underlying, d, instrument_key)"):
        i = minute(t)
        if 0 <= i < 375 and (u, d) in days:
            days[(u, d)]["v"][i] = float(v)
    vix = defaultdict(lambda: [None] * 375)
    for d, t, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), close from hist.candle "
                     f"where underlying = 'INDIA_VIX' and {span}"):
        i = minute(t)
        if 0 <= i < 375:
            vix[d][i] = float(c)
    expiries = defaultdict(list)
    for u, e in q("select distinct underlying, expiry::text from hist.candle where kind = 'OPTION' "
                  "and underlying in ('NIFTY','SENSEX') and expiry is not null"):
        expiries[u].append(e)
    for u in expiries:
        expiries[u].sort()
    return days, vix, expiries


def complete(b):
    return all(x is not None for k in "ohlc" for x in b[k])


def vwap(b):
    out, pv, vv = [], 0.0, 0.0
    for i in range(375):
        tp = (b["h"][i] + b["l"][i] + b["c"][i]) / 3
        v = b["v"][i] or 0.0
        pv, vv = pv + tp * v, vv + v
        out.append(pv / vv if vv > 0 else None)
    return out


def s1(b, prev, vx):
    pdh, pdl, op = max(prev["h"]), min(prev["l"]), b["o"][0]
    ev = []
    if op > pdh:
        i = next((i for i in range(1, 31) if b["c"][i] < pdh), None)
        if i is not None:
            ev.append((i, -1))
    if op < pdl:
        i = next((i for i in range(1, 31) if b["c"][i] > pdl), None)
        if i is not None:
            ev.append((i, 1))
    return ev


def s2(b, prev, vx):
    vw = vwap(b)
    if any(x is None for x in vw[1:]):
        return []
    op, c, h, l = b["o"][0], b["c"], b["h"], b["l"]
    ev, done = [], set()
    for i in range(max(minute("10:30"), 60), minute("13:30") + 1):
        if -1 not in done and all(c[j] < vw[j] for j in range(i - 59, i + 1)) and c[i] <= op * 0.996 \
                and max(h[i - 9:i + 1]) >= min(l[i - 29:i + 1]) * 1.0015 and c[i] < l[i - 1]:
            ev.append((i, -1)); done.add(-1)
        if 1 not in done and all(c[j] > vw[j] for j in range(i - 59, i + 1)) and c[i] >= op * 1.004 \
                and min(l[i - 9:i + 1]) <= max(h[i - 29:i + 1]) * 0.9985 and c[i] > h[i - 1]:
            ev.append((i, 1)); done.add(1)
    return ev


def s3(b, prev, vx):
    op, c, v = b["o"][0], b["c"], b["v"]
    if any(x is None for x in v):
        return []
    sums = [sum(v[j - 14:j + 1]) for j in range(14, 375)]          # sums[k] = 15-bar volume ending at bar k + 14
    for i in range(minute("11:30"), minute("14:30") + 1):
        m = c[i - 15] / op - 1
        if abs(m) < 0.008:
            continue
        r = c[i] / c[i - 15] - 1
        med = statistics.median(sums[:i - 14])
        if med > 0 and sums[i - 14] >= 2 * med:
            if m < 0 and r >= 0.003:
                return [(i, 1)]
            if m > 0 and r <= -0.003:
                return [(i, -1)]
    return []


def s4(b, prev, vx):
    h, l, c = b["h"], b["l"], b["c"]
    am = max(h[0:135]) - min(l[0:135])
    mh, ml = max(h[135:256]), min(l[135:256])
    if am <= 0 or mh - ml > 0.4 * am:
        return []
    for i in range(256, minute("14:45") + 1):
        if c[i] > mh:
            return [(i, 1)]
        if c[i] < ml:
            return [(i, -1)]
    return []


def s5(b, prev, vx):
    if vx is None:
        return []
    c, ev, done = b["c"], [], set()
    for i in range(minute("10:00"), minute("14:00") + 1):
        if vx[i] is None or vx[i - 30] is None or vx[i - 30] <= 0:
            continue
        r, dv = c[i] / c[i - 30] - 1, vx[i] / vx[i - 30] - 1
        if -1 not in done and r <= -0.003 and dv >= 0.03:
            ev.append((i, -1)); done.add(-1)
        if 1 not in done and r >= 0.003 and dv <= -0.03:
            ev.append((i, 1)); done.add(1)
    return ev


SETUPS = {"S1": s1, "S2": s2, "S3": s3, "S4": s4, "S5": s5}
NAMES = {"S1": "gap-through-level fade", "S2": "trend pullback continuation", "S3": "squeeze reversal",
         "S4": "midday compression break", "S5": "VIX-confirmed move"}


def option_trade(u, d, b, i, sign, expiries):
    e = min(i + HOLD, LAST)
    k = round(b["c"][i] / STEP[u]) * STEP[u]
    exp = next((x for x in expiries[u] if x >= d), None)
    if exp is None:
        return None
    ty = "CE" if sign > 0 else "PE"
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and bar_start between '{d} 09:15+05:30' and '{d} 15:29+05:30'")
    px = {minute(t): float(c) for t, c in rows if c and float(c) > 0}

    def at(m):
        return next((px[m + o] for o in (0, 1, -1, 2, -2) if m + o in px), None)
    p0, p1 = at(i), at(e)
    if not p0 or not p1:
        return None
    path = [px[m] for m in range(i, e + 1) if m in px]
    return {"u": u, "d": d, "i": i, "sign": sign, "net": p1 / p0 - 1 - COST, "mfe": max(path) / p0 - 1,
            "spot": sign * (b["c"][e] / b["c"][i] - 1) * 1e4, "expiry_day": exp == d, "entry": p0, "exit": p1}


def run(phase, only):
    lo, hi = PHASES[phase]
    days, vix, expiries = load(lo, hi)
    trades, skipped = defaultdict(list), defaultdict(int)
    for u in ("NIFTY", "SENSEX"):
        ds = sorted(d for (uu, d) in days if uu == u)
        for prev_d, d in zip(ds, ds[1:]):
            if not (lo <= d <= hi) or d in HELD_OUT:
                continue
            b, prev = days[(u, d)], days[(u, prev_d)]
            if not complete(b) or not complete(prev):
                skipped["incomplete-day"] += 1
                continue
            for s in only:
                for i, sign in SETUPS[s](b, prev, vix.get(d)):
                    t = option_trade(u, d, b, i, sign, expiries)
                    if t is None:
                        skipped[s + " no-option-bars"] += 1
                    else:
                        trades[s].append(t)
    print(f"A-176 {phase} {lo}..{hi}  cost {COST:.1%}  hold {HOLD} min / 15:20  skipped {dict(skipped)}")
    nmin = 30 if phase == "discovery" else 15
    for s in only:
        ts = trades[s]
        print(f"\n{s} {NAMES[s]}")
        if not ts:
            print("  no trades")
            continue
        report(ts, phase, nmin)
        for label, f in (("NIFTY", lambda t: t["u"] == "NIFTY"), ("SENSEX", lambda t: t["u"] == "SENSEX"),
                         ("expiry day", lambda t: t["expiry_day"]), ("not expiry", lambda t: not t["expiry_day"]),
                         ("CE", lambda t: t["sign"] > 0), ("PE", lambda t: t["sign"] < 0)):
            sub = [t for t in ts if f(t)]
            if sub:
                print(f"    {label:11s} n={len(sub):3d} mean {statistics.mean(t['net'] for t in sub):+.1%} "
                      f"hit {sum(t['net'] > 0 for t in sub) / len(sub):.0%}")


def report(ts, phase, nmin):
    nets, spots = [t["net"] for t in ts], [t["spot"] for t in ts]
    n, mean, med = len(ts), statistics.mean(nets), statistics.median(nets)
    hit = sum(x > 0 for x in nets) / n
    tsp = statistics.mean(spots) / (statistics.stdev(spots) / n ** 0.5) if n > 1 and statistics.stdev(spots) > 0 else 0
    ok = n >= nmin and mean >= 0.05 and med > 0 and hit >= 0.5 and tsp >= 2
    if phase == "confirmation":
        ok = mean > 0
    print(f"  n={n} mean net {mean:+.1%} median {med:+.1%} hit {hit:.0%} spot {statistics.mean(spots):+.1f} bp "
          f"(t {tsp:.2f}) mean MFE {statistics.mean(t['mfe'] for t in ts):+.1%} worst {min(nets):+.1%} best {max(nets):+.1%}"
          f"  -> {'PASS' if ok else 'FAIL'}")


if __name__ == "__main__":
    phase = sys.argv[1]
    only = sys.argv[2].split(",") if len(sys.argv) > 2 else list(SETUPS)
    if phase != "discovery" and (len(sys.argv) < 3 or (phase == "validation" and len(only) > 2)):
        sys.exit("validation / confirmation take the named survivors (validation: at most 2)")
    run(phase, only)
