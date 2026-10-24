#!/usr/bin/env python3
"""A-164 dealer gamma regime (definitions and criteria fixed in the ledger before this ran).

At 09:44: G = (sum gamma*OI calls - sum gamma*OI puts) / sum gamma*OI, nearest expiry, strikes within +-5 %, Black-76 with
the near-month future as forward and one ATM implied volatility. Outcomes 09:45 -> 15:24: trend efficiency E and range.
Local database only. Usage: python3 2026-10-22-gamma-regime.py [main|heldout]
"""
import csv, io, math, random, re, statistics, subprocess, sys
from collections import defaultdict
from datetime import date, datetime

HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
FORWARD = ["2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16",
           "2026-10-19", "2026-10-21"]
STEP = {"NIFTY": 50, "SENSEX": 100}
IST = "at time zone 'Asia/Kolkata'"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def ncdf(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def black(F, K, T, s, call):
    v = s * math.sqrt(T); d1 = (math.log(F / K) + v * v / 2) / v; d2 = d1 - v
    return F * ncdf(d1) - K * ncdf(d2) if call else K * ncdf(-d2) - F * ncdf(-d1)


def implied(price, F, K, T, call):
    intrinsic = max(0.0, F - K) if call else max(0.0, K - F)
    if not price or price <= intrinsic + 1e-6:
        return None
    lo, hi = 0.005, 3.0
    for _ in range(80):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if black(F, K, T, mid, call) < price else (lo, mid)
    return (lo + hi) / 2


def gamma(F, K, T, s):
    v = s * math.sqrt(T); d1 = (math.log(F / K) + v * v / 2) / v
    return math.exp(-d1 * d1 / 2) / math.sqrt(2 * math.pi) / (F * v)


def years(d, expiry):
    t0 = datetime.fromisoformat(f"{d} 09:45"); t1 = datetime.fromisoformat(f"{expiry} 15:30")
    return max((t1 - t0).total_seconds(), 3600) / (365 * 86400)


def regime(spot, fwd, expiry, d, chain, u):
    """chain: {(type, strike): (oi, close)} at 09:44. Returns G or None."""
    T = years(d, expiry)
    atm = round(spot / STEP[u]) * STEP[u]
    ivs = [implied(chain.get((ty, atm), (0, None))[1], fwd, atm, T, ty == "CE") for ty in ("CE", "PE")]
    ivs = [v for v in ivs if v]
    if not ivs:
        return None
    s = statistics.mean(ivs)
    num = den = 0.0
    for (ty, k), (oi, _) in chain.items():
        if not oi or abs(k / spot - 1) > 0.05:
            continue
        g = gamma(fwd, k, T, s) * oi
        num += g if ty == "CE" else -g
        den += g
    return num / den if den > 0 else None


def bar_day(u, d):
    exp = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
            f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:45+05:30')::timestamptz")
    idx = {t: (float(o), float(h), float(l), float(c)) for t, o, h, l, c in q(
        f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' and kind = 'INDEX' "
        f"and (bar_start {IST})::date = '{d}' and (bar_start {IST})::time between '09:44' and '15:24'")}
    fut = q(f"select close from hist.candle where underlying = '{u}' and kind = 'FUTURE' and expiry = (select expiry from hist.candle "
            f"where underlying = '{u}' and kind = 'FUTURE' and (bar_start {IST})::date = '{d}' group by expiry order by sum(volume) desc limit 1) "
            f"and bar_start <= ('{d} 09:44+05:30')::timestamptz and (bar_start {IST})::date = '{d}' order by bar_start desc limit 1")
    if not exp or not exp[0][0] or "09:44" not in idx or not fut:
        return None
    spot = idx["09:44"][3]
    chain = {}
    for ty, k, oi, c in q(f"select distinct on (option_type, strike) option_type, strike, "
                          f"(select o2.oi from hist.candle o2 where o2.instrument_key = c.instrument_key and o2.oi > 0 "
                          f" and o2.bar_start <= ('{d} 09:44+05:30')::timestamptz and o2.bar_start >= ('{d} 09:00+05:30')::timestamptz order by o2.bar_start desc limit 1), close "
                          f"from hist.candle c where underlying = '{u}' and kind = 'OPTION' and expiry = '{exp[0][0]}' "
                          f"and bar_start <= ('{d} 09:44+05:30')::timestamptz and bar_start >= ('{d} 09:15+05:30')::timestamptz "
                          f"and strike between {spot * 0.95} and {spot * 1.05} order by option_type, strike, bar_start desc"):
        chain[(ty, float(k))] = (float(oi) if oi else 0.0, float(c) if c else None)
    return spot, float(fut[0][0]), exp[0][0], chain, idx


def own_day(u, d):
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")
    idx = {t: (float(o), float(h), float(l), float(c)) for t, o, h, l, c in q(
        f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), first(price, recv_ts), max(price), min(price), last(price, recv_ts) "
        f"from md.index_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
        f"and recv_ts >= '{d} 09:44+05:30' and recv_ts < '{d} 15:25+05:30' group by 1")}
    fut = q(f"select last(price, recv_ts) from md.future_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
            f"and recv_ts < '{d} 09:45+05:30' and expiry = (select min(expiry) from md.future_tick where session_date = '{d}' "
            f"and underlying = '{u}' and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30')")
    if not exp or not exp[0][0] or "09:44" not in idx or not fut or not fut[0][0]:
        return None
    spot = idx["09:44"][3]
    chain = {}
    for ty, k, oi, ltp in q(f"select option_type, strike, last(oi, recv_ts) filter (where oi > 0), last(ltp, recv_ts) filter (where ltp > 0) "
                            f"from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{exp[0][0]}' "
                            f"and recv_ts >= '{d} 09:14+05:30' and recv_ts < '{d} 09:45+05:30' "
                            f"and strike between {spot * 0.95} and {spot * 1.05} group by 1, 2"):
        chain[(ty, float(k))] = (float(oi) if oi else 0.0, float(ltp) if ltp else None)
    return spot, float(fut[0][0]), exp[0][0], chain, idx


