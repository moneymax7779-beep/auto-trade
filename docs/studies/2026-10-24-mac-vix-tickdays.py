#!/usr/bin/env python3
"""A-210 comparison: arm A (live set) vs arm B (mac-v6, previous-day VIX < 15) on the 27 tick days, both on image a210-local.
Reads .local/a210/progress.txt for the session ids. Descriptive (in-sample)."""
import csv, io, re, subprocess
from collections import defaultdict

P = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a210/progress.txt"
SHORT = {"early-confirm-runner": "ecr", "expiry-trend-rider": "etr", "ma-cross": "mac", "opening-drive-momentum": "odm"}


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


sid = {}
for line in open(P):
    m = re.search(r" ([AB]) (zt|own) (\d{4}-\d\d-\d\d) sid=(\d+) \{events=(\d+)", line)
    if m and int(m[5]) > 0:
        sid[(m[1], m[3])] = m[4]
days = sorted({d for _, d in sid})
both = [d for d in days if ("A", d) in sid and ("B", d) in sid]
prev = {}
for d in both:
    r = q(f"select close from ref.index_candle where symbol = 'INDIA_VIX' and session_date < '{d}' order by session_date desc, "
          f"bar_start desc limit 1")
    prev[d] = float(r[0][0]) if r else None
res = defaultdict(lambda: defaultdict(float))
cnt = defaultdict(lambda: defaultdict(int))
for arm in ("A", "B"):
    ids = ",".join(sid[(arm, d)] for d in both)
    for d, st, n in q("select s.session_date::text, p.strategy_id, p.net from trade.position p join trade.session s on s.id = p.session_id "
                      f"where p.session_id in ({ids}) and p.quantity > 0"):
        res[(arm, d)][SHORT.get(st, st)] += float(n)
        cnt[(arm, d)][SHORT.get(st, st)] += 1
print(f"days compared: {len(both)} of 27\n")
print("| Day | prev VIX | A set | B set | B − A | A mac | B mac | A others | B others |\n|---|---|---|---|---|---|---|---|---|")
tot = defaultdict(float)
for d in both:
    a, b = res[("A", d)], res[("B", d)]
    sa, sb = sum(a.values()), sum(b.values())
    oa, ob = sa - a["mac"], sb - b["mac"]
    for k, v in (("A", sa), ("B", sb), ("Amac", a["mac"]), ("Bmac", b["mac"]), ("Aoth", oa), ("Both", ob)):
        tot[k] += v
        tot[k + ("1" if d <= "2026-09-30" else "2")] += v
    flag = "" if prev[d] is None else (" ≥15" if prev[d] >= 15 else "")
    print(f"| {d} | {prev[d]:.2f}{flag} | {sa:+,.0f} | {sb:+,.0f} | {sb - sa:+,.0f} | {a['mac']:+,.0f} ({cnt[('A', d)]['mac']}) | "
          f"{b['mac']:+,.0f} ({cnt[('B', d)]['mac']}) | {oa:+,.0f} | {ob:+,.0f} |")
print()
for lab, sfx in (("all", ""), ("2–30 Sep", "1"), ("1–23 Oct", "2")):
    print(f"{lab}: set A {tot['A' + sfx]:+,.0f}  B {tot['B' + sfx]:+,.0f}  (B−A {tot['B' + sfx] - tot['A' + sfx]:+,.0f}); "
          f"mac A {tot['Amac' + sfx]:+,.0f}  B {tot['Bmac' + sfx]:+,.0f}; others A {tot['Aoth' + sfx]:+,.0f}  B {tot['Both' + sfx]:+,.0f}")
for arm in ("A", "B"):
    per = defaultdict(float)
    for d in both:
        for k, v in res[(arm, d)].items():
            per[k] += v
    print(f"arm {arm} by strategy: " + ", ".join(f"{k} {v:+,.0f}" for k, v in sorted(per.items())))
