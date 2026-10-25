#!/usr/bin/env python3
"""A-220: ma-cross exit lab (rules fixed in the ledger before this ran).

mac-v1's entries replicated on 1-minute bars (EMA9/EMA20 of 3-minute closes, SMA-seeded; Wilder ATR(14) on 3-minute bars;
stop = last confirmed 3-minute swing, strength 2; skip a stop > 3 ATR or on the wrong side; target 2R; entries 09:30-14:30;
never the expiring index; one position per index), then V0..V11 exits. Every variant keeps the initial swing stop, the
25 % premium stop and flat 15:15. Bars are walked as the engine's bar replay feeds them: open, the extremes in the bar's
direction (up bar: low then high), close.
Option: ATM, nearest weekly expiry, bought at the entry minute's open + 0.75 %, sold at the exit price - 0.75 %.
Usage: python3 2026-10-25-mac-exit-lab.py run | report | match
"""
import csv, io, math, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict
from datetime import date, timedelta

IST = "at time zone 'Asia/Kolkata'"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a220", "trades.pkl")
STEP = {"NIFTY": 50, "SENSEX": 100}
COST, BUDGET = 0.0075, 250000.0
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
OPEN, FIRST, LAST, FLAT, END = 555, 570, 870, 915, 930        # 09:15, 09:30, 14:30 (exclusive), 15:15, 15:30
TIME_LIMIT = 60                                                # A-220b sweep overrides it for V0 only
VARIANTS = ["V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12"]
LABEL = {"V0": "mac-v1 (2R + TIME_60)", "V1": "2R, no time stop", "V2": "structure trail", "V3": "3m close across EMA20",
         "V4": "3m close across VWAP", "V5": "chandelier 2 ATR", "V6": "EMA9 momentum fade", "V7": "stall 30m + EMA9",
         "V8": "volatility-scaled time + trail", "V9": "level target + TIME_60", "V10": "premium trail 20/15",
         "V11": "trail + level target + premium trail", "V12": "conditional time stop (A-220c)"}


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


# ---------------------------------------------------------------- data
def index_minutes(u):
    out = defaultdict(dict)
    for d, t, o, h, l, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                              f"from hist.candle where underlying = '{u}' and kind = 'INDEX' and bar_start >= '2024-08-01'"):
        m = hm(t)
        if OPEN <= m < END:
            out[d][m] = (float(o), float(h), float(l), float(c))
    return out


def futures_minutes(u):
    """Front-month future per day: {day: {minute: (close, volume)}}."""
    rows = q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), expiry::text, close, coalesce(volume, 0) "
             f"from hist.candle where underlying = '{u}' and kind = 'FUTURE' and bar_start >= '2024-10-01'")
    by = defaultdict(lambda: defaultdict(dict))
    for d, t, e, c, v in rows:
        if e >= d:
            by[d][e][hm(t)] = (float(c), float(v))
    return {d: es[min(es)] for d, es in by.items()}


def expiries(u):
    return {d: e for d, e in q(f"select (bar_start {IST})::date::text, min(expiry)::text from hist.candle where underlying = '{u}' "
                                f"and kind = 'OPTION' and expiry >= (bar_start {IST})::date and (bar_start {IST})::time between '09:15' and '09:20' "
                                f"group by 1")}


_opt = {}


def option_bars(u, d, e, k, ty):
    key = (u, d, e, k, ty)
    if key not in _opt:
        _opt[key] = {hm(t): (float(o), float(c)) for t, o, c in q(
            f"select to_char(bar_start {IST},'HH24:MI'), open, close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
            f"and expiry = '{e}' and strike = {k} and option_type = '{ty}' and bar_start >= ('{d} 09:15+05:30')::timestamptz "
            f"and bar_start < ('{d} 15:30+05:30')::timestamptz") if c and float(c) > 0}
    return _opt[key]


