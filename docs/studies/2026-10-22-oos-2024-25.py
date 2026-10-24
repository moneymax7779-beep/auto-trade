#!/usr/bin/env python3
"""A-168 (live set out of sample, Oct 2024-Dec 2025) and A-169 (ma-cross VIX gate / no chasing, path-free) scoring,
as registered. Local database only."""
import csv, io, re, statistics, subprocess
from collections import defaultdict

PROGRESS = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-cv/.local/a168/progress.txt"
HALF = "2025-06-30"
IST = "at time zone 'Asia/Kolkata'"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


runs = defaultdict(dict)
for line in open(PROGRESS):
    m = re.search(r" (set|mac|etr|odm8|odb|ecr|ebs) (\d{4}-\d\d-\d\d) sid=(\d+) \{.*?net=(-?\d+)", line)
    if m:
        runs[m[1]][m[2]] = (int(m[3]), int(m[4]))


def trades(sids):
    return [(r[0], r[1], r[2], r[3], r[4], float(r[5])) for r in q(
        f"select s.session_date::text, p.strategy_id, p.underlying, p.option_side, to_char(p.opened_at {IST},'HH24:MI'), p.net "
        f"from trade.position p join trade.session s on s.id = p.session_id where p.session_id in ({','.join(map(str, sids))}) "
        f"and p.quantity > 0")] if sids else []


def pf(nets):
    w = sum(x for x in nets if x > 0); l = -sum(x for x in nets if x < 0)
    return w / l if l else float("inf")


def maxdd(daily):
    peak = cur = dd = 0.0
    for d in sorted(daily):
        cur += daily[d]; peak = max(peak, cur); dd = max(dd, peak - cur)
    return dd


print("A-168: live strategies on Oct 2024-Dec 2025 bar days (never seen by any rule)\n")
for v in ("set", "mac", "etr", "odm8", "odb", "ecr", "ebs"):
    r = runs.get(v, {})
    if not r:
        continue
    tr = trades([sid for sid, _ in r.values()])
    nets = [t[5] for t in tr]
    daily = {d: n for d, (_, n) in r.items()}
    h1 = [t[5] for t in tr if t[0] <= HALF]; h2 = [t[5] for t in tr if t[0] > HALF]
    verdict = ("holds" if sum(h1) > 0 and sum(h2) > 0 and pf(h1) >= 1.2 and pf(h2) >= 1.2 else
               "fails" if sum(nets) <= 0 else "mixed")
    print(f"  {v:4s} days {len(r):3d} trades {len(nets):4d} net {sum(nets):>+12,.0f} PF {pf(nets):5.2f} win {sum(1 for x in nets if x > 0) / max(1, len(nets)):.0%} "
          f"maxDD {maxdd(daily):>10,.0f} | half1 {sum(h1):>+11,.0f} (PF {pf(h1):.2f}) half2 {sum(h2):>+11,.0f} (PF {pf(h2):.2f}) -> {verdict}")

# A-169 path-free on mac alone and on mac inside the set
bars = defaultdict(dict)
for u, d, t, o, h, l, c in q(f"select underlying, (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                             f"from hist.candle where kind in ('INDEX','VIX') and bar_start >= '2024-10-01' and bar_start < '2026-01-01' "
                             f"and underlying in ('NIFTY','SENSEX','INDIA_VIX')"):
    bars[(u, d)][t] = (float(o), float(h), float(l), float(c))


def hm(x):
    return f"{x // 60:02d}:{x % 60:02d}"


def conditions(d, u, side, t):
    b = bars.get((u, d)); v = bars.get(("INDIA_VIX", d))
    h, mnt = map(int, t.split(":")); m = h * 60 + mnt
    last30 = [b[hm(x)] for x in range(m - 30, m) if b and hm(x) in b]
    upto = [b[hm(x)] for x in range(9 * 60 + 15, m) if b and hm(x) in b]
    if not last30 or not upto:
        return None, None
    px = upto[-1][3]
    run30 = (px - min(x[2] for x in last30)) / px * 100 if side == "CE" else (max(x[1] for x in last30) - px) / px * 100
    vix = v["09:15"][0] if v and "09:15" in v else None
    return vix, run30