def outcome(idx):
    w = [idx[t] for t in sorted(idx) if "09:45" <= t <= "15:24"]
    if len(w) < 300:
        return None
    o, c = w[0][0], w[-1][3]
    h, l = max(x[1] for x in w), min(x[2] for x in w)
    return (abs(c - o) / (h - l) if h > l else 0.0), (h - l) / o * 1e4


def observations(days, loader):
    obs = []
    for u in ("NIFTY", "SENSEX"):
        for d in days:
            got = loader(u, d)
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            if not got:
                continue
            spot, fwd, exp, chain, idx = got
            g = regime(spot, fwd, exp, d, chain, u); oc = outcome(idx)
            if g is None or oc is None:
                continue
            obs.append({"u": u, "d": d, "G": g, "E": oc[0], "range": oc[1], "expiry": exp == d})
    print(file=sys.stderr)
    return obs


def mean(xs):
    xs = list(xs)
    return statistics.mean(xs) if xs else float("nan")


def diff_ci(obs, key, n=10000, seed=164):
    by = defaultdict(list)
    for o in obs:
        by[o["d"]].append(o)
    keys = list(by); rng = random.Random(seed); ds = []
    for _ in range(n):
        pick = [o for k in (rng.choice(keys) for _ in keys) for o in by[k]]
        neg = [o[key] for o in pick if o["G"] < 0]; pos = [o[key] for o in pick if o["G"] >= 0]
        if neg and pos:
            ds.append(mean(neg) - mean(pos))
    ds.sort()
    return ds[int(0.025 * len(ds))], ds[int(0.975 * len(ds))]


def compare(label, obs):
    neg = [o for o in obs if o["G"] < 0]; pos = [o for o in obs if o["G"] >= 0]
    if not neg or not pos:
        print(f"  {label}: one regime empty (neg {len(neg)}, pos {len(pos)})")
        return float("nan"), float("nan"), (float("nan"), float("nan"))
    dE = mean(o["E"] for o in neg) - mean(o["E"] for o in pos)
    dR = mean(o["range"] for o in neg) - mean(o["range"] for o in pos)
    ci = diff_ci(obs, "E")
    print(f"  {label}: negative n={len(neg)} E {mean(o['E'] for o in neg):.3f} range {mean(o['range'] for o in neg):.0f} bp | "
          f"positive n={len(pos)} E {mean(o['E'] for o in pos):.3f} range {mean(o['range'] for o in pos):.0f} bp | "
          f"dE {dE:+.3f} CI95 [{ci[0]:+.3f}, {ci[1]:+.3f}]  dRange {dR:+.0f} bp")
    return dE, dR, ci


