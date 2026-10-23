#!/usr/bin/env python3
"""A-185 day-extreme break with a futures volume surge (definitions and criteria fixed in the ledger before this ran).

Trigger at the close of minute m (09:45-14:50): the index close above the day's highest 1-min high so far (call) or below
its lowest low (put); futures volume of minute m >= 3x (variant 5x) the median of the day's earlier minutes; variant: the
ATM +- 2 OI share over m-3..m on the break's side. One per index and direction per 30 minutes. Entry, exits and pricing
from A-183 (2026-10-24-impulse-scalp.py).
Usage: python3 2026-10-24-day-extreme-break.py ticks | bars26 | bars2425 | design
"""
import importlib.util, os, pickle, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a183", os.path.join(HERE, "2026-10-24-impulse-scalp.py"))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
a, b = s.a, s.b
FIRST, LAST, GAP, BAND = 9 * 60 + 45, 14 * 60 + 50, 30, 2
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a185"
VARIANTS = ("a", "a+b", "a+b5", "a+b+c")


def signals(data, u):
    idx = data["idx"]
    if not idx or not data["opt"]:
        return []
    minutes = list(range(9 * 60 + 14, 15 * 60 + 30))
    close = a.carried({m: v[2] for m, v in idx.items()}, minutes)
    oi = {key: a.carried(v, minutes) for key, v in data["opt"].items()}
    fut = data["fut"]
    step = s.STEP[u]
    out = []
    for m in range(FIRST, LAST + 1):
        if m not in idx:
            continue
        before = [idx[x] for x in range(9 * 60 + 15, m) if x in idx]
        if len(before) < 20:
            continue
        c = idx[m][2]
        hi, lo = max(x[0] for x in before), min(x[1] for x in before)
        sign = 1 if c > hi else -1 if c < lo else 0
        if not sign:
            continue
        vols = [fut.get(x, 0.0) for x in range(9 * 60 + 15, m) if fut.get(x, 0.0) > 0]
        med = statistics.median(vols) if len(vols) >= 10 else 0
        ratio = fut.get(m, 0.0) / med if med > 0 else 0.0
        atm = round(c / step) * step
        dce = dpe = tot = 0.0
        ok = True
        for k in (atm + i * step for i in range(-BAND, BAND + 1)):
            for ty in ("CE", "PE"):
                ser = oi.get((ty, float(k)))
                x0 = ser.get(m - 3) if ser else None
                x1 = ser.get(m) if ser else None
                if x0 is None or x1 is None:
                    ok = False
                    break
                tot += x0
                if ty == "CE":
                    dce += x1 - x0
                else:
                    dpe += x1 - x0
            if not ok:
                break
        share = (dpe - dce) / tot if ok and tot > 0 else None
        out.append((m, sign, ratio, share is not None and share * sign > 0, atm))
    return out


def pick(sigs, variant):
    last, ev = {1: -999, -1: -999}, []
    for m, sign, ratio, oi_ok, atm in sigs:
        if variant in ("a+b", "a+b+c") and ratio < 3:
            continue
        if variant == "a+b5" and ratio < 5:
            continue
        if variant == "a+b+c" and not oi_ok:
            continue
        if m - last[sign] < GAP:
            continue
        last[sign] = m
        ev.append((m, sign, atm))
    return ev


def run(days, source):
    os.makedirs(CACHE, exist_ok=True)
    path = f"{CACHE}/{source}-{days[0]}-{days[-1]}-{len(days)}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    out = defaultdict(list)
    for d in days:
        for u in ("NIFTY", "SENSEX"):
            try:
                data = a.day(u, d) if source == "ticks" else b.day(u, d, "bars")
            except IndexError:
                data = None
            print(f"\r{source} {u} {d}   ", end="", file=sys.stderr)
            if not data:
                continue
            sigs = signals(data, u)
            exp = s.expiry(u, d, source) if sigs else None
            if not exp:
                continue
            for v in VARIANTS:
                for m, sign, atm in pick(sigs, v):
                    r = (s.trade_ticks if source == "ticks" else s.trade_bars)(u, d, exp, atm, "CE" if sign > 0 else "PE", m)
                    if r:
                        out[v].append((u, d, m, sign, r))
    print(file=sys.stderr)
    pickle.dump(dict(out), open(path, "wb"))
    return dict(out)


def report(name, res):
    print(f"\n== {name}")
    for ex in s.EXITS:
        print(f"  {ex} {s.EXITS[ex]}")
        for v in VARIANTS:
            print(f"    {v:6s} {s.fmt(s.summary(res.get(v, []), ex))}")
            if v == "a+b":
                ci = s.boot(res.get(v, []), ex)
                if ci:
                    print(f"           a+b day-bootstrap 95 % CI [{ci[0]:+.2%}, {ci[1]:+.2%}]")


if __name__ == "__main__":
    which = sys.argv[1]
    if which == "ticks":
        res = run(s.TICK_DAYS, "ticks")
        report(f"tick days ({len(s.TICK_DAYS)}; in-sample)", res)
        for u, d, m, sign, r in sorted(res.get("a+b", []), key=lambda e: (e[1], e[2])):
            print(f"     {u:6s} {d} {m // 60:02d}:{m % 60:02d} {'up  ' if sign > 0 else 'down'}  E10 {r['E10'][0]:+6.1%} {r['E10'][1]:6s} E20 {r['E20'][0]:+6.1%} {r['E20'][1]}")
    elif which == "design":
        report("23 Oct (not counted)", run(s.DESIGN, "ticks"))
    elif which == "bars26":
        ds = s.bar_days("2026-01-01", "2026-09-30")
        report(f"bars Jan-Sep 2026 ({len(ds)} days)", run(ds, "bars"))
    elif which == "bars2425":
        ds = s.bar_days("2024-10-01", "2025-12-31")
        res = run(ds, "bars")
        report("bars Oct 2024-Jun 2025", {v: [e for e in x if e[1] <= "2025-06-30"] for v, x in res.items()})
        report("bars Jul-Dec 2025", {v: [e for e in x if e[1] > "2025-06-30"] for v, x in res.items()})
