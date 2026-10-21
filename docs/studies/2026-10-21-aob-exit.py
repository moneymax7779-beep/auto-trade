#!/usr/bin/env python3
"""A-158 exit choice for aob-v1 (candidates and rule fixed in the ledger before this ran).

Entries exactly as aob-v1 (A-157's rule, one position per index, 15 minutes between same-direction entries); each candidate
exit simulated on own-capture option last traded prices (ATM strike fixed at entry; entry at the next minute's price, exits
at the price of the minute after the exit signal; the 30 % premium stop at the minute it is touched). Uses the A-157 minute
data (.local/a157). Usage: python3 2026-10-21-aob-exit.py
"""
import importlib.util, statistics
spec = importlib.util.spec_from_file_location("a157", __file__.replace("2026-10-21-aob-exit.py", "2026-10-21-atm-oi-burst.py"))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
a.LAST = 15 * 60 + 15                                   # rows to 15:15 for the exits; entries stay <= 14:45
ENTRY_LAST, FLAT = 14 * 60 + 45, 15 * 60 + 15
CANDIDATES = ("E1", "E2", "E3", "E4")


def price(px, key, m):
    p = a.carried(px.get(key, {}), [m])[m]
    return p


def simulate(feats, data, exit_kind):
    trades = []
    for (u, d), rows in feats.items():
        px = data[(u, d)]["px"]
        strikes = sorted({k for (_, k) in data[(u, d)]["opt"]})
        step = min(b - c for c, b in zip(strikes, strikes[1:]) if b > c)
        last_entry = {1: -999, -1: -999}
        pos = None
        for m in sorted(rows):
            share, rel, brk, ratio, chg = rows[m][3]
            if pos:
                sign, key, entry, t0, peak = pos
                p = price(px, key, m)
                if p:
                    peak = max(peak, p); pos = (sign, key, entry, t0, peak)
                reason = None
                if p and p <= entry * 0.70:
                    trades.append((u, d, t0, m, sign, p / entry - 1, "PREMIUM_STOP")); pos = None; continue
                if m >= FLAT:
                    reason = "FLAT_BY"
                elif exit_kind == "E0" and m >= t0 + 30:
                    reason = "TIME_30"
                elif share is not None and rel is not None and sign * share < 0 and (
                        (exit_kind in ("E1", "E4") and rel >= 1) or (exit_kind == "E2" and rel >= 3)):
                    reason = "OI_AGAINST" if exit_kind != "E2" else "OPPOSITE_BURST"
                elif exit_kind in ("E3", "E4") and peak >= entry * 1.20 and p and p <= peak * 0.85:
                    reason = "TRAIL"
                if reason:
                    q = price(px, key, m + 1) or p
                    trades.append((u, d, t0, m, sign, q / entry - 1, reason)); pos = None
                continue
            if a.FIRST <= m <= ENTRY_LAST and share is not None and rel is not None and rel >= 3 and ratio is not None and ratio >= 1.5:
                sign = 1 if share > 0 else -1
                if m - last_entry[sign] < 15:
                    continue
                e_spot = rows[m + 1]["ret30"] is not None and a.carried({k: v[2] for k, v in data[(u, d)]["idx"].items()}, [m + 1])[m + 1]
                if not e_spot:
                    continue
                key = ("CE" if sign > 0 else "PE", round(e_spot / step) * step)
                entry = price(px, key, m + 1)
                if not entry:
                    continue
                last_entry[sign] = m
                pos = (sign, key, entry, m + 1, entry)
        if pos:
            sign, key, entry, t0, peak = pos
            q = price(px, key, FLAT) or entry
            trades.append((u, d, t0, FLAT, sign, q / entry - 1, "FLAT_BY"))
    return trades


def summary(tr):
    if not tr:
        return "n=0"
    r = [t[5] * 100 for t in tr]
    win = sum(x for x in r if x > 0); loss = -sum(x for x in r if x < 0)
    hold = statistics.mean(t[3] - t[2] for t in tr)
    reasons = {}
    for t in tr:
        reasons[t[6]] = reasons.get(t[6], 0) + 1
    return (f"n={len(r):3d} mean {statistics.mean(r):+6.1f} %  hit {sum(1 for x in r if x > 0) / len(r):.0%}  "
            f"PF {win / loss if loss else float('inf'):.2f}  ~Rs {statistics.mean(r) / 100 * 250000:+,.0f}/trade  hold {hold:.0f} min  {reasons}")


def run(days, label, detail=False):
    feats = a.build(days)
    data = {(u, d): a.day(u, d) for (u, d) in feats}
    out = {}
    print(f"\n== {label}")
    for k in ("E0",) + CANDIDATES:
        tr = simulate(feats, data, k)
        out[k] = tr
        print(f"  {k}{' (reference)' if k == 'E0' else ''}: {summary(tr)}")
    return out, data


if __name__ == "__main__":
    sel, _ = run(a.SELECTION, "selection 1, 5-9 Oct (chooses the exit)")
    best = max(CANDIDATES, key=lambda k: statistics.mean([t[5] for t in sel[k]]) if sel[k] else -9)
    print(f"\nchosen exit: {best}")
    val, _ = run(a.VALIDATION, "12-19 Oct (second look, stated in the ledger)")
    des, _ = run(a.DESIGN, "21 Oct (apart)")
    for label, res in (("12-19 Oct", val), ("21 Oct", des)):
        print(f"\n{label} trades with {best}:")
        for t in res[best]:
            print(f"   {t[0]} {t[1]} {t[2] // 60:02d}:{t[2] % 60:02d}-{t[3] // 60:02d}:{t[3] % 60:02d} {'CE' if t[4] > 0 else 'PE'} {t[5] * 100:+.1f} % {t[6]}")
