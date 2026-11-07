#!/usr/bin/env python3
"""A-284 late expiry trend buy without a stop (definitions fixed in the ledger before this ran).

Expiring index on its expiry day; synthetic spot S (strike + call - put). Trend at t in 15:20:00-15:28:00: S(t)/S(t-3m) - 1 >=
0.15 %, three rising one-minute steps, S(t) beyond S(15:15) (mirrored for down). The first trend buys the ATM option that way.
No stop; T30 / T50 sell at the bid at +30 % / +50 %, else the bid at 15:29:30. Ticks: the 10 own expiry days 29 Sep - 29 Oct
(per second, ask in / bid out). Bars: expiries Aug - Sep 2026 (per minute, +/- 0.75 %). All descriptive (seen days).
"""
import importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a189", os.path.join(HERE, "2026-10-24-expiry-close-squeeze.py"))
x = importlib.util.module_from_spec(spec)
spec.loader.exec_module(x)
TREND, TARGETS = 0.0015, {"T30": 0.30, "T50": 0.50}
S0, S1, END, REF = 15 * 3600 + 20 * 60, 15 * 3600 + 28 * 60, 15 * 3600 + 29 * 60 + 30, 15 * 3600 + 15 * 60
DAYS = x.TICK_DAYS + [("2026-10-27", "NIFTY"), ("2026-10-29", "SENSEX")]   # 27 / 29 Oct released by the user on 30 Oct


def trend(S, t, step):
    """+1 / -1 when the trend holds at t (points t - 3 step .. t), else 0."""
    pts = [S.get(t - i * step) for i in (3, 2, 1, 0)]
    ref = S.get(REF if step == 60 else x.mn("15:15"))
    if None in pts or ref is None:
        return 0
    move = pts[3] / pts[0] - 1
    if move >= TREND and all(b > a for a, b in zip(pts, pts[1:])) and pts[3] > ref:
        return 1
    if move <= -TREND and all(b < a for a, b in zip(pts, pts[1:])) and pts[3] < ref:
        return -1
    return 0


def tick_spot(quotes, ref):
    strikes = sorted({k for k, _ in quotes})
    S = {}
    for s in range(REF - 60, END + 1):
        for k in sorted(strikes, key=lambda k: abs(k - ref)):
            c, p = quotes.get((k, "CE"), {}).get(s), quotes.get((k, "PE"), {}).get(s)
            if c and p:
                S[s] = ref = k + (c[0] + c[1]) / 2 - (p[0] + p[1]) / 2
                break
    return S


def hms(s):
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def report(name, rows):
    print(f"\n== {name}: {len(rows)} trades")
    for ex in TARGETS:
        xs = [r[ex] for r in rows]
        if xs:
            print(f"  {ex}: mean {statistics.mean(xs):+.1%} median {statistics.median(xs):+.1%} win {sum(v > 0 for v in xs)}/{len(xs)} "
                  f"targets {sum(v >= TARGETS[ex] - 0.02 for v in xs)} ended below -90 % {sum(v < -0.9 for v in xs)}")


print("ticks: the 10 own expiry days, per second")
rows = []
for d, u in DAYS:
    ref, quotes, _ = x.tick_day(u, d)
    S = tick_spot(quotes, ref)
    trig = next(((t, trend(S, t, 60)) for t in range(S0, S1 + 1) if trend(S, t, 60)), None)
    if not trig:
        print(f"  {d} {u:6s} no trend")
        continue
    t, sign = trig
    ty = "CE" if sign > 0 else "PE"
    k = min({k for k, y in quotes if y == ty}, key=lambda k: abs(k - S[t]))
    v = quotes.get((k, ty), {})
    if t + 1 not in v:
        print(f"  {d} {u:6s} trend {hms(t)} but no quote")
        continue
    entry, r = v[t + 1][1], {}
    for ex, tg in TARGETS.items():
        hit = next((s for s in range(t + 2, END + 1) if s in v and v[s][0] >= entry * (1 + tg)), None)
        r[ex] = v[hit][0] / entry - 1 if hit else v[END][0] / entry - 1
        r[ex + "_how"] = f"target {hms(hit)}" if hit else "15:29:30"
    peak = max(v[s][0] for s in range(t + 1, END + 1) if s in v) / entry - 1
    rows.append(r)
    print(f"  {d} {u:6s} {hms(t)} {'up  ' if sign > 0 else 'down'} buy {ty} {int(k)} @ {entry:.2f} | T30 {r['T30']:+.0%} ({r['T30_how']}) "
          f"| T50 {r['T50']:+.0%} ({r['T50_how']}) | peak bid {peak:+.0%}")
report("ticks", rows)

print("\nbars: expiries Aug - Sep 2026, per minute (not the tick days)")
rows = []
tickset = {d for d, _ in DAYS}
for d, u in x.expiry_days("2026-08-01", "2026-09-30"):
    if d in tickset:
        continue
    got = x.bar_day(u, d)
    if not got:
        print(f"  {d} {u:6s} no bars")
        continue
    ref, bars, closes, S = got
    trig = next(((m, trend(S, m, 1)) for m in range(x.mn("15:20"), x.mn("15:28") + 1) if trend(S, m, 1)), None)
    if not trig:
        print(f"  {d} {u:6s} no trend")
        continue
    m, sign = trig
    ty = "CE" if sign > 0 else "PE"
    k = min({k for k, y in bars if y == ty}, key=lambda k: abs(k - S[m]))
    b = bars.get((k, ty), {})
    if m + 1 not in b:
        print(f"  {d} {u:6s} trend but no bar")
        continue
    entry = b[m + 1][2] * (1 + x.COST)
    r = {}
    for ex, tg in TARGETS.items():
        hit = next((t for t in range(m + 2, x.M1529 + 1) if t in b and b[t][0] >= entry * (1 + tg)), None)
        last = closes[(k, ty)].get(x.M1529)
        r[ex] = tg - x.COST if hit else (last * (1 - x.COST) / entry - 1 if last else -1.0)
    rows.append(r)
    print(f"  {d} {u:6s} {m // 60}:{m % 60:02d} {'up  ' if sign > 0 else 'down'} buy {ty} {int(k)} @ {b[m + 1][2]:.2f} | "
          f"T30 {r['T30']:+.0%} | T50 {r['T50']:+.0%}")
report("bars Aug - Sep 2026", rows)
