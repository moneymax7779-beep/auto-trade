#!/usr/bin/env python3
"""A-162 futures-OI quality filter for aob's burst signals (definitions and criteria fixed in the ledger first).

Signals = A-157's chosen rule (ATM +- 2 share3 >= 3x the day's mean, futures volume >= 1.5x the day's median window,
09:30-14:45, one per direction per 15 minutes). Filter F: over m-3 .. m the near-month futures OI rose and the index moved
the signal's way (fresh positions). Bars (hist.candle) and own capture (md.*), local database only.
Usage: python3 2026-10-22-futures-oi-filter.py [main|heldout|design]
"""
import csv, importlib.util, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a157", os.path.join(HERE, "2026-10-21-atm-oi-burst.py"))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
a.ROOT = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-atmoi/.local/a157"       # the A-157 own-capture cache
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a162"
IST = "at time zone 'Asia/Kolkata'"
HELD_OUT = ["2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"]
S2 = ["2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13",
      "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19"]
DESIGN = ["2026-10-21"]
q = a.q


def load_bars(u, d):
    idx = {a.hm(t): (float(h), float(l), float(c)) for t, h, l, c in q(
        f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' and kind = 'INDEX' "
        f"and (bar_start {IST})::date = '{d}' and (bar_start {IST})::time between '09:15' and '15:29'")}
    if not idx:
        return None
    exp = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
            f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:20+05:30')::timestamptz")
    if not exp or not exp[0][0]:
        return None
    lo = min(v[1] for v in idx.values()); hi = max(v[0] for v in idx.values())
    span = 1000 if u == "SENSEX" else 500
    opt, px = defaultdict(dict), defaultdict(dict)
    for ty, k, t, oi, c in q(f"select option_type, strike, to_char(bar_start {IST},'HH24:MI'), oi, close from hist.candle "
                             f"where underlying = '{u}' and kind = 'OPTION' and expiry = '{exp[0][0]}' "
                             f"and bar_start >= ('{d} 09:00+05:30')::timestamptz and bar_start < ('{d} 15:31+05:30')::timestamptz "
                             f"and strike between {lo - span} and {hi + span}"):
        if oi and float(oi) > 0:
            opt[(ty, float(k))][a.hm(t)] = float(oi)
        if c and float(c) > 0:
            px[(ty, float(k))][a.hm(t)] = float(c)
    fexp = q(f"select expiry::text from hist.candle where underlying = '{u}' and kind = 'FUTURE' "
             f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 15:31+05:30')::timestamptz "
             f"group by 1 order by sum(volume) desc limit 1")
    fut, futoi = {}, {}
    if fexp:
        for t, v, oi in q(f"select to_char(bar_start {IST},'HH24:MI'), volume, oi from hist.candle where underlying = '{u}' "
                          f"and kind = 'FUTURE' and expiry = '{fexp[0][0]}' and bar_start >= ('{d} 09:15+05:30')::timestamptz "
                          f"and bar_start < ('{d} 15:31+05:30')::timestamptz"):
            fut[a.hm(t)] = float(v or 0)
            if oi and float(oi) > 0:
                futoi[a.hm(t)] = float(oi)
    return {"idx": idx, "opt": dict(opt), "px": dict(px), "fut": fut, "futoi": futoi}


