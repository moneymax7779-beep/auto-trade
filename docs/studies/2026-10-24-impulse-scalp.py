#!/usr/bin/env python3
"""A-183 impulse scalp with a fixed 10 % / 20 % target (definitions and criteria fixed in the ledger before this ran).

Trigger at the close of minute m (09:20-14:50): (a) |3-min index move| >= 0.20 %; (b) 3-min futures volume >= 2x the
median of the day's earlier 3-minute windows; (c) the ATM +- 2 OI share over m-3..m on the move's side. Entry: ATM option
of the move's side at m+1. Exits E10 (+10 % / -10 % / 20 min) and E20 (+20 % / -15 % / 30 min).
Ticks: ask in, bid out (own capture). Bars: close of m+1 + 0.75 % in, high / low for target / stop (both = stop), 0.75 % out.
Usage: python3 2026-10-24-impulse-scalp.py ticks | bars2425 | bars26 | design
"""
import importlib.util, os, pickle, random, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))


def load_module(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


a = load_module("a157", "2026-10-21-atm-oi-burst.py")
b = load_module("a162", "2026-10-22-futures-oi-filter.py")
q, IST = a.q, "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
MOVE, VOL, BAND, GAP = 0.0020, 2.0, 2, 15
FIRST, LAST = 9 * 60 + 20, 14 * 60 + 50
EXITS = {"E10": (0.10, 0.10, 20), "E20": (0.20, 0.15, 30)}
BAR_COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
TICK_DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
             "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21",
             "2026-10-22"]
DESIGN = ["2026-10-23"]
CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a183"


def signals(data, u):
    """[(m, sign, has_vol, has_oi)] for every minute passing (a); (b) and (c) flagged."""
    idx = data["idx"]
    if not idx or not data["opt"]:
        return []
    minutes = list(range(9 * 60 + 14, 15 * 60 + 30))
    close = a.carried({m: v[2] for m, v in idx.items()}, minutes)
    oi = {key: a.carried(s, minutes) for key, s in data["opt"].items()}
    fut = data["fut"]
    win = {e: sum(fut.get(x, 0.0) for x in range(e - 2, e + 1)) for e in range(9 * 60 + 18, 15 * 60 + 30)}
    step = STEP[u]
    out = []
    for m in range(FIRST, LAST + 1):
        c, c0 = close.get(m), close.get(m - 3)
        if not c or not c0 or m not in idx:
            continue
        r = c / c0 - 1
        if abs(r) < MOVE:
            continue
        sign = 1 if r > 0 else -1
        base = [win[e] for e in range(9 * 60 + 18, m - 2) if e in win]          # earlier, non-overlapping-ish windows
        med = statistics.median(base) if len(base) >= 5 else 0
        has_vol = med > 0 and win.get(m, 0.0) >= VOL * med
        atm = round(c / step) * step
        dce = dpe = tot = 0.0
        ok = True
        for k in (atm + i * step for i in range(-BAND, BAND + 1)):
            for ty in ("CE", "PE"):
                s = oi.get((ty, float(k)))
                x = s.get(m - 3) if s else None
                y = s.get(m) if s else None
                if x is None or y is None:
                    ok = False
                    break
                tot += x
                if ty == "CE":
                    dce += y - x
                else:
                    dpe += y - x
            if not ok:
                break
        share = (dpe - dce) / tot if ok and tot > 0 else None
        has_oi = share is not None and share * sign > 0
        out.append((m, sign, has_vol, has_oi, atm))
    return out


def pick(sigs, need_vol, need_oi):
    last, ev = {1: -999, -1: -999}, []
    for m, sign, v, o, atm in sigs:
        if (need_vol and not v) or (need_oi and not o) or m - last[sign] < GAP:
            continue
        last[sign] = m
        ev.append((m, sign, atm))
    return ev


def expiry(u, d, source):
    if source == "ticks":
        r = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
              f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")
    else:
        r = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
              f"and bar_start >= ('{d} 09:15+05:30')::timestamptz and bar_start < ('{d} 09:20+05:30')::timestamptz")
    return r[0][0] if r and r[0][0] else None


def hhmm(m):
    return f"{m // 60:02d}:{m % 60:02d}"


def trade_ticks(u, d, exp, k, ty, m):
    rows = q(f"select extract(epoch from recv_ts)::float8, bid_px[1], ask_px[1] from md.option_tick where session_date = '{d}' "
             f"and underlying = '{u}' and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and recv_ts >= '{d} {hhmm(m + 1)}+05:30' and recv_ts < '{d} {hhmm(m + 32)}+05:30' order by recv_ts")
    rows = [(float(t), float(bd) if bd else 0.0, float(ak) if ak else 0.0) for t, bd, ak in rows]
    start = next((i for i, (_, bd, ak) in enumerate(rows) if ak > 0 and bd > 0), None)
    if start is None:
        return None
    t0, _, entry = rows[start]
    res = {}
    for name, (tgt, stp, mins) in EXITS.items():
        out = None
        for t, bd, ak in rows[start + 1:]:
            if bd <= 0:
                continue
            if t - t0 >= mins * 60:
                out = (bd, "time"); break
            if bd >= entry * (1 + tgt):
                out = (bd, "target"); break
            if bd <= entry * (1 - stp):
                out = (bd, "stop"); break
        if out is None:
            last = next((bd for _, bd, _ in reversed(rows) if bd > 0), None)
            out = (last, "time") if last else None
        res[name] = (out[0] / entry - 1, out[1]) if out else None
    return res


