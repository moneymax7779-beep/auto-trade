#!/usr/bin/env python3
"""A-200..A-203: setups from the Udemy course "BankNifty/Sensex Option Buying Jackpot Setup" (rules fixed in the ledger
before this ran; notes in auto-trade/docs/research/udemy/banknifty-sensex-option-buying-jackpot.md).

A-200 j1  first 10-minute candle body >= 0.20 % -> ATM put on a break of its low (09:25-10:30); option stop -13 %, cost at
          +13 %, then trail 13 % of entry below the option high; 15:15.
A-201 j2  KITE break: gap-up >= 0.10 % not filled by the first 3-min candle, first candle a hammer, first bearish 3-min
          candle while the first high holds -> ATM put on a break of its low; index stop above the swing high, cost after
          0.06 %, target 0.25 %, 15:15.
A-202 j3  DKIF: first two 3-min candles hammers, then bearish B + green G with G's body inside B's -> ATM call on a break of
          G's high; index stop below min(B low, G low), cost after 0.06 %, target 0.25 %, 15:15.
A-203 vix trades split by the previous day's India VIX close.
Bars only (hist.candle): buy at the entry minute's close + 0.75 %, sell - 0.75 %.
Usage: python3 2026-10-24-jackpot-setups.py run      (computes and caches all setups)
       python3 2026-10-24-jackpot-setups.py report
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a200/trades.pkl"
PERIODS = [("Oct 2024-Jun 2025", "2024-10-01", "2025-06-30"), ("Jul-Dec 2025", "2025-07-01", "2025-12-31"),
           ("Jan-Sep 2026", "2026-01-01", "2026-09-30"), ("Oct 2026 (to 6 Oct, descriptive)", "2026-10-01", "2026-10-31")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


T = hm


def days():
    ds = [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' "
                          f"and bar_start >= '2024-09-27' order by 1")]
    return ds


def index_bars(u, d):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
             f"and kind = 'INDEX' and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 15:30+05:30')::timestamptz order by 1")
    return {hm(t): tuple(map(float, v)) for t, *v in rows}


def expiry(u, d):
    r = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:20+05:30')::timestamptz")
    return r[0][0] if r and r[0] and r[0][0] else None


def option_bars(u, d, exp, k, ty):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 15:30+05:30')::timestamptz order by 1")
    return {hm(t): (float(h), float(l), float(c)) for t, h, l, c in rows if c and float(c) > 0}


def vix_prev():
    rows = q(f"select (bar_start {IST})::date::text, (array_agg(close order by bar_start desc))[1] from hist.candle "
             f"where underlying = 'INDIA_VIX' and kind = 'VIX' group by 1 order by 1")
    out, prev = {}, None
    for d, c in rows:
        out[d] = prev
        prev = float(c)
    return out


def candles3(idx, upto):
    """3-minute candles from 09:15 (o, h, l, c, start, end) built from 1-min bars, up to minute `upto`."""
    out = []
    for s in range(T("09:15"), upto, 3):
        bars = [idx[m] for m in range(s, s + 3) if m in idx]
        if len(bars) < 3:
            continue
        out.append((bars[0][0], max(b[1] for b in bars), min(b[2] for b in bars), bars[-1][3], s, s + 2))
    return out


def hammer(c):
    o, h, l, cl = c[:4]
    rng = h - l
    if rng <= 0:
        return False
    lower, upper = min(o, cl) - l, h - max(o, cl)
    return lower >= 0.5 * rng and lower >= 2 * upper


# ---- signals: (entry minute, side, extra) -------------------------------------------------------------------------

def j1(idx, prev_close, threshold=0.0020, mirror=False):
    if T("09:15") not in idx or T("09:24") not in idx:
        return []
    o, c = idx[T("09:15")][0], idx[T("09:24")][3]
    hi = max(idx[m][1] for m in range(T("09:15"), T("09:25")) if m in idx)
    lo = min(idx[m][2] for m in range(T("09:15"), T("09:25")) if m in idx)
    body = abs(c - o) / o
    if body < threshold:
        return []
    for m in range(T("09:25"), T("10:30") + 1):
        if m not in idx:
            continue
        if not mirror and idx[m][2] < lo:
            return [(m, -1, {"red": c < o, "body": body})]
        if mirror and idx[m][1] > hi and c > o:
            return [(m, 1, {"red": False, "body": body})]
    return []


def j2(idx, prev_close):
    if not prev_close or T("09:15") not in idx:
        return []
    cs = candles3(idx, T("10:30") + 1)
    if len(cs) < 2:
        return []
    first = cs[0]
    if first[0] / prev_close - 1 < 0.0010 or first[2] <= prev_close or not hammer(first):
        return []
    trig = None
    for i in range(1, len(cs)):
        if cs[i][1] > first[1]:
            return []
        if cs[i][3] < cs[i][0]:
            trig = i
            break
    if trig is None:
        return []
    tc = cs[trig]
    swing = max(tc[1], cs[trig - 1][1])
    for m in range(tc[5] + 1, T("10:45") + 1):
        if m not in idx:
            continue
        if idx[m][1] > first[1]:
            return []
        if idx[m][2] < tc[2]:
            return [(m, -1, {"stop": swing})]
    return []


def j3(idx, prev_close):
    cs = candles3(idx, T("11:00") + 1)
    if len(cs) < 4 or not (hammer(cs[0]) and hammer(cs[1])):
        return []
    for i in range(2, len(cs) - 1):
        b, g = cs[i], cs[i + 1]
        if b[3] < b[0] and g[3] > g[0] and g[0] >= b[3] and g[3] <= b[0]:
            for m in range(g[5] + 1, T("11:15") + 1):
                if m in idx and idx[m][1] > g[1]:
                    return [(m, 1, {"stop": min(b[2], g[2])})]
            return []
    return []


# ---- exits -----------------------------------------------------------------------------------------------------------

def exit_option_trail(ob, m):
    """A-200: option stop -13 %, cost at +13 %, then trail 13 % of entry below the option's high; 15:15."""
    if m not in ob:
        return None
    entry = ob[m][2] * (1 + COST)
    stop, peak = entry * 0.87, entry
    for x in range(m + 1, T("15:15") + 1):
        if x not in ob:
            continue
        h, l, c = ob[x]
        if l <= stop:
            return stop * (1 - COST) / entry - 1, "stop" if stop < entry else "trail", x
        peak = max(peak, h)
        if peak >= entry * 1.13:
            stop = max(stop, entry, peak - 0.13 * entry)
    last = max((x for x in ob if x <= T("15:15")), default=m)
    return ob[last][2] * (1 - COST) / entry - 1, "time", last


