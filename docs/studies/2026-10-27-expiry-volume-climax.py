#!/usr/bin/env python3
"""A-247: the expiry volume-climax filter as an event study on 1-minute bars (rules in the ledger, fixed before this ran).

On each index's own expiry days (an option expiry of that index in hist.candle), the first minute in 12:30-14:45 whose
close is above every earlier high of the day (up) / below every earlier low (down) and meets a group's conditions:
A = near-month futures volume >= 3 x the median of that minute over the previous 20 sessions and VIX(t) - VIX(t-15) <= 0;
B = volume >= 3 x and VIX rising; C = volume < 3 x. Outcome: index return close(t) -> close(t+15), close(t+30) signed
in the break's direction, in basis points; halves Jan 2024-Mar 2025 / Apr 2025-Oct 2026; day-clustered bootstrap CI.
"""
import csv, io, random, statistics, subprocess
from collections import defaultdict

HALF = "2025-04-01"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True,
                         stdin=subprocess.DEVNULL).stdout
    return list(csv.reader(io.StringIO(out)))


def minutes(rows):
    """[(day, minute, ...values)] -> day -> minute -> values."""
    out = defaultdict(dict)
    for d, t, *v in rows:
        out[d][int(t[:2]) * 60 + int(t[3:5])] = tuple(float(x) if x else None for x in v)
    return out


IST = "at time zone 'Asia/Kolkata'"
vix = minutes(q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), close from hist.candle "
                f"where kind = 'VIX' and bar_start >= '2024-01-01'"))


def study(u):
    idx = minutes(q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), high, low, close from "
                    f"hist.candle where kind = 'INDEX' and underlying = '{u}' and bar_start >= '2024-01-01'"))
    fut = minutes(q(f"with f as (select (bar_start {IST})::date d, bar_start, expiry, volume from hist.candle where kind = "
                    f"'FUTURE' and underlying = '{u}' and bar_start >= '2023-12-01'), near as (select d, min(expiry) e from f "
                    f"where expiry >= d group by 1) select f.d::text, to_char(f.bar_start {IST},'HH24:MI'), f.volume from f "
                    f"join near n on n.d = f.d and n.e = f.expiry"))
    expiries = {r[0] for r in q(f"select distinct expiry::text from hist.candle where kind = 'OPTION' and underlying = '{u}'")}
    days = sorted(idx)
    exp_days = [d for d in days if d in expiries]
    events = []
    for d in exp_days:
        prior = [p for p in days if p < d][-20:]
        bars = idx[d]
        hi = lo = None
        done = set()
        for m in sorted(bars):
            h, l, c = bars[m]
            if 750 <= m <= 885 and hi is not None:
                for side, broke in (("up", c > hi), ("down", c < lo)):
                    if not broke:
                        continue
                    vol = (fut.get(d) or {}).get(m, (None,))[0]
                    hist = [fut[p][m][0] for p in prior if p in fut and m in fut[p] and fut[p][m][0] is not None]
                    if vol is None or len(hist) < 10:
                        continue
                    norm = statistics.median(hist)
                    x = vol / norm if norm > 0 else None
                    v0, v15 = (vix.get(d) or {}).get(m, (None,))[0], (vix.get(d) or {}).get(m - 15, (None,))[0]
                    if x is None or v0 is None or v15 is None:
                        continue
                    group = "A" if x >= 3 and v0 - v15 <= 0 else "B" if x >= 3 else "C"
                    if (side, group) in done:
                        continue
                    done.add((side, group))
                    sign = 1 if side == "up" else -1
                    r = {}
                    for w in (15, 30):
                        later = bars.get(m + w)
                        r[w] = sign * (later[2] / c - 1) * 1e4 if later else None
                    events.append(dict(day=d, minute=m, side=side, group=group, x=x, r15=r[15], r30=r[30]))
            hi = h if hi is None else max(hi, h)
            lo = l if lo is None else min(lo, l)
    return exp_days, events


def ci(values_by_day, n=2000):
    days = list(values_by_day)
    means = []
    for _ in range(n):
        pick = [x for d in random.choices(days, k=len(days)) for x in values_by_day[d]]
        means.append(sum(pick) / len(pick))
    means.sort()
    return means[int(0.05 * n)], means[int(0.95 * n)]


random.seed(247)
for u in ("NIFTY", "SENSEX"):
    exp_days, events = study(u)
    print(f"== {u}: {len(exp_days)} expiry days with bars ({exp_days[0] if exp_days else '-'} .. {exp_days[-1] if exp_days else '-'})")
    for side in ("up", "down"):
        for group in ("A", "B", "C"):
            ev = [e for e in events if e["side"] == side and e["group"] == group and e["r30"] is not None]
            if not ev:
                print(f"  {side:4s} {group}: no events")
                continue
            by_day = defaultdict(list)
            for e in ev:
                by_day[e["day"]].append(e["r30"])
            lo, hi = ci(by_day) if len(by_day) > 1 else (float("nan"), float("nan"))
            h1 = [e["r30"] for e in ev if e["day"] < HALF]
            h2 = [e["r30"] for e in ev if e["day"] >= HALF]
            r15 = [e["r15"] for e in ev if e["r15"] is not None]
            print(f"  {side:4s} {group}: n {len(ev):3d}  15m {statistics.mean(r15):+6.1f} bp  30m {statistics.mean(e['r30'] for e in ev):+6.1f} bp "
                  f"[90% CI {lo:+6.1f}, {hi:+6.1f}]  failed(30m<0) {sum(e['r30'] < 0 for e in ev) / len(ev):4.0%}  "
                  f"H1 n {len(h1)} {statistics.mean(h1) if h1 else float('nan'):+6.1f}  H2 n {len(h2)} "
                  f"{statistics.mean(h2) if h2 else float('nan'):+6.1f}")
