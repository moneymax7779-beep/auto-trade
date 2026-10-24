#!/usr/bin/env python3
"""A-193 late expiry short strangle (definitions and criteria fixed in the ledger before this ran).

At 15:24:00 sell the first OTM call and put of the expiring series; buy back at 15:29:30 (S0), or once the combined ask
>= 2 x the credit (S2). P&L in % of the credit. Usage: python3 2026-10-24-expiry-short-strangle.py
"""
import importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m
x = load("a189", "2026-10-24-expiry-close-squeeze.py")
y = load("a190", "2026-10-24-expiry-spike-scalp.py")
S_IN, END = 15 * 3600 + 24 * 60, 15 * 3600 + 29 * 60 + 30
TICK_SET = {d for d, _ in x.TICK_DAYS}


def tick(d, u):
    ref, quotes, _ = x.tick_day(u, d)
    S = y.tick_spot(quotes, ref)
    if S_IN not in S:
        return None
    strikes = sorted({k for k, _ in quotes})
    kc, kp = x.first_otm(strikes, S[S_IN], 1), x.first_otm(strikes, S[S_IN], -1)
    c, p = quotes.get((kc, "CE")), quotes.get((kp, "PE"))
    if not c or not p or S_IN not in c or S_IN not in p:
        return None
    credit = c[S_IN][0] + p[S_IN][0]
    s0 = (credit - (c[END][1] + p[END][1])) / credit
    s2 = s0
    for t in range(S_IN + 1, END + 1):
        if t in c and t in p and c[t][1] + p[t][1] >= 2 * credit:
            s2 = (credit - (c[t][1] + p[t][1])) / credit
            break
    return s0, s2, kc, kp, credit


def bar(d, u):
    got = x.bar_day(u, d)
    if not got:
        return None
    ref, bars, closes, S = got
    m0 = x.mn("15:24")
    if m0 not in S:
        return None
    strikes = sorted({k for k, _ in bars})
    kc, kp = x.first_otm(strikes, S[m0], 1), x.first_otm(strikes, S[m0], -1)
    cc, pc = closes.get((kc, "CE")), closes.get((kp, "PE"))
    if not cc or not pc or m0 not in cc or m0 not in pc or x.M1529 not in cc or x.M1529 not in pc:
        return None
    credit = (cc[m0] + pc[m0]) * (1 - x.COST)
    s0 = (credit - (cc[x.M1529] + pc[x.M1529]) * (1 + x.COST)) / credit
    s2 = s0
    for m in range(m0 + 1, x.M1529 + 1):
        back = (cc.get(m, 0) + pc.get(m, 0)) * (1 + x.COST)
        if back >= 2 * credit:
            s2 = (credit - back) / credit
            break
    return s0, s2, kc, kp, credit / (1 - x.COST)


def summary(name, rows):
    for i, lab in ((0, "S0 hold"), (1, "S2 stop 2x")):
        xs = [r[i] for r in rows]
        print(f"  {name} {lab}: n={len(xs)} mean {statistics.mean(xs):+.1%} of credit, median {statistics.median(xs):+.1%}, win {sum(v > 0 for v in xs) / len(xs):.0%}, "
              f"worst {min(xs):+.0%}, best {max(xs):+.0%}")


if __name__ == "__main__":
    print("== tick days (own capture, in-sample)")
    rows = []
    for d, u in x.TICK_DAYS:
        r = tick(d, u)
        if r:
            rows.append(r)
            print(f"  {d} {u:6s} sell {int(r[2])} CE + {int(r[3])} PE for {r[4]:.2f}: S0 {r[0]:+.0%}  S2 {r[1]:+.0%}")
    summary("ticks", rows)
    print("\n== bar days Aug-Sep 2026 (new regime, not tick days, not held-out)")
    rows = []
    for d, u in x.expiry_days("2026-08-01", "2026-09-30"):
        if d in TICK_SET:
            continue
        r = bar(d, u)
        if r:
            rows.append(r)
            print(f"  {d} {u:6s} sell {int(r[2])} CE + {int(r[3])} PE for {r[4]:.2f}: S0 {r[0]:+.0%}  S2 {r[1]:+.0%}")
    summary("bars Aug-Sep", rows)
    print("\n== old regime bar days Oct 2024-Jun 2026 (descriptive)")
    rows = [r for r in (bar(d, u) for d, u in x.expiry_days("2024-10-01", "2026-06-30")) if r]
    summary("old regime", rows)
