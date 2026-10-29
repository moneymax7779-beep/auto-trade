#!/usr/bin/env python3
"""A-248 (a): how often the 3-minute futures OI state differs between the near month alone (features v12) and the sum
of the listed months (v16), on NIFTY 1-minute bars Oct 2024 - Oct 2026 for the days that have both months' bars.

Classifier as OiState.classify with v12's thresholds: |price change| >= 0.10 x spot ATR(3m) and |OI change| >= 0.05 % of
OI, else NEUTRAL; price up + OI up = FRESH_LONG, up + down = SHORT_COVERING, down + up = FRESH_SHORT, down + down =
LONG_UNWIND. Price = the near-month future; spot ATR(3m) = Wilder ATR14 of 3-minute index bars from 09:15.
"""
import csv, io, subprocess
from collections import Counter, defaultdict

IST = "at time zone 'Asia/Kolkata'"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True,
                         stdin=subprocess.DEVNULL).stdout
    return list(csv.reader(io.StringIO(out)))


def classify(dp, doi, min_p, min_oi):
    if abs(dp) < min_p or abs(doi) < min_oi:
        return "NEUTRAL"
    if dp > 0:
        return "FRESH_LONG" if doi > 0 else "SHORT_COVERING"
    return "FRESH_SHORT" if doi > 0 else "LONG_UNWIND"


fut = defaultdict(lambda: defaultdict(dict))       # day -> expiry -> minute -> (close, oi)
for d, t, e, c, oi in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), expiry::text, close, oi "
                        f"from hist.candle where kind = 'FUTURE' and underlying = 'NIFTY' and bar_start >= '2024-10-01' "
                        f"and oi > 0"):
    fut[d][e][int(t[:2]) * 60 + int(t[3:])] = (float(c), float(oi))
idx = defaultdict(dict)
for d, t, h, l, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), high, low, close from "
                       f"hist.candle where kind = 'INDEX' and underlying = 'NIFTY' and bar_start >= '2024-10-01'"):
    idx[d][int(t[:2]) * 60 + int(t[3:])] = (float(h), float(l), float(c))

trading_days = sorted(idx)
by_dte = defaultdict(Counter)
flips = Counter()
days_used = 0
for d in sorted(fut):
    exps = sorted(e for e in fut[d] if e >= d)
    if len(exps) < 2 or d not in idx:
        continue
    near, nxt = exps[0], exps[1]
    days_used += 1
    dte = sum(1 for x in trading_days if d < x <= near)               # sessions to the near month's expiry
    bars = idx[d]
    atr, prev, trs, atr_at = None, None, [], {}
    ms = sorted(bars)
    for i in range(0, len(ms) - 2, 3):
        g = [bars[m] for m in ms[i:i + 3]]
        h, l = max(b[0] for b in g), min(b[1] for b in g)
        tr = h - l if prev is None else max(h - l, abs(h - prev), abs(l - prev))
        prev = g[-1][2]
        trs.append(tr)
        atr = sum(trs) / 14 if len(trs) == 14 else (atr * 13 + tr) / 14 if len(trs) > 14 else None
        atr_at[ms[i + 2] + 1] = atr
    a = None
    for m in range(558, 930):
        a = atr_at.get(m, a)
        n0, n3 = fut[d][near].get(m), fut[d][near].get(m - 3)
        x0, x3 = fut[d][nxt].get(m), fut[d][nxt].get(m - 3)
        if not (n0 and n3 and x0 and x3 and a):
            continue
        dp = n0[0] - n3[0]
        s_near = classify(dp, n0[1] - n3[1], 0.10 * a, 0.0005 * n0[1])
        s_comb = classify(dp, (n0[1] + x0[1]) - (n3[1] + x3[1]), 0.10 * a, 0.0005 * (n0[1] + x0[1]))
        bucket = min(dte, 8)
        by_dte[bucket]["minutes"] += 1
        by_dte[bucket]["differ"] += s_near != s_comb
        by_dte[bucket]["near_unwind_or_cover"] += s_near in ("LONG_UNWIND", "SHORT_COVERING")
        by_dte[bucket]["comb_unwind_or_cover"] += s_comb in ("LONG_UNWIND", "SHORT_COVERING")
        if s_near != s_comb:
            flips[(s_near, s_comb)] += 1

print(f"NIFTY days with both months' futures bars: {days_used}")
print("sessions to near expiry | minutes | states differ | near-only says unwinding/covering | sum says unwinding/covering")
for k in sorted(by_dte):
    c = by_dte[k]
    n = c["minutes"]
    print(f"  {('>= 8' if k == 8 else k):>5} | {n:6d} | {c['differ'] / n:6.1%} | {c['near_unwind_or_cover'] / n:6.1%} | "
          f"{c['comb_unwind_or_cover'] / n:6.1%}")
print("most common changes (near-only -> sum):")
for (a_, b_), n in flips.most_common(8):
    print(f"  {a_:15s} -> {b_:15s} {n:6d}")
