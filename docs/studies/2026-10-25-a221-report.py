#!/usr/bin/env python3
"""A-221 report: mac-v7 (conditional time stop) vs mac-v1 in the engine (registered criteria in the ledger).
Bars: mac alone, Oct 2024-Jun 2025 / Jul-Dec 2025 / 2026. Tick days: live set with mac-v1 (C) vs mac-v7 (R).
Usage: python3 2026-10-25-a221-report.py
"""
import csv, io, os, random, re, subprocess
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", ".local", "a221")


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def main():
    sid = {}
    for line in open(os.path.join(OUT, "progress.txt")):
        m = re.search(r" (mac1|mac7|C|R) (bars|zt|own) (\S+) sid=(\d+) \{events=(\d+)", line)
        if m and int(m.group(5)) > 0:
            sid[(m.group(1), m.group(3))] = int(m.group(4))
    ids = ",".join(str(v) for v in sid.values())
    pos = defaultdict(list)
    for s, st, net, why in q(f"select session_id, strategy_id, net, exit_reason from trade.\"position\" where session_id in ({ids})"):
        pos[int(s)].append((st, float(net), why))

    def period(d):
        return "H1 Oct24-Jun25" if d < "2025-07-01" else "H2 Jul-Dec25" if d < "2026-01-01" else "2026 bars"

    print("A-221 mac-v7 (conditional time stop) vs mac-v1, engine replays, image a221-local\n")
    print("Bars, mac alone (risk v17, features v12):\n")
    print("| period | days | mac-v1 Rs (n, PF) | mac-v7 Rs (n, PF) | v7 − v1 | 95 % CI (paired days) |\n|---|---|---|---|---|---|")
    verdict = []
    for p in ("H1 Oct24-Jun25", "H2 Jul-Dec25", "2026 bars"):
        days = sorted(d for (a, d) in sid if a == "mac1" and (("mac7", d) in sid) and period(d) == p and d < "2026-10-01")
        rows = {}
        for a in ("mac1", "mac7"):
            xs = [n for d in days for (st, n, w) in pos[sid[(a, d)]]]
            w = sum(x for x in xs if x > 0); l = -sum(x for x in xs if x < 0)
            rows[a] = (sum(xs), len(xs), w / l if l else 0)
        diffs = [sum(n for (st, n, w) in pos[sid[("mac7", d)]]) - sum(n for (st, n, w) in pos[sid[("mac1", d)]]) for d in days]
        rng, means = random.Random(221), []
        for _ in range(10000):
            means.append(sum(diffs[rng.randrange(len(diffs))] for _ in diffs))
        means.sort()
        dlt = rows["mac7"][0] - rows["mac1"][0]
        verdict.append(dlt > 0)
        print(f"| {p} | {len(days)} | {rows['mac1'][0]:+,.0f} ({rows['mac1'][1]}, {rows['mac1'][2]:.2f}) | {rows['mac7'][0]:+,.0f} "
              f"({rows['mac7'][1]}, {rows['mac7'][2]:.2f}) | {dlt:+,.0f} | [{means[250]:+,.0f}, {means[9749]:+,.0f}] |")
    for a in ("mac1", "mac7"):
        c = defaultdict(lambda: [0, 0.0])
        for (arm, d), s in sid.items():
            if arm == a:
                for st, n, w in pos[s]:
                    c[w][0] += 1; c[w][1] += n
        print(f"  {a} exits: " + ", ".join(f"{k} {v[0]} ({v[1]:+,.0f})" for k, v in sorted(c.items(), key=lambda kv: -kv[1][0])))

    print("\nTick days, live set with mac-v1 (C) vs mac-v7 (R), Rs 5 L a day:\n")
    tick_days = sorted(d for (a, d) in sid if a == "C")
    a210 = {r[0]: float(r[1]) for r in q("select s.session_date::text, coalesce(sum(p.net), 0) from trade.session s left join trade.\"position\" p "
                                          "on p.session_id = s.id where s.id between 14698 and 14724 group by 1")}
    tot = defaultdict(float); repro = 0
    print("| day | C set | R set | R − C | mac C | mac R | others R − C |\n|---|---|---|---|---|---|---|")
    for d in tick_days:
        if ("R", d) not in sid:
            continue
        c = defaultdict(float); r = defaultdict(float)
        for st, n, w in pos[sid[("C", d)]]:
            c[st] += n
        for st, n, w in pos[sid[("R", d)]]:
            r[st] += n
        cs, rs = sum(c.values()), sum(r.values())
        repro += abs(a210.get(d, 1e18) - cs) < 1
        oth = (rs - r["ma-cross"]) - (cs - c["ma-cross"])
        tot["C"] += cs; tot["R"] += rs; tot["mc"] += c["ma-cross"]; tot["mr"] += r["ma-cross"]; tot["oth"] += oth
        if abs(rs - cs) > 0.5:
            print(f"| {d} | {cs:+,.0f} | {rs:+,.0f} | {rs - cs:+,.0f} | {c['ma-cross']:+,.0f} | {r['ma-cross']:+,.0f} | {oth:+,.0f} |")
    print(f"\n(days not listed are identical) control reproduces A-210 arm A on {repro} of {len(tick_days)} days")
    print(f"tick days: set C {tot['C']:+,.0f}, R {tot['R']:+,.0f} ({tot['R'] - tot['C']:+,.0f}); mac C {tot['mc']:+,.0f} vs R {tot['mr']:+,.0f}; others {tot['oth']:+,.0f}")
    ok = all(verdict) and tot["R"] - tot["C"] >= 0 and tot["oth"] >= -50000
    print(f"\nRegistered verdict: {'PASS' if ok else 'FAIL'} (bars better in all three periods: {all(verdict)}; tick set >= control: "
          f"{tot['R'] - tot['C'] >= 0}; others within -Rs 50,000: {tot['oth'] >= -50000})")


if __name__ == "__main__":
    main()
