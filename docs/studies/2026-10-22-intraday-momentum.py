#!/usr/bin/env python3
"""A-163 intraday momentum (definitions and criteria fixed in the ledger before this ran).

r1 = 09:44 close / previous 15:29 close - 1; outcome r_late = 15:24 close / 14:59 close - 1; strategy = sign(r1) x r_late.
Local database only: hist.candle index / VIX / option bars; md.index_tick for the October forward days.
Usage: python3 2026-10-22-intraday-momentum.py [main|heldout]
"""
import csv, io, random, statistics, subprocess, sys
from collections import defaultdict

HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
FORWARD = ["2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16",
           "2026-10-19", "2026-10-21"]
STEP = {"NIFTY": 50, "SENSEX": 100}
IST = "at time zone 'Asia/Kolkata'"
TIMES = ("09:15", "09:44", "14:29", "14:59", "15:24", "15:29")


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def bar_points():
    """{(u, day): {time: (open, close)}} for the index bars at TIMES; VIX 09:44 closes {day: vix}."""
    tl = ",".join(f"'{t}'" for t in TIMES)
    pts = defaultdict(dict)
    for u, d, t, o, c in q(f"select underlying, (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, close "
                           f"from hist.candle where kind = 'INDEX' and underlying in ('NIFTY','SENSEX') "
                           f"and to_char(bar_start {IST},'HH24:MI') in ({tl})"):
        pts[(u, d)][t] = (float(o), float(c))
    vix = {d: float(c) for d, c in q(f"select (bar_start {IST})::date::text, close from hist.candle where underlying = 'INDIA_VIX' "
                                     f"and to_char(bar_start {IST},'HH24:MI') = '09:44'")}
    expiries = {(u, d) for u, d in q("select distinct underlying, expiry::text from hist.candle where kind = 'OPTION' "
                                     "and underlying in ('NIFTY','SENSEX')")}
    return pts, vix, expiries


def own_points(days):
    pts = defaultdict(dict)
    dl = ",".join(f"'{d}'" for d in days)
    for u, d, t, c in q(f"select underlying, session_date::text, to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), "
                        f"last(price, recv_ts) from md.index_tick where session_date in ({dl}) and price > 0 "
                        f"and underlying in ('NIFTY','SENSEX','INDIA_VIX') and to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI') "
                        f"in ({','.join(repr(t) for t in TIMES)}) group by 1, 2, 3"):
        pts[(u, d)][t] = (float(c), float(c))
    return pts


def option_return(u, d, spot, sign):
    k = round(spot / STEP[u]) * STEP[u]
    ty = "CE" if sign > 0 else "PE"
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and option_type = '{ty}' and strike = {k} and expiry = (select min(expiry) from hist.candle where underlying = '{u}' "
             f"and kind = 'OPTION' and expiry >= '{d}' and (bar_start {IST})::date = '{d}') and (bar_start {IST})::date = '{d}' "
             f"and to_char(bar_start {IST},'HH24:MI') in ('14:59','15:24')")
    p = {t: float(c) for t, c in rows if c and float(c) > 0}
    return (p["15:24"] / p["14:59"] - 1) * 100 if "14:59" in p and "15:24" in p else None


def observations(pts, days, vix=None, expiries=frozenset(), with_options=False):
    obs = []
    for u in ("NIFTY", "SENSEX"):
        prev = None
        for d in days:
            p = pts.get((u, d))
            if not p:
                prev = None
                continue
            if prev and all(t in p for t in ("09:15", "09:44", "14:29", "14:59", "15:24")) and "15:29" in prev:
                pc = prev["15:29"][1]
                r1 = p["09:44"][1] / pc - 1
                rlate = p["15:24"][1] / p["14:59"][1] - 1
                rclose = p["15:29"][1] / p["14:59"][1] - 1 if "15:29" in p else None
                on = p["09:15"][0] / pc - 1
                r12 = p["14:59"][1] / p["14:29"][1] - 1
                sign = 1 if r1 > 0 else -1 if r1 < 0 else 0
                if sign:
                    o = option_return(u, d, p["14:59"][1], sign) if with_options else None
                    obs.append({"u": u, "d": d, "r1": r1, "on": on, "r12": r12, "late": rlate, "close": rclose,
                                "ret": sign * rlate * 1e4, "ret_close": sign * rclose * 1e4 if rclose is not None else None,
                                "on_ret": (1 if on > 0 else -1) * rlate * 1e4 if on else None,
                                "r12_ret": (1 if r12 > 0 else -1) * rlate * 1e4 if r12 else None,
                                "vix": (vix or {}).get(d), "expiry": (u, d) in expiries, "opt": o})
            prev = p
    return obs


def mean(xs):
    xs = [x for x in xs if x is not None]
    return statistics.mean(xs) if xs else float("nan")


