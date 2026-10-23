#!/usr/bin/env python3
"""A-178 covering vs writing near-ATM OI bursts (definitions and criteria fixed in the ledger before this ran).

A-157's chosen burst rule unchanged (ATM +- 2, 3-minute window, |share| >= 3 x the day's mean |share| so far); each burst
split by the leg that drives it: covering (writers closing) or writing (writers adding), with or without a futures volume
surge. Own-capture tick days; minute data comes from A-157's loader and cache.
Usage: python3 2026-10-23-covering-bursts.py main | design
"""
import importlib.util, os, random, statistics, sys
from collections import defaultdict

spec = importlib.util.spec_from_file_location("a157", os.path.join(os.path.dirname(__file__), "2026-10-21-atm-oi-burst.py"))
a157 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a157)

W, K, BAND, MIN_HISTORY = 3, 3, 2, 10
SURGE, SURGE_HI = 3.0, 5.0
FIRST, LAST, HORIZON, GAP = 9 * 60 + 30, 14 * 60 + 45, 30, 15
HALF1 = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09"]
HALF2 = ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21", "2026-10-22"]
DESIGN = ["2026-10-23"]


def bursts(data):
    """Per burst minute: (m, sign, kind 'C'/'W', volume ratio, ret30 in the burst's direction, ATM option % or None)."""
    idx = data["idx"]
    minutes = list(range(9 * 60 + 14, 15 * 60 + 30))
    strikes = sorted({k for (_, k) in data["opt"]})
    if len(strikes) < 5 or not idx:
        return []
    step = min(b - a for a, b in zip(strikes, strikes[1:]) if b > a)
    oi = {key: a157.carried(s, minutes) for key, s in data["opt"].items()}
    close = a157.carried({m: v[2] for m, v in idx.items()}, minutes)
    fut = data["fut"]
    wsum = {e: sum(fut.get(x, 0.0) for x in range(e - W + 1, e + 1)) for e in range(9 * 60 + 14 + W, 15 * 60 + 30)}
    seen, out = [], []
    for m in range(9 * 60 + 16, LAST + 1):
        c = close.get(m)
        if c is None:
            continue
        atm = round(c / step) * step
        dce = dpe = tot = 0.0
        ok = True
        for k in (atm + i * step for i in range(-BAND, BAND + 1)):
            for ty in ("CE", "PE"):
                s = oi.get((ty, k))
                a = s.get(m - W) if s else None
                b = s.get(m) if s else None
                if a is None or b is None:
                    ok = False
                    break
                tot += a
                if ty == "CE":
                    dce += b - a
                else:
                    dpe += b - a
            if not ok:
                break
        if not ok or tot <= 0:
            continue
        share = (dpe - dce) / tot
        mean = statistics.mean(seen) if len(seen) >= MIN_HISTORY else None
        seen.append(abs(share))
        if m < FIRST or m not in idx or not mean or abs(share) < K * mean:
            continue
        sign = 1 if share > 0 else -1
        kind = ("C" if -dce > dpe else "W") if sign > 0 else ("C" if -dpe > dce else "W")
        base = [wsum[e] for e in range(9 * 60 + 14 + W, m) if e in wsum]
        med = statistics.median(base) if base else 0
        ratio = wsum.get(m, 0.0) / med if med > 0 else None
        e, x = close.get(m + 1), close.get(m + 1 + HORIZON)
        if not e or not x:
            continue
        k = round(e / step) * step
        p = a157.carried(data["px"].get(("CE" if sign > 0 else "PE", k), {}), [m + 1, m + 1 + HORIZON])
        opt = (p[m + 1 + HORIZON] / p[m + 1] - 1) * 100 if p[m + 1] and p[m + 1 + HORIZON] else None
        out.append((m, sign, kind, ratio, sign * (x / e - 1) * 1e4, opt))
    return out


