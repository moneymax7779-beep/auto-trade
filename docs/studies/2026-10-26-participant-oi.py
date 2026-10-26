#!/usr/bin/env python3
"""A-236: institutional positioning from NSE participant-wise OI (claims fixed in the ledger before this ran).
Always the previous session's file. F1: FII index-futures long ratio < 20 % -> the next session's NIFTY open-to-close and
next 3 sessions' close-to-close returns are higher than on other days. F2: Client net index calls above their previous-60
-session 80th percentile and Pro net index calls below their 20th -> the next session's NIFTY open-to-close return < 0.
Usage: python3 2026-10-26-participant-oi.py
"""
import csv, io, random, statistics, subprocess
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]


def boot(a, b=None, n=10000, seed=236):
    r, vals = random.Random(seed), []
    for _ in range(n):
        sa = [r.choice(a) for _ in a]
        if b is None:
            vals.append(mean(sa))
        else:
            vals.append(mean(sa) - mean([r.choice(b) for _ in b]))
    vals.sort()
    return vals[250], vals[9749]


def main():
    poi = defaultdict(dict)
    for d, ct, fl, fs, cl, cs in q("select session_date::text, upper(client_type), future_index_long, future_index_short, "
                                   "option_index_call_long, option_index_call_short from ref.participant_oi order by 1"):
        poi[d][ct] = dict(fl=float(fl), fs=float(fs), net_call=float(cl) - float(cs))
    days_poi = sorted(poi)
    # NIFTY daily open / close from the 1-minute bars
    bars = {d: (float(o), float(c)) for d, o, c in q(
        f"select (bar_start {IST})::date::text, (array_agg(open order by bar_start))[1], (array_agg(close order by bar_start desc))[1] "
        f"from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' and bar_start >= '2024-09-01' group by 1")}
    days = sorted(bars)
    print(f"A-236 participant OI: {len(days_poi)} files ({days_poi[0]} .. {days_poi[-1]}), {len(days)} NIFTY bar days")
    print("client types seen:", sorted({ct for d in poi.values() for ct in d}), "\n")
    rows = []
    for i, d in enumerate(days):
        prev_files = [x for x in days_poi if x < d]
        if not prev_files or i + 3 >= len(days) or d < "2024-10-01" or d > "2026-09-30":
            continue
        f = prev_files[-1]
        p = poi[f]
        if "FII" not in p or "CLIENT" not in p or "PRO" not in p:
            continue
        ratio = p["FII"]["fl"] / (p["FII"]["fl"] + p["FII"]["fs"]) * 100
        o, c = bars[d]
        oc = (c / o - 1) * 100
        cc3 = (bars[days[i + 2]][1] / bars[days[i - 1]][1] - 1) * 100 if i >= 1 else None
        hist = [x for x in days_poi if x < f][-60:]
        f2 = False
        if len(hist) >= 40 and all("CLIENT" in poi[x] and "PRO" in poi[x] for x in hist):
            cl = [poi[x]["CLIENT"]["net_call"] for x in hist]
            pr = [poi[x]["PRO"]["net_call"] for x in hist]
            f2 = p["CLIENT"]["net_call"] > pct(cl, 0.8) and p["PRO"]["net_call"] < pct(pr, 0.2)
        rows.append(dict(d=d, ratio=ratio, oc=oc, cc3=cc3, f1=ratio < 20, f2=f2))
    print(f"days with a previous file: {len(rows)}; FII long ratio min {min(r['ratio'] for r in rows):.1f} %, median "
          f"{statistics.median(r['ratio'] for r in rows):.1f} %, max {max(r['ratio'] for r in rows):.1f} %\n")
    for key, lab in (("oc", "next-session open-to-close"), ("cc3", "next 3 sessions close-to-close")):
        ok = []
        for hl, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
            sub = [r for r in rows if lo <= r["d"] <= hi and r[key] is not None]
            a = [r[key] for r in sub if r["f1"]]
            b = [r[key] for r in sub if not r["f1"]]
            diff = mean(a) - mean(b)
            ci = boot(a, b) if hl == "pooled" and len(a) > 1 else None
            print(f"F1 {lab} {hl}: FII ratio < 20 % {mean(a):+.2f} % (n {len(a)}) vs others {mean(b):+.2f} % (n {len(b)}); diff {diff:+.2f}"
                  + (f", CI95 [{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else ""))
            if hl != "pooled":
                ok.append(None if len(a) < 15 else diff > 0)
            else:
                pooled = ci
        print("  -> " + ("INSUFFICIENT" if any(o is None for o in ok) else "HOLDS" if all(ok) and pooled and pooled[0] > 0 else "NOT SUPPORTED") + "\n")
    ok = []
    for hl, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
        a = [r["oc"] for r in rows if lo <= r["d"] <= hi and r["f2"]]
        ci = boot(a) if hl == "pooled" and len(a) > 1 else None
        print(f"F2 next-session open-to-close {hl}: {mean(a):+.2f} % (n {len(a)})" + (f", CI95 [{ci[0]:+.2f}, {ci[1]:+.2f}]" if ci else ""))
        if hl != "pooled":
            ok.append(None if len(a) < 15 else mean(a) < 0)
        else:
            pooled = ci
    print("  -> " + ("INSUFFICIENT" if any(o is None for o in ok) else "HOLDS" if all(ok) and pooled and pooled[1] < 0 else "NOT SUPPORTED"))
    print("\nDescriptive: next-session open-to-close by FII long-ratio band")
    for lab, lo, hi in (("< 15 %", 0, 15), ("15-20 %", 15, 20), ("20-30 %", 20, 30), ("30-45 %", 30, 45), ("> 45 %", 45, 101)):
        sub = [r["oc"] for r in rows if lo <= r["ratio"] < hi]
        print(f"  {lab:8s} n {len(sub):3d}  mean {mean(sub):+.2f} %  up-days {sum(x > 0 for x in sub) / len(sub) if sub else float('nan'):.0%}")


if __name__ == "__main__":
    main()
