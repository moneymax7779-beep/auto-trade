#!/usr/bin/env python3
"""A-250: morning near-ATM OI burst (1- and 3-minute) in the direction of an EMA20 cross, continuation or reversal.
Rules fixed in the ledger before this ran. Event study on 1-minute bars Oct 2024 - Oct 2026 with A-232's loaders.
"""
import importlib.util, os, pickle, random, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a232", os.path.join(HERE, "2026-10-26-oi-flip-reversal.py"))
a232 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a232)

COST = 0.0075
FROM, TO, EXIT = 9 * 60 + 20, 11 * 60, 12 * 60 + 30
PERIODS = [("P1", "2024-10-01", "2025-06-30"), ("P2", "2025-07-01", "2025-12-31"), ("P3", "2026-01-01", "2026-10-06")]
CACHE = os.path.join(HERE, "..", "..", ".local", "a250", "minutes.pkl")


def period(d):
    return next((n for n, a, b in PERIODS if a <= d <= b), None)


def day_minutes(u, d, prev_closes):
    """Per morning minute: close, ema, crossed (+1 / -1 / 0 within 3 min), share1, share3; plus the loaders for exits."""
    got = a232.bar_day(u, d)
    if not got:
        return None
    idx, oi_at, price_for, expiry = got
    mins = sorted(idx)
    step = a232.STEP[u]
    ema, k = None, 2 / 21
    for c in prev_closes[-60:]:
        ema = c if ema is None else ema + k * (c - ema)
    side_hist, rows = [], {}
    open_ = idx[mins[0]][0]
    for m in mins:
        c = idx[m][3]
        ema = c if ema is None else ema + k * (c - ema)
        side_hist.append((m, 1 if c > ema else -1))
        if not (FROM <= m < TO):
            continue
        recent = [s for mm, s in side_hist if m - 3 <= mm <= m]
        now = side_hist[-1][1]
        crossed = now if len(set(recent)) > 1 and recent[-1] == now else 0
        atm = round(c / step) * step
        strikes = [atm + j * step for j in range(-2, 3)]

        def flow(w):
            dp = dc = tot = 0.0
            for s in strikes:
                for ty in ("PE", "CE"):
                    a, b = oi_at(m, s, ty), oi_at(m - w, s, ty)
                    if a is None or b is None:
                        return None
                    if ty == "PE":
                        dp += a - b
                    else:
                        dc += a - b
                    tot += a
            return (dp - dc) / tot if tot > 0 else None
        rows[m] = dict(c=c, ema=ema, crossed=crossed, s1=flow(1), s3=flow(3), cont_sign=1 if c > open_ else -1, atm=atm)
    return dict(rows=rows, idx=idx, price_for=price_for, expiry=expiry)


def stop_minute(idx, m, side):
    lo = min(idx[x][2] for x in range(m - 9, m + 1) if x in idx)
    hi = max(idx[x][1] for x in range(m - 9, m + 1) if x in idx)
    for x in sorted(idx):
        if x > m and (idx[x][2] < lo if side > 0 else idx[x][1] > hi):
            return x
    return None


def trade(day, u, m, side):
    pf = day["price_for"](("CE" if side > 0 else "PE"), day["rows"][m]["atm"], m + 1, stop_minute(day["idx"], m, side))
    if not pf:
        return None
    return pf["exits"]["x1230"][0]


def main():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    if os.path.exists(CACHE):
        data = pickle.load(open(CACHE, "rb"))
    else:
        data = {}
        days = a232.bar_days()
        for u in ("NIFTY", "SENSEX"):
            prev = []
            for d in days:
                if period(d) is None:
                    continue
                day = day_minutes(u, d, prev)
                if day:
                    closes = [v[3] for _, v in sorted(day["idx"].items())]
                    # keep only what the events need (price_for is a closure over the day's option bars)
                    data[(u, d)] = day
                    prev = closes
            print(u, "days", sum(1 for k in data if k[0] == u), file=sys.stderr)
        # closures cannot be pickled; the run is done in one go instead of caching
    thresholds = {}
    for u in ("NIFTY", "SENSEX"):
        p1 = sorted(abs(r["s3"]) for (uu, d), day in data.items() if uu == u and period(d) == "P1"
                    for r in day["rows"].values() if r["s3"] is not None)
        thresholds[u] = p1[int(0.95 * len(p1))]
        print(f"{u}: 95th percentile of |3-min ATM±2 net flow share| on P1 morning minutes = {thresholds[u]:.5f} (n {len(p1)})")
    events = defaultdict(list)                    # arm -> [(period, day, u, ret)]
    for (u, d), day in sorted(data.items()):
        done = set()
        for m in sorted(day["rows"]):
            r = day["rows"][m]
            if r["crossed"] == 0:
                continue
            side = r["crossed"]
            if "cross" not in done:                                   # baseline: the first morning cross, no OI
                ret = trade(day, u, m, side)
                if ret is not None:
                    events["cross only"].append((period(d), d, u, ret))
                    done.add("cross")
            s1, s3 = r["s1"], r["s3"]
            if s1 is None or s3 is None or "oi" in done:
                continue
            burst = s3 * side >= thresholds[u] and (s1 * side > 0 if u == "NIFTY" else s1 * side >= 0)
            if burst:
                ret = trade(day, u, m, side)
                if ret is not None:
                    kind = "continuation" if side == r["cont_sign"] else "reversal"
                    events["OI burst + cross"].append((period(d), d, u, ret))
                    events[f"  {kind}"].append((period(d), d, u, ret))
                    done.add("oi")

    def boot(ev, n=5000):
        by = defaultdict(list)
        for p, d, u, x in ev:
            by[d].append(x)
        days = list(by)
        random.seed(250)
        ms = sorted(statistics.mean([x for dd in random.choices(days, k=len(days)) for x in by[dd]]) for _ in range(n))
        return ms[int(0.025 * n)], ms[int(0.975 * n)]

    print("\noption return per trade (net of 0.75 % a side), exit: index stop / 25 % premium stop / 12:30")
    for arm, ev in events.items():
        line = f"{arm:22s}"
        for p, _, _ in PERIODS:
            xs = [x for pp, _, _, x in ev if pp == p]
            line += f" | {p} n {len(xs):3d} {statistics.mean(xs) * 100 if xs else float('nan'):+6.1f} % win {sum(x > 0 for x in xs) / len(xs) if xs else 0:4.0%}"
        late = [e for e in ev if e[0] in ("P2", "P3")]
        lo, hi = boot(late) if len(late) > 10 else (float("nan"), float("nan"))
        line += f" | P2+P3 {statistics.mean(e[3] for e in late) * 100 if late else float('nan'):+6.1f} % [95% CI {lo * 100:+.1f}, {hi * 100:+.1f}]"
        print(line)
    for arm in ("OI burst + cross", "  continuation", "  reversal"):
        ev = events.get(arm, [])
        per = {p: [x for pp, _, _, x in ev if pp == p] for p, _, _ in PERIODS}
        late = [e for e in ev if e[0] in ("P2", "P3")]
        base = [e for e in events["cross only"] if e[0] in ("P2", "P3")]
        ok = (all(len(per[p]) >= 30 for p in ("P2", "P3")) and all(per[p] and statistics.mean(per[p]) > 0 for p in per)
              and late and boot(late)[0] > 0 and statistics.mean(e[3] for e in late) > statistics.mean(e[3] for e in base))
        print(f"{arm.strip():18s} -> {'PASS' if ok else 'FAIL / INSUFFICIENT'}")


if __name__ == "__main__":
    main()
