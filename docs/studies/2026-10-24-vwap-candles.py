#!/usr/bin/env python3
"""A-212: the VWAP opening / signal / entry candle rule from the Udemy course "13 Strategies for Intraday & options"
(Section 5, Strategy 2; rules fixed in the ledger before this ran; notes in docs/research/udemy/13-strategies-intraday-options.md).

5-minute candles from 09:15 on the front-month index future, VWAP from its 1-minute bars (typical price x volume from 09:15).
Long: opening candle O closes above VWAP after the previous close was at or below it; signal S = first of the next 3 candles
to break O's high (cancelled if O's low breaks first, or both in one minute); entry = first 1-minute bar in the next 3 candles
after S whose high breaks S's high (cancelled if S's low breaks first). Near-VWAP filter: S's high within 0.15 % of VWAP at
S's close. Short = mirror. Stop: first 5-min close back across VWAP. Arms: T2R (also a 2R target, R = |entry - VWAP| floored
at 0.05 %) and HOLD (stop or 15:15). Entries 09:30-14:30, flat 15:15, one position per index at a time.
Bars only (hist.candle): ATM option of the nearest weekly expiry bought at the entry minute's close + 0.75 %, sold at the exit
minute's close - 0.75 % (as A-200). Verdict on NIFTY; SENSEX descriptive (thin futures volume).
Usage: python3 2026-10-24-vwap-candles.py run      (computes and caches)
       python3 2026-10-24-vwap-candles.py report
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
NEAR = 0.0015
R_FLOOR = 0.0005
WAIT = 3
FIRST_ENTRY, LAST_ENTRY, FLAT = 570, 870, 914          # 09:30, 14:30, the 15:14 bar's close = 15:15
SENSEX_MIN_TRADED = 0.60
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a212", "trades.pkl")
PERIODS = [("Oct 2024-Jun 2025", "2024-10-01", "2025-06-30"), ("Jul-Dec 2025", "2025-07-01", "2025-12-31"),
           ("Jan-Sep 2026", "2026-01-01", "2026-09-30")]
LATE = ("Oct 2026 (to 6 Oct, descriptive)", "2026-10-01", "2026-10-31")
ARMS = [("T2R", True, True), ("HOLD", False, True), ("T2R-nofilter", True, False), ("HOLD-nofilter", False, False)]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


def day_range(d):
    return f"bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 15:30+05:30')::timestamptz"


def days():
    return [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' "
                            f"and kind = 'FUTURE' and bar_start >= '2024-10-01' order by 1")]


def future_bars(u, d):
    """{minute: (o, h, l, c, volume)} of the front-month future (nearest expiry on or after the day)."""
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close, coalesce(volume, 0) from hist.candle "
             f"where underlying = '{u}' and kind = 'FUTURE' and {day_range(d)} and expiry = (select min(expiry) from hist.candle "
             f"where underlying = '{u}' and kind = 'FUTURE' and expiry >= '{d}' and {day_range(d)}) order by 1")
    return {hm(t): tuple(map(float, v)) for t, *v in rows}


def index_close(u, d):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' and kind = 'INDEX' "
             f"and {day_range(d)}")
    return {hm(t): float(c) for t, c in rows}


def expiry(u, d):
    r = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:20+05:30')::timestamptz")
    return r[0][0] if r and r[0] and r[0][0] else None


def option_closes(u, d, exp, k, ty):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and expiry = '{exp}' and strike = {k} and option_type = '{ty}' and {day_range(d)}")
    return {hm(t): float(c) for t, c in rows if c and float(c) > 0}


def at_or_after(series, m, within=3):
    for x in range(m, m + within + 1):
        if x in series:
            return series[x]
    return None


def vwaps(fut):
    """Cumulative VWAP through each minute (None until volume trades)."""
    out, pv, vol = {}, 0.0, 0.0
    for m in range(555, 930):
        b = fut.get(m)
        if b and b[4] > 0:
            pv += (b[1] + b[2] + b[3]) / 3 * b[4]
            vol += b[4]
        out[m] = pv / vol if vol > 0 else None
    return out


def candles(fut, vw):
    """[(start, last_minute, o, h, l, c, vwap at the close)] of 5-minute candles from 09:15."""
    out = []
    for s in range(555, 930, 5):
        ms = [m for m in range(s, s + 5) if m in fut]
        if not ms:
            continue
        out.append((s, ms[-1], fut[ms[0]][0], max(fut[m][1] for m in ms), min(fut[m][2] for m in ms), fut[ms[-1]][3], vw[ms[-1]]))
    return out


def first_break(fut, start, end, side, level_with, level_against):
    """First minute in [start, end] that breaks level_with in the trade's direction: ('with', m), ('against', m) or None."""
    for m in range(start, end + 1):
        b = fut.get(m)
        if not b:
            continue
        w = b[1] > level_with if side > 0 else b[2] < level_with
        a = b[2] < level_against if side > 0 else b[1] > level_against
        if w and a:
            return ("both", m)
        if a:
            return ("against", m)
        if w:
            return ("with", m)
    return None