def own_futoi(u, d):
    fexp = q(f"select min(expiry)::text from md.future_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
             f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")
    if not fexp or not fexp[0][0]:
        return {}
    return {a.hm(t): float(oi) for t, oi in q(
        f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), last(oi, recv_ts) from md.future_tick "
        f"where session_date = '{d}' and underlying = '{u}' and expiry = '{fexp[0][0]}' and oi > 0 "
        f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:30+05:30' group by 1")}


def day(u, d, source):
    os.makedirs(CACHE, exist_ok=True)
    path = f"{CACHE}/{source}-{u}-{d}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    if source == "bars":
        data = load_bars(u, d)
    else:
        data = a.day(u, d)
        if data:
            data = dict(data); data["futoi"] = own_futoi(u, d)
    pickle.dump(data, open(path, "wb"))
    return data


def signals(days, source):
    out = []
    for u in a.UNDERLYINGS:
        for d in days:
            data = day(u, d, source)
            print(f"\r{source} {u} {d}  ", end="", file=sys.stderr)
            if not data:
                continue
            rows = a.features(data, None)
            if not rows:
                continue
            futoi = a.carried(data["futoi"], list(range(9 * 60 + 14, 15 * 60 + 30)))
            last = {1: -999, -1: -999}
            for m in sorted(rows):
                share, rel, brk, ratio, chg = rows[m][3]
                if not (a.FIRST <= m <= a.LAST) or rel is None or rel < 3 or ratio is None or ratio < 1.5 or not share:
                    continue
                sign = 1 if share > 0 else -1
                if m - last[sign] < 15 or rows[m]["ret30"] is None:
                    continue
                last[sign] = m
                f0, f1 = futoi.get(m - 3), futoi.get(m)
                fresh = f0 is not None and f1 is not None and f1 > f0 and chg * sign > 0
                o = rows[m]["opt"].get("CE" if sign > 0 else "PE")
                out.append((u, d, m, sign, sign * rows[m]["ret30"], o, fresh, (f1 - f0) if f0 is not None and f1 is not None else None))
    print(file=sys.stderr)
    return out


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def describe(ev, label):
    f = [e for e in ev if e[6]]; n = [e for e in ev if not e[6]]
    def s(es):
        o = [e[5] for e in es if e[5] is not None]
        return (f"n={len(es):4d} days={len({e[1] for e in es}):3d} mean30 {mean([e[4] for e in es]):+6.1f} bp  "
                f"hit {sum(1 for e in es if e[4] > 0) / len(es):.0%}  option {mean(o):+5.1f} %") if es else "n=0"
    print(f"\n== {label}")
    print(f"   all aob signals   {s(ev)}")
    print(f"   F (fresh futures) {s(f)}")
    print(f"   not F             {s(n)}")
    for u in a.UNDERLYINGS:
        print(f"   {u:6s} F {s([e for e in f if e[0] == u])}")
        print(f"   {u:6s} - {s([e for e in n if e[0] == u])}")
    return f, n


def boot_diff(ev, n=10000, seed=162):
    by = defaultdict(list)
    for e in ev:
        by[e[1]].append(e)
    keys = list(by)
    rng, diffs = random.Random(seed), []
    for _ in range(n):
        pick = [x for k in (rng.choice(keys) for _ in keys) for x in by[k]]
        f = [e[4] for e in pick if e[6]]; o = [e[4] for e in pick if not e[6]]
        if f and o:
            diffs.append(mean(f) - mean(o))
    diffs.sort()
    return diffs[int(0.025 * len(diffs))], diffs[int(0.975 * len(diffs))]


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    if which == "main":
        bars = [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle where underlying = 'NIFTY' "
                                f"and kind = 'INDEX' and bar_start >= '2026-01-01') x order by d")]
        s1_days = [d for d in bars[15:] if d <= "2026-09-30" and d not in HELD_OUT]
        s1 = signals(s1_days, "bars")
        f, n = describe(s1, f"S1 bar days ({len(s1_days)})")
        lo, hi = boot_diff(s1)
        diff = mean([e[4] for e in f]) - mean([e[4] for e in n])
        print(f"   F - not F: {diff:+.1f} bp, day-bootstrap 95 % [{lo:+.1f}, {hi:+.1f}]")
        s2 = signals(S2, "own")
        f2, n2 = describe(s2, f"S2 October own days ({len(S2)})")
        c1 = len(f) >= 30 and mean([e[4] for e in f]) > 0
        c2 = diff > 0 and lo > 0
        c3 = mean([e[4] for e in f]) > mean([e[4] for e in s1])
        c4 = mean([e[4] for e in f2]) >= mean([e[4] for e in s2])
        for name, ok in (("(1) S1 >= 30 filtered, mean > 0", c1), ("(2) S1 F - not F > 0, CI low > 0", c2),
                         ("(3) S1 F mean > all signals", c3), ("(4) S2 F mean >= all signals", c4)):
            print(f"{'PASS' if ok else 'FAIL'}  {name}")
        print("A-162:", "PASS" if all((c1, c2, c3, c4)) else "FAIL")
    else:
        ev = signals(HELD_OUT, "bars") if which == "heldout" else signals(DESIGN, "own")
        describe(ev, which)
        for e in ev:
            print(f"   {e[0]} {e[1]} {e[2] // 60:02d}:{e[2] % 60:02d} {'up' if e[3] > 0 else 'down'} {e[4]:+.1f} bp  "
                  f"{'F' if e[6] else '-'} futOI {e[7] if e[7] is None else int(e[7]):+}")


if __name__ == "__main__":
    main()