def levels(days, mins, d):
    prev = [x for x in days if x < d]
    if not prev:
        return {}
    p = mins[prev[-1]]
    lv = {"PDH": max(b[1] for m, b in p.items() if m < FLAT), "PDL": min(b[2] for m, b in p.items() if m < FLAT),
          "pORH": max((b[1] for m, b in p.items() if m < FIRST), default=float("nan")),
          "pORL": min((b[2] for m, b in p.items() if m < FIRST), default=float("nan"))}
    dd = date.fromisoformat(d)
    wk = [x for x in prev if date.fromisoformat(x).isocalendar()[:2] == (dd - timedelta(days=7)).isocalendar()[:2]]
    pm = (dd.year, dd.month - 1) if dd.month > 1 else (dd.year - 1, 12)
    mo = [x for x in prev if (date.fromisoformat(x).year, date.fromisoformat(x).month) == pm]
    for name, ds in (("PW", wk), ("PM", mo)):
        if ds:
            lv[name + "H"] = max(b[1] for x in ds for m, b in mins[x].items() if m < FLAT)
            lv[name + "L"] = min(b[2] for x in ds for m, b in mins[x].items() if m < FLAT)
    return lv


# ---------------------------------------------------------------- indicators on 3-minute bars
class Ema:
    def __init__(self, n):
        self.n, self.k, self.vals, self.v = n, 2 / (n + 1), [], float("nan")

    def update(self, x):
        self.vals.append(x)
        if len(self.vals) == self.n:
            self.v = sum(self.vals) / self.n
        elif len(self.vals) > self.n:
            self.v = self.k * x + (1 - self.k) * self.v
        return self.v


class WilderAtr:
    def __init__(self, n=14):
        self.n, self.trs, self.v, self.prev = n, [], float("nan"), None

    def update(self, h, l, c):
        tr = h - l if self.prev is None else max(h - l, abs(h - self.prev), abs(l - self.prev))
        self.prev = c
        self.trs.append(tr)
        if len(self.trs) == self.n:
            self.v = sum(self.trs) / self.n
        elif len(self.trs) > self.n:
            self.v = (self.v * (self.n - 1) + tr) / self.n
        return self.v


def three_minute_states(mm):
    """Per 3-minute close (the minute the next 3-min bar opens): dict with close, ema9, ema20, atr, last swing high/low,
    the swing newly confirmed at this close, ema9 three bars earlier."""
    e9, e20, atr = Ema(9), Ema(20), WilderAtr(14)
    bars, swings_h, swings_l, out = [], [], [], {}
    ema9_hist = []
    for s in range(OPEN, END, 3):
        ms = [m for m in range(s, s + 3) if m in mm]
        if not ms:
            continue
        o, h, l, c = mm[ms[0]][0], max(mm[m][1] for m in ms), min(mm[m][2] for m in ms), mm[ms[-1]][3]
        bars.append((h, l))
        v9, v20, a = e9.update(c), e20.update(c), atr.update(h, l, c)
        ema9_hist.append(v9)
        new_low = new_high = None
        if len(bars) >= 5:
            ph, pl = bars[-3]
            win = bars[-5:]
            if all(ph > x[0] for i, x in enumerate(win) if i != 2):
                swings_h.append(ph); new_high = ph
            if all(pl < x[1] for i, x in enumerate(win) if i != 2):
                swings_l.append(pl); new_low = pl
        out[s + 3] = dict(close=c, ema9=v9, ema20=v20, atr=a, swing_high=swings_h[-1] if swings_h else float("nan"),
                          swing_low=swings_l[-1] if swings_l else float("nan"), new_high=new_high, new_low=new_low,
                          ema9_3ago=ema9_hist[-4] if len(ema9_hist) >= 4 else float("nan"))
    return out


def vwap_proxy(fut, mm):
    """Spot VWAP proxy per minute: futures VWAP minus the 15-minute mean basis; None without traded volume."""
    out, pv, vol, basis = {}, 0.0, 0.0, []
    for m in range(OPEN, END):
        if m in fut and fut[m][1] > 0:
            pv += fut[m][0] * fut[m][1]; vol += fut[m][1]
        if m in fut and m in mm:
            basis.append(fut[m][0] - mm[m][3]); basis = basis[-15:]
        out[m] = (pv / vol - sum(basis) / len(basis)) if vol > 0 and basis else None
    return out


# ---------------------------------------------------------------- one index-day, one variant
def path(b):
    o, h, l, c = b
    return [o, l, h, c] if c >= o else [o, h, l, c]


