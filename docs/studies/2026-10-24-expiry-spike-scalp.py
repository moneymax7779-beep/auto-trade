#!/usr/bin/env python3
"""A-190 late expiry spike scalp (definitions and criteria fixed in the ledger before this ran).

Expiring index on its expiry day; synthetic spot S (ATM strike + call - put). From 15:24:00 to 15:28:00 the first time S
moved >= 0.10 % in 60 s (ticks: every second; bars: the 1-minute close-to-close move of 15:24-15:27), buy the first OTM
strike that way. Exits X50 (+50 % / -50 %) and X100 (+100 % / -50 %), else the bid at 15:29:30 (bars: 15:29 close).
Usage: python3 2026-10-24-expiry-spike-scalp.py
"""
import importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a189", os.path.join(HERE, "2026-10-24-expiry-close-squeeze.py"))
x = importlib.util.module_from_spec(spec)
spec.loader.exec_module(x)
MOVE = 0.0010
EXITS = {"X50": (0.50, 0.50), "X100": (1.00, 0.50)}
S0, S1, END = 15 * 3600 + 24 * 60, 15 * 3600 + 28 * 60, 15 * 3600 + 29 * 60 + 30
TICK_SET = {d for d, _ in x.TICK_DAYS}


def tick_spot(quotes, ref):
    """Synthetic spot per second from bid/ask mids, strike nearest the previous value with both sides quoted."""
    strikes = sorted({k for k, _ in quotes})
    S = {}
    for s in range(15 * 3600 + 20 * 60, END + 1):
        for k in sorted(strikes, key=lambda k: abs(k - ref)):
            c, p = quotes.get((k, "CE"), {}).get(s), quotes.get((k, "PE"), {}).get(s)
            if c and p:
                S[s] = ref = k + (c[0] + c[1]) / 2 - (p[0] + p[1]) / 2
                break
    return S


def run_tick(d, u):
    ref, quotes, _ = x.tick_day(u, d)
    S = tick_spot(quotes, ref)
    trig = next(((s, 1 if S[s] > S[s - 60] else -1) for s in range(S0, S1 + 1)
                 if s in S and s - 60 in S and abs(S[s] / S[s - 60] - 1) >= MOVE), None)
    if not trig:
        return None, None
    s, sign = trig
    strikes = sorted({k for k, _ in quotes})
    k = x.first_otm(strikes, S[s], sign)
    v = quotes.get((k, "CE" if sign > 0 else "PE"))
    if not v or s + 1 not in v:
        return trig, None
    entry = v[s + 1][1]
    out = {"k": k, "ty": "CE" if sign > 0 else "PE", "entry": entry, "t": s}
    for name, (tgt, stp) in EXITS.items():
        res = None
        for t in range(s + 2, END + 1):
            if t not in v:
                continue
            bd = v[t][0]
            if bd >= entry * (1 + tgt) or bd <= entry * (1 - stp):
                res = (bd / entry - 1, "target" if bd >= entry else "stop", t)
                break
        out[name] = res or (v[END][0] / entry - 1, "15:29:30", END)
    pk = max(v[t][0] for t in range(s + 1, END + 1) if t in v)
    out["peak"] = pk / entry - 1
    return trig, out


def run_bar(d, u):
    got = x.bar_day(u, d)
    if not got:
        return None, None
    ref, bars, closes, S = got
    trig = next(((m, 1 if S[m] > S[m - 1] else -1) for m in range(x.mn("15:24"), x.mn("15:27") + 1)
                 if m in S and m - 1 in S and abs(S[m] / S[m - 1] - 1) >= MOVE), None)
    if not trig:
        return None, None
    m, sign = trig
    strikes = sorted({k for k, _ in bars})
    k = x.first_otm(strikes, S[m], sign)
    ty = "CE" if sign > 0 else "PE"
    b = bars.get((k, ty))
    if not b or m + 1 not in b:
        return trig, None
    entry = b[m + 1][2] * (1 + x.COST)
    out = {"k": k, "ty": ty, "entry": b[m + 1][2], "t": m}
    for name, (tgt, stp) in EXITS.items():
        res = None
        for t in range(m + 2, x.M1529 + 1):
            if t not in b:
                continue
            h, l, c = b[t]
            if l <= entry * (1 - stp):
                res = (-stp - x.COST, "stop", t); break
            if h >= entry * (1 + tgt):
                res = (tgt - x.COST, "target", t); break
        last = closes[(k, ty)].get(x.M1529)
        out[name] = res or (last * (1 - x.COST) / entry - 1, "15:29", x.M1529)
    return trig, out


def summarize(name, rows):
    print(f"\n== {name}: {len(rows)} trades")
    for ex in EXITS:
        xs = [r[ex][0] for r in rows]
        if xs:
            print(f"  {ex}: mean {statistics.mean(xs):+.1%} median {statistics.median(xs):+.1%} win {sum(v > 0 for v in xs) / len(xs):.0%} "
                  f"targets {sum(r[ex][1] == 'target' for r in rows)} stops {sum(r[ex][1] == 'stop' for r in rows)}")


def hms(s):
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


if __name__ == "__main__":
    rows = []
    print("tick days (own capture, in-sample):")
    for d, u in x.TICK_DAYS:
        trig, r = run_tick(d, u)
        if not trig:
            print(f"  {d} {u:6s} no trigger"); continue
        if not r:
            print(f"  {d} {u:6s} trigger {hms(trig[0])} but no quote"); continue
        rows.append(r)
        print(f"  {d} {u:6s} {hms(trig[0])} {'up  ' if trig[1] > 0 else 'down'} buy {r['ty']} {int(r['k'])} @ {r['entry']:.2f} | peak bid {r['peak']:+.0%} | "
              f"X50 {r['X50'][0]:+.0%} ({r['X50'][1]} {hms(r['X50'][2])}) | X100 {r['X100'][0]:+.0%} ({r['X100'][1]} {hms(r['X100'][2])})")
    summarize("tick days", rows)
    rows = []
    print("\nbar days, new regime (Aug-Sep 2026, not tick days, not held-out):")
    for d, u in x.expiry_days("2026-08-01", "2026-09-30"):
        if d in TICK_SET:
            continue
        trig, r = run_bar(d, u)
        if not trig:
            print(f"  {d} {u:6s} no trigger"); continue
        if not r:
            print(f"  {d} {u:6s} trigger but no bar"); continue
        rows.append(r)
        print(f"  {d} {u:6s} {r['t'] // 60}:{r['t'] % 60:02d} {'up  ' if trig[1] > 0 else 'down'} buy {r['ty']} {int(r['k'])} @ {r['entry']:.2f} | "
              f"X50 {r['X50'][0]:+.0%} ({r['X50'][1]}) | X100 {r['X100'][0]:+.0%} ({r['X100'][1]})")
    summarize("bar days Aug-Sep 2026", rows)
