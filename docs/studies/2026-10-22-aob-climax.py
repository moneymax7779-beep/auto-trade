#!/usr/bin/env python3
"""A-161 OI climax exit for aob (definitions and criteria fixed in the ledger before this ran).

aob's entries (A-157 rule, one position per index, 15 minutes between same-direction entries) simulated on own-capture
option last traded prices, as the A-158 exit study. Exits: E3 = aob-v2 (premium trail +20 % / 15 %, 30 % stop, 15:15);
E5a = E3 + the climax exit; E5b = climax + 30 % stop + 15:15. Usage: python3 2026-10-22-aob-climax.py
"""
import importlib.util, statistics
spec = importlib.util.spec_from_file_location("a157", __file__.replace("2026-10-22-aob-climax.py", "2026-10-21-atm-oi-burst.py"))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
a.LAST = 15 * 60 + 15
ENTRY_LAST, FLAT = 14 * 60 + 45, 15 * 60 + 15
COUNTED = ["2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14",
           "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21"]
HALVES = (COUNTED[:5], COUNTED[5:])
DESIGN = ["2026-10-01"]


def driving(data):
    """Per minute m: (call share3, put share3, mean |call share3| so far, mean |put share3| so far) over ATM +- 2."""
    minutes = list(range(9 * 60 + 14, 15 * 60 + 30))
    strikes = sorted({k for (_, k) in data["opt"]})
    step = min(b - c for c, b in zip(strikes, strikes[1:]) if b > c)
    oi = {key: a.carried(s, minutes) for key, s in data["opt"].items()}
    close = a.carried({m: v[2] for m, v in data["idx"].items()}, minutes)
    seen = {"CE": [], "PE": []}
    out = {}
    for m in range(9 * 60 + 16, FLAT + 1):
        c = close.get(m)
        if c is None:
            continue
        atm = round(c / step) * step
        band = [atm + i * step for i in range(-2, 3)]
        tot = 0.0; chg = {"CE": 0.0, "PE": 0.0}; ok = True
        for k in band:
            for ty in ("CE", "PE"):
                s = oi.get((ty, k))
                x0 = s.get(m - 3) if s else None; x1 = s.get(m) if s else None
                if x0 is None or x1 is None:
                    ok = False; break
                tot += x0; chg[ty] += x1 - x0
            if not ok:
                break
        if not ok or tot <= 0:
            continue
        shares = {ty: chg[ty] / tot for ty in chg}
        means = {ty: statistics.mean(seen[ty]) if len(seen[ty]) >= 10 else None for ty in seen}
        out[m] = (shares["CE"], shares["PE"], means["CE"], means["PE"])
        for ty in seen:
            seen[ty].append(abs(shares[ty]))
    return out


def simulate(feats, data, kind):
    trades = []
    for (u, d), rows in feats.items():
        dd = data[(u, d)]
        px, idx = dd["px"], dd["idx"]
        strikes = sorted({k for (_, k) in dd["opt"]})
        step = min(b - c for c, b in zip(strikes, strikes[1:]) if b > c)
        drive = driving(dd)
        closes = a.carried({k: v[2] for k, v in idx.items()}, list(range(9 * 60 + 14, 15 * 60 + 30)))
        last_entry = {1: -999, -1: -999}
        pos = None
        for m in sorted(rows):
            share, rel, brk, ratio, chg = rows[m][3]
            if pos:
                sign, key, entry, t0, peak, extreme = pos
                p = a.carried(px.get(key, {}), [m])[m]
                if p:
                    peak = max(peak, p)
                reason = None
                if p and p <= entry * 0.70:
                    trades.append((u, d, t0, m, sign, p / entry - 1, "PREMIUM_STOP")); pos = None; continue
                window = [idx[x] for x in range(m - 2, m + 1) if x in idx]
                new_extreme = False
                if window:
                    lo, hi = min(w[1] for w in window), max(w[0] for w in window)
                    new_extreme = lo < extreme if sign < 0 else hi > extreme
                    extreme = min(extreme, lo) if sign < 0 else max(extreme, hi)
                pos = (sign, key, entry, t0, peak, extreme)
                if m >= FLAT:
                    reason = "FLAT_BY"
                elif kind in ("E5a", "E5b") and m - t0 >= 10 and p and p > entry and m in drive:
                    ce, pe, mce, mpe = drive[m]
                    own, mean = (ce, mce) if sign < 0 else (pe, mpe)
                    if mean and own >= 3 * mean and new_extreme:
                        reason = "OI_CLIMAX"
                if reason is None and kind in ("E3", "E5a") and peak >= entry * 1.20 and p and p <= peak * 0.85:
                    reason = "TRAIL"
                if reason:
                    q = a.carried(px.get(key, {}), [m + 1])[m + 1] or p
                    trades.append((u, d, t0, m, sign, q / entry - 1, reason)); pos = None
                continue
            if a.FIRST <= m <= ENTRY_LAST and share is not None and rel is not None and rel >= 3 and ratio is not None and ratio >= 1.5:
                sign = 1 if share > 0 else -1
                if m - last_entry[sign] < 15:
                    continue
                e_spot = closes.get(m + 1)
                if not e_spot:
                    continue
                key = ("CE" if sign > 0 else "PE", round(e_spot / step) * step)
                entry = a.carried(px.get(key, {}), [m + 1])[m + 1]
                if not entry:
                    continue
                last_entry[sign] = m
                first = idx.get(m + 1)
                extreme = (first[1] if sign < 0 else first[0]) if first else e_spot
                pos = (sign, key, entry, m + 1, entry, extreme)
        if pos:
            sign, key, entry, t0, peak, extreme = pos
            q = a.carried(px.get(key, {}), [FLAT])[FLAT] or entry
            trades.append((u, d, t0, FLAT, sign, q / entry - 1, "FLAT_BY"))
    return trades