def events(days, group, surge=SURGE):
    """group: 'C+S', 'W+S', 'C', 'W' (the last two without the volume condition). One per underlying, direction, 15 min."""
    ev = []
    for u in a157.UNDERLYINGS:
        for d in days:
            data = a157.day(u, d)
            if not data:
                continue
            last = {1: -999, -1: -999}
            for m, sign, kind, ratio, r, opt in bursts(data):
                if kind != group[0]:
                    continue
                if group.endswith("+S") and (ratio is None or ratio < surge):
                    continue
                if m - last[sign] < GAP:
                    continue
                last[sign] = m
                ev.append((u, d, m, sign, r, opt))
    return ev


def stats(ev):
    if not ev:
        return None
    r = [e[4] for e in ev]
    o = [e[5] for e in ev if e[5] is not None]
    return {"n": len(r), "mean": statistics.mean(r), "hit": sum(x > 0 for x in r) / len(r),
            "opt": statistics.mean(o) if o else None, "days": len({e[1] for e in ev})}


def boot(ev, n=10000, seed=178):
    by = defaultdict(list)
    for e in ev:
        by[e[1]].append(e[4])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def fmt(s):
    if not s:
        return "n=0"
    o = f"  ATM option {s['opt']:+5.1f} %" if s["opt"] is not None else ""
    return f"n={s['n']:3d} days={s['days']:2d}  mean30 {s['mean']:+6.1f} bp  hit {s['hit']:.0%}{o}"


def show(ev):
    for u, d, m, sign, r, opt in ev:
        o = f"  option {opt:+.1f} %" if opt is not None else ""
        print(f"     {u:6s} {d} {m // 60:02d}:{m % 60:02d} {'up  ' if sign > 0 else 'down'} {r:+7.1f} bp{o}")


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    if which == "design":
        for g in ("C+S", "W+S"):
            ev = events(DESIGN, g)
            print(f"== design 23 Oct (not counted) {g}: {fmt(stats(ev))}")
            show(ev)
        return
    print(f"A-178: A-157 rule w{W} k{K} ATM ± {BAND}; surge ≥ {SURGE:g}x the day's 3-min median; 30-min outcome")
    res = {}
    for name, days in (("half 1 (28 Sep-9 Oct)", HALF1), ("half 2 (12-22 Oct)", HALF2), ("pooled", HALF1 + HALF2)):
        print(f"\n== {name}")
        for g in ("C+S", "W+S", "C", "W"):
            ev = events(days, g)
            res[(name, g)] = ev
            print(f"  {g:4s} {fmt(stats(ev))}")
        print(f"  (descriptive) C+S at ≥ {SURGE_HI:g}x: {fmt(stats(events(days, 'C+S', SURGE_HI)))}   "
              f"W+S at ≥ {SURGE_HI:g}x: {fmt(stats(events(days, 'W+S', SURGE_HI)))}")
    cs = res[("pooled", "C+S")]
    ci = boot(cs)
    print(f"\npooled C+S day-bootstrap 95 % CI: {'[%+.1f, %+.1f]' % ci if ci else 'n/a'}")
    for nu in a157.UNDERLYINGS:
        print(f"  {nu}: C+S {fmt(stats([e for e in cs if e[0] == nu]))}")
    print("  C+S events:")
    show(cs)
    h1c, h2c = stats(res[("half 1 (28 Sep-9 Oct)", "C+S")]), stats(res[("half 2 (12-22 Oct)", "C+S")])
    h1w, h2w = stats(res[("half 1 (28 Sep-9 Oct)", "W+S")]), stats(res[("half 2 (12-22 Oct)", "W+S")])
    checks = [
        ("C+S n >= 20", len(cs) >= 20),
        ("C+S mean30 > 0 in both halves", bool(h1c and h2c and h1c["mean"] > 0 and h2c["mean"] > 0)),
        ("C+S mean30 > W+S mean30 in both halves", bool(h1c and h2c and (not h1w or h1c["mean"] > h1w["mean"])
                                                       and (not h2w or h2c["mean"] > h2w["mean"]))),
        ("pooled C+S CI low > 0", bool(ci and ci[0] > 0)),
    ]
    print()
    for label, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {label}")
    print("A-178:", "PASS" if all(ok for _, ok in checks) else "FAIL")


if __name__ == "__main__":
    main()
