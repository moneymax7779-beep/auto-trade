#!/usr/bin/env python3
"""A-192 late expiry strangle with a +20 % combined target (definitions and criteria fixed in the ledger before this ran).

At the entry time buy the first OTM call and put of the expiring series (from the synthetic spot); sell both when the
combined bid >= 1.20 x the combined cost, else at 15:29:30 (bars: 15:29 closes). No stop.
Usage: python3 2026-10-24-expiry-strangle-20.py
"""
import importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a189", os.path.join(HERE, "2026-10-24-expiry-close-squeeze.py"))
x = importlib.util.module_from_spec(spec)
spec.loader.exec_module(x)
spec2 = importlib.util.spec_from_file_location("a190", os.path.join(HERE, "2026-10-24-expiry-spike-scalp.py"))
y = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(y)
TARGET = 0.20
END = 15 * 3600 + 29 * 60 + 30
TICK_SET = {d for d, _ in x.TICK_DAYS}


def tick(d, u, hhmm):
    ref, quotes, _ = x.tick_day(u, d)
    S = y.tick_spot(quotes, ref)
    s0 = 15 * 3600 + int(hhmm[3:]) * 60 if hhmm.startswith("15") else None
    if s0 not in S:
        return None
    strikes = sorted({k for k, _ in quotes})
    kc, kp = x.first_otm(strikes, S[s0], 1), x.first_otm(strikes, S[s0], -1)
    c, p = quotes.get((kc, "CE")), quotes.get((kp, "PE"))
    if not c or not p or s0 not in c or s0 not in p:
        return None
    cost = c[s0][1] + p[s0][1]
    for t in range(s0 + 1, END + 1):
        if t in c and t in p and c[t][0] + p[t][0] >= cost * (1 + TARGET):
            return (c[t][0] + p[t][0]) / cost - 1, "target", t, kc, kp, cost
    return (c[END][0] + p[END][0]) / cost - 1, "15:29:30", END, kc, kp, cost


def bar(d, u, hhmm):
    got = x.bar_day(u, d)
    if not got:
        return None
    ref, bars, closes, S = got
    m0 = x.mn(hhmm)
    if m0 not in S:
        return None
    strikes = sorted({k for k, _ in bars})
    kc, kp = x.first_otm(strikes, S[m0], 1), x.first_otm(strikes, S[m0], -1)
    cc, pc = closes.get((kc, "CE")), closes.get((kp, "PE"))
    if not cc or not pc or m0 not in cc or m0 not in pc:
        return None
    cost = (cc[m0] + pc[m0]) * (1 + x.COST)
    for m in range(m0 + 1, x.M1529 + 1):
        v = (cc.get(m, 0) + pc.get(m, 0)) * (1 - x.COST)
        if v >= cost * (1 + TARGET) or m == x.M1529:
            return v / cost - 1, "target" if v >= cost * (1 + TARGET) else "15:29", m, kc, kp, cost / (1 + x.COST)
    return None


def summary(name, rows):
    xs = [r[0] for r in rows]
    if not xs:
        print(f"  {name}: none")
        return
    print(f"  {name}: n={len(xs)} mean {statistics.mean(xs):+.1%} median {statistics.median(xs):+.1%} win {sum(v > 0 for v in xs) / len(xs):.0%} "
          f"targets {sum(r[1] == 'target' for r in rows)} worst {min(xs):+.0%}")


if __name__ == "__main__":
    print("== tick days (own capture, in-sample)")
    for hhmm in ("15:24", "15:22", "15:26"):
        rows = []
        for d, u in x.TICK_DAYS:
            r = tick(d, u, hhmm)
            if r:
                rows.append(r)
                if hhmm == "15:24":
                    t = r[2]
                    print(f"  {d} {u:6s} buy {int(r[3])} CE + {int(r[4])} PE for {r[5]:.2f} -> {r[0]:+.0%} ({r[1]} {t // 3600:02d}:{t % 3600 // 60:02d}:{t % 60:02d})")
        summary(f"entry {hhmm}{'' if hhmm == '15:24' else ' (descriptive)'}", rows)
    print("\n== bar days Aug-Sep 2026 (new regime, not tick days, not held-out)")
    days = [(d, u) for d, u in x.expiry_days("2026-08-01", "2026-09-30") if d not in TICK_SET]
    for hhmm in ("15:24", "15:22", "15:26"):
        rows = []
        for d, u in days:
            r = bar(d, u, hhmm)
            if r:
                rows.append(r)
                if hhmm == "15:24":
                    print(f"  {d} {u:6s} buy {int(r[3])} CE + {int(r[4])} PE for {r[5]:.2f} -> {r[0]:+.0%} ({r[1]} {r[2] // 60}:{r[2] % 60:02d})")
        summary(f"entry {hhmm}{'' if hhmm == '15:24' else ' (descriptive)'}", rows)
    print("\n== old regime bar days Oct 2024-Jun 2026 (descriptive)")
    rows = [r for r in (bar(d, u, "15:24") for d, u in x.expiry_days("2024-10-01", "2026-06-30")) if r]
    summary("entry 15:24", rows)
