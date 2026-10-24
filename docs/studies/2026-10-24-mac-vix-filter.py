#!/usr/bin/env python3
"""A-208: mac only when the previous trading day's India VIX close is below 15 (rule fixed in the ledger before the run).
Splits the live-set replay trades of .local/hist-2024q4 (out of sample) and, for consistency only, .local/hist-2025-26 (A-199,
where the pattern was found) by the previous day's VIX close.
Usage: python3 2026-10-24-mac-vix-filter.py
"""
import csv, io, re, statistics, subprocess
from collections import defaultdict

BASE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def sessions(folder):
    runs = {}
    for line in open(f"{BASE}/{folder}/progress.txt"):
        m = re.search(r" (bars|zt|own) (\d{4}-\d\d-\d\d) sid=(\d+) \{events=(\d+)", line)
        if m and int(m[4]) > 0:
            runs[m[2]] = m[3]
    return runs


def prev_vix():
    out, prev = {}, None
    for d, c in q("select (bar_start at time zone 'Asia/Kolkata')::date::text, (array_agg(close order by bar_start desc))[1] "
                  "from hist.candle where underlying = 'INDIA_VIX' and kind = 'VIX' group by 1 order by 1"):
        out[d] = prev
        prev = float(c)
    return out


def trades(runs):
    return [(d, st, float(n)) for d, st, n in q(
        "select s.session_date::text, p.strategy_id, p.net from trade.position p join trade.session s on s.id = p.session_id "
        f"where p.session_id in ({','.join(runs.values())}) and p.quantity > 0")]


def line(xs):
    if not xs:
        return "n=0"
    w = sum(x for x in xs if x > 0); l = -sum(x for x in xs if x < 0)
    return (f"n={len(xs):4d} total Rs {sum(xs):+11,.0f} mean {statistics.mean(xs):+8,.0f} win {sum(x > 0 for x in xs) / len(xs):.0%} "
            f"PF {w / l if l else float('inf'):.2f}")


def report(name, tr, vix):
    mac = [(d, n) for d, st, n in tr if st == "ma-cross"]
    lo = [n for d, n in mac if vix.get(d) is not None and vix[d] < 15]
    hi = [n for d, n in mac if vix.get(d) is not None and vix[d] >= 15]
    na = [n for d, n in mac if vix.get(d) is None]
    print(f"\n== {name}")
    print(f"  mac all            {line([n for _, n in mac])}")
    print(f"  mac prev VIX < 15  {line(lo)}")
    print(f"  mac prev VIX >= 15 {line(hi)}")
    if na:
        print(f"  mac no VIX data    {line(na)}")
    rest = sum(n for d, st, n in tr if st != "ma-cross")
    print(f"  live set total Rs {sum(n for *_, n in tr):+,.0f}; with the filter (mac >= 15 trades dropped) Rs {rest + sum(lo) + sum(na):+,.0f}")
    return lo, hi


if __name__ == "__main__":
    vix = prev_vix()
    lo, hi = report("Oct-Dec 2024 (out of sample)", trades(sessions("hist-2024q4")), vix)
    a199 = trades(sessions("hist-2025-26"))
    for y in ("2025", "2026"):
        report(f"{y} (A-199, where the pattern was found; consistency only)", [t for t in a199 if t[0].startswith(y)], vix)
