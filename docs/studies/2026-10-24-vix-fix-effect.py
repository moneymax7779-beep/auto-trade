#!/usr/bin/env python3
"""A-211: the live set on the 27 tick days without (A-199, image 86f806567430-local) and with (A-210 arm A, image a210-local)
the daily-VIX fix. Per-day / per-strategy totals, ecr's NIFTY volatility-regime labels, and every ecr trade that differs."""
import csv, io, re, subprocess
from collections import Counter, defaultdict

BASE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local"
SHORT = {"early-confirm-runner": "ecr", "expiry-trend-rider": "etr", "ma-cross": "mac", "opening-drive-momentum": "odm"}


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


old, new = {}, {}
for line in open(f"{BASE}/hist-2025-26/progress.txt"):
    m = re.search(r" (zt|own) (\d{4}-\d\d-\d\d) sid=(\d+) \{events=(\d+)", line)
    if m and int(m[4]) > 0:
        old[m[2]] = m[3]
for line in open(f"{BASE}/a210/progress.txt"):
    m = re.search(r" A (zt|own) (\d{4}-\d\d-\d\d) sid=(\d+) \{events=(\d+)", line)
    if m and int(m[4]) > 0:
        new[m[2]] = m[3]
days = sorted(set(old) & set(new))


def positions(sids):
    out = defaultdict(list)
    for d, st, u, side, t0, t1, why, net in q(
            "select s.session_date::text, p.strategy_id, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), "
            "to_char(p.closed_at at time zone 'Asia/Kolkata','HH24:MI'), p.exit_reason, p.net from trade.position p "
            f"join trade.session s on s.id = p.session_id where p.session_id in ({','.join(sids)}) and p.quantity > 0 order by 1, 5"):
        out[d].append((SHORT.get(st, st), u, side, t0, t1, why, float(net)))
    return out


def regimes(sids):
    out = {}
    for sid, reg, n in q("select session_id, state->>'regime', count(*) from trade.decision where strategy_id = 'early-confirm-runner' "
                         f"and underlying = 'NIFTY' and session_id in ({','.join(sids)}) group by 1, 2"):
        out.setdefault(sid, Counter())[reg] += int(n)
    return out


po, pn = positions([old[d] for d in days]), positions([new[d] for d in days])
ro, rn = regimes([old[d] for d in days]), regimes([new[d] for d in days])
print(f"days: {len(days)} of 27\n")
print("| Day | without fix | with fix | difference | NIFTY ecr regime without → with |\n|---|---|---|---|---|")
tot = defaultdict(float)
diffs = []
for d in days:
    a, b = sum(x[-1] for x in po[d]), sum(x[-1] for x in pn[d])
    tot["old"] += a; tot["new"] += b
    for x in po[d]:
        tot["old-" + x[0]] += x[-1]
    for x in pn[d]:
        tot["new-" + x[0]] += x[-1]
    def top(c):
        if not c:
            return "–"
        n = sum(c.values())
        return ", ".join(f"{k.split('/')[-1] if k else '?'} {v * 100 // n}%" for k, v in c.most_common(2))
    print(f"| {d} | {a:+,.0f} | {b:+,.0f} | {b - a:+,.0f} | {top(ro.get(old[d]))} → {top(rn.get(new[d]))} |")
    if sorted(po[d]) != sorted(pn[d]):
        diffs.append(d)
print(f"\nset total without {tot['old']:+,.0f}, with {tot['new']:+,.0f} (difference {tot['new'] - tot['old']:+,.0f})")
for s in ("ecr", "etr", "mac", "odm"):
    print(f"  {s}: without {tot['old-' + s]:+,.0f}, with {tot['new-' + s]:+,.0f}")
print(f"\ndays whose trades differ: {len(diffs)}")
for d in diffs:
    print(f"  {d}")
    for x in sorted(set(po[d]) - set(pn[d])):
        print(f"     only without: {x[0]} {x[1]} {x[2]} {x[3]}-{x[4]} {x[5]} {x[6]:+,.0f}")
    for x in sorted(set(pn[d]) - set(po[d])):
        print(f"     only with:    {x[0]} {x[1]} {x[2]} {x[3]}-{x[4]} {x[5]} {x[6]:+,.0f}")