def simulate(v, mm, st, vw, lv, opt_for):
    trades, pos, prev_diff = [], None, float("nan")
    for m in range(OPEN, END):
        if m not in mm:
            continue
        close3 = st.get(m)                                   # a 3-minute bar just closed (decisions at this minute)
        held = pos is not None                               # the engine sees the position open all this minute
        # ---- exits on an open position (intra-minute price path first, then 3-minute-close rules)
        if pos:
            sgn, ob = pos["sgn"], pos["opt"]
            reason, px = None, None
            if m >= FLAT:
                reason = "FLAT_BY"
            else:
                for p in path(mm[m]):
                    fav = sgn * (p - pos["entry"])
                    pos["mfe"] = max(pos["mfe"], fav)
                    if sgn * (p - pos["best"]) > 0:
                        pos["best"], pos["best_t"] = p, m
                    if pos["target"] is not None and sgn * (p - pos["target"]) >= 0:
                        reason = "TARGET"; break
                    if sgn * (p - pos["stop"]) < 0:
                        reason = "STOP"; break
                if not reason and m in ob:
                    oc = ob[m][1]
                    if oc <= 0.75 * pos["prem"]:
                        reason, px = "PREMIUM_STOP", 0.75 * pos["prem"]
                    if not reason and v in ("V10", "V11"):
                        pos["peak"] = max(pos["peak"], oc)
                        if pos["peak"] >= 1.20 * pos["prem"] and oc <= 0.85 * pos["peak"]:
                            reason, px = "PREMIUM_TRAIL", oc
                if not reason and v in ("V0", "V9") and m - pos["t"] >= (TIME_LIMIT if v == "V0" else 60):
                    reason = "TIME_60"
                if not reason and close3 is not None:
                    c, e9, e20 = close3["close"], close3["ema9"], close3["ema20"]
                    if v == "V3" and sgn * (c - e20) < 0:
                        reason = "EMA20_CLOSE"
                    elif v == "V4" and vw.get(m - 1) is not None and sgn * (c - vw[m - 1]) < 0:
                        reason = "VWAP_CLOSE"
                    elif v == "V5":
                        pos["best3"] = c if pos["best3"] is None else (max if sgn > 0 else min)(pos["best3"], c)
                        if sgn * (c - (pos["best3"] - sgn * 2.0 * close3["atr"])) < 0:
                            reason = "CHANDELIER"
                    elif v == "V6" and sgn * (e9 - close3["ema9_3ago"]) < 0:
                        reason = "MOMENTUM_FADE"
                    elif v == "V7" and m - pos["best_t"] >= 30 and sgn * (c - e9) < 0:
                        reason = "STALL"
                    elif v == "V8" and not pos["trail_on"] and m - pos["t"] >= pos["limit"]:
                        if pos["mfe"] < pos["r"]:
                            reason = "VOL_TIME"
                        else:
                            pos["trail_on"] = True
                    elif v == "V12" and not pos["trail_on"] and m - pos["t"] >= 60:
                        vwp = vw.get(m - 1)
                        working = sgn * (c - pos["entry"]) >= 0.5 * pos["r"]
                        intact = sgn * (c - e20) > 0 and (vwp is None or sgn * (c - vwp) > 0)
                        if working and intact:
                            pos["trail_on"] = True
                        else:
                            reason = "TIME_60"
                    # structure trail: raise the stop to a newly confirmed swing in the trade's favour
                    if not reason and (v in ("V2", "V11") or (v in ("V8", "V12") and pos["trail_on"])):
                        nw = close3["new_low"] if sgn > 0 else close3["new_high"]
                        if nw is not None and sgn * (nw - pos["stop"]) > 0 and sgn * (c - nw) > 0:
                            pos["stop"] = nw
            if reason:
                if px is None:
                    px = ob[m][0] if m in ob and reason in ("FLAT_BY", "TIME_60") else (ob[m][1] if m in ob else None)
                    if px is None:
                        later = [x for x in ob if x >= m]
                        px = ob[min(later)][1] if later else pos["prem"]
                net = px * (1 - COST) / (pos["prem"] * (1 + COST)) - 1
                trades.append(dict(side=pos["side"], t=pos["t"], x=m, why=reason, net=net, rs=net * BUDGET, px_in=pos["prem"], px_out=px,
                                   r_mult=sgn * ((mm[m][3] if reason not in ("TARGET", "STOP") else (pos["target"] if reason == "TARGET" else pos["stop"])) - pos["entry"]) / pos["r"]))
                pos = None
        # ---- entries at a 3-minute close
        if close3 is None:
            continue
        diff = close3["ema9"] - close3["ema20"]
        up = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff < 0 < diff
        dn = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff > 0 > diff
        prev_diff = diff if not math.isnan(diff) else prev_diff
        if held or pos or not (up or dn) or not FIRST <= m < LAST:
            continue
        spot, atr = mm[m][0], close3["atr"]
        stop = close3["swing_low"] if up else close3["swing_high"]
        risk = (spot - stop) if up else (stop - spot)
        if math.isnan(stop) or not atr > 0 or not risk > 0 or risk > 3.0 * atr:
            continue
        sgn = 1 if up else -1
        ob = opt_for("CE" if up else "PE", spot)
        if not ob or m not in ob:
            continue
        prem = ob[m][0]
        target = spot + sgn * 2.0 * risk
        if v in ("V9", "V11"):
            ahead = sorted((abs(x - spot) for x in lv.values() if not math.isnan(x) and sgn * (x - spot) >= risk and abs(x - spot) <= 3 * risk))
            if ahead:
                target = spot + sgn * ahead[0]
        bars_to_target = 2 * risk / atr
        pos = dict(side="CE" if up else "PE", sgn=sgn, t=m, entry=spot, stop=stop, target=target, r=risk, prem=prem, opt=ob,
                   mfe=0.0, best=spot, best_t=m, best3=None, peak=prem, trail_on=False,
                   limit=min(120, max(30, 2 * bars_to_target * 3)))
    return trades


