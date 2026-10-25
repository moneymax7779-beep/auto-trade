#!/usr/bin/env python3
"""A-173 report (trend-day capture): orb-v1 / orb-v4 / orc-v2 / orc-v3 alone, from `.local/a173/progress.txt` sessions.
Registered criteria (ledger A-173): a candidate "captures out of sample" if net > 0 and PF >= 1.2 in both 2024-25 halves
(to Jun 2025 / from Jul 2025) and net > 0 on the 2026 bars; a trail variant is preferred to its base if it improves net in
at least 2 of the 3 samples (2024-25 bars, 2026 bars, ticks) and in both 2024-25 halves.
Usage: python3 2026-10-25-a173-report.py
"""
import csv, io, os, re, subprocess
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
PROGRESS = os.path.join(HERE, "..", "..", ".local", "a173", "progress.txt")
NAMES = {"orb1": "orb-v1", "orb4": "orb-v4 (orb-v1 + trail)", "orc2": "orc-v2", "orc3": "orc-v3 (orc-v2 + trail)"}


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def main():
    sid = {}
    for line in open(PROGRESS):
        m = re.search(r" (orb1|orb4|orc2|orc3) (bars|zt|own) (\S+) sid=(\d+) \{events=(\d+)", line)
        if m and int(m.group(5)) > 0:
            sid[(m.group(1), m.group(3))] = (int(m.group(4)), "ticks" if m.group(2) != "bars" else "bars")
    ids = ",".join(str(v[0]) for v in sid.values())
    trades = defaultdict(list)            # session -> [(net, opened, underlying, exit)]
    for s, net, t, u, why in q(f"select session_id, net, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), underlying, exit_reason "
                               f"from trade.\"position\" where session_id in ({ids})"):
        trades[int(s)].append((float(net), t, u, why))

    def sample(v, d, kind):
        if kind == "ticks":
            return "ticks"
        if d < "2025-07-01":
            return "2024-25 H1"
        if d < "2026-01-01":
            return "2024-25 H2"
        return "2026 bars"

    agg = defaultdict(lambda: defaultdict(list))
    days = defaultdict(lambda: defaultdict(int))
    for (v, d), (s, kind) in sid.items():
        smp = sample(v, d, kind)
        days[v][smp] += 1
        agg[v][smp].extend(x[0] for x in trades.get(s, []))
    order = ["2024-25 H1", "2024-25 H2", "2026 bars", "ticks"]
    print("A-173 trend-day capture, each candidate alone (risk v17, features v12, image a217-local)\n")
    print("| candidate | sample | days | trades | net | PF | win % | worst trade |\n|---|---|---|---|---|---|---|---|")
    res = {}
    for v in ("orb1", "orb4", "orc2", "orc3"):
        for smp in order:
            xs = agg[v][smp]
            net = sum(xs); win = sum(x for x in xs if x > 0); loss = -sum(x for x in xs if x < 0)
            pf = win / loss if loss else float("inf") if win else 0.0
            res[(v, smp)] = (net, pf, len(xs))
            print(f"| {NAMES[v]} | {smp} | {days[v][smp]} | {len(xs)} | {net:+,.0f} | {pf:.2f} | "
                  f"{(sum(x > 0 for x in xs) / len(xs) * 100 if xs else 0):.0f} | {min(xs) if xs else 0:+,.0f} |")
    print("\nVerdicts (registered criteria):")
    for v in ("orb1", "orb4", "orc2", "orc3"):
        ok = all(res[(v, h)][0] > 0 and res[(v, h)][1] >= 1.2 for h in ("2024-25 H1", "2024-25 H2")) and res[(v, "2026 bars")][0] > 0
        print(f"  {NAMES[v]}: {'CAPTURES OUT OF SAMPLE (PASS)' if ok else 'FAIL'}")
    for trail, base in (("orb4", "orb1"), ("orc3", "orc2")):
        b25 = res[(trail, "2024-25 H1")][0] + res[(trail, "2024-25 H2")][0] - res[(base, "2024-25 H1")][0] - res[(base, "2024-25 H2")][0]
        better = sum([b25 > 0, res[(trail, "2026 bars")][0] > res[(base, "2026 bars")][0], res[(trail, "ticks")][0] > res[(base, "ticks")][0]])
        halves = all(res[(trail, h)][0] > res[(base, h)][0] for h in ("2024-25 H1", "2024-25 H2"))
        print(f"  {NAMES[trail]} vs {NAMES[base]}: better in {better} of 3 samples, both 2024-25 halves {'yes' if halves else 'no'} -> "
              f"{'trail preferred' if better >= 2 and halves else 'base kept'}")
    print("\nTick days, day by day (net per candidate):")
    tick_days = sorted({d for (v, d), (s, k) in sid.items() if k == "ticks"})
    for d in tick_days:
        print(f"  {d}: " + "  ".join(f"{v} {sum(x[0] for x in trades.get(sid[(v, d)][0], [])) if (v, d) in sid else float('nan'):+,.0f}"
                                     for v in ("orb1", "orb4", "orc2", "orc3")))


if __name__ == "__main__":
    main()
