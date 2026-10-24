#!/usr/bin/env python3
"""A-165 futures order-flow imbalance as an entry-timing gate (definitions and criteria fixed in the ledger first).

Own-capture near-month futures ticks; OFI per Cont, Kukanov & Stoikov at the best level (and summed over 5 levels, reported);
samples every 15 s 09:20-15:15; outcome = mid change over the next 60 s in bp. Local database only.
"""
import bisect, csv, io, random, statistics, subprocess, sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
        "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21"]
HALF = "2026-10-08"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def ticks(u, d):
    exp = q(f"select min(expiry)::text from md.future_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")
    rows = q(f"select (extract(epoch from recv_ts) * 1000)::bigint, bid_px, bid_qty, ask_px, ask_qty from md.future_tick "
             f"where session_date = '{d}' and underlying = '{u}' and expiry = '{exp[0][0]}' "
             f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:20+05:30' order by recv_ts, src_seq")
    out = []
    for ms, bp, bq, ap, aq in rows:
        arr = lambda s: [float(x) for x in s.strip("{}").split(",") if x not in ("", "NULL")]
        bp, bq, ap, aq = arr(bp), arr(bq), arr(ap), arr(aq)
        if bp and ap and bp[0] > 0 and ap[0] > 0 and ap[0] > bp[0]:
            out.append((int(ms), bp, bq, ap, aq))
    return out


def e_level(prev, cur, lv):
    pb0, qb0, pa0, qa0 = prev[1][lv], prev[2][lv], prev[3][lv], prev[4][lv]
    pb, qb, pa, qa = cur[1][lv], cur[2][lv], cur[3][lv], cur[4][lv]
    return ((qb if pb >= pb0 else 0) - (qb0 if pb <= pb0 else 0) - (qa if pa <= pa0 else 0) + (qa0 if pa >= pa0 else 0))


def samples(u, d):
    tk = ticks(u, d)
    if len(tk) < 1000:
        return []
    t = [x[0] for x in tk]
    mid = [(x[1][0] + x[3][0]) / 2 for x in tk]
    depth = [(x[2][0] + x[4][0]) / 2 for x in tk]
    e1 = [0.0]; e5 = [0.0]
    for i in range(1, len(tk)):
        e1.append(e_level(tk[i - 1], tk[i], 0))
        lv = min(5, len(tk[i][1]), len(tk[i - 1][1]), len(tk[i][3]), len(tk[i - 1][3]))
        e5.append(sum(e_level(tk[i - 1], tk[i], k) for k in range(lv)))
    c1 = [0.0]; c5 = [0.0]; cd = [0.0]
    for i in range(len(tk)):
        c1.append(c1[-1] + e1[i]); c5.append(c5[-1] + e5[i]); cd.append(cd[-1] + depth[i])
    start = int(datetime.fromisoformat(f"{d} 09:20:00").replace(tzinfo=IST).timestamp() * 1000)
    end = int(datetime.fromisoformat(f"{d} 15:15:00").replace(tzinfo=IST).timestamp() * 1000)
    out = []
    for s in range(start, end + 1, 15000):
        i = bisect.bisect_right(t, s) - 1               # last tick at or before s
        j = bisect.bisect_right(t, s - 30000) - 1        # last tick at or before s - 30 s
        if i < 1 or j < 0 or i - j < 3:
            continue
        n = i - j
        mean_depth = (cd[i + 1] - cd[j + 1]) / n
        if mean_depth <= 0:
            continue
        ofi = (c1[i + 1] - c1[j + 1]) / mean_depth
        ofi5 = (c5[i + 1] - c5[j + 1]) / mean_depth
        m0 = mid[i]
        fut = {}
        for h in (30, 60, 180):
            k = bisect.bisect_right(t, s + h * 1000) - 1
            fut[h] = (mid[k] / m0 - 1) * 1e4 if k > i else None
        if fut[60] is None:
            continue
        out.append({"u": u, "d": d, "ofi": ofi, "ofi5": ofi5, "r30": fut[30], "r60": fut[60], "r180": fut[180],
                    "spread": (tk[i][3][0] - tk[i][1][0]) / m0 * 1e4})
    # strong = top 20 % of |OFI30| within the index-day
    cut = sorted(abs(x["ofi"]) for x in out)[int(0.8 * (len(out) - 1))] if out else 0
    cut5 = sorted(abs(x["ofi5"]) for x in out)[int(0.8 * (len(out) - 1))] if out else 0
    for x in out:
        x["strong"] = abs(x["ofi"]) >= cut and x["ofi"] != 0
        x["strong5"] = abs(x["ofi5"]) >= cut5 and x["ofi5"] != 0
    return out


def mean(xs):
    xs = [x for x in xs if x is not None]
    return statistics.mean(xs) if xs else float("nan")


def signed(xs, key, fkey="ofi"):
    return [(1 if x[fkey] > 0 else -1) * x[key] for x in xs if x[key] is not None and x[fkey] != 0]


def boot(xs, key, fkey="ofi", n=10000, seed=165):
    by = defaultdict(list)
    for x in xs:
        if x[key] is not None and x[fkey] != 0:
            by[(x["d"])].append((1 if x[fkey] > 0 else -1) * x[key])
    keys = list(by); rng = random.Random(seed); ms = []
    for _ in range(n):
        vals = [v for k in (rng.choice(keys) for _ in keys) for v in by[k]]
        ms.append(sum(vals) / len(vals))
    ms.sort()
    return ms[int(0.025 * n)], ms[int(0.975 * n)]


def corr(a, b):
    ma, mb = mean(a), mean(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = (sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b)) ** 0.5
    return num / den if den else float("nan")


def line(label, xs, key="r60", fkey="ofi"):
    v = signed(xs, key, fkey)
    lo, hi = boot(xs, key, fkey)
    return f"  {label:34s} n={len(v):6d} mean {mean(v):+6.2f} bp  hit {sum(1 for x in v if x > 0) / max(1, len(v)):.0%}  CI95 [{lo:+.2f}, {hi:+.2f}]"


def main():
    allx = []
    for u in ("NIFTY", "SENSEX"):
        for d in DAYS:
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            allx += samples(u, d)
    print(file=sys.stderr)
    strong = [x for x in allx if x["strong"]]
    print(f"samples {len(allx)} on {len({x['d'] for x in allx})} days; median futures spread {statistics.median(x['spread'] for x in allx):.2f} bp")
    print(line("strong OFI30 -> next 60 s [primary]", strong))
    print(line("  half 28 Sep-8 Oct", [x for x in strong if x["d"] <= HALF]))
    print(line("  half 9-21 Oct", [x for x in strong if x["d"] > HALF]))
    print(line("  NIFTY", [x for x in strong if x["u"] == "NIFTY"]))
    print(line("  SENSEX", [x for x in strong if x["u"] == "SENSEX"]))
    print(line("strong -> next 30 s", strong, "r30"))
    print(line("strong -> next 180 s", strong, "r180"))
    print(line("all samples -> next 60 s", allx))
    print(line("strong 5-level OFI -> next 60 s", [x for x in allx if x["strong5"]], "r60", "ofi5"))
    print(f"  corr(OFI30, next 60 s) all: {corr([x['ofi'] for x in allx], [x['r60'] for x in allx]):+.3f}; "
          f"contemporaneous check corr(OFI30, last 30 s mid change) not computed (outcome is forward only)")
    m = mean(signed(strong, "r60")); lo, _ = boot(strong, "r60")
    h1 = mean(signed([x for x in strong if x["d"] <= HALF], "r60")); h2 = mean(signed([x for x in strong if x["d"] > HALF], "r60"))
    c1 = m > 0 and lo > 0; c2 = h1 > 0 and h2 > 0; c3 = m >= 1.0
    for name, ok in (("(1) mean > 0, CI low > 0", c1), ("(2) both halves > 0", c2), ("(3) mean >= 1.0 bp", c3)):
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    print("A-165:", "PASS" if c1 and c2 and c3 else "FAIL")


if __name__ == "__main__":
    main()
