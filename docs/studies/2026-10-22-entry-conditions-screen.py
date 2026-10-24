#!/usr/bin/env python3
"""Discovery screen (2026 bar days, descriptive): the live set's control trades (A-167 ctl runs) by conditions at entry.
Candidates found here are registered and validated on Oct 2024-Dec 2025 (A-168 data), never adopted from this screen."""
import csv, io, re, statistics, subprocess
from collections import defaultdict

PROGRESS = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-cv/.local/a167/progress.txt"
IST = "at time zone 'Asia/Kolkata'"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


sids = {}
for line in open(PROGRESS):
    m = re.search(r" ctl bars (\d{4}-\d\d-\d\d) sid=(\d+) ", line)
    if m:
        sids[m[1]] = int(m[2])
trades = q(f"select s.session_date::text, p.strategy_id, p.underlying, p.option_side, to_char(p.opened_at {IST},'HH24:MI'), "
           f"p.net, p.average_cost * p.quantity from trade.position p join trade.session s on s.id = p.session_id "
           f"where p.session_id in ({','.join(map(str, sids.values()))}) and p.quantity > 0")
bars = defaultdict(dict)
for u, d, t, o, h, l, c in q(f"select underlying, (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                             f"from hist.candle where kind in ('INDEX','VIX') and bar_start >= '2026-01-01' "
                             f"and underlying in ('NIFTY','SENSEX','INDIA_VIX')"):
    bars[(u, d)][t] = (float(o), float(h), float(l), float(c))
days = sorted({d for (_, d) in bars})
prev_close = {}
for u in ("NIFTY", "SENSEX"):
    pc = None
    for d in days:
        b = bars.get((u, d))
        if b:
            prev_close[(u, d)] = pc
            last = max(b)
            pc = b[last][3]


def mins(t):
    h, m = map(int, t.split(":")); return h * 60 + m


def hm(x):
    return f"{x // 60:02d}:{x % 60:02d}"


rows = []
for d, strat, u, side, t, net, prem in trades:
    b = bars.get((u, d)); v = bars.get(("INDIA_VIX", d))
    if not b or "09:15" not in b:
        continue
    m = mins(t)
    upto = [b[hm(x)] for x in range(9 * 60 + 15, m) if hm(x) in b]
    last30 = [b[hm(x)] for x in range(m - 30, m) if hm(x) in b]
    if not upto or not last30:
        continue
    px = upto[-1][3]
    sign = 1 if side == "CE" else -1
    run30 = (px - min(x[2] for x in last30)) / px * 100 if sign > 0 else (max(x[1] for x in last30) - px) / px * 100
    rng = (max(x[1] for x in upto) - min(x[2] for x in upto)) / b["09:15"][0] * 100
    pc = prev_close.get((u, d))
    gap = (b["09:15"][0] / pc - 1) * 100 * sign if pc else None
    trend = (px / b["09:15"][0] - 1) * 100 * sign
    vix0 = v["09:15"][0] if v and "09:15" in v else None
    vixnow = next((v[hm(x)][3] for x in range(m - 1, 9 * 60 + 14, -1) if v and hm(x) in v), None)
    rows.append({"d": d, "s": strat.split("-")[0] + ("-" + strat.split("-")[1] if "-" in strat else ""), "u": u, "net": float(net),
                 "prem": float(prem), "min": m, "run30": run30, "range": rng, "gap": gap, "trend": trend, "vix": vix0,
                 "dvix": (vixnow / vix0 - 1) * 100 if vix0 and vixnow else None})


def tercile_table(key, label):
    print(f"\n== {label}")
    for strat in sorted({r["s"] for r in rows}):
        rs = [r for r in rows if r["s"] == strat and r[key] is not None]
        if len(rs) < 15:
            continue
        vals = sorted(r[key] for r in rs); c1, c2 = vals[len(vals) // 3], vals[2 * len(vals) // 3]
        out = []
        for name, f in (("low", lambda x: x <= c1), ("mid", lambda x: c1 < x <= c2), ("high", lambda x: x > c2)):
            g = [r for r in rs if f(r[key])]
            out.append(f"{name} n={len(g):3d} net {sum(r['net'] for r in g):>+10,.0f} win {sum(1 for r in g if r['net'] > 0) / max(1, len(g)):.0%}")
        print(f"  {strat:14s} cuts {c1:.2f}/{c2:.2f} | " + " | ".join(out))


print(f"{len(rows)} trades with context on {len({r['d'] for r in rows})} bar days")
for strat in sorted({r["s"] for r in rows}):
    rs = [r for r in rows if r["s"] == strat]
    print(f"  {strat:14s} n={len(rs):3d} net {sum(r['net'] for r in rs):>+11,.0f} win {sum(1 for r in rs if r['net'] > 0) / len(rs):.0%}")
tercile_table("vix", "VIX at 09:15")
tercile_table("dvix", "VIX change since the open, % (to entry)")
tercile_table("run30", "how far price already ran in the trade's direction from the 30-min extreme, % (chasing)")
tercile_table("range", "day's range so far, %")
tercile_table("gap", "gap in the trade's direction, %")
tercile_table("trend", "move since the open in the trade's direction, %")
tercile_table("min", "minute of day")
