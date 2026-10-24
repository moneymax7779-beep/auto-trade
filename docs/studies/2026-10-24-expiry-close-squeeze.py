#!/usr/bin/env python3
"""A-189 expiry close squeeze (definitions and criteria fixed in the ledger before this ran).

Expiring index on its expiry day. Synthetic spot S = ATM strike + call - put of the expiring series (the index value
freezes 15:15-15:28). At the close of each minute m, 15:20-15:27: trigger if S(m)/S(m-3)-1 >= +0.15 % and S(m) > S(15:15)
(call) or <= -0.15 % and S(m) < S(15:15) (put); first trigger of the day. Buy the first OTM strike that way.
Exit A: hold to 15:29 (bars: 15:29 close; ticks: bid at 15:29:30). Exit B: 30 % below the high since entry, else A.
Usage: python3 2026-10-24-expiry-close-squeeze.py bars | ticks
"""
import csv, io, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
MOVE, GIVE, COST = 0.0015, 0.30, 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
TICK_DAYS = [("2026-09-29", "NIFTY"), ("2026-10-01", "SENSEX"), ("2026-10-06", "NIFTY"), ("2026-10-08", "SENSEX"),
             ("2026-10-13", "NIFTY"), ("2026-10-15", "SENSEX"), ("2026-10-19", "NIFTY"), ("2026-10-22", "SENSEX")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mn(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


M1515, M1520, M1527, M1529 = mn("15:15"), mn("15:20"), mn("15:27"), mn("15:29")


def synthetic(prices, minutes, ref):
    """prices[(k, ty)][minute] = value (carried); S per minute from the strike nearest the previous S with both sides."""
    strikes = sorted({k for k, _ in prices})
    S = {}
    for m in minutes:
        best = None
        for k in sorted(strikes, key=lambda k: abs(k - ref)):
            c, p = prices.get((k, "CE"), {}).get(m), prices.get((k, "PE"), {}).get(m)
            if c is not None and p is not None:
                best = k + c - p
                break
        if best is not None:
            S[m] = ref = best
    return S


def carry(series, minutes):
    out, last = {}, None
    for m in minutes:
        if m in series:
            last = series[m]
        if last is not None:
            out[m] = last
    return out


def trigger(S):
    if M1515 not in S:
        return None
    for m in range(M1520, M1527 + 1):
        if m in S and m - 3 in S:
            r = S[m] / S[m - 3] - 1
            if r >= MOVE and S[m] > S[M1515]:
                return m, 1
            if r <= -MOVE and S[m] < S[M1515]:
                return m, -1
    return None


def first_otm(strikes, s, sign):
    return min((k for k in strikes if k > s), default=None) if sign > 0 else max((k for k in strikes if k < s), default=None)


# ---------------------------------------------------------------- bars

def bar_day(u, d):
    ix = q(f"select close from hist.candle where underlying = '{u}' and kind = 'INDEX' and bar_start = ('{d} 15:14+05:30')::timestamptz")
    if not ix:
        return None
    ref = float(ix[0][0])
    rows = q(f"select strike, option_type, to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' "
             f"and kind = 'OPTION' and expiry = '{d}' and bar_start >= ('{d} 15:10+05:30')::timestamptz "
             f"and bar_start <= ('{d} 15:29+05:30')::timestamptz and strike between {ref * 0.98} and {ref * 1.02}")
    bars = defaultdict(dict)
    for k, ty, t, h, l, c in rows:
        if c and float(c) > 0:
            bars[(float(k), ty)][mn(t)] = (float(h), float(l), float(c))
    minutes = list(range(mn("15:10"), M1529 + 1))
    closes = {key: carry({m: v[2] for m, v in s.items()}, minutes) for key, s in bars.items()}
    return ref, bars, closes, synthetic(closes, minutes, ref)


def bar_trade(bars, closes, S, m, sign):
    strikes = sorted({k for k, _ in bars})
    k = first_otm(strikes, S[m], sign)
    ty = "CE" if sign > 0 else "PE"
    b = bars.get((k, ty)) if k is not None else None
    if not b or m + 1 not in b:
        return None
    entry = b[m + 1][2] * (1 + COST)
    last = closes[(k, ty)].get(M1529)
    if last is None:
        return None
    a = last * (1 - COST) / entry - 1
    hi, res = entry, None
    for x in range(m + 2, M1529 + 1):
        if x not in b:
            continue
        h, l, c = b[x]
        if l <= hi * (1 - GIVE):
            res = hi * (1 - GIVE) * (1 - COST) / entry - 1
            break
        hi = max(hi, h)
    return {"A": a, "B": res if res is not None else a, "k": k, "ty": ty, "entry": entry / (1 + COST), "exit": last}


def baseline(bars, closes, S):
    m = mn("15:24")
    if m not in S:
        return None
    strikes = sorted({k for k, _ in bars})
    kc, kp = first_otm(strikes, S[m], 1), first_otm(strikes, S[m], -1)
    try:
        cost = (bars[(kc, "CE")][m + 1][2] + bars[(kp, "PE")][m + 1][2]) * (1 + COST)
        value = (closes[(kc, "CE")][M1529] + closes[(kp, "PE")][M1529]) * (1 - COST)
    except (KeyError, TypeError):
        return None
    return value / cost - 1


def expiry_days(lo, hi):
    return [(d, u) for u, d in q(f"select distinct underlying, expiry::text from hist.candle where kind = 'OPTION' "
                                 f"and underlying in ('NIFTY','SENSEX') and expiry between '{lo}' and '{hi}' order by 2, 1")
            if d not in HELD_OUT]


# ---------------------------------------------------------------- ticks

def tick_day(u, d):
    ix = q(f"select price from md.index_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
           f"and recv_ts < '{d} 15:15+05:30' order by recv_ts desc limit 1")
    ref = float(ix[0][0])
    rows = q(f"select strike, option_type, extract(epoch from time_bucket('1 second', recv_ts) - '{d} 00:00+05:30'::timestamptz)::int, "
             f"last(bid_px[1], recv_ts), last(ask_px[1], recv_ts) from md.option_tick where session_date = '{d}' and underlying = '{u}' "
             f"and expiry = '{d}' and recv_ts >= '{d} 15:10+05:30' and recv_ts < '{d} 15:30+05:30' "
             f"and strike between {ref * 0.98} and {ref * 1.02} group by 1, 2, 3")
    q2 = defaultdict(dict)
    for k, ty, s, bd, ak in rows:
        q2[(float(k), ty)][int(s)] = (float(bd) if bd else 0.0, float(ak) if ak else 0.0)
    secs = list(range(15 * 3600 + 10 * 60, 15 * 3600 + 30 * 60))
    quotes = {}
    for key, ser in q2.items():
        out, last = {}, None
        for s in secs:
            if s in ser and ser[s][0] > 0 and ser[s][1] > 0:
                last = ser[s]
            if last:
                out[s] = last
        quotes[key] = out
    # minute closes of the mid (second 59 of each minute)
    mids = {key: {m: (v[m * 60 + 59][0] + v[m * 60 + 59][1]) / 2 for m in range(mn("15:10"), M1529 + 1) if m * 60 + 59 in v}
            for key, v in quotes.items()}
    return ref, quotes, synthetic(mids, list(range(mn("15:10"), M1529 + 1)), ref)


def tick_trade(quotes, S, m, sign):
    strikes = sorted({k for k, _ in quotes})
    k = first_otm(strikes, S[m], sign)
    ty = "CE" if sign > 0 else "PE"
    v = quotes.get((k, ty)) if k is not None else None
    if not v:
        return None
    s0, end = (m + 1) * 60, 15 * 3600 + 29 * 60 + 30
    if s0 not in v:
        return None
    entry = v[s0][1]                                           # the ask in force at the start of minute m + 1
    a = v[end][0] / entry - 1 if end in v else None
    hi, res = entry, None
    for s in range(s0 + 1, end + 1):
        if s not in v:
            continue
        bd = v[s][0]
        if bd <= hi * (1 - GIVE):
            res = bd / entry - 1
            break
        hi = max(hi, bd)
    return {"A": a, "B": res if res is not None else a, "k": k, "ty": ty, "entry": entry, "exit": v[end][0] if end in v else None}


# ---------------------------------------------------------------- report

def boot(xs, n=10000, seed=189):
    if len(xs) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        means.append(statistics.mean(rng.choice(xs) for _ in xs))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def report(name, trades, base, days):
    print(f"\n== {name}: {days} expiry days, {len(trades)} triggered")
    for ex in ("A", "B"):
        xs = [t[ex] for t in trades if t[ex] is not None]
        if not xs:
            print(f"  exit {ex}: none")
            continue
        ci = boot(xs)
        print(f"  exit {ex}: n={len(xs)} mean {statistics.mean(xs):+.1%} median {statistics.median(xs):+.1%} win {sum(x > 0 for x in xs) / len(xs):.0%} "
              f"best {max(xs):+.0%} worst {min(xs):+.0%}" + (f"  CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
    if base:
        print(f"  baseline (first OTM call + put at 15:24, hold to 15:29): n={len(base)} mean {statistics.mean(base):+.1%} win {sum(x > 0 for x in base) / len(base):.0%}")


if __name__ == "__main__":
    which = sys.argv[1]
    if which == "bars":
        for name, lo, hi in (("design Oct 2024-Sep 2025", "2024-10-01", "2025-09-30"), ("validation Oct 2025-Sep 2026", "2025-10-01", "2026-09-30")):
            days = expiry_days(lo, hi)
            trades, base = [], []
            for d, u in days:
                print(f"\r{u} {d}  ", end="", file=sys.stderr)
                got = bar_day(u, d)
                if not got:
                    continue
                ref, bars, closes, S = got
                bl = baseline(bars, closes, S)
                if bl is not None:
                    base.append(bl)
                tr = trigger(S)
                if tr:
                    r = bar_trade(bars, closes, S, *tr)
                    if r:
                        r.update(day=d, u=u, m=tr[0], sign=tr[1])
                        trades.append(r)
            print(file=sys.stderr)
            report(name, trades, base, len(days))
            for t in trades[-12:] if name.startswith("validation") else []:
                print(f"     {t['day']} {t['u']:6s} {t['m'] // 60}:{t['m'] % 60:02d} {t['ty']} {int(t['k'])} {t['entry']:.2f} -> {t['exit']:.2f}  A {t['A']:+.0%}  B {t['B']:+.0%}")
    elif which == "ticks":
        trades = []
        for d, u in TICK_DAYS:
            ref, quotes, S = tick_day(u, d)
            tr = trigger(S)
            line = f"  {d} {u:6s} S 15:15 {S.get(M1515, 0):,.1f} -> 15:27 {S.get(M1527, 0):,.1f}: "
            if not tr:
                print(line + "no trigger")
                continue
            r = tick_trade(quotes, S, *tr)
            if not r:
                print(line + f"trigger {tr} but no quote")
                continue
            r.update(day=d, u=u)
            trades.append(r)
            print(line + f"trigger {tr[0] // 60}:{tr[0] % 60:02d} {r['ty']} {int(r['k'])} ask {r['entry']:.2f} -> bid 15:29:30 {r['exit']}  "
                         f"A {r['A']:+.0%}  B {r['B']:+.0%}")
        report("tick days 28 Sep-22 Oct (own capture)", trades, None, len(TICK_DAYS))
