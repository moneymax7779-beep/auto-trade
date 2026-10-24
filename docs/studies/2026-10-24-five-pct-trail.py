#!/usr/bin/env python3
"""A-188: let it run, exit on a 5 % give-back from the option's best bid (fixed in the ledger before this ran).

Triggers: A-183 impulse (a+b+c) and A-185 day-extreme (a+b), unchanged. Exits: T5 (5 % below the high since entry, entry
counts as the first high) and T5-armed (-10 % stop until +5 %, then the 5 % give-back); flat at 15:15.
Usage: python3 2026-10-24-five-pct-trail.py ticks | bars26 | design
"""
import importlib.util, os, pickle, random, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


imp = load("a183", "2026-10-24-impulse-scalp.py")
ext = load("a185", "2026-10-24-day-extreme-break.py")
a, b, q, IST = imp.a, imp.b, imp.q, imp.IST
FLAT = 15 * 60 + 15
GIVE, ARM, HARD = 0.05, 0.05, 0.10
COST = imp.BAR_COST
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a188"


def exits_from(path, entry):
    """path: [(seconds_from_entry, bid)] after entry. Returns {T5: (net, why, minutes), T5armed: ...} on bids."""
    out = {}
    for name in ("T5", "T5armed"):
        hi, armed, res = entry, name == "T5", None
        for t, bd in path:
            if bd <= 0:
                continue
            if not armed and bd >= entry * (1 + ARM):
                armed = True
            stop = hi * (1 - GIVE) if armed else entry * (1 - HARD)
            if bd <= stop:
                res = (bd / entry - 1, "trail" if armed and hi > entry else "stop", t / 60)
                break
            hi = max(hi, bd)
        if res is None and path:
            t, bd = path[-1]
            res = (bd / entry - 1, "flat", t / 60)
        out[name] = res
    return out


def trade_ticks(u, d, exp, k, ty, m):
    rows = q(f"select extract(epoch from recv_ts)::float8, bid_px[1], ask_px[1] from md.option_tick where session_date = '{d}' "
             f"and underlying = '{u}' and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and recv_ts >= '{d} {imp.hhmm(m + 1)}+05:30' and recv_ts < '{d} 15:15+05:30' order by recv_ts")
    rows = [(float(t), float(bd) if bd else 0.0, float(ak) if ak else 0.0) for t, bd, ak in rows]
    start = next((i for i, (_, bd, ak) in enumerate(rows) if ak > 0 and bd > 0), None)
    if start is None:
        return None
    t0, _, entry = rows[start]
    return exits_from([(t - t0, bd) for t, bd, _ in rows[start + 1:]], entry)


def trade_bars(u, d, exp, k, ty, m):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and bar_start >= ('{d} {imp.hhmm(m + 1)}+05:30')::timestamptz and bar_start <= ('{d} 15:15+05:30')::timestamptz order by 1")
    bars = {a.hm(t): (float(h), float(l), float(c)) for t, h, l, c in rows if c and float(c) > 0}
    if m + 1 not in bars:
        return None
    entry = bars[m + 1][2] * (1 + COST)
    out = {}
    for name in ("T5", "T5armed"):
        hi, armed, res = entry, name == "T5", None
        for x in range(m + 2, FLAT + 1):
            if x not in bars:
                continue
            h, l, c = bars[x]
            stop = hi * (1 - GIVE) if armed else entry * (1 - HARD)
            if l <= stop:                                       # checked against the high so far, before this bar's high
                res = (stop * (1 - COST) / entry - 1, "trail" if armed and hi > entry else "stop", x - m - 1)
                break
            if not armed and h >= entry * (1 + ARM):
                armed = True
            hi = max(hi, h)
            if x == FLAT:
                res = (c * (1 - COST) / entry - 1, "flat", x - m - 1)
        if res is None:
            last = max(x for x in bars)
            res = (bars[last][2] * (1 - COST) / entry - 1, "flat", last - m - 1)
        out[name] = res
    return out


def run(days, source):
    os.makedirs(CACHE, exist_ok=True)
    path = f"{CACHE}/{source}-{days[0]}-{days[-1]}-{len(days)}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    out = defaultdict(list)
    for d in days:
        for u in ("NIFTY", "SENSEX"):
            try:
                data = a.day(u, d) if source == "ticks" else b.day(u, d, "bars")
            except IndexError:
                data = None
            print(f"\r{source} {u} {d}   ", end="", file=sys.stderr)
            if not data:
                continue
            evs = {"impulse": [(m, s_, atm) for m, s_, atm in imp.pick(imp.signals(data, u), True, True)],
                   "extreme": ext.pick(ext.signals(data, u), "a+b")}
            if not any(evs.values()):
                continue
            exp = imp.expiry(u, d, source)
            if not exp:
                continue
            for trig, ev in evs.items():
                for m, sign, atm in ev:
                    r = (trade_ticks if source == "ticks" else trade_bars)(u, d, exp, atm, "CE" if sign > 0 else "PE", m)
                    if r:
                        out[trig].append((u, d, m, sign, r))
    print(file=sys.stderr)
    pickle.dump(dict(out), open(path, "wb"))
    return dict(out)


def boot(ev, ex, n=10000, seed=188):
    by = defaultdict(list)
    for e in ev:
        by[e[1]].append(e[4][ex][0])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def report(title, res):
    print(f"\n== {title}")
    for trig in ("impulse", "extreme"):
        ev = res.get(trig, [])
        for ex in ("T5", "T5armed"):
            xs = [e[4][ex] for e in ev if e[4].get(ex)]
            if not xs:
                print(f"  {trig:8s} {ex:8s} n=0")
                continue
            nets = [x[0] for x in xs]
            ci = boot(ev, ex)
            print(f"  {trig:8s} {ex:8s} n={len(nets):4d} mean {statistics.mean(nets):+6.2%} median {statistics.median(nets):+6.2%} "
                  f"win {sum(v > 0 for v in nets) / len(nets):.0%}  best {max(nets):+.0%}  hold {statistics.mean(x[2] for x in xs):4.1f} min"
                  + (f"  CI95 [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))


if __name__ == "__main__":
    which = sys.argv[1]
    if which == "ticks":
        report(f"tick days ({len(imp.TICK_DAYS)}; in-sample)", run(imp.TICK_DAYS, "ticks"))
    elif which == "design":
        report("23 Oct (not counted)", run(imp.DESIGN, "ticks"))
    elif which == "bars26":
        days = imp.bar_days("2026-01-01", "2026-09-30")
        report(f"bars Jan-Sep 2026 ({len(days)} days)", run(days, "bars"))
