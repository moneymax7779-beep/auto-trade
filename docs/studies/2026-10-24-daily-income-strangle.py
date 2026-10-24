#!/usr/bin/env python3
"""A-204: the "daily income" intraday hedged strangle from the Udemy course "Nifty & BankNifty: Options Future Trading
for Weekly Income" (rules fixed in the ledger before this ran).

NIFTY, nearest weekly expiry. Skip if India VIX at 10:30 > 25. At 10:30 sell the call and put nearest the target delta for
the days to expiry (>= 4: 0.35, 3: 0.30, 2: 0.25, 1/0: 0.175; Black-Scholes, each option's own IV, r = 0), buy the
further strikes nearest Rs 5. Exits each minute: net gain >= 13.3 points per unit (1 % of Rs 1 L per 75 units), NIFTY
high/low at a breakeven, or 15:10 (14:10 reported). Each leg pays max(0.75 %, Rs 0.10) per side.
Usage: python3 2026-10-24-daily-income-strangle.py run | report
"""
import csv, io, math, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict
from datetime import date, timedelta

IST = "at time zone 'Asia/Kolkata'"
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a204/trades.pkl"
TARGET_PTS, CAP_UNITS = 1000 / 75, 75          # Rs 1,000 per 75 units = 1 % of Rs 1 L
PERIODS = [("Oct 2024-Jun 2025", "2024-10-01", "2025-06-30"), ("Jul-Dec 2025", "2025-07-01", "2025-12-31"),
           ("Jan-Sep 2026", "2026-01-01", "2026-09-30"), ("Oct 2026 (to 6 Oct, descriptive)", "2026-10-01", "2026-10-31")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


def ncdf(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def bs(S, K, T, v, call):
    if T <= 0 or v <= 0:
        return max(0.0, S - K) if call else max(0.0, K - S)
    d1 = (math.log(S / K) + 0.5 * v * v * T) / (v * math.sqrt(T))
    d2 = d1 - v * math.sqrt(T)
    return S * ncdf(d1) - K * ncdf(d2) if call else K * ncdf(-d2) - S * ncdf(-d1)


def delta(S, K, T, price, call):
    lo, hi = 0.01, 3.0
    if price <= bs(S, K, T, lo, call) or price >= bs(S, K, T, hi, call):
        return None
    for _ in range(60):
        mid = (lo + hi) / 2
        if bs(S, K, T, mid, call) < price:
            lo = mid
        else:
            hi = mid
    v = (lo + hi) / 2
    d1 = (math.log(S / K) + 0.5 * v * v * T) / (v * math.sqrt(T))
    return ncdf(d1) if call else ncdf(d1) - 1


def dte(d, e):
    a, b, n = date.fromisoformat(d), date.fromisoformat(e), 0
    while a < b:
        a += timedelta(days=1)
        n += a.weekday() < 5
    return n


def target_delta(n):
    return 0.35 if n >= 4 else 0.30 if n == 3 else 0.25 if n == 2 else 0.175


def cost(p):
    return max(0.0075 * p, 0.10)


def one_day(d):
    idx = {hm(t): (float(h), float(l), float(c)) for t, h, l, c in q(
        f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' "
        f"and bar_start >= ('{d} 10:30+05:30')::timestamptz and bar_start <= ('{d} 15:10+05:30')::timestamptz")}
    if hm("10:30") not in idx:
        return None
    vix = q(f"select close from hist.candle where underlying = 'INDIA_VIX' and kind = 'VIX' "
            f"and bar_start <= ('{d} 10:30+05:30')::timestamptz and bar_start >= ('{d} 09:15+05:30')::timestamptz order by bar_start desc limit 1")
    vix = float(vix[0][0]) if vix and vix[0] else None
    if vix is not None and vix > 25:
        return {"skip": "vix", "vix": vix}
    r = q(f"select min(expiry)::text from hist.candle where underlying = 'NIFTY' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start >= ('{d} 10:30+05:30')::timestamptz and bar_start < ('{d} 10:31+05:30')::timestamptz")
    if not r or not r[0] or not r[0][0]:
        return None
    e = r[0][0]
    rows = q(f"select strike, option_type, to_char(bar_start {IST},'HH24:MI'), close, max(lot_size) over () from hist.candle "
             f"where underlying = 'NIFTY' and kind = 'OPTION' and expiry = '{e}' "
             f"and bar_start >= ('{d} 10:30+05:30')::timestamptz and bar_start <= ('{d} 15:10+05:30')::timestamptz")
    px = defaultdict(dict)
    lot = 75
    for k, ty, t, c, ls in rows:
        if c and float(c) > 0:
            px[(float(k), ty)][hm(t)] = float(c)
        lot = int(ls) if ls else lot
    S = idx[hm("10:30")][2]
    T = (dte(d, e) * 1440 + (hm("15:30") - hm("10:30"))) / (365 * 1440)
    n = dte(d, e)
    tgt = target_delta(n)
    legs = {}
    for ty, call in (("CE", True), ("PE", False)):
        cands = []
        for (k, t2), ser in px.items():
            if t2 != ty or hm("10:30") not in ser:
                continue
            if (call and k <= S) or (not call and k >= S):
                continue
            dl = delta(S, k, T, ser[hm("10:30")], call)
            if dl is not None:
                cands.append((abs(abs(dl) - tgt), k, dl))
        if not cands:
            return None
        _, ks, dl = min(cands)
        wings = [(abs(ser[hm("10:30")] - 5.0), k) for (k, t2), ser in px.items()
                 if t2 == ty and hm("10:30") in ser and ((call and k > ks) or (not call and k < ks))]
        if not wings:
            return None
        kw = min(wings)[1]
        legs[ty] = (ks, kw, dl)
    sc, wc = px[(legs["CE"][0], "CE")], px[(legs["CE"][1], "CE")]
    sp, wp = px[(legs["PE"][0], "PE")], px[(legs["PE"][1], "PE")]
    m0 = hm("10:30")
    entry = {"sc": sc[m0], "wc": wc[m0], "sp": sp[m0], "wp": wp[m0]}
    credit = entry["sc"] + entry["sp"] - entry["wc"] - entry["wp"]
    be_hi, be_lo = legs["CE"][0] + credit, legs["PE"][0] - credit
    last = dict(entry)
    series = {"sc": sc, "wc": wc, "sp": sp, "wp": wp}

    def mtm():
        return (entry["sc"] - last["sc"]) + (entry["sp"] - last["sp"]) + (last["wc"] - entry["wc"]) + (last["wp"] - entry["wp"])

    def net():
        costs = sum(cost(entry[k]) + cost(last[k]) for k in entry)
        return mtm() - costs

    res = {}
    for m in range(m0 + 1, hm("15:10") + 1):
        for k, ser in series.items():
            if m in ser:
                last[k] = ser[m]
        if "1410" not in res and m == hm("14:10"):
            res["1410"] = (net(), "time")
        if m in idx and (idx[m][0] >= be_hi or idx[m][1] <= be_lo):
            out = (net(), "breakeven")
            res.setdefault("1410", out)
            res["1510"] = out
            break
        if mtm() >= TARGET_PTS:
            out = (net(), "target")
            res.setdefault("1410", out)
            res["1510"] = out
            break
    else:
        res["1510"] = (net(), "time")
    return {"d": d, "exp": e, "dte": n, "vix": vix, "lot": lot, "S": S, "legs": legs, "credit": credit, "res": res}


def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    ds = [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' and kind = 'OPTION' "
                          f"and bar_start >= '2024-10-01' order by 1")]
    out = []
    for d in ds:
        if d in HELD_OUT:
            continue
        print(f"\r{d}   ", end="", file=sys.stderr)
        r = one_day(d)
        if r:
            out.append(r)
    print(file=sys.stderr)
    pickle.dump(out, open(CACHE, "wb"))
    return out


def boot(xs, n=10000, seed=204):
    if len(xs) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        s = [rng.choice(xs) for _ in xs]
        means.append(sum(s) / len(s))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def line(rs, ex):
    xs = [r["res"][ex][0] * CAP_UNITS for r in rs if ex in r["res"]]
    if not xs:
        return "n=0"
    why = defaultdict(int)
    for r in rs:
        if ex in r["res"]:
            why[r["res"][ex][1]] += 1
    cum, peak, dd = 0.0, 0.0, 0.0
    for x in xs:
        cum += x; peak = max(peak, cum); dd = max(dd, peak - cum)
    return (f"n={len(xs):3d} mean Rs {statistics.mean(xs):+7.0f} median {statistics.median(xs):+6.0f} win {sum(x > 0 for x in xs) / len(xs):.0%} "
            f"total Rs {sum(xs):+9,.0f} (per Rs 1 L) maxDD {dd:,.0f}  " + " ".join(f"{k} {v}" for k, v in sorted(why.items())))


def report(out):
    trades = [r for r in out if "res" in r]
    print(f"days with a trade {len(trades)}, VIX skips {sum(1 for r in out if r.get('skip') == 'vix')}")
    for ex in ("1510", "1410"):
        print(f"\n== exit {ex} ({'the rule' if ex == '1510' else 'descriptive'}); Rs per Rs 1 L capital (75 units)")
        for lab, lo, hi in PERIODS:
            print(f"  {lab:34s} {line([r for r in trades if lo <= r['d'] <= hi], ex)}")
        pooled = [r for r in trades if r["d"] <= "2026-09-30"]
        ci = boot([r["res"][ex][0] * CAP_UNITS for r in pooled])
        print(f"  {'pooled Oct 2024-Sep 2026':34s} {line(pooled, ex)}" + (f"  CI [{ci[0]:+.0f}, {ci[1]:+.0f}]" if ci else ""))
        for n in (0, 1, 2, 3, 4):
            sub = [r for r in pooled if (r["dte"] if r["dte"] < 4 else 4) == n]
            print(f"    dte {n}{'+' if n == 4 else ' '}  {line(sub, ex)}")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        report(run())
    else:
        report(pickle.load(open(CACHE, "rb")))