def exit_index(ob, idx, m, side, stop_lvl):
    """A-201/A-202: index stop, cost after 0.06 %, target 0.25 %, 15:15; option priced at the exit minute's close."""
    if m not in ob or m not in idx:
        return None
    entry = ob[m][2] * (1 + COST)
    e = idx[m][3]
    stop, why, out = stop_lvl, "time", None
    for x in range(m + 1, T("15:15") + 1):
        if x not in idx:
            continue
        o, h, l, c = idx[x]
        adverse = h >= stop if side < 0 else l <= stop
        if adverse:
            why, out = ("cost" if stop == e else "stop"), x
            break
        fav = (e - l) / e if side < 0 else (h - e) / e
        if fav >= 0.0025:
            why, out = "target", x
            break
        if fav >= 0.0006:
            stop = e
    if out is None:
        out = T("15:15")
    xs = [x for x in ob if m < x <= out]
    px = ob[max(xs)][2] if xs else ob[m][2]
    return px * (1 - COST) / entry - 1, why, out


# ---- run ---------------------------------------------------------------------------------------------------------------

def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    ds = days()
    vix = vix_prev()
    trades = defaultdict(list)          # setup -> [(u, d, m, side, net, why, vix, extra)]
    prev = {}
    for i, d in enumerate(ds):
        for u in ("NIFTY", "SENSEX"):
            idx = index_bars(u, d)
            pc = prev.get(u)
            if idx:
                prev[u] = idx[max(idx)][3]
            if d < "2024-10-01" or d in HELD_OUT or not idx:
                continue
            print(f"\r{d} {u}   ", end="", file=sys.stderr)
            sigs = {"j1": j1(idx, pc), "j1-030": j1(idx, pc, 0.0030), "j1-mirror": j1(idx, pc, mirror=True),
                    "j2": j2(idx, pc), "j3": j3(idx, pc)}
            if not any(sigs.values()):
                continue
            exp = expiry(u, d)
            if not exp:
                continue
            for name, ss in sigs.items():
                for m, side, extra in ss:
                    k = round(idx[m][3] / STEP[u]) * STEP[u]
                    ob = option_bars(u, d, exp, k, "CE" if side > 0 else "PE")
                    r = exit_option_trail(ob, m) if name.startswith("j1") else exit_index(ob, idx, m, side, extra["stop"])
                    if r:
                        trades[name].append((u, d, m, side, r[0], r[1], vix.get(d), extra))
    print(file=sys.stderr)
    pickle.dump(dict(trades), open(CACHE, "wb"))
    return dict(trades)


def boot(tr, n=10000, seed=200):
    by = defaultdict(list)
    for t in tr:
        by[t[1]].append(t[4])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def line(tr):
    if not tr:
        return "n=0"
    xs = [t[4] for t in tr]
    whys = defaultdict(int)
    for t in tr:
        whys[t[5]] += 1
    return (f"n={len(xs):4d} mean {statistics.mean(xs):+6.2%} median {statistics.median(xs):+6.2%} win {sum(x > 0 for x in xs) / len(xs):.0%} "
            f"sum {sum(xs):+7.1%}  " + " ".join(f"{k} {v}" for k, v in sorted(whys.items())))


def report(trades):
    for name in ("j1", "j1-red", "j1-030", "j1-mirror", "j2", "j3"):
        tr = trades.get(name, []) if name != "j1-red" else [t for t in trades.get("j1", []) if t[7]["red"]]
        print(f"\n== {name}")
        for lab, lo, hi in PERIODS:
            sub = [t for t in tr if lo <= t[1] <= hi]
            print(f"  {lab:34s} {line(sub)}")
        allp = [t for t in tr if t[1] <= "2026-09-30"]
        ci = boot(allp)
        print(f"  {'pooled Oct 2024-Sep 2026':34s} {line(allp)}" + (f"  CI [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))
        for u in ("NIFTY", "SENSEX"):
            print(f"    {u:6s} {line([t for t in allp if t[0] == u])}")
        for lab, f in (("VIX < 15", lambda v: v is not None and v < 15), ("VIX 15-20", lambda v: v is not None and 15 <= v <= 20),
                       ("VIX > 20", lambda v: v is not None and v > 20)):
            print(f"    {lab:9s} {line([t for t in allp if f(t[6])])}")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        report(run())
    else:
        report(pickle.load(open(CACHE, "rb")))
