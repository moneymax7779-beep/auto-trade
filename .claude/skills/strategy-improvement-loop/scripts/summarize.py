#!/usr/bin/env python3
"""Summarise replay_batch.sh runs: per label totals, halves, best-day dependence, per-day nets, every trade.

usage: summarize.py OUT_DIR [--split YYYY-MM-DD] [--base LABEL] [--progress extra/progress.txt ...]
  OUT_DIR     a replay_batch.sh folder (reads OUT_DIR/progress.txt, writes OUT_DIR/summary.txt and OUT_DIR/trades.tsv)
  --split     last day of the first half (default: the middle day of the days run)
  --base      label the others are compared with (adds "vs base" columns and the both-halves verdict)
  --progress  more progress files to read (e.g. an earlier run's), later lines win for the same label/day

A label is complete only if every day has a summary; incomplete labels are reported, not silently totalled.
"""
import argparse, collections, re, subprocess, sys

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("--split"); ap.add_argument("--base"); ap.add_argument("--progress", nargs="*", default=[])
a = ap.parse_args()
RX = re.compile(r"^\S+ (\S+) (\d{4}-\d\d-\d\d) (\d+) \{.*positions=(\d+), wins=(\d+), net=(-?\d+)")
runs = collections.defaultdict(dict)          # label -> day -> session id
failed = collections.defaultdict(set)
for f in a.progress + [f"{a.out}/progress.txt"]:
    for line in open(f):
        m = RX.match(line)
        if m:
            runs[m.group(1)][m.group(2)] = m.group(3); failed[m.group(1)].discard(m.group(2))
        elif "NO SUMMARY" in line:
            p = line.split(); failed[p[1]].add(p[2])
days = sorted({d for r in runs.values() for d in r})
if not days:
    sys.exit("no completed replays")
split = a.split or days[(len(days) - 1) // 2]
sids = [s for r in runs.values() for s in r.values()]
q = ("select session_id, underlying, option_side, symbol, quantity, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI:SS'), "
     "to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI:SS'), round(average_cost::numeric,2), round(average_exit::numeric,2), "
     "exit_reason, round(net), round(costs) from trade.position where quantity > 0 and session_id in (" + ",".join(sids) + ") order by opened_at")
rows = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAtF|", "-c", q],
                      capture_output=True, text=True, check=True).stdout.splitlines()
back = {sid: (lab, d) for lab, r in runs.items() for d, sid in r.items()}
T = collections.defaultdict(list)
for r in rows:
    f = r.split("|"); lab, d = back[f[0]]
    T[lab].append(dict(day=d, sid=f[0], und=f[1], side=f[2], symbol=f[3], qty=f[4], opened=f[5], closed=f[6],
                       entry=f[7], exit=f[8], reason=f[9], net=float(f[10]), costs=float(f[11])))

def stats(tr):
    n = [t["net"] for t in tr]; w = sum(x for x in n if x > 0); l = -sum(x for x in n if x <= 0)
    return dict(n=len(n), wins=sum(1 for x in n if x > 0), net=sum(n), pf=(w / l if l else float("inf")))
def by_day(tr, ds):
    m = {d: 0.0 for d in ds}
    for t in tr:
        if t["day"] in m: m[t["day"]] += t["net"]
    return m
def no_best(tr, ds):
    m = by_day(tr, ds); return sum(m.values()) - max(m.values()) if m else 0.0
def max_dd(tr):
    eq = pk = dd = 0.0
    for d, v in sorted(by_day(tr, days).items()):
        eq += v; pk = max(pk, eq); dd = max(dd, pk - eq)
    return dd

H1 = [d for d in days if d <= split]; H2 = [d for d in days if d > split]
out = [f"days {days[0]} .. {days[-1]} ({len(days)}); halves: {H1[0]} .. {H1[-1]} ({len(H1)}) / "
       f"{H2[0] if H2 else '-'} .. {H2[-1] if H2 else '-'} ({len(H2)})", ""]
hdr = f"{'label':24} {'days':>4} {'trades':>6} {'wins':>4} {'net':>12} {'PF':>5} {'maxDD':>10} {'H1':>11} {'H2':>11} {'H1-best':>11} {'H2-best':>11}"
out.append(hdr)
labels = sorted(runs, key=lambda l: (l != a.base, l))
for lab in labels:
    tr = T[lab]; s = stats(tr); h1 = [t for t in tr if t["day"] <= split]; h2 = [t for t in tr if t["day"] > split]
    miss = [d for d in days if d not in runs[lab]]
    out.append(f"{lab:24} {len(runs[lab]):4d} {s['n']:6d} {s['wins']:4d} {s['net']:12,.0f} {s['pf']:5.2f} {max_dd(tr):10,.0f} "
               f"{stats(h1)['net']:11,.0f} {stats(h2)['net']:11,.0f} {no_best(h1, H1):11,.0f} {no_best(h2, H2):11,.0f}"
               + (f"   INCOMPLETE: missing {len(miss)} days" if miss else ""))
if a.base and a.base in runs:
    out += ["", f"vs {a.base} (gain = label - base, per half; 'without best' drops the half's day where the label gains most)"]
    bd1, bd2 = by_day(T[a.base], H1), by_day(T[a.base], H2)
    for lab in labels:
        if lab == a.base: continue
        g1 = {d: v - bd1[d] for d, v in by_day(T[lab], H1).items()}; g2 = {d: v - bd2[d] for d, v in by_day(T[lab], H2).items()}
        s1, s2 = sum(g1.values()), sum(g2.values()); n1 = s1 - max(g1.values(), default=0); n2 = s2 - max(g2.values(), default=0)
        ok = s1 > 0 and s2 > 0 and n1 > 0 and n2 > 0
        out.append(f"  {lab:24} H1 {s1:+11,.0f} (w/o best {n1:+11,.0f})  H2 {s2:+11,.0f} (w/o best {n2:+11,.0f})  -> "
                   + ("beats base in both halves and without the best day" if ok else "does NOT beat base on all four"))
out += ["", "per-day net", f"{'day':10} " + " ".join(f"{l[-12:]:>12}" for l in labels)]
for d in days:
    out.append(f"{d} " + " ".join(f"{by_day(T[l], [d])[d]:12,.0f}" if d in runs[l] else f"{'-':>12}" for l in labels))
for lab in labels:
    if failed[lab]: out.append(f"FAILED (retry): {lab} {sorted(failed[lab])}")
open(f"{a.out}/summary.txt", "w").write("\n".join(out) + "\n")
with open(f"{a.out}/trades.tsv", "w") as f:
    f.write("label\tday\tsession\tindex\tside\tsymbol\tqty\topened\tclosed\tentry\texit\texit_reason\tnet\tcosts\n")
    for lab in labels:
        for t in T[lab]:
            f.write("\t".join([lab, t["day"], t["sid"], t["und"], t["side"], t["symbol"], t["qty"], t["opened"], t["closed"],
                               t["entry"], t["exit"], t["reason"], f"{t['net']:.0f}", f"{t['costs']:.0f}"]) + "\n")
print("\n".join(out))