def mean_pct(tr):
    return statistics.mean(t[5] * 100 for t in tr) if tr else float("nan")


def summary(tr):
    if not tr:
        return "n=0"
    r = [t[5] * 100 for t in tr]
    win = sum(x for x in r if x > 0); loss = -sum(x for x in r if x < 0)
    reasons = {}
    for t in tr:
        reasons[t[6]] = reasons.get(t[6], 0) + 1
    return (f"n={len(r):3d} mean {statistics.mean(r):+6.1f} %  hit {sum(1 for x in r if x > 0) / len(r):.0%}  "
            f"PF {win / loss if loss else float('inf'):.2f}  {reasons}")


if __name__ == "__main__":
    feats = a.build(COUNTED)
    data = {(u, d): a.day(u, d) for (u, d) in feats}
    res = {k: simulate(feats, data, k) for k in ("E3", "E5a", "E5b")}
    print("== 12 counted days")
    for k, tr in res.items():
        print(f"  {k}: {summary(tr)}")
    per_day = {}
    for d in COUNTED:
        e3 = [t for t in res["E3"] if t[1] == d]; e5 = [t for t in res["E5a"] if t[1] == d]
        per_day[d] = (sum(t[5] for t in e5) - sum(t[5] for t in e3)) * 100
    gain = mean_pct(res["E5a"]) - mean_pct(res["E3"])
    best = max(per_day, key=lambda d: per_day[d])
    without_e5 = [t for t in res["E5a"] if t[1] != best]; without_e3 = [t for t in res["E3"] if t[1] != best]
    gain_wo = mean_pct(without_e5) - mean_pct(without_e3)
    print(f"\nE5a - E3 mean per trade: {gain:+.1f} pp; largest-gain day {best} ({per_day[best]:+.1f} pp summed); without it {gain_wo:+.1f} pp")
    halves = []
    for h in HALVES:
        e3 = [t for t in res["E3"] if t[1] in h]; e5 = [t for t in res["E5a"] if t[1] in h]
        halves.append(mean_pct(e5) - mean_pct(e3))
        print(f"  half {h[0]}..{h[-1]}: E3 {mean_pct(e3):+.1f} %, E5a {mean_pct(e5):+.1f} %  ({halves[-1]:+.1f} pp)")
    ok = gain > 0 and gain_wo >= 0 and all(x >= 0 for x in halves)
    print(f"\nA-161: {'PASS' if ok else 'FAIL'}")
    print("\nclimax exits (E5a) and the E3 exit of the same trade:")
    e3_by = {(t[0], t[1], t[2]): t for t in res["E3"]}
    for t in res["E5a"]:
        if t[6] == "OI_CLIMAX":
            o = e3_by.get((t[0], t[1], t[2]))
            other = f"E3 {o[3] // 60:02d}:{o[3] % 60:02d} {o[5] * 100:+.1f} % {o[6]}" if o else "E3: different trade path"
            print(f"   {t[0]} {t[1]} in {t[2] // 60:02d}:{t[2] % 60:02d} {'CE' if t[4] > 0 else 'PE'} climax {t[3] // 60:02d}:{t[3] % 60:02d} {t[5] * 100:+.1f} %  | {other}")
    df = a.build(DESIGN)
    dd = {(u, d): a.day(u, d) for (u, d) in df}
    print("\n== 1 Oct (design day, not counted)")
    for k in ("E3", "E5a", "E5b"):
        tr = simulate(df, dd, k)
        print(f"  {k}: {summary(tr)}  " + "; ".join(f"{t[0]} {t[2] // 60:02d}:{t[2] % 60:02d}-{t[3] // 60:02d}:{t[3] % 60:02d} {t[5] * 100:+.0f}% {t[6]}" for t in tr))