def boot(obs, key="ret", n=10000, seed=163):
    by = defaultdict(list)
    for o in obs:
        if o[key] is not None:
            by[o["d"]].append(o[key])
    keys = list(by)
    rng, ms = random.Random(seed), []
    for _ in range(n):
        vals = [v for k in (rng.choice(keys) for _ in keys) for v in by[k]]
        ms.append(sum(vals) / len(vals))
    ms.sort()
    return ms[int(0.025 * n)], ms[int(0.975 * n)]


def line(label, obs, key="ret"):
    xs = [o[key] for o in obs if o[key] is not None]
    if not xs:
        return f"  {label:38s} n=0"
    lo, hi = boot(obs, key)
    return (f"  {label:38s} n={len(xs):3d} mean {mean(xs):+6.2f} bp  hit {sum(1 for x in xs if x > 0) / len(xs):.0%}  "
            f"CI95 [{lo:+.2f}, {hi:+.2f}]")


def trailing_median(obs, field, days_back=60):
    """For each obs: whether its field is above the median of the same index's previous <= 60 observations (>= 20)."""
    flags = {}
    hist = defaultdict(list)
    for o in sorted(obs, key=lambda o: o["d"]):
        h = hist[o["u"]]
        v = abs(o[field]) if field == "r1" else o[field]
        if v is not None and len(h) >= 20:
            flags[(o["u"], o["d"])] = v > statistics.median(h[-days_back:])
        if v is not None:
            h.append(v)
    return flags


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    pts, vix, expiries = bar_points()
    days = sorted({d for (_, d) in pts})
    if which == "heldout":
        obs = [o for o in observations(pts, days, vix, expiries, True) if o["d"] in HELD_OUT]
        print(line("held-out 22-25 Sep", obs))
        for o in obs:
            print(f"   {o['u']} {o['d']} r1 {o['r1'] * 1e4:+.0f} bp late {o['late'] * 1e4:+.0f} bp -> {o['ret']:+.1f}")
        return
    allobs = observations(pts, days, vix, expiries, True)
    test = [o for o in allobs if o["d"] not in HELD_OUT]
    h1 = [o for o in test if o["d"] <= "2026-05-31"]; h2 = [o for o in test if o["d"] > "2026-05-31"]
    print(f"test: {len({o['d'] for o in test})} days, {len(test)} index-days ({days[0]}..{days[-1]} bars, held-out excluded)")
    print(line("sign(r1) x late  [primary]", test))
    print(line("  half to 31 May", h1)); print(line("  half from 1 Jun", h2))
    print(line("  NIFTY", [o for o in test if o["u"] == "NIFTY"])); print(line("  SENSEX", [o for o in test if o["u"] == "SENSEX"]))
    print(line("to the 15:29 close", test, "ret_close"))
    print(line("overnight gap only (Liu & Tse)", test, "on_ret"))
    print(line("14:30-15:00 only", test, "r12_ret"))
    big = trailing_median(test, "r1"); hv = trailing_median(test, "vix")
    print(line("|r1| above its trailing median", [o for o in test if big.get((o["u"], o["d"]))]))
    print(line("|r1| at or below", [o for o in test if big.get((o["u"], o["d"])) is False]))
    print(line("VIX above its trailing median", [o for o in test if hv.get((o["u"], o["d"]))]))
    print(line("VIX at or below", [o for o in test if hv.get((o["u"], o["d"])) is False]))
    print(line("expiring index", [o for o in test if o["expiry"]]))
    print(line("not expiring", [o for o in test if not o["expiry"]]))
    opt = [o["opt"] for o in test if o["opt"] is not None]
    print(f"  ATM option 14:59 -> 15:24 in the signal's direction: n={len(opt)} mean {mean(opt):+.2f} %  "
          f"hit {sum(1 for x in opt if x > 0) / max(1, len(opt)):.0%} (bar closes, before costs)")
    fobs = observations(own_points(sorted(set(FORWARD) | {"2026-10-06", "2026-10-20"})), sorted(set(FORWARD) | {"2026-10-06"}))
    fobs = [o for o in fobs if o["d"] in FORWARD]
    print(line("forward own days 7-21 Oct", fobs))
    for o in fobs:
        print(f"     {o['u']} {o['d']} r1 {o['r1'] * 1e4:+.0f} bp, late {o['late'] * 1e4:+.0f} bp -> {o['ret']:+.1f}")
    m = mean([o["ret"] for o in test]); lo, _ = boot(test)
    c1 = m > 0 and lo > 0; c2 = mean([o["ret"] for o in h1]) > 0 and mean([o["ret"] for o in h2]) > 0
    c3 = mean([o["ret"] for o in fobs]) > 0
    for name, ok in (("(1) test mean > 0, CI low > 0", c1), ("(2) both halves > 0", c2), ("(3) forward mean > 0", c3)):
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    print("A-163:", "PASS" if c1 and c2 and c3 else "FAIL")


if __name__ == "__main__":
    main()