# ---------------------------------------------------------------- run / report
def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    out = []
    for u in ("NIFTY", "SENSEX"):
        mins, futs, exps = index_minutes(u), futures_minutes(u), expiries(u)
        days = sorted(mins)
        for d in days:
            if d < "2024-10-01" or d in HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue                                       # no option data, held out, short day, or the expiring index
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            mm = mins[d]
            st = three_minute_states(mm)
            vw = vwap_proxy(futs.get(d, {}), mm)
            lv = levels(days, mins, d)
            e = exps[d]
            opt_for = lambda ty, spot: option_bars(u, d, e, round(spot / STEP[u]) * STEP[u], ty)
            for v in VARIANTS:
                for t in simulate(v, mm, st, vw, lv, opt_for):
                    t.update(v=v, u=u, d=d)
                    out.append(t)
    print(file=sys.stderr)
    pickle.dump(out, open(CACHE, "wb"))
    return out


def run_only(vs):
    out = []
    for u in ("NIFTY", "SENSEX"):
        mins, futs, exps = index_minutes(u), futures_minutes(u), expiries(u)
        days = sorted(mins)
        for d in days:
            if d < "2024-10-01" or d in HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue
            mm = mins[d]; st = three_minute_states(mm); vw = vwap_proxy(futs.get(d, {}), mm); lv = levels(days, mins, d); e = exps[d]
            opt_for = lambda ty, spot: option_bars(u, d, e, round(spot / STEP[u]) * STEP[u], ty)
            for v in vs:
                for t in simulate(v, mm, st, vw, lv, opt_for):
                    t.update(v=v, u=u, d=d); out.append(t)
    pickle.dump(out, open(CACHE.replace("trades.pkl", "v12.pkl"), "wb"))
    return out


def per_day(tr):
    by = defaultdict(float)
    for t in tr:
        by[(t["u"], t["d"])] += t["rs"]
    return by