print("\nA-169: ma-cross conditions (cuts from 2026), path-free on Oct 2024-Dec 2025")
for v in ("mac", "set"):
    tr = [t for t in trades([sid for sid, _ in runs.get(v, {}).values()]) if t[1] == "ma-cross"]
    rows = []
    for d, s, u, side, t, net in tr:
        vix, run30 = conditions(d, u, side, t)
        rows.append((d, net, vix, run30))
    for name, f in (("H1 VIX at 09:15 > 15.89", lambda r: r[2] is not None and r[2] > 15.89),
                    ("H2 run30 > 0.17 %", lambda r: r[3] is not None and r[3] > 0.17)):
        rem = [r for r in rows if f(r)]
        by_day = defaultdict(float)
        for r in rem:
            by_day[r[0]] += r[1]
        worst = min(by_day.values()) if by_day else 0
        s_all = sum(r[1] for r in rem)
        s1 = sum(r[1] for r in rem if r[0] <= HALF); s2 = sum(r[1] for r in rem if r[0] > HALF)
        ok = s_all < 0 and (s_all - worst) < 0 and s1 < 0 and s2 < 0
        print(f"  mac in {v:3s} {name:24s}: removed {len(rem):3d} of {len(rows)} trades, net {s_all:>+11,.0f} (wins {sum(1 for r in rem if r[1] > 0)}), "
              f"without the worst day {s_all - worst:>+11,.0f}, halves {s1:>+10,.0f} / {s2:>+10,.0f} -> {'PASS' if ok else 'FAIL'}")

# A-170: odm gap / stale drive, odb gap-made level break (path-free on the strategy-alone replays)
days = sorted({d for (u, d) in bars if u == "NIFTY"})
prev = {}
for u in ("NIFTY", "SENSEX"):
    last = None
    for d in days:
        b = bars.get((u, d))
        if not b:
            continue
        hs = [x for t, x in b.items() if "09:15" <= t <= "15:29"]
        if last:
            prev[(u, d)] = last
        if hs:
            last = (max(x[1] for x in hs), min(x[2] for x in hs), b[max(t for t in b if t <= "15:29")][3])


def context(d, u, side, t):
    b = bars.get((u, d)); p = prev.get((u, d))
    if not b or "09:15" not in b or not p:
        return None
    o = b["09:15"][0]; sign = 1 if side == "CE" else -1
    gap = (o / p[2] - 1) * 100 * sign
    h, mn = map(int, t.split(":")); m = h * 60 + mn
    upto = [b[hm(x)] for x in range(9 * 60 + 15, m) if hm(x) in b]
    if len(upto) < 4:
        return None
    before = upto[:-3]; recent = upto[-3:]
    stale = (max(x[1] for x in recent) <= max(x[1] for x in before)) if sign > 0 else (min(x[2] for x in recent) >= min(x[2] for x in before))
    gap_break = (o > p[0]) if sign > 0 else (o < p[1])
    return gap, stale, gap_break


print("\nA-170: opening-strategy context rules, path-free on Oct 2024-Dec 2025")
for strat, v, name, f in (("opening-drive-momentum", "odm8", "G1 gap >= 0.5 % in the trade's direction", lambda c: c[0] >= 0.5),
                          ("opening-drive-momentum", "odm8", "G2 stale drive (no new extreme in 3 min)", lambda c: c[1]),
                          ("opening-drive", "odb", "G3 level broken by the opening gap", lambda c: c[2])):
    tr = [t for t in trades([sid for sid, _ in runs.get(v, {}).values()]) if t[1] == strat]
    rem = []
    for d, s, u, side, t, net in tr:
        c = context(d, u, side, t)
        if c and f(c):
            rem.append((d, net))
    by_day = defaultdict(float)
    for d, n in rem:
        by_day[d] += n
    worst = min(by_day.values()) if by_day else 0
    s_all = sum(n for _, n in rem); s1 = sum(n for d, n in rem if d <= HALF); s2 = sum(n for d, n in rem if d > HALF)
    ok = rem and s_all < 0 and (s_all - worst) < 0 and s1 < 0 and s2 < 0
    print(f"  {v} {name:44s}: removed {len(rem):3d} of {len(tr)} trades, net {s_all:>+11,.0f} (wins {sum(1 for _, n in rem if n > 0)}), "
          f"without the worst day {s_all - worst:>+11,.0f}, halves {s1:>+10,.0f} / {s2:>+10,.0f} -> {'PASS' if ok else 'FAIL'}")
