#!/usr/bin/env python3
"""A-344 order-flow absorption reversal at a discount (definitions and criteria fixed in the ledger before this was written).

Near-month NIFTY / SENSEX futures ticks (md.future_tick, own capture). Prior session's volume-at-price value area, value
migration structure, Fibonacci 0.705-0.886 zone outside value, 5-min bars 09:15-10:45 with aggressor-classified footprints,
absorption bar A + failed retest bar B -> entry; control C = location only. Local database only.

Usage: python3 2026-11-07-absorption-reversal.py [OUTDIR]   (default .local/a344)
"""
import csv, io, json, os, statistics, subprocess, sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
VA_BIN = {"NIFTY": 5.0, "SENSEX": 10.0}      # prior-value bins
FP_BIN = {"NIFTY": 1.0, "SENSEX": 2.0}       # footprint bins
BUF = {"NIFTY": 2.0, "SENSEX": 5.0}          # stop buffer and stressed slippage, index points
HALF2 = "2026-10-19"                         # second half starts
FIRST_TRADE_DAY = "2026-09-30"
BAR_MS = 300_000


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def ms(d, hhmm):
    return int(datetime.fromisoformat(f"{d} {hhmm}:00").replace(tzinfo=IST).timestamp() * 1000)


def days(u):
    return [r[0] for r in q(f"select distinct session_date::text from md.future_tick where underlying = '{u}' "
                            f"and session_date <= '2026-11-06' order by 1")]