def boot_diff(a, b, keys, n=10000, seed=220):
    keys = list(keys)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    diffs = [a.get(k, 0.0) - b.get(k, 0.0) for k in keys]
    for _ in range(n):
        s = [diffs[rng.randrange(len(diffs))] for _ in diffs]
        means.append(sum(s))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def report(out):
    periods = [("design H1 Oct24-Jun25", "2024-10-01", "2025-06-30"), ("design H2 Jul-Dec25", "2025-07-01", "2025-12-31"),
               ("validation Jan-Sep26", "2026-01-01", "2026-09-30")]
    allkeys = {(t["u"], t["d"]) for t in out}
    print("A-220 ma-cross exit lab: option net per trade at a fixed Rs 2.5 L premium; ±0.75 % costs; 1-minute bars\n")
    base = {p[0]: per_day([t for t in out if t["v"] == "V0" and p[1] <= t["d"] <= p[2]]) for p in periods}
    print("| variant | " + " | ".join(f"{p[0]}: Rs (n, PF, win)" for p in periods) + " | validation − V0 (95 % CI, paired days) | rule |")
    print("|---|---|---|---|---|---|")
    for v in VARIANTS:
        cells, beats = [], []
        for p in periods:
            tr = [t for t in out if t["v"] == v and p[1] <= t["d"] <= p[2]]
            w = sum(t["rs"] for t in tr if t["rs"] > 0); l = -sum(t["rs"] for t in tr if t["rs"] < 0)
            tot = sum(t["rs"] for t in tr)
            cells.append(f"{tot:+,.0f} ({len(tr)}, {w / l if l else 0:.2f}, {sum(t['rs'] > 0 for t in tr) / max(1, len(tr)):.0%})")
            beats.append(tot - sum(base[p[0]].values()))
        vd = per_day([t for t in out if t["v"] == v and periods[2][1] <= t["d"] <= periods[2][2]])
        keys = {k for k in allkeys if periods[2][1] <= k[1] <= periods[2][2]}
        ci = boot_diff(vd, base[periods[2][0]], keys)
        ok = all(b > 0 for b in beats) and ci and ci[0] > 0
        tag = "—" if v == "V0" else ("ENGINE TEST" if ok else ("positive, not significant" if all(b > 0 for b in beats) else "no"))
        print(f"| {v} {LABEL[v]} | " + " | ".join(cells) + f" | {beats[2]:+,.0f} [{ci[0]:+,.0f}, {ci[1]:+,.0f}] | {tag} |")
    print("\nExit reasons (all periods):")
    for v in VARIANTS:
        c = defaultdict(lambda: [0, 0.0])
        for t in out:
            if t["v"] == v:
                c[t["why"]][0] += 1; c[t["why"]][1] += t["rs"]
        print(f"  {v:4s} " + ", ".join(f"{k} {n} ({s:+,.0f})" for k, (n, s) in sorted(c.items(), key=lambda kv: -kv[1][0])))


def match(out):
    """Replica V0 entries vs the engine's mac trades in A-199's bar replays (sessions from its progress file)."""
    import re
    sids = []
    for l in open(os.path.join(HERE, "..", "..", "..", "auto-trade-deploy", ".local", "hist-2025-26", "progress.txt")):
        mm = re.search(r" bars \S+ sid=(\d+)", l)
        if mm:
            sids.append(mm.group(1))
    eng = {(u, d, s, hm(t)) for d, u, s, t in q(
        f"select s.session_date::text, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI') "
        f"from trade.session s join trade.\"position\" p on p.session_id = s.id where s.id in ({','.join(sids)}) and p.strategy_id = 'ma-cross'")}
    days = {(u, d) for (u, d, s, t) in eng}
    rep = {(t["u"], t["d"], t["side"], t["t"]) for t in out if t["v"] == "V0" and (t["u"], t["d"]) in days}
    hit = sum(1 for (u, d, s, t) in eng if any((u, d, s, t + k) in rep for k in range(-3, 4)))
    hit2 = sum(1 for (u, d, s, t) in rep if any((u, d, s, t + k) in eng for k in range(-3, 4)))
    print(f"engine mac trades on those bar days: {len(eng)}; matched by the replica (±3 min): {hit} ({hit / max(1, len(eng)):.0%}); "
          f"replica trades {len(rep)}, matched by the engine {hit2} ({hit2 / max(1, len(rep)):.0%})")


