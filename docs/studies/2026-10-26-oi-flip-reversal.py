#!/usr/bin/env python3
"""A-232: opening flush reversal with an OI flip (rules fixed in the ledger before this ran).

Per index and day, one trade per variant. Flush: the session low (high) reaches <= open x (1 - 0.4 %) (>= open x 1.004)
between 09:15 and 11:00. Then at each 3-minute close (09:18, 09:21, ...) up to 11:00: OI flip = over the 5 minutes ending
at that close, near-ATM (ATM at the close, +-3 strikes, nearest weekly expiry) put OI added > 0 and > call OI added (mirror:
calls); price = the 3-minute close back above (below) the flush midpoint M = (open + L) / 2. Both -> buy the ATM call (put).
Exits: the index trades beyond the flush extreme (stop), 25 % premium stop, else 12:30 (primary) / 15:15 (reported).
Variants: OI (the rule) and PRICE (no OI condition, reported). Bars: option close +-0.75 %; ticks: ask in, bid out.
Usage: python3 2026-10-26-oi-flip-reversal.py
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
FLUSH, COST = 0.004, 0.0075
OPEN, LAST_DECISION, EXIT_1230, EXIT_1515 = 555, 660, 750, 915
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
DESIGN = "2026-10-26"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a232", "trades.pkl")
PERIODS = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul-Dec25", "2025-07-01", "2025-12-31"),
           ("Jan-Sep26", "2026-01-01", "2026-09-30")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def rng(d):
    return f"('{d} 09:15+05:30')::timestamptz and ('{d} 15:30+05:30')::timestamptz"


# ---------------------------------------------------------------- the rule on one day (minute data)
def day_trades(idx, oi_at, price_for):
    """idx {minute: (o,h,l,c)}; oi_at(minute, strike, type) -> OI at the end of that minute or None;
    price_for(type, strike, entry_minute, exits) -> (entry px, {name: exit px}) or None.
    Returns {variant: trade dict}."""
    ms = sorted(m for m in idx if OPEN <= m < 930)
    if len(ms) < 300:
        return {}
    op = idx[ms[0]][0]
    out = {}
    for variant in ("OI", "PRICE"):
        lo_so_far = hi_so_far = None
        for d in range(OPEN + 3, LAST_DECISION + 1, 3):
            bars = [idx[m] for m in ms if m < d]
            if not bars:
                continue
            lo_so_far = min(b[2] for b in bars)
            hi_so_far = max(b[1] for b in bars)
            close = idx[max(m for m in ms if m < d)][3]
            for side, extreme, flushed in (("CE", lo_so_far, lo_so_far <= op * (1 - FLUSH)),
                                           ("PE", hi_so_far, hi_so_far >= op * (1 + FLUSH))):
                if not flushed:
                    continue
                mid = (op + extreme) / 2
                if not (close > mid if side == "CE" else close < mid):
                    continue
                step = STEP_NOW
                atm = round(close / step) * step
                if variant == "OI":
                    ce_add = pe_add = 0.0
                    ok = True
                    for k in range(atm - 3 * step, atm + 4 * step, step):
                        for ty in ("CE", "PE"):
                            now, then = oi_at(d - 1, k, ty), oi_at(d - 6, k, ty)
                            if now is None or then is None:
                                ok = False
                                break
                            if ty == "CE":
                                ce_add += now - then
                            else:
                                pe_add += now - then
                        if not ok:
                            break
                    if not ok:
                        continue
                    flip = (pe_add > 0 and pe_add > ce_add) if side == "CE" else (ce_add > 0 and ce_add > pe_add)
                    if not flip:
                        continue
                # stop: the index beyond the flush extreme after entry
                stop_minute = None
                for m in ms:
                    if m >= d and (idx[m][2] < extreme if side == "CE" else idx[m][1] > extreme):
                        stop_minute = m
                        break
                priced = price_for(side, atm, d, stop_minute)
                if priced is None:
                    continue
                out[variant] = dict(side=side, entry_minute=d, strike=atm, stop_minute=stop_minute, **priced)
                break
            if variant in out:
                break
    return out


STEP_NOW = 50


# ---------------------------------------------------------------- bars
def bar_days():
    return [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' "
                            f"and bar_start >= '2024-10-01' order by 1")]


def bar_day(u, d):
    global STEP_NOW
    STEP_NOW = STEP[u]
    idx = {hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
        f"and kind = 'INDEX' and bar_start between {rng(d)}")}
    if len(idx) < 300:
        return None
    e = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
    if not e or not e[0] or not e[0][0]:
        return None
    exp = e[0][0]
    op = idx[min(idx)][0]
    lo_k, hi_k = op * 0.96, op * 1.04
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), strike, option_type, close, coalesce(oi, 0) from hist.candle "
             f"where underlying = '{u}' and kind = 'OPTION' and expiry = '{exp}' and strike between {lo_k} and {hi_k} "
             f"and bar_start between {rng(d)}")
    px, oi = defaultdict(dict), defaultdict(dict)
    for t, k, ty, c, o in rows:
        key = (float(k), ty)
        if c and float(c) > 0:
            px[key][hm(t)] = float(c)
        if float(o) > 0:
            oi[key][hm(t)] = float(o)

    def last_at(series, minute):
        best = None
        for m in range(minute, minute - 6, -1):          # carry the last value up to 5 minutes (exchange OI clock)
            if m in series:
                return series[m]
        return best

    def oi_at(minute, k, ty):
        return last_at(oi.get((float(k), ty), {}), minute)

    def price_for(side, k, entry_minute, stop_minute):
        s = px.get((float(k), side), {})
        buy = last_at(s, entry_minute - 1)
        if buy is None:
            return None
        res = {}
        for name, until in (("x1230", EXIT_1230), ("x1515", EXIT_1515)):
            x_minute, why = until - 1, "time"
            for m in range(entry_minute, until):
                if stop_minute is not None and m >= stop_minute:
                    x_minute, why = m, "stop"
                    break
                p = s.get(m)
                if p is not None and p <= 0.75 * buy:
                    x_minute, why = m, "premium_stop"
                    break
            sell = last_at(s, x_minute) or buy
            res[name] = (sell * (1 - COST) / (buy * (1 + COST)) - 1, why, x_minute)
        return dict(buy=buy, exits=res)

    return idx, oi_at, price_for, exp == d


# ---------------------------------------------------------------- ticks (own capture)
def tick_days():
    return [r[0] for r in q("select distinct session_date::text from md.option_tick where oi > 0 order by 1")]


def tick_day(u, d):
    global STEP_NOW
    STEP_NOW = STEP[u]
    idx = {hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), first(price, recv_ts), max(price), min(price), "
        f"last(price, recv_ts) from md.index_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
        f"and recv_ts between {rng(d)} group by 1")}
    if len(idx) < 300:
        return None
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")[0][0]
    op = idx[min(idx)][0]
    rows = q(f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), strike, option_type, last(oi, recv_ts) "
             f"from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{exp}' and oi > 0 "
             f"and strike between {op * 0.96} and {op * 1.04} and recv_ts between ('{d} 09:15+05:30')::timestamptz "
             f"and ('{d} 11:01+05:30')::timestamptz group by 1, 2, 3")
    oi = defaultdict(dict)
    for t, k, ty, o in rows:
        oi[(float(k), ty)][hm(t)] = float(o)

    def oi_at(minute, k, ty):
        s = oi.get((float(k), ty), {})
        for m in range(minute, minute - 6, -1):
            if m in s:
                return s[m]
        return None

    def quote(k, ty, minute, field):
        r = q(f"select {field}[1] from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{exp}' "
              f"and strike = {k} and option_type = '{ty}' and {field}[1] > 0 and recv_ts >= ('{d}'::date + time '{minute // 60:02d}:{minute % 60:02d}') "
              f"at time zone 'Asia/Kolkata' order by recv_ts limit 1")
        return float(r[0][0]) if r else None

    def price_for(side, k, entry_minute, stop_minute):
        buy = quote(k, side, entry_minute, "ask_px")
        if buy is None:
            return None
        mids = {hm(t): float(p) for t, p in q(
            f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), last(bid_px[1], recv_ts) from md.option_tick "
            f"where session_date = '{d}' and underlying = '{u}' and expiry = '{exp}' and strike = {k} and option_type = '{side}' "
            f"and bid_px[1] > 0 and recv_ts between {rng(d)} group by 1")}
        res = {}
        for name, until in (("x1230", EXIT_1230), ("x1515", EXIT_1515)):
            x_minute, why = until, "time"
            for m in range(entry_minute, until):
                if stop_minute is not None and m >= stop_minute:
                    x_minute, why = m + 1, "stop"
                    break
                if m in mids and mids[m] <= 0.75 * buy:
                    x_minute, why = m + 1, "premium_stop"
                    break
            sell = quote(k, side, x_minute, "bid_px") or buy
            res[name] = (sell / buy - 1, why, x_minute)
        return dict(buy=buy, exits=res)

    return idx, oi_at, price_for, exp == d


# ---------------------------------------------------------------- run / report
def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    out = []
    for src, days, loader in (("bars", bar_days(), bar_day), ("ticks", tick_days(), tick_day)):
        for d in days:
            if d in HELD_OUT or (src == "bars" and d > "2026-09-30"):
                continue
            for u in ("NIFTY", "SENSEX"):
                print(f"\r{src} {d} {u}   ", end="", file=sys.stderr)
                loaded = loader(u, d)
                if not loaded:
                    continue
                idx, oi_at, price_for, expiry_day = loaded
                for v, t in day_trades(idx, oi_at, price_for).items():
                    t.update(src=src, d=d, u=u, v=v, expiry=expiry_day)
                    out.append(t)
    print(file=sys.stderr)
    pickle.dump(out, open(CACHE, "wb"))
    return out


def boot(tr, key, n=10000, seed=232):
    by = defaultdict(list)
    for t in tr:
        by[t["d"]].append(t["exits"][key][0])
    ks = list(by)
    if len(ks) < 2:
        return None
    r, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (r.choice(ks) for _ in ks) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[250], means[9749]


def line(tr, key="x1230"):
    if not tr:
        return "n=0"
    xs = [t["exits"][key][0] for t in tr]
    whys = defaultdict(int)
    for t in tr:
        whys[t["exits"][key][1]] += 1
    return (f"n={len(xs):3d} mean {statistics.mean(xs):+6.1%} median {statistics.median(xs):+6.1%} win {sum(x > 0 for x in xs) / len(xs):3.0%} "
            + " ".join(f"{k} {v}" for k, v in sorted(whys.items())))


def report(out):
    print("A-232 opening flush reversal with an OI flip. Option net % of premium per trade (bars +-0.75 %; ticks ask in / bid out)\n")
    for v in ("OI", "PRICE"):
        print(f"== {v}" + (" (the rule)" if v == "OI" else " (price-only reclaim, reported)"))
        bars = [t for t in out if t["src"] == "bars" and t["v"] == v]
        oks = []
        for lab, lo, hi in PERIODS:
            sub = [t for t in bars if lo <= t["d"] <= hi]
            print(f"  bars {lab:12s} exit 12:30: {line(sub)}")
            print(f"  {'':17s} exit 15:15: {line(sub, 'x1515')}")
            oks.append(len(sub) >= 30 and sub and statistics.mean(t['exits']['x1230'][0] for t in sub) > 0)
            if len(sub) < 30:
                oks[-1] = None
        ci = boot(bars, "x1230")
        print(f"  bars pooled       exit 12:30: {line(bars)}" + (f"  CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
        for lab, f in (("expiry days", lambda t: t["expiry"]), ("non-expiry", lambda t: not t["expiry"]),
                       ("calls (flush down)", lambda t: t["side"] == "CE"), ("puts (flush up)", lambda t: t["side"] == "PE")):
            print(f"    {lab:18s} {line([t for t in bars if f(t)])}")
        ticks = [t for t in out if t["src"] == "ticks" and t["v"] == v and t["d"] != DESIGN]
        print(f"  ticks 28 Sep-23 Oct exit 12:30: {line(ticks)}")
        print(f"  {'':19s} exit 15:15: {line(ticks, 'x1515')}")
        for t in [t for t in out if t["src"] == "ticks" and t["v"] == v and t["d"] == DESIGN]:
            print(f"  design day 26 Oct {t['u']} {t['side']} {t['strike']} at {t['entry_minute'] // 60:02d}:{t['entry_minute'] % 60:02d}: "
                  f"12:30 {t['exits']['x1230'][0]:+.1%} ({t['exits']['x1230'][1]}), 15:15 {t['exits']['x1515'][0]:+.1%}")
        if v == "OI":
            rule = (oks, ci)
        print()
    oks, ci = rule
    price = [t for t in out if t["src"] == "bars" and t["v"] == "PRICE"]
    oi_b = [t for t in out if t["src"] == "bars" and t["v"] == "OI"]
    beats = oi_b and price and statistics.mean(t["exits"]["x1230"][0] for t in oi_b) > statistics.mean(t["exits"]["x1230"][0] for t in price)
    verdict = "INSUFFICIENT" if any(o is None for o in oks) else (
        "PASS" if all(oks) and ci and ci[0] > 0 and beats else "FAIL")
    print(f"Registered verdict: {verdict} (periods > 0: {oks}; CI low > 0: {bool(ci and ci[0] > 0)}; OI beats price-only: {bool(beats)})")


if __name__ == "__main__":
    report(pickle.load(open(CACHE, "rb")) if sys.argv[1:] == ["report"] else run())