def load(u, d):
    """Ticks of the near-month contract 09:15-15:30: (ms, price, cumvol, bid1, ask1), and that contract's expiry."""
    exp = q(f"select min(expiry)::text from md.future_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}'")[0][0]
    rows = q(f"select (extract(epoch from recv_ts) * 1000)::bigint, price, volume, bid_px[1], ask_px[1] from md.future_tick "
             f"where session_date = '{d}' and underlying = '{u}' and expiry = '{exp}' "
             f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:30+05:30' order by recv_ts, src_seq")
    f = lambda s: float(s) if s not in ("", None) else None
    tk = [(int(t), float(p), int(v) if v else None, f(b), f(a)) for t, p, v, b, a in rows]
    return exp, tk


def classify(tk):
    """Per tick: volume increment and aggressor side (+1 buy, -1 sell, 0 none). A-304 N3: price >= previous tick's ask buy,
    <= previous bid sell, else tick rule (vs the last different trade price; unchanged -> previous side)."""
    out = []
    pv = None; pb = pa = None; last_px = None; ref_px = None; side_prev = 0
    for t, p, v, b, a in tk:
        dv = 0; side = 0
        if v is not None and pv is not None and v > pv:
            dv = v - pv
            if pa is not None and p >= pa:
                side = 1
            elif pb is not None and p <= pb:
                side = -1
            elif ref_px is not None and p > ref_px:
                side = 1
            elif ref_px is not None and p < ref_px:
                side = -1
            else:
                side = side_prev
            side_prev = side
            if last_px is not None and p != last_px:
                ref_px = last_px
            last_px = p
        if v is not None:
            pv = v if pv is None else max(pv, v)
        if b and b > 0:
            pb = b
        if a and a > 0:
            pa = a
        out.append((dv, side))
    return out


def value_area(u, tk, cl):
    vol = defaultdict(int)
    for (t, p, v, b, a), (dv, s) in zip(tk, cl):
        if dv > 0:
            vol[int(p // VA_BIN[u])] += dv
    if not vol:
        return None
    total = sum(vol.values())
    poc = max(vol, key=lambda k: (vol[k], -k))
    lo = hi = poc; acc = vol[poc]; kmax = max(vol); kmin = min(vol)
    while acc < 0.7 * total:
        can_up = hi < kmax; can_dn = lo > kmin
        if not can_up and not can_dn:
            break
        up = vol.get(hi + 1, 0) if can_up else -1; dn = vol.get(lo - 1, 0) if can_dn else -1
        if up >= dn:
            hi += 1; acc += up
        else:
            lo -= 1; acc += dn
    w = VA_BIN[u]
    prices = [p for (_, p, _, _, _) in tk]
    return {"poc": poc * w + w / 2, "val": lo * w, "vah": (hi + 1) * w, "low": min(prices), "high": max(prices)}


def bars(u, d, tk, cl, start="09:15", end="15:30"):
    """5-min bars with OHLC (trade prices), volume, delta, footprint {bin: [buy, sell]}, and tick index range."""
    s0 = ms(d, start); e0 = ms(d, end)
    out = {}
    for i, ((t, p, v, b, a), (dv, s)) in enumerate(zip(tk, cl)):
        if t < s0 or t >= e0:
            continue
        k = (t - s0) // BAR_MS
        bar = out.get(k)
        if bar is None:
            bar = out[k] = {"start": s0 + k * BAR_MS, "end": s0 + (k + 1) * BAR_MS, "o": None, "h": None, "l": None, "c": None,
                            "vol": 0, "delta": 0, "fp": defaultdict(lambda: [0, 0])}
        if dv > 0:
            if bar["o"] is None:
                bar["o"] = bar["h"] = bar["l"] = p
            bar["h"] = max(bar["h"], p); bar["l"] = min(bar["l"], p); bar["c"] = p
            bar["vol"] += dv; bar["delta"] += side_sign(s) * dv
            if s:
                bar["fp"][int(p // FP_BIN[u])][0 if s > 0 else 1] += dv
    res = [out[k] for k in sorted(out) if out[k]["o"] is not None]
    return res


def side_sign(s):
    return 1 if s > 0 else (-1 if s < 0 else 0)


def slot(bar, d):
    return (bar["start"] - ms(d, "09:15")) // BAR_MS


def absorb_ok(u, bar, direction):
    """A: aggressor against the trade dominates (delta), its volume POC at the bar's extreme third, close in trade direction."""
    rng = bar["h"] - bar["l"]
    if rng <= 0:
        return False
    w = FP_BIN[u]
    if direction > 0:
        if not (bar["delta"] < 0 and bar["c"] > bar["o"]):
            return False
        sells = {k: v[1] for k, v in bar["fp"].items() if v[1] > 0}
        if not sells:
            return False
        k = max(sells, key=lambda k: (sells[k], -k))
        return k * w + w / 2 <= bar["l"] + rng / 3
    if not (bar["delta"] > 0 and bar["c"] < bar["o"]):
        return False
    buys = {k: v[0] for k, v in bar["fp"].items() if v[0] > 0}
    if not buys:
        return False
    k = max(buys, key=lambda k: (buys[k], k))
    return k * w + w / 2 >= bar["h"] - rng / 3


def retest_ok(bar, a, direction):
    if direction > 0:
        return bar["l"] > a["l"] and bar["delta"] > 0 and bar["c"] > bar["o"] and \
            any(b >= 4 * s and b > 0 for b, s in bar["fp"].values())
    return bar["h"] < a["h"] and bar["delta"] < 0 and bar["c"] < bar["o"] and \
        any(s >= 4 * b and s > 0 for b, s in bar["fp"].values())


def first_tick_at(tk, t0, i0=0):
    for i in range(i0, len(tk)):
        if tk[i][0] >= t0 and tk[i][3] and tk[i][4] and tk[i][4] > tk[i][3]:
            return i
    return None


def simulate(u, d, tk, bs, entry_t, direction, stop, target, val_level, use_reclaim=True):
    """Both lanes. Returns dict with R per lane and exit reason. Long: buy at ask, sell at bid; short mirrored."""
    i = first_tick_at(tk, entry_t)
    if i is None:
        return None
    j = first_tick_at(tk, tk[i][0] + 1000, i)
    if j is None:
        return None
    ent = {"base": tk[i][4] if direction > 0 else tk[i][3],
           "stressed": (tk[j][4] + BUF[u]) if direction > 0 else (tk[j][3] - BUF[u])}
    risk = {k: (v - stop) * direction for k, v in ent.items()}
    if min(risk.values()) <= 0:
        return None
    # reclaim deadline: close of the 3rd bar after the entry bar
    after = [b for b in bs if b["start"] >= entry_t]
    deadline = None
    if use_reclaim:
        first3 = after[:3]
        ok = any((b["c"] - val_level) * direction > 0 for b in first3)
        if not ok and len(first3) == 3:
            deadline = first3[-1]["end"]
    t_end = ms(d, "15:15")
    reason = "TIME"; k = None
    for n in range(max(i, j) + 1, len(tk)):
        t, p, v, b, a = tk[n]
        if not b or not a or a <= b:
            continue
        exit_px = b if direction > 0 else a
        if (exit_px - stop) * direction <= 0:
            reason = "STOP"; k = n; break
        if (exit_px - target) * direction >= 0:
            reason = "TARGET"; k = n; break
        if deadline is not None and t >= deadline:
            reason = "NO_RECLAIM"; k = n; break
        if t >= t_end:
            reason = "TIME"; k = n; break
    if k is None:
        k = len(tk) - 1
    k2 = first_tick_at(tk, tk[k][0] + 1000, k) or k
    xb = tk[k][3] if direction > 0 else tk[k][4]
    xs = (tk[k2][3] - BUF[u]) if direction > 0 else (tk[k2][4] + BUF[u])
    out = {"reason": reason, "entry_ts": datetime.fromtimestamp(tk[i][0] / 1000, IST).strftime("%H:%M:%S"),
           "exit_ts": datetime.fromtimestamp(tk[k][0] / 1000, IST).strftime("%H:%M:%S")}
    for lane, x in (("base", xb), ("stressed", xs)):
        out[f"pts_{lane}"] = (x - ent[lane]) * direction
        out[f"R_{lane}"] = out[f"pts_{lane}"] / risk[lane]
    out["entry_base"] = ent["base"]; out["stop"] = stop; out["target"] = target
    return out


def run_day(u, d, tk, cl, prev1, prev2, med, arm, use_participation=True, use_reclaim=True):
    """arm: 'confirm' (A then B) or 'control' (first close inside the zone)."""
    v1, v2 = prev1["va"], prev2["va"]
    if v1["poc"] > v2["poc"] and v1["val"] > v2["val"]:
        direction = 1
    elif v1["poc"] < v2["poc"] and v1["vah"] < v2["vah"]:
        direction = -1
    else:
        return [], "SIDEWAYS"
    bs_all = bars(u, d, tk, cl)
    win_end = ms(d, "10:45")
    bs = [b for b in bs_all if b["end"] <= win_end]
    extreme = prev1["va"]["high"] if direction > 0 else prev1["va"]["low"]   # running H (long) / Lo (short) from prev open
    anchor = prev1["va"]["low"] if direction > 0 else prev1["va"]["high"]   # L (long) / Hi (short)

    def part_ok(b):
        if not use_participation:
            return True
        m = med.get(slot(b, d))
        return m is not None and b["vol"] >= m

    trades = []; losses = 0; cancelled = False; busy_until = 0; pending_a = None; a_age = 0
    for idx, b in enumerate(bs):
        extreme = max(extreme, b["h"]) if direction > 0 else min(extreme, b["l"])
        R = (extreme - anchor) * direction
        if R <= 0:
            continue
        if direction > 0:
            z_bot, z_top = extreme - 0.886 * R, extreme - 0.705 * R
            valid = z_top <= v1["val"]
            beyond = b["c"] < z_bot
            touch = b["l"] <= z_top
            inside_close = z_bot <= b["c"] <= z_top
        else:
            z_bot, z_top = extreme + 0.886 * R, extreme + 0.705 * R    # mirror: 'bot' = 0.886 level (cancel), 'top' = 0.705
            valid = z_top >= v1["vah"]
            beyond = b["c"] > z_bot
            touch = b["h"] >= z_top
            inside_close = z_top <= b["c"] <= z_bot
        if beyond:
            cancelled = True
        if cancelled or len(trades) >= 2 or losses >= 2 or b["end"] <= busy_until:
            pending_a = None
            continue
        target = extreme
        if arm == "control":
            if valid and inside_close and part_ok(b):
                stop = z_bot - BUF[u] * direction
                r = simulate(u, d, tk, bs_all, b["end"], direction, stop, target,
                             v1["val"] if direction > 0 else v1["vah"], use_reclaim)
                if r:
                    r.update({"u": u, "d": d, "dir": direction, "arm": arm})
                    trades.append(r)
                    busy_until = int(datetime.strptime(f"{d} {r['exit_ts']}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=IST).timestamp() * 1000)
                    losses += r["R_base"] < 0
            continue
        # confirm arm
        if pending_a is not None:
            a_age += 1
            if a_age > 3:
                pending_a = None
            elif retest_ok(b, pending_a, direction) and part_ok(b):
                a = pending_a; pending_a = None
                stop = (a["l"] - BUF[u]) if direction > 0 else (a["h"] + BUF[u])
                r = simulate(u, d, tk, bs_all, b["end"], direction, stop, target,
                             v1["val"] if direction > 0 else v1["vah"], use_reclaim)
                if r:
                    r.update({"u": u, "d": d, "dir": direction, "arm": arm,
                              "A": datetime.fromtimestamp(a["start"] / 1000, IST).strftime("%H:%M"),
                              "B": datetime.fromtimestamp(b["start"] / 1000, IST).strftime("%H:%M")})
                    trades.append(r)
                    busy_until = int(datetime.strptime(f"{d} {r['exit_ts']}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=IST).timestamp() * 1000)
                    losses += r["R_base"] < 0
                continue
        if valid and touch and absorb_ok(u, b, direction) and part_ok(b):
            pending_a = b; a_age = 0
    return trades, ("UP" if direction > 0 else "DOWN")


def stats(tr, lane):
    if not tr:
        return {"n": 0}
    R = [t[f"R_{lane}"] for t in tr]
    win = sum(r for r in R if r > 0); loss = -sum(r for r in R if r < 0)
    return {"n": len(R), "avgR": statistics.mean(R), "sumR": sum(R), "win": sum(r > 0 for r in R) / len(R),
            "pf": (win / loss) if loss > 0 else float("inf")}


def fmt(s):
    if s["n"] == 0:
        return "n=0"
    return f"n={s['n']} avgR={s['avgR']:+.3f} sumR={s['sumR']:+.2f} win={s['win']:.0%} PF={s['pf']:.2f}"


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else ".local/a344"
    os.makedirs(outdir, exist_ok=True)
    only = os.environ.get("A344_DAYS")
    results = defaultdict(list); log = []
    for u in ("NIFTY", "SENSEX"):
        ds = days(u)
        cache = {}
        for n, d in enumerate(ds):
            exp, tk = load(u, d)
            cl = classify(tk)
            bs = bars(u, d, tk, cl, "09:15", "10:45")
            cache[d] = {"exp": exp, "va": value_area(u, tk, cl), "slot_vol": {slot(b, d): b["vol"] for b in bs}}
            if d < FIRST_TRADE_DAY or n < 2 or (only and d not in only.split(",")):
                continue
            p1, p2 = cache[ds[n - 1]], cache[ds[n - 2]]
            if p1["exp"] != exp or p2["exp"] != exp:
                log.append(f"{u} {d} SKIP_ROLL (prior sessions on another contract)")
                continue
            prev = [cache[x]["slot_vol"] for x in ds[max(0, n - 10):n]]
            med = {}
            for s in range(18):
                vals = [pv[s] for pv in prev if s in pv]
                if len(vals) >= 5:
                    med[s] = statistics.median(vals)
            for arm, part, recl, key in (("confirm", True, True, "confirm"), ("control", True, True, "control"),
                                         ("confirm", False, True, "confirm_nopart"), ("confirm", True, False, "confirm_noreclaim")):
                tr, regime = run_day(u, d, tk, cl, p1, p2, med, arm, part, recl)
                results[key] += tr
            log.append(f"{u} {d} {regime} VAL {p1['va']['val']:.1f} VAH {p1['va']['vah']:.1f} POC {p1['va']['poc']:.1f} "
                       f"confirm {sum(1 for t in results['confirm'] if t['u']==u and t['d']==d)} "
                       f"control {sum(1 for t in results['control'] if t['u']==u and t['d']==d)}")
            print(log[-1], flush=True)
    lines = []
    for key in ("confirm", "control", "confirm_nopart", "confirm_noreclaim"):
        tr = results[key]
        for lane in ("base", "stressed"):
            h1 = [t for t in tr if t["d"] < HALF2]; h2 = [t for t in tr if t["d"] >= HALF2]
            lines.append(f"{key:18s} {lane:8s} all {fmt(stats(tr, lane))} | H1 {fmt(stats(h1, lane))} | H2 {fmt(stats(h2, lane))}")
            for u in ("NIFTY", "SENSEX"):
                lines.append(f"{'':18s} {'':8s} {u:6s} {fmt(stats([t for t in tr if t['u']==u], lane))}")
    c = results["confirm"]; k = results["control"]
    st = lambda tr: stats(tr, "stressed")
    h = lambda tr, f: [t for t in tr if (t["d"] < HALF2) == f]
    crit0 = len(c) >= 20
    crit1 = c and st(c)["avgR"] > 0 and st(c)["pf"] >= 1.3
    crit2 = all(st(h(c, f))["n"] and st(h(c, f))["avgR"] > 0 for f in (True, False))
    crit3 = all(st(h(c, f))["n"] and st(h(k, f))["n"] and st(h(c, f))["avgR"] > st(h(k, f))["avgR"] for f in (True, False))
    verdict = "INSUFFICIENT" if not crit0 else ("PASS" if (crit1 and crit2 and crit3) else "FAIL")
    lines.append(f"criteria (stressed): (0) n>=20 {crit0} [{len(c)}]; (1) avgR>0 & PF>=1.3 {bool(crit1)}; "
                 f"(2) both halves avgR>0 {crit2}; (3) beats control both halves {crit3} -> {verdict}")
    report = "\n".join(lines)
    print("\n" + report)
    with open(f"{outdir}/a344-result.txt", "w") as f:
        f.write("\n".join(log) + "\n\n" + report + "\n")
    with open(f"{outdir}/a344-trades.json", "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