def simulate(u, fut, vw, cs, target, near_filter):
    """[(side, entry minute, exit minute, entry price, exit price, why)] on one index-day."""
    trades, i, busy_until = [], 1, -1
    while i < len(cs):
        o, prev = cs[i], cs[i - 1]
        if o[0] <= busy_until or o[6] is None or prev[6] is None:
            i += 1
            continue
        side = 1 if o[5] > o[6] and prev[5] <= prev[6] else -1 if o[5] < o[6] and prev[5] >= prev[6] else 0
        if not side:
            i += 1
            continue
        # signal: the next WAIT candles, minute by minute
        sig = None
        if i + 1 < len(cs):
            hit = first_break(fut, cs[i + 1][0], cs[min(i + WAIT, len(cs) - 1)][1], side,
                              o[3] if side > 0 else o[4], o[4] if side > 0 else o[3])
            if hit and hit[0] == "with":
                sig = next(j for j in range(i + 1, len(cs)) if cs[j][0] <= hit[1] <= cs[j][1])
        if sig is None:
            i += 1
            continue
        s = cs[sig]
        level = s[3] if side > 0 else s[4]
        if near_filter and (s[6] is None or abs(level - s[6]) / s[6] > NEAR):
            i += 1
            continue
        if sig + 1 >= len(cs):
            break
        hit = first_break(fut, cs[sig + 1][0], cs[min(sig + WAIT, len(cs) - 1)][1], side, level, s[4] if side > 0 else s[3])
        if not hit or hit[0] != "with" or not FIRST_ENTRY <= hit[1] <= LAST_ENTRY:
            i += 1
            continue
        e = hit[1]
        entry = max(level, fut[e][0]) if side > 0 else min(level, fut[e][0])
        r = max(abs(entry - vw[e]) if vw[e] else 0.0, R_FLOOR * entry)
        tgt = entry + side * 2 * r
        x, why = None, None
        for m in range(e + 1, FLAT + 1):
            b = fut.get(m)
            if not b:
                continue
            if target and (b[1] >= tgt if side > 0 else b[2] <= tgt):
                x, why = m, "target"
                break
            if (m - 555) % 5 == 4 and vw[m] is not None and (b[3] < vw[m] if side > 0 else b[3] > vw[m]):
                x, why = m, "stop"
                break
        if x is None:
            x = max(m for m in fut if m <= FLAT)
            why = "15:15"
        exit_px = tgt if why == "target" else fut[x][3]
        trades.append((side, e, x, entry, exit_px, why))
        busy_until = x
        i = next((j for j in range(len(cs)) if cs[j][0] > x), len(cs))
    return trades