def replay_nets(path, pattern):
    nets = {}
    try:
        for line in open(path):
            m = re.search(pattern, line)
            if m:
                nets[m[1]] = int(m[2])
    except FileNotFoundError:
        pass
    return nets


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    bars = [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle where underlying = 'NIFTY' "
                            f"and kind = 'INDEX' and bar_start >= '2026-01-02') x order by d")]
    if which == "heldout":
        obs = observations(sorted(HELD_OUT), bar_day)
        compare("held-out", obs)
        for o in obs:
            print(f"   {o['u']} {o['d']} G {o['G']:+.2f} E {o['E']:.2f} range {o['range']:.0f}")
        return
    test = observations([d for d in bars if d not in HELD_OUT], bar_day)
    neg_share = sum(1 for o in test if o["G"] < 0) / len(test)
    print(f"test: {len(test)} index-days on {len({o['d'] for o in test})} days; negative-gamma share {neg_share:.0%}; "
          f"G median {statistics.median(o['G'] for o in test):+.2f}")
    dE, dR, ci = compare("all", test)
    h1 = compare("to 31 May", [o for o in test if o["d"] <= "2026-05-31"])
    h2 = compare("from 1 Jun", [o for o in test if o["d"] > "2026-05-31"])
    for u in ("NIFTY", "SENSEX"):
        compare(u, [o for o in test if o["u"] == u])
    compare("expiring index", [o for o in test if o["expiry"]])
    compare("not expiring", [o for o in test if not o["expiry"]])
    srt = sorted(test, key=lambda o: o["G"]); n3 = len(srt) // 3
    lo3, hi3 = srt[:n3], srt[-n3:]
    print(f"  G thirds: lowest (G <= {lo3[-1]['G']:+.2f}) E {mean(o['E'] for o in lo3):.3f} range {mean(o['range'] for o in lo3):.0f} bp | "
          f"highest (G >= {hi3[0]['G']:+.2f}) E {mean(o['E'] for o in hi3):.3f} range {mean(o['range'] for o in hi3):.0f} bp")
    nifty = {o["d"]: o["G"] for o in test if o["u"] == "NIFTY"}
    for name, path, pat in (("odb-v3 (A-155 bars)", "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-odbveto/.local/a155/progress.txt",
                             r" bars (\d{4}-\d\d-\d\d) sid=\d+ \{.*?net=(-?\d+)"),
                            ("mac-v1 (A-156 bars)", "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-mac2/.local/a156/progress.txt",
                             r" v1 bars (\d{4}-\d\d-\d\d) sid=\d+ \{.*?net=(-?\d+)")):
        nets = replay_nets(path, pat)
        neg = [v for d, v in nets.items() if d in nifty and nifty[d] < 0]; pos = [v for d, v in nets.items() if d in nifty and nifty[d] >= 0]
        print(f"  {name} by the NIFTY regime: negative {len(neg)} days mean Rs {mean(neg):+,.0f} | positive {len(pos)} days mean Rs {mean(pos):+,.0f}")
    fwd = observations(FORWARD, own_day)
    fdE, fdR, fci = compare("forward 7-21 Oct (own)", fwd)
    for o in fwd:
        print(f"     {o['u']} {o['d']} G {o['G']:+.2f} E {o['E']:.2f} range {o['range']:.0f} bp{' expiry' if o['expiry'] else ''}")
    c0 = 0.2 <= neg_share <= 0.8
    c1 = dE > 0 and ci[0] > 0
    c2 = h1[0] > 0 and h2[0] > 0
    c3 = dR > 0
    c4 = fdE >= 0
    if not c0:
        print("A-164: INCONCLUSIVE (a regime has < 20 % of index-days)")
        return
    for name, ok in (("(1) dE > 0, CI low > 0", c1), ("(2) both halves dE > 0", c2), ("(3) range larger when negative", c3),
                     ("(4) forward dE >= 0", c4)):
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    print("A-164:", "PASS" if c1 and c2 and c3 and c4 else "FAIL")


if __name__ == "__main__":
    main()
