#!/usr/bin/env python3
"""A-283 late expiry one-sided ATM buy, +30 % target / -50 % stop (definitions and criteria fixed in the ledger before this ran).

Expiring index on its expiry day; synthetic spot S (ATM strike + call - put, bid/ask mids, per second). From 15:20:00 to
15:28:00 the first second S moved >= 0.10 % against S 60 s earlier; buy the ATM option that way (the strike nearest S) at the
next second's ask; sell at the bid when it is >= +30 % or <= -50 % of the entry, else at the bid at 15:29:30.
Usage: python3 2026-10-30-expiry-atm-30.py   (the 8 own tick expiry days 29 Sep - 22 Oct, in-sample; the days after 23 Oct
are frozen for the A-194 / A-283 forward read and are not in the list)
"""
import importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a189", os.path.join(HERE, "2026-10-24-expiry-close-squeeze.py"))
x = importlib.util.module_from_spec(spec)
spec.loader.exec_module(x)
MOVE, TARGET, STOP = 0.0010, 0.30, 0.50
S0, S1, END = 15 * 3600 + 20 * 60, 15 * 3600 + 28 * 60, 15 * 3600 + 29 * 60 + 30
import sys
if "--days" in sys.argv:          # 30 Oct amendment (user: "run them now"): 27 Oct NIFTY and 29 Oct SENSEX, removed from the forward sample
    x.TICK_DAYS = [tuple(a.split(":")) for a in sys.argv[sys.argv.index("--days") + 1].split(",")]
    assert all(d in ("2026-10-27", "2026-10-29") for d, _ in x.TICK_DAYS), "only the two days the user released on 30 Oct"
assert all(d <= "2026-10-23" or d in ("2026-10-27", "2026-10-29") for d, _ in x.TICK_DAYS), "A-194 / A-283 forward days must not be read here"


def tick_spot(quotes, ref):
    """Synthetic spot per second from 15:19:00 (so the 60 s look-back exists at 15:20:00)."""
    strikes = sorted({k for k, _ in quotes})
    S = {}
    for s in range(S0 - 60, END + 1):
        for k in sorted(strikes, key=lambda k: abs(k - ref)):
            c, p = quotes.get((k, "CE"), {}).get(s), quotes.get((k, "PE"), {}).get(s)
            if c and p:
                S[s] = ref = k + (c[0] + c[1]) / 2 - (p[0] + p[1]) / 2
                break
    return S


def hms(s):
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


rows = []
print("A-283, the 8 own tick expiry days (in-sample, descriptive): trigger 15:20-15:28, ATM, +30 % / -50 %, else 15:29:30")
for d, u in x.TICK_DAYS:
    ref, quotes, _ = x.tick_day(u, d)
    S = tick_spot(quotes, ref)
    trig = next(((s, 1 if S[s] > S[s - 60] else -1) for s in range(S0, S1 + 1)
                 if s in S and s - 60 in S and abs(S[s] / S[s - 60] - 1) >= MOVE), None)
    if not trig:
        print(f"  {d} {u:6s} no trigger")
        continue
    s, sign = trig
    ty = "CE" if sign > 0 else "PE"
    strikes = sorted({k for k, t in quotes if t == ty})
    k = min(strikes, key=lambda k: abs(k - S[s]))
    v = quotes.get((k, ty), {})
    if s + 1 not in v:
        print(f"  {d} {u:6s} trigger {hms(s)} but no quote")
        continue
    entry = v[s + 1][1]
    res = None
    for t in range(s + 2, END + 1):
        if t in v and (v[t][0] >= entry * (1 + TARGET) or v[t][0] <= entry * (1 - STOP)):
            res = (v[t][0] / entry - 1, "target" if v[t][0] >= entry else "stop", t)
            break
    res = res or (v[END][0] / entry - 1, "15:29:30", END)
    peak = max(v[t][0] for t in range(s + 1, END + 1) if t in v) / entry - 1
    low = min(v[t][0] for t in range(s + 1, END + 1) if t in v) / entry - 1
    rows.append(res[0])
    print(f"  {d} {u:6s} {hms(s)} {'up  ' if sign > 0 else 'down'} S {S[s]:.1f} buy {ty} {int(k)} @ {entry:.2f} -> "
          f"{res[0]:+.1%} ({res[1]} {hms(res[2])}) | after entry: peak bid {peak:+.0%}, lowest bid {low:+.0%}")
if rows:
    print(f"\n{len(rows)} trades: mean {statistics.mean(rows):+.1%}, median {statistics.median(rows):+.1%}, "
          f"win {sum(r > 0 for r in rows)}/{len(rows)}, targets {sum(r >= TARGET - 1e-9 for r in rows)}, "
          f"stops {sum(r <= -STOP + 1e-9 for r in rows)}")