def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    out = defaultdict(list)          # arm -> [(u, d, side, entry m, exit m, bp, net option %, why)]
    skipped = defaultdict(int)
    for d in days():
        if d in HELD_OUT:
            continue
        for u in ("NIFTY", "SENSEX"):
            fut = future_bars(u, d)
            if len(fut) < 300:
                skipped[f"{u} short day"] += 1
                continue
            traded = sum(1 for b in fut.values() if b[4] > 0) / 375
            if u == "SENSEX" and traded < SENSEX_MIN_TRADED:
                skipped["SENSEX thin volume"] += 1
                continue
            print(f"\r{d} {u}   ", end="", file=sys.stderr)
            vw = vwaps(fut)
            cs = candles(fut, vw)
            sims = {name: simulate(u, fut, vw, cs, tgt, nf) for name, tgt, nf in ARMS}
            if not any(sims.values()):
                continue
            idx, exp, opt = index_close(u, d), expiry(u, d), {}
            for name, ts in sims.items():
                for side, e, x, entry, exit_px, why in ts:
                    spot = at_or_after(idx, e, 2)
                    if spot is None or exp is None:
                        skipped[f"{name} no index/expiry"] += 1
                        continue
                    k, ty = round(spot / STEP[u]) * STEP[u], "CE" if side > 0 else "PE"
                    if (k, ty) not in opt:
                        opt[(k, ty)] = option_closes(u, d, exp, k, ty)
                    buy, sell = at_or_after(opt[(k, ty)], e), at_or_after(opt[(k, ty)], x)
                    if buy is None or sell is None:
                        skipped[f"{name} no option bar"] += 1
                        continue
                    net = sell * (1 - COST) / (buy * (1 + COST)) - 1
                    bp = side * (exit_px - entry) / entry * 1e4
                    out[name].append((u, d, side, e, x, bp, net, why))
    print(file=sys.stderr)
    res = {"trades": dict(out), "skipped": dict(skipped)}
    pickle.dump(res, open(CACHE, "wb"))
    return res


def boot(tr, n=10000, seed=212, conf=0.975):
    by = defaultdict(list)
    for t in tr:
        by[t[1]].append(t[6])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    a = (1 - conf) / 2
    return means[int(a * n)], means[int((1 - a) * n) - 1]


def line(tr):
    if not tr:
        return "n=0"
    xs, bp = [t[6] for t in tr], [t[5] for t in tr]
    whys = defaultdict(int)
    for t in tr:
        whys[t[7]] += 1
    return (f"n={len(xs):4d} mean {statistics.mean(xs):+6.2%} median {statistics.median(xs):+6.2%} win {sum(x > 0 for x in xs) / len(xs):3.0%} "
            f"| index {statistics.mean(bp):+5.1f} bp/trade | " + " ".join(f"{k} {v}" for k, v in sorted(whys.items())))


def report(res):
    trades = res["trades"]
    print("A-212 VWAP opening / signal / entry candle. Option net % of premium per trade (bars, +-0.75 %), index-future bp per trade.")
    print("skipped:", ", ".join(f"{k} {v}" for k, v in sorted(res["skipped"].items())))
    for name, _, nf in ARMS:
        tr = trades.get(name, [])
        print(f"\n== {name}" + ("" if nf else "   (reported, not judged)"))
        for u in ("NIFTY", "SENSEX"):
            sub = [t for t in tr if t[0] == u]
            print(f"  {u}" + ("  (verdict)" if u == "NIFTY" and nf else "  (descriptive)"))
            for lab, lo, hi in PERIODS + [LATE]:
                print(f"    {lab:34s} {line([t for t in sub if lo <= t[1] <= hi])}")
            pooled = [t for t in sub if t[1] <= "2026-09-30"]
            ci = boot(pooled)
            print(f"    {'pooled Oct 2024-Sep 2026':34s} {line(pooled)}" + (f"  97.5 % CI [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))
            for lab, sd in (("calls", 1), ("puts", -1)):
                print(f"      {lab:5s} {line([t for t in pooled if t[2] == sd])}")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        report(run())
    else:
        report(pickle.load(open(CACHE, "rb")))
