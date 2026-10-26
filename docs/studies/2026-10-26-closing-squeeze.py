#!/usr/bin/env python3
"""A-242: closing-squeeze continuation playbook (rules fixed in the ledger before this ran).

Precondition on day D (bull): close in the top 10 % of the range; over ATM +- 5 around the closing ATM (nearest expiry
after D) put OI rose and call OI fell over the day. Mirror (bear): bottom 10 %, call OI up, put OI down.
Next day D+1, 5-minute candles from 09:15:
  S1 retest bounce (ATM option): open not below the zone; zone [PDH - 0.20 %, PDH]; first candle closing 09:20-11:00
     with its low in [stop, PDH], green, close in the top 40 % of its range and >= PDH - 0.10 %, ATM +- 5 put OI change
     over the candle >= 0; stop = zone bottom - 0.02 %; half at 1R (stop of the rest to entry); rest at PDH + 0.30 %, a
     call-OI spike, the time-out (3 candles without a new high) or 15:15.
  S2 opening momentum (1 strike ITM): candle 2 or 3 closes above candle 1's high and PDH, its futures volume >= 1.5 x
     the slot's 20-session median, call OI change since 09:15 < 0; stop candle 1's low; at +20 % premium the stop moves
     to the entry level; exit on a close below the previous completed candle's low, the time-out or 15:15.
Mirrors for puts. Controls: the same entries on every day (no precondition). Option close +- 0.75 % a leg.
Usage: python3 2026-10-26-closing-squeeze.py [report]
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", ".local", "a242")
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]
OPEN, FLAT = 555, 914                      # 09:15 and the 15:14 bar (its close = 15:15)


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True,
                         stdin=subprocess.DEVNULL).stdout
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


# ---------------------------------------------------------------- data

def load_day(u, d):
    idx = {hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
        f"and kind = 'INDEX' and bar_start between {rng(d)}")}
    if len(idx) < 300:
        return None
    fe = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'FUTURE' and expiry >= '{d}' "
           f"and bar_start between {rng(d)}")
    fut = {}
    if fe and fe[0] and fe[0][0]:
        fut = {hm(t): float(v or 0) for t, v in q(
            f"select to_char(bar_start {IST},'HH24:MI'), volume from hist.candle where underlying = '{u}' and kind = 'FUTURE' "
            f"and expiry = '{fe[0][0]}' and bar_start between {rng(d)}")}
    op = idx[min(idx)][0]
    opts = defaultdict(lambda: defaultdict(dict))            # expiry -> (strike, type) -> minute -> (px, oi)
    for t, e, k, ty, c, o in q(
            f"select to_char(bar_start {IST},'HH24:MI'), expiry::text, strike, option_type, close, coalesce(oi, 0) "
            f"from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' and strike between "
            f"{op * 0.95} and {op * 1.05} and bar_start between {rng(d)}"):
        opts[e][(float(k), ty)][hm(t)] = (float(c or 0), float(o))
    return dict(idx=idx, fut=fut, opts={e: dict(v) for e, v in opts.items()})


def load(u):
    path = os.path.join(OUT, f"days-{u}.pkl")
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    days = [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = '{u}' "
                            f"and kind = 'INDEX' and bar_start >= '2024-09-01' and bar_start < '2026-10-01' order by 1")]
    data = {}
    for i, d in enumerate(days):
        print(f"\r{u} {d} {i + 1}/{len(days)}  ", end="", file=sys.stderr)
        x = load_day(u, d)
        if x:
            data[d] = x
    print(file=sys.stderr)
    pickle.dump(data, open(path, "wb"))
    return data


# ---------------------------------------------------------------- helpers

def candles(idx):
    out = []
    for j in range(75):
        ms = [m for m in range(OPEN + 5 * j, OPEN + 5 * j + 5) if m in idx]
        if not ms:
            out.append(None)
            continue
        out.append(dict(o=idx[ms[0]][0], h=max(idx[m][1] for m in ms), l=min(idx[m][2] for m in ms), c=idx[ms[-1]][3],
                        end=OPEN + 5 * j + 4))
    return out


def band(opts, atm, step, ty, m_from, m_to):
    """Sum over ATM +- 5 of OI(m_to) - OI(m_from) for one type; None if fewer than 9 of 11 contracts have both."""
    tot, n = 0.0, 0
    for k in range(-5, 6):
        s = opts.get((atm + k * step, ty))
        if not s:
            continue
        a = last_at({m: v[1] for m, v in s.items() if v[1] > 0}, m_from, 5)
        b = last_at({m: v[1] for m, v in s.items() if v[1] > 0}, m_to, 5)
        if a and b:
            tot += b - a
            n += 1
    return tot if n >= 9 else None


def precondition(day, u, d):
    idx = day["idx"]
    h = max(v[1] for v in idx.values())
    l = min(v[2] for v in idx.values())
    c = idx[max(idx)][3]
    later = sorted(e for e in day["opts"] if e > d)
    if not later or h <= l:
        return None
    opts, step = day["opts"][later[0]], STEP[u]
    atm = round(c / step) * step
    first = min(m for s in opts.values() for m in s)
    last = max(m for s in opts.values() for m in s)
    dp, dc = band(opts, atm, step, "PE", first, last), band(opts, atm, step, "CE", first, last)
    if dp is None or dc is None:
        return None
    return dict(h=h, l=l, c=c, bull=c >= h - 0.1 * (h - l) and dp > 0 and dc < 0,
                bear=c <= l + 0.1 * (h - l) and dc > 0 and dp < 0)


def ret(entry, exit_):
    return exit_ * (1 - COST) / (entry * (1 + COST)) - 1


def manage(idx, cs, opts, key, entry_m, entry_spot, sgn, stop, step, kind, one_r=None, target=None, spike_ty=None,
           trail=False, be_pct=None):
    """Walk minute bars after the entry; returns the weighted option return and the exit reason."""
    px = {m: v[0] for m, v in opts[key].items() if v[0] > 0}
    p0 = last_at(px, entry_m)
    if not p0:
        return None
    parts, left, stop_rest, half = [], 1.0, stop, False
    best = max(sgn * cs[(entry_m - OPEN) // 5]["h" if sgn > 0 else "l"], sgn * entry_spot)
    stale, spikes = 0, []
    for j in range(0, (entry_m - OPEN) // 5 + 1):           # the day's 5-min call/put OI changes up to the entry
        if cs[j] and spike_ty:
            atm = round(cs[j]["c"] / step) * step
            x = band(opts_all, atm, step, spike_ty, cs[j]["end"] - 5, cs[j]["end"])
            if x is not None:
                spikes.append(abs(x))
    for m in range(entry_m + 1, FLAT + 1):
        if m not in idx:
            continue
        s = idx[m][3]
        p = last_at(px, m)
        if not p:
            continue
        if be_pct is not None and p >= p0 * (1 + be_pct) and sgn * (entry_spot - stop_rest) > 0:
            stop_rest = entry_spot
        if sgn * (s - stop_rest) < 0:
            parts.append((left, ret(p0, p)))
            return sum(w * r for w, r in parts), "STOP" if not half else "BE_STOP"
        if target is not None and sgn * (s - target) >= 0:
            parts.append((left, ret(p0, p)))
            return sum(w * r for w, r in parts), "TARGET"
        if one_r is not None and not half and sgn * (s - one_r) >= 0:
            parts.append((0.5, ret(p0, p)))
            left, half, stop_rest = 0.5, True, entry_spot
        if trail:
            jprev = (m - OPEN) // 5 - 1                          # the last completed candle
            if jprev >= 0 and cs[jprev] and cs[jprev]["end"] < m and sgn * (s - cs[jprev]["l" if sgn > 0 else "h"]) < 0 \
                    and cs[jprev]["end"] >= entry_m:
                parts.append((left, ret(p0, p)))
                return sum(w * r for w, r in parts), "TRAIL"
        if (m - OPEN) % 5 == 4:                                 # a candle closed
            cdl = cs[(m - OPEN) // 5]
            if cdl:
                ext = sgn * (cdl["h"] if sgn > 0 else cdl["l"])
                if ext > best:
                    best, stale = ext, 0
                else:
                    stale += 1
                if spike_ty:
                    atm = round(s / step) * step
                    x = band(opts_all, atm, step, spike_ty, m - 5, m)
                    if x is not None:
                        if len(spikes) >= 3 and x > 0 and x >= 3 * statistics.mean(spikes):
                            parts.append((left, ret(p0, p)))
                            return sum(w * r for w, r in parts), "OI_SPIKE"
                        spikes.append(abs(x))
                if stale >= 3:
                    parts.append((left, ret(p0, p)))
                    return sum(w * r for w, r in parts), "TIME_OUT"
    p = last_at(px, FLAT, 15)
    parts.append((left, ret(p0, p)))
    return sum(w * r for w, r in parts), "FLAT"


opts_all = None


def s1(u, day, pdh, pdl, bull):
    global opts_all
    idx, step, sgn = day["idx"], STEP[u], 1 if bull else -1
    level = pdh if bull else pdl
    zone_far = level * (1 - sgn * 0.002)
    stop = zone_far - sgn * level * 0.0002
    if sgn * (idx[min(idx)][0] - zone_far) < 0:
        return None
    cs = candles(idx)
    for j in range(0, 21):                                       # candle closes 09:20 .. 11:00
        cdl = cs[j]
        if not cdl:
            continue
        closes = [idx[m][3] for m in range(cdl["end"] - 4, cdl["end"] + 1) if m in idx]
        worst = min(closes) if bull else max(closes)
        if sgn * (worst - stop) < 0:
            return None                                          # a close beyond the stop: the level is lost today
        lo, hi, o, c = cdl["l"], cdl["h"], cdl["o"], cdl["c"]
        inside = (stop <= lo <= level) if bull else (level <= hi <= stop)
        rejection = (c > o and c - lo >= 0.6 * (hi - lo) and c >= level * 0.999) if bull else \
                    (c < o and hi - c >= 0.6 * (hi - lo) and c <= level * 1.001)
        if not (inside and rejection):
            continue
        atm = round(c / step) * step
        defend = band(opts_all, atm, step, "PE" if bull else "CE", cdl["end"] - 5, cdl["end"])
        if defend is None or defend < 0:
            continue
        key = (atm, "CE" if bull else "PE")
        if key not in opts_all:
            return None
        r = abs(c - stop)
        out = manage(idx, cs, opts_all, key, cdl["end"], c, sgn, stop, step, "S1", one_r=c + sgn * r,
                     target=level * (1 + sgn * 0.003), spike_ty="CE" if bull else "PE")
        return None if out is None else dict(minute=cdl["end"], ret=out[0], why=out[1])
    return None


def s2(u, day, pdh, pdl, bull, vol_normal):
    idx, step, sgn = day["idx"], STEP[u], 1 if bull else -1
    cs = candles(idx)
    c0 = cs[0]
    if not c0:
        return None
    for j in (1, 2):
        cdl = cs[j]
        if not cdl:
            continue
        c = cdl["c"]
        brk = (c > c0["h"] and c > pdh) if bull else (c < c0["l"] and c < pdl)
        vol = sum(day["fut"].get(m, 0) for m in range(OPEN + 5 * j, OPEN + 5 * j + 5))
        norm = vol_normal.get(j)
        vol_ok = norm is not None and norm > 0 and vol >= 1.5 * norm
        atm = round(c / step) * step
        cover = band(opts_all, atm, step, "CE" if bull else "PE", OPEN, cdl["end"])
        if not (brk and vol_ok and cover is not None and cover < 0):
            continue
        key = (atm - sgn * step, "CE" if bull else "PE")             # 1 strike ITM
        if key not in opts_all:
            return None
        stop = c0["l"] if bull else c0["h"]
        if sgn * (c - stop) <= 0:
            return None
        out = manage(idx, cs, opts_all, key, cdl["end"], c, sgn, stop, step, "S2", trail=True, be_pct=0.20)
        return None if out is None else dict(minute=cdl["end"], ret=out[0], why=out[1])
    return None


def run():
    global opts_all
    os.makedirs(OUT, exist_ok=True)
    trades = []
    for u in ("NIFTY", "SENSEX"):
        data = load(u)
        ds = sorted(data)
        slot_hist = defaultdict(list)                            # candle index -> previous sessions' futures volumes
        for i, d in enumerate(ds):
            day = data[d]
            vol_normal = {j: statistics.median(v[-20:]) for j, v in slot_hist.items() if len(v) >= 10}
            for j in (1, 2):
                v = sum(day["fut"].get(m, 0) for m in range(OPEN + 5 * j, OPEN + 5 * j + 5))
                if v > 0:
                    slot_hist[j].append(v)
            if i == 0 or d < "2024-10-01" or d in HELD_OUT:
                continue
            prev = ds[i - 1]
            pre = precondition(data[prev], u, prev)
            exps = sorted(e for e in day["opts"] if e >= d)
            if pre is None or not exps:
                continue
            opts_all = day["opts"][exps[0]]
            zero_dte = exps[0] == d
            for bull in (True, False):
                cond = pre["bull"] if bull else pre["bear"]
                for name, fn in (("S1", lambda: s1(u, day, pre["h"], pre["l"], bull)),
                                 ("S2", lambda: s2(u, day, pre["h"], pre["l"], bull, vol_normal))):
                    t = fn()
                    if t:
                        trades.append(dict(u=u, d=d, strat=name, side="CALL" if bull else "PUT", cond=cond,
                                           zero_dte=zero_dte, **t))
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
    print(file=sys.stderr)
    pickle.dump(trades, open(os.path.join(OUT, "trades.pkl"), "wb"))
    return trades


# ---------------------------------------------------------------- report

def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def boot(rows, n=10000, seed=242):
    by = defaultdict(list)
    for d, v in rows:
        by[d].append(v)
    ks = list(by)
    if len(ks) < 2:
        return None
    r, vals = random.Random(seed), []
    for _ in range(n):
        vals.append(mean([v for k in (r.choice(ks) for _ in ks) for v in by[k]]))
    vals.sort()
    return vals[int(0.025 * n)], vals[int(0.975 * n) - 1]


def line(rows):
    xs = [t["ret"] for t in rows]
    return f"n {len(xs):4d}  mean {mean(xs):+6.1%}  win {sum(x > 0 for x in xs) / len(xs) if xs else float('nan'):4.0%}"


def report(trades):
    print(f"A-242 closing-squeeze continuation: {len(trades)} trades (all variants), bars Oct 2024-Sep 2026\n")
    for strat in ("S1", "S2"):
        for side in ("CALL", "PUT"):
            judge = ("NIFTY", "SENSEX") if strat == "S1" else ("NIFTY",)
            print(f"== {strat} {side} (verdict on {' + '.join(judge)})")
            for var, sel in (("CONDITIONED", lambda t: t["cond"]), ("ALL DAYS", lambda t: True)):
                rows = [t for t in trades if t["strat"] == strat and t["side"] == side and sel(t)]
                print(f"  {var}")
                for u in ("NIFTY", "SENSEX"):
                    print(f"    {u:7s} {line([t for t in rows if t['u'] == u])}")
                jr = [t for t in rows if t["u"] in judge]
                ok = []
                for lab, lo, hi in HALVES:
                    h = [t for t in jr if lo <= t["d"] <= hi]
                    print(f"    {lab}: {line(h)}")
                    ok.append(None if len(h) < 20 else mean([t["ret"] for t in h]) > 0)
                ci = boot([(t["d"], t["ret"]) for t in jr])
                print(f"    pooled {line(jr)}" + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
                print(f"    0DTE {line([t for t in jr if t['zero_dte']])} | other {line([t for t in jr if not t['zero_dte']])}")
                ex = defaultdict(int)
                for t in jr:
                    ex[t["why"]] += 1
                print(f"    exits {dict(ex)}")
                if var == "CONDITIONED":
                    cond_mean, cond_ok, cond_ci = mean([t["ret"] for t in jr]), ok, ci
                else:
                    all_mean = mean([t["ret"] for t in jr])
            verdict = ("INSUFFICIENT" if any(o is None for o in cond_ok) else
                       "PASS" if all(cond_ok) and cond_ci and cond_ci[0] > 0 and cond_mean > all_mean else "FAIL")
            print(f"  -> {verdict} (conditioned {cond_mean:+.1%} vs all days {all_mean:+.1%})\n")


if __name__ == "__main__":
    report(pickle.load(open(os.path.join(OUT, "trades.pkl"), "rb")) if sys.argv[1:] == ["report"] else run())