def trade_bars(u, d, exp, k, ty, m):
    rows = q(f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
             f"and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and bar_start >= ('{d} {hhmm(m + 1)}+05:30')::timestamptz and bar_start <= ('{d} {hhmm(m + 31)}+05:30')::timestamptz order by 1")
    bars = {a.hm(t): (float(h), float(l), float(c)) for t, h, l, c in rows if c and float(c) > 0}
    if m + 1 not in bars:
        return None
    entry = bars[m + 1][2] * (1 + BAR_COST)
    res = {}
    for name, (tgt, stp, mins) in EXITS.items():
        out = None
        for x in range(m + 2, m + 2 + mins):
            if x not in bars:
                continue
            h, l, c = bars[x]
            if l <= entry * (1 - stp):
                out = (entry * (1 - stp), "stop"); break           # both in one bar counts as the stop
            if h >= entry * (1 + tgt):
                out = (entry * (1 + tgt), "target"); break
            if x == m + 1 + mins:
                out = (c, "time"); break
        if out is None:
            later = [bars[x][2] for x in range(m + 2, m + 2 + mins) if x in bars]
            out = (later[-1], "time") if later else (bars[m + 1][2], "time")
        px, why = out
        net = (px * (1 - BAR_COST)) / entry - 1 if why == "time" else px / entry - 1 - BAR_COST
        res[name] = (net, why)
    return res


def run(days, source):
    os.makedirs(CACHE, exist_ok=True)
    path = f"{CACHE}/{source}-{days[0]}-{days[-1]}-{len(days)}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    out = defaultdict(list)                                    # variant -> [(u, d, m, sign, {exit: (net, why)})]
    for d in days:
        for u in ("NIFTY", "SENSEX"):
            try:
                data = a.day(u, d) if source == "ticks" else b.day(u, d, "bars")
            except IndexError:                                   # a day without option bars (A-162's loader)
                data = None
            print(f"\r{source} {u} {d}   ", end="", file=sys.stderr)
            if not data:
                continue
            sigs = signals(data, u)
            if not sigs:
                continue
            exp = expiry(u, d, source)
            if not exp:
                continue
            for variant, (nv, no) in {"a": (False, False), "a+b": (True, False), "a+c": (False, True), "a+b+c": (True, True)}.items():
                for m, sign, atm in pick(sigs, nv, no):
                    ty = "CE" if sign > 0 else "PE"
                    r = (trade_ticks if source == "ticks" else trade_bars)(u, d, exp, atm, ty, m)
                    if r:
                        out[variant].append((u, d, m, sign, r))
    print(file=sys.stderr)
    pickle.dump(dict(out), open(path, "wb"))
    return dict(out)


def boot(ev, ex, n=10000, seed=183):
    by = defaultdict(list)
    for e in ev:
        if e[4].get(ex):
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


def summary(ev, ex):
    xs = [e[4][ex] for e in ev if e[4].get(ex)]
    if not xs:
        return None
    nets = [x[0] for x in xs]
    return {"n": len(nets), "days": len({e[1] for e in ev}), "mean": statistics.mean(nets), "median": statistics.median(nets),
            "target": sum(x[1] == "target" for x in xs) / len(xs), "stop": sum(x[1] == "stop" for x in xs) / len(xs),
            "win": sum(v > 0 for v in nets) / len(nets)}


def fmt(s):
    if not s:
        return "n=0"
    return (f"n={s['n']:4d} days={s['days']:3d}  mean {s['mean']:+6.2%}  median {s['median']:+6.2%}  win {s['win']:.0%}  "
            f"target {s['target']:.0%}  stop {s['stop']:.0%}")


def report(name, res):
    print(f"\n== {name}")
    for ex in EXITS:
        print(f"  {ex} {EXITS[ex]}")
        for v in ("a", "a+b", "a+c", "a+b+c"):
            print(f"    {v:6s} {fmt(summary(res.get(v, []), ex))}")


def bar_days(lo, hi):
    ds = [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle where underlying = 'NIFTY' "
                          f"and kind = 'INDEX' and bar_start >= '{lo}' and bar_start < ('{hi}'::date + 1)) x order by d")]
    return [d for d in ds if d not in HELD_OUT]


if __name__ == "__main__":
    which = sys.argv[1]
    if which == "ticks":
        res = run(TICK_DAYS, "ticks")
        report(f"tick days {TICK_DAYS[0]}..{TICK_DAYS[-1]} ({len(TICK_DAYS)}; in-sample: they hold the user's examples)", res)
    elif which == "design":
        res = run(DESIGN, "ticks")
        report("23 Oct (design day, not counted)", res)
    elif which == "bars2425":
        ds = bar_days("2024-10-01", "2025-12-31")
        res = run(ds, "bars")
        h1 = {v: [e for e in evs if e[1] <= "2025-06-30"] for v, evs in res.items()}
        h2 = {v: [e for e in evs if e[1] > "2025-06-30"] for v, evs in res.items()}
        report(f"bars Oct 2024-Jun 2025", h1)
        report(f"bars Jul-Dec 2025", h2)
        for ex in EXITS:
            print(f"  {ex} a+b+c pooled CI95 {boot(res.get('a+b+c', []), ex)}")
    elif which == "bars26":
        ds = bar_days("2026-01-01", "2026-09-30")
        res = run(ds, "bars")
        report(f"bars Jan-Sep 2026 ({len(ds)} days)", res)
        for ex in EXITS:
            print(f"  {ex} a+b+c CI95 {boot(res.get('a+b+c', []), ex)}")