def sweep():
    """A-220b: time-stop sweep (V0 with T minutes), cost sensitivity, and V1 trades still open at 60 minutes."""
    global TIME_LIMIT
    periods = [("H1 Oct24-Jun25", "2024-10-01", "2025-06-30"), ("H2 Jul-Dec25", "2025-07-01", "2025-12-31"),
               ("validation Jan-Sep26", "2026-01-01", "2026-09-30")]
    data = []
    for u in ("NIFTY", "SENSEX"):
        mins, futs, exps = index_minutes(u), futures_minutes(u), expiries(u)
        days = sorted(mins)
        for d in days:
            if d < "2024-10-01" or d in HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue
            mm = mins[d]
            data.append((u, d, mm, three_minute_states(mm), vwap_proxy(futs.get(d, {}), mm), levels(days, mins, d), exps[d]))
    print("A-220b (1) time-stop sweep: V0 (2R target, swing stop, premium stop, 15:15) with the time stop at T minutes\n")
    print("| T | " + " | ".join(f"{p[0]}: Rs (n, PF)" for p in periods) + " | design H1+H2 |\n|---|---|---|---|---|")
    for T in (20, 30, 45, 60, 75, 90, 120, 10 ** 6):
        TIME_LIMIT = T
        tr = []
        for u, d, mm, st, vw, lv, e in data:
            opt_for = lambda ty, spot, u=u, d=d, e=e: option_bars(u, d, e, round(spot / STEP[u]) * STEP[u], ty)
            for t in simulate("V0", mm, st, vw, lv, opt_for):
                t.update(u=u, d=d); tr.append(t)
        cells, des = [], 0.0
        for i, p in enumerate(periods):
            x = [t["rs"] for t in tr if p[1] <= t["d"] <= p[2]]
            w = sum(v for v in x if v > 0); l = -sum(v for v in x if v < 0)
            cells.append(f"{sum(x):+,.0f} ({len(x)}, {w / l if l else 0:.2f})")
            des += sum(x) if i < 2 else 0
        print(f"| {'none' if T > 10 ** 5 else T} | " + " | ".join(cells) + f" | {des:+,.0f} |")
    TIME_LIMIT = 60
    out = pickle.load(open(CACHE, "rb"))
    print("\nA-220b (2) cost sensitivity: per-period totals at ±0.25 % a side and at zero cost (from the same fills)\n")
    print("| variant | " + " | ".join(f"{p[0]} ±0.25 % / gross" for p in periods) + " |\n|---|---|---|---|")
    for v in VARIANTS:
        cells = []
        for p in periods:
            x = [t for t in out if t["v"] == v and p[1] <= t["d"] <= p[2]]
            c25 = sum((t["px_out"] * 0.9975 / (t["px_in"] * 1.0025) - 1) * BUDGET for t in x)
            g = sum((t["px_out"] / t["px_in"] - 1) * BUDGET for t in x)
            cells.append(f"{c25:+,.0f} / {g:+,.0f}")
        print(f"| {v} {LABEL[v]} | " + " | ".join(cells) + " |")
    print("\nA-220b (3) V1 (no time stop) trades still open 60 minutes after entry:\n")
    for p in periods:
        x = [t for t in out if t["v"] == "V1" and p[1] <= t["d"] <= p[2] and t["x"] - t["t"] > 60]
        if x:
            print(f"  {p[0]}: {len(x)} trades; later TARGET {sum(t['why'] == 'TARGET' for t in x)}, STOP {sum(t['why'] == 'STOP' for t in x)}, "
                  f"premium stop {sum(t['why'] == 'PREMIUM_STOP' for t in x)}, 15:15 {sum(t['why'] == 'FLAT_BY' for t in x)}; "
                  f"their final Rs {sum(t['rs'] for t in x):+,.0f}")
    v0 = {(t["u"], t["d"], t["t"]): t for t in out if t["v"] == "V0" and t["why"] == "TIME_60"}
    pairs = [(v0[k], t) for t in out if t["v"] == "V1" for k in [(t["u"], t["d"], t["t"])] if k in v0]
    for p in periods:
        pp = [(a, b) for a, b in pairs if p[1] <= a["d"] <= p[2]]
        if pp:
            print(f"  {p[0]}: {len(pp)} trades V0 cut at 60 min: V0 Rs {sum(a['rs'] for a, b in pp):+,.0f} vs held (V1) Rs {sum(b['rs'] for a, b in pp):+,.0f}; "
                  f"held ended TARGET {sum(b['why'] == 'TARGET' for a, b in pp)}, STOP {sum(b['why'] == 'STOP' for a, b in pp)}, "
                  f"premium stop {sum(b['why'] == 'PREMIUM_STOP' for a, b in pp)}, 15:15 {sum(b['why'] == 'FLAT_BY' for a, b in pp)}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "run":
        report(run())
    elif cmd == "report":
        report(pickle.load(open(CACHE, "rb")))
    elif cmd == "v12":
        VARIANTS[:] = ["V0", "V12"]
        report(run_only(["V0", "V12"]))
    elif cmd == "sweep":
        sweep()
    elif cmd == "match":
        match(pickle.load(open(CACHE, "rb")))
