#!/usr/bin/env python3
"""A-233: the futures build-up matrix for option buyers (rules fixed in the ledger before this ran).

At each 5-minute close 09:45-14:30, over the last 15 minutes of the front-month future:
  LB  price up, OI up, 15-min volume >= 1.2 x normal, future above its VWAP, index +0.15 %, put OI added below > 0 and >
      call OI added above                                            -> call 1 strike ITM
  SC  price up, OI down, last 5-min volume >= 2.5 x normal, new session high, call OI (ATM..+3) falling -> ATM call
  SB  mirror of LB                                                   -> put 1 strike ITM
  LU  mirror of SC                                                   -> ATM put
Variants: FULL (the rule), NOCHAIN (no option-chain condition), PRICEVOL (no futures-OI and no chain condition).
Exit 30 min (primary) or 25 % premium stop; 60 min reported. Option close +-0.75 %. Normal volume = median of the same
5-minute slot over the previous 20 sessions. Verdict on NIFTY; SENSEX reported.
Usage: python3 2026-10-26-buildup-matrix.py [report]
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict, deque

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a233", "trades.pkl")
PERIODS = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul-Dec25", "2025-07-01", "2025-12-31"),
           ("Jan-Sep26", "2026-01-01", "2026-09-30")]
CELLS = ("LB", "SC", "SB", "LU")
VARIANTS = ("FULL", "NOCHAIN", "PRICEVOL")


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def rng(d):
    return f"('{d} 09:15+05:30')::timestamptz and ('{d} 15:30+05:30')::timestamptz"


def last_at(series, minute, back=5):
    for m in range(minute, minute - back - 1, -1):
        if m in series:
            return series[m]
    return None


def load_day(u, d):
    idx = {hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
        f"and kind = 'INDEX' and bar_start between {rng(d)}")}
    fe = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'FUTURE' and expiry >= '{d}' "
           f"and bar_start between {rng(d)}")
    oe = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
           f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
    if len(idx) < 300 or not fe or not fe[0] or not fe[0][0] or not oe or not oe[0] or not oe[0][0]:
        return None
    fut = {hm(t): (float(c), float(v or 0), float(o or 0)) for t, c, v, o in q(
        f"select to_char(bar_start {IST},'HH24:MI'), close, volume, oi from hist.candle where underlying = '{u}' and kind = 'FUTURE' "
        f"and expiry = '{fe[0][0]}' and bar_start between {rng(d)}")}
    op = idx[min(idx)][0]
    opt_px, opt_oi = defaultdict(dict), defaultdict(dict)
    for t, k, ty, c, o in q(f"select to_char(bar_start {IST},'HH24:MI'), strike, option_type, close, coalesce(oi, 0) from hist.candle "
                            f"where underlying = '{u}' and kind = 'OPTION' and expiry = '{oe[0][0]}' and strike between {op * 0.95} "
                            f"and {op * 1.05} and bar_start between {rng(d)}"):
        key = (float(k), ty)
        if c and float(c) > 0:
            opt_px[key][hm(t)] = float(c)
        if float(o) > 0:
            opt_oi[key][hm(t)] = float(o)
    return idx, fut, opt_px, opt_oi, oe[0][0] == d


def slot_volumes(fut):
    """{5-min slot start: volume} from the per-minute futures volume (bars carry per-minute volume)."""
    out = defaultdict(float)
    for m, (_, v, _) in fut.items():
        out[m - (m - 555) % 5] += v
    return out


def signals(u, idx, fut, opt_px, opt_oi, normal5):
    """Yields (variant, cell, decision minute, option type, strike)."""
    step = STEP[u]
    ms = sorted(m for m in idx if 555 <= m < 930)
    vol5 = slot_volumes(fut)
    pv = cv = 0.0
    vwap = {}
    for m in range(555, 930):                                  # futures session VWAP by minute
        if m in fut and fut[m][1] > 0:
            pv += fut[m][0] * fut[m][1]; cv += fut[m][1]
        vwap[m] = pv / cv if cv else None
    for d in range(585, 871, 5):                               # 09:45 .. 14:30 decisions (bars up to d - 1)
        last = d - 1
        f_now, f_then = last_at(fut, last), last_at(fut, last - 15)
        i_now, i_then = last_at({m: idx[m][3] for m in ms}, last), last_at({m: idx[m][3] for m in ms}, last - 15)
        if not (f_now and f_then and i_now and i_then):
            continue
        dp, doi = f_now[0] - f_then[0], f_now[2] - f_then[2]
        slots = [d - 15, d - 10, d - 5]
        v15 = sum(vol5.get(s, 0) for s in slots)
        n15 = sum(normal5.get(s) or 0 for s in slots)
        v5, n5 = vol5.get(d - 5, 0), normal5.get(d - 5) or 0
        rvol15 = v15 / n15 if n15 else None
        rvol5 = v5 / n5 if n5 else None
        idx_chg = (i_now - i_then) / i_then * 100
        prior_hi = max(idx[m][1] for m in ms if m < d - 5) if any(m < d - 5 for m in ms) else None
        prior_lo = min(idx[m][2] for m in ms if m < d - 5) if any(m < d - 5 for m in ms) else None
        bar_hi = max(idx[m][1] for m in ms if d - 5 <= m < d) if any(d - 5 <= m < d for m in ms) else None
        bar_lo = min(idx[m][2] for m in ms if d - 5 <= m < d) if any(d - 5 <= m < d for m in ms) else None
        atm = round(i_now / step) * step

        def oi_change(strikes, ty):
            tot = 0.0
            for k in strikes:
                a, b = last_at(opt_oi.get((float(k), ty), {}), last), last_at(opt_oi.get((float(k), ty), {}), last - 15)
                if a is None or b is None:
                    return None
                tot += a - b
            return tot

        below = [atm - i * step for i in (1, 2, 3)]
        above = [atm + i * step for i in (1, 2, 3)]
        put_below, call_above = oi_change(below, "PE"), oi_change(above, "CE")
        call_atm_up, put_atm_down = oi_change([atm] + above, "CE"), oi_change([atm] + below, "PE")
        vw = vwap.get(last)
        conds = {
            "LB": (dp > 0 and idx_chg >= 0.15 and rvol15 is not None and rvol15 >= 1.2 and vw is not None and f_now[0] > vw,
                   doi > 0, put_below is not None and call_above is not None and put_below > 0 and put_below > call_above,
                   "CE", atm - step),
            "SC": (dp > 0 and rvol5 is not None and rvol5 >= 2.5 and prior_hi is not None and bar_hi is not None and bar_hi > prior_hi,
                   doi < 0, call_atm_up is not None and call_atm_up < 0, "CE", atm),
            "SB": (dp < 0 and idx_chg <= -0.15 and rvol15 is not None and rvol15 >= 1.2 and vw is not None and f_now[0] < vw,
                   doi > 0, put_below is not None and call_above is not None and call_above > 0 and call_above > put_below,
                   "PE", atm + step),
            "LU": (dp < 0 and rvol5 is not None and rvol5 >= 2.5 and prior_lo is not None and bar_lo is not None and bar_lo < prior_lo,
                   doi < 0, put_atm_down is not None and put_atm_down < 0, "PE", atm),
        }
        for cell, (price_vol, oi_ok, chain_ok, ty, k) in conds.items():
            if not price_vol:
                continue
            yield "PRICEVOL", cell, d, ty, k
            if oi_ok:
                yield "NOCHAIN", cell, d, ty, k
                if chain_ok:
                    yield "FULL", cell, d, ty, k


def price_trade(opt_px, ty, k, d):
    s = opt_px.get((float(k), ty), {})
    buy = last_at(s, d - 1)
    if buy is None:
        return None
    res = {}
    for name, hold in (("x30", 30), ("x60", 60)):
        x, why = d - 1 + hold, "time"
        for m in range(d, d - 1 + hold):
            if m in s and s[m] <= 0.75 * buy:
                x, why = m, "premium_stop"
                break
        sell = last_at(s, x) or buy
        res[name] = (sell * (1 - COST) / (buy * (1 + COST)) - 1, why)
    return buy, res


def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    days = [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' and kind = 'FUTURE' "
                            f"and bar_start >= '2024-09-01' order by 1")]
    out = []
    for u in ("NIFTY", "SENSEX"):
        hist = defaultdict(lambda: deque(maxlen=20))            # slot -> last 20 sessions' 5-min volumes
        for d in days:
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            loaded = load_day(u, d)
            if not loaded:
                continue
            idx, fut, opt_px, opt_oi, expiry_day = loaded
            normal5 = {s: statistics.median(v) for s, v in hist.items() if len(v) >= 10}
            if d >= "2024-10-01" and d <= "2026-09-30" and d not in HELD_OUT:
                taken = set()
                busy_until = {v: -1 for v in VARIANTS}
                for v, cell, dm, ty, k in signals(u, idx, fut, opt_px, opt_oi, normal5):
                    if (v, cell) in taken or dm <= busy_until[v]:
                        continue
                    p = price_trade(opt_px, ty, k, dm)
                    if p is None:
                        continue
                    taken.add((v, cell))
                    busy_until[v] = dm + 30
                    out.append(dict(u=u, d=d, v=v, cell=cell, minute=dm, ty=ty, k=k, buy=p[0], exits=p[1], expiry=expiry_day))
            for s, vol in slot_volumes(fut).items():
                hist[s].append(vol)
    print(file=sys.stderr)
    pickle.dump(out, open(CACHE, "wb"))
    return out


def boot(tr, key, n=10000, seed=233):
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
    return means[125], means[9874]


def line(tr, key="x30"):
    if not tr:
        return "n=0"
    xs = [t["exits"][key][0] for t in tr]
    return f"n={len(xs):4d} mean {statistics.mean(xs):+6.1%} median {statistics.median(xs):+6.1%} win {sum(x > 0 for x in xs) / len(xs):3.0%}"


def report(out):
    print("A-233 futures build-up matrix. Option net % of premium per trade (bars, +-0.75 %), exit 30 min unless noted\n")
    verdicts = {}
    for cell in CELLS:
        name = {"LB": "Long buildup -> ITM call", "SC": "Short covering -> ATM call", "SB": "Short buildup -> ITM put",
                "LU": "Long unwinding -> ATM put"}[cell]
        print(f"=== {cell}: {name}")
        means = {}
        for v in VARIANTS:
            nifty = [t for t in out if t["u"] == "NIFTY" and t["cell"] == cell and t["v"] == v]
            ok = []
            for lab, lo, hi in PERIODS:
                sub = [t for t in nifty if lo <= t["d"] <= hi]
                ok.append(None if len(sub) < 30 else statistics.mean(t["exits"]["x30"][0] for t in sub) > 0)
                print(f"  {v:8s} NIFTY {lab:12s} {line(sub)}   | 60 min {line(sub, 'x60')}")
            ci = boot(nifty, "x30")
            means[v] = statistics.mean(t["exits"]["x30"][0] for t in nifty) if nifty else float("nan")
            print(f"  {v:8s} NIFTY pooled       {line(nifty)}" + (f"  CI97.5 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
            sx = [t for t in out if t["u"] == "SENSEX" and t["cell"] == cell and t["v"] == v]
            print(f"  {v:8s} SENSEX pooled      {line(sx)}  (descriptive)")
            if v == "FULL":
                full = (ok, ci)
        ok, ci = full
        beats = means["FULL"] > means["PRICEVOL"]
        verdicts[cell] = ("INSUFFICIENT" if any(o is None for o in ok) else
                          "PASS" if all(ok) and ci and ci[0] > 0 and beats else "FAIL")
        print(f"  -> {cell}: {verdicts[cell]} (periods > 0 {ok}; CI low > 0 {bool(ci and ci[0] > 0)}; beats price+volume only {beats})\n")
    print("Verdicts: " + ", ".join(f"{c} {v}" for c, v in verdicts.items()))


if __name__ == "__main__":
    report(pickle.load(open(CACHE, "rb")) if sys.argv[1:] == ["report"] else run())
