#!/usr/bin/env python3
"""A-222: ma-cross entry and stop lab (rules fixed in the ledger before this ran). Reuses the A-220 replica
(2026-10-25-mac-exit-lab.py: data, indicators, option pricing, costs); exits are mac-v1's for every variant
(2R of the variant's own risk, TIME_60, 25 % premium stop, 15:15).
E0 mac-v1 | S1 ATR stop 1.5x | S2 swing + 0.5 ATR buffer | C1 skip if 60-min range < 2.5 ATR | C2 skip if >= 2 crosses in 60 min
| W1 warm-up state entry | X best stop + better chop + W1 (chosen on design).
Usage: python3 2026-10-25-mac-entry-lab.py
"""
import importlib.util, math, os, pickle, random, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("lab", os.path.join(HERE, "2026-10-25-mac-exit-lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
CACHE = os.path.join(HERE, "..", "..", ".local", "a222", "trades.pkl")
PERIODS = [("design H1 Oct24-Jun25", "2024-10-01", "2025-06-30"), ("design H2 Jul-Dec25", "2025-07-01", "2025-12-31"),
           ("validation Jan-Sep26", "2026-01-01", "2026-09-30")]


def simulate(cfg, mm, st, opt_for, vw=None, prev_close=None):
    """cfg: dict(stop='swing'|'atr'|'buffer', chop=None|'range'|'whipsaw', warmup=bool)."""
    trades, pos, prev_diff, warm_done = [], None, float("nan"), False
    cross_minutes = []
    for m in range(lab.OPEN, lab.END):
        if m not in mm:
            continue
        close3 = st.get(m)
        held = pos is not None
        if pos:
            sgn, ob = pos["sgn"], pos["opt"]
            reason, px = None, None
            if m >= lab.FLAT:
                reason = "FLAT_BY"
            else:
                for p in lab.path(mm[m]):
                    if sgn * (p - pos["target"]) >= 0:
                        reason = "TARGET"; break
                    if sgn * (p - pos["stop"]) < 0:
                        reason = "STOP"; break
                if not reason and m in ob and ob[m][1] <= 0.75 * pos["prem"]:
                    reason, px = "PREMIUM_STOP", 0.75 * pos["prem"]
                if not reason and m - pos["t"] >= 60:
                    reason = "TIME_60"
            if reason:
                if px is None:
                    px = ob[m][0] if m in ob and reason in ("FLAT_BY", "TIME_60") else (ob[m][1] if m in ob else None)
                    if px is None:
                        later = [x for x in ob if x >= m]
                        px = ob[min(later)][1] if later else pos["prem"]
                net = px * (1 - lab.COST) / (pos["prem"] * (1 + lab.COST)) - 1
                trades.append(dict(side=pos["side"], t=pos["t"], x=m, why=reason, net=net, rs=net * lab.BUDGET, px_in=pos["prem"],
                                   px_out=px, kind=pos["kind"]))
                pos = None
        if close3 is None:
            continue
        diff = close3["ema9"] - close3["ema20"]
        up = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff < 0 < diff
        dn = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff > 0 > diff
        kind = "cross"
        if (up or dn):
            cross_minutes.append(m)
        if cfg.get("warmup") and not warm_done and not math.isnan(diff) and math.isnan(prev_diff):
            warm_done = True                                   # the first 3-minute close with EMA20
            c, day_open = close3["close"], mm[min(mm)][0]
            if diff > 0 and c > close3["ema9"] and c > close3["ema20"] and (c - day_open) / day_open >= 0.003:
                up, kind = True, "warmup"
            elif diff < 0 and c < close3["ema9"] and c < close3["ema20"] and (day_open - c) / day_open >= 0.003:
                dn, kind = True, "warmup"
        prev_diff = diff if not math.isnan(diff) else prev_diff
        if held or pos or not (up or dn) or not lab.FIRST <= m < lab.LAST:
            continue
        spot, atr = mm[m][0], close3["atr"]
        if not atr > 0:
            continue
        if cfg.get("filters"):                                 # A-224 F1-F5
            sgn0 = 1 if up else -1
            day_open = mm[min(mm)][0]
            if prev_close and sgn0 * (day_open - prev_close) / prev_close * 100 < -0.3:
                continue                                       # F1 gap against
            vwp = (vw or {}).get(m - 1)
            if vwp is not None and sgn0 * (spot - vwp) < -1.0 * atr:
                continue                                       # F2 > 1 ATR on the wrong side of VWAP
            if 720 <= m < 780:
                continue                                       # F3 lunch hour
            drift = sgn0 * (spot - day_open) / day_open * 100
            if -0.3 <= drift < -0.1:
                continue                                       # F4 mild counter-drift
            swing0 = close3["swing_low"] if up else close3["swing_high"]
            if not math.isnan(swing0) and sgn0 * (spot - swing0) < 1.25 * atr:
                continue                                       # F5 stop closer than 1.25 ATR
        if cfg.get("chop") == "range":
            win = [mm[x] for x in range(m - 60, m) if x in mm]
            if win and max(b[1] for b in win) - min(b[2] for b in win) < 2.5 * atr:
                continue
        if cfg.get("chop") == "whipsaw" and sum(1 for x in cross_minutes if m - 60 <= x < m) >= 2:
            continue
        sgn = 1 if up else -1
        if cfg["stop"] == "atr":
            stop = spot - sgn * 1.5 * atr
        else:
            sw = close3["swing_low"] if up else close3["swing_high"]
            if math.isnan(sw):
                continue
            stop = sw - sgn * 0.5 * atr if cfg["stop"] == "buffer" else sw
        risk = sgn * (spot - stop)
        if cfg.get("data_stop") and risk > 0:                  # A-224 D1: stop at 0.75 x the swing distance, same target
            target_r = risk
            stop = spot - sgn * 0.75 * risk
        else:
            target_r = None
        if not risk > 0 or (cfg["stop"] != "atr" and risk > 3.0 * atr + (0.5 * atr if cfg["stop"] == "buffer" else 0)):
            continue
        ob = opt_for("CE" if up else "PE", spot)
        if not ob or m not in ob:
            continue
        pos = dict(side="CE" if up else "PE", sgn=sgn, t=m, entry=spot, stop=stop, target=spot + sgn * 2.0 * (target_r or risk), r=risk,
                   prem=ob[m][0], opt=ob, kind=kind)
    return trades


CFGS = {"E0": dict(stop="swing"), "S1": dict(stop="atr"), "S2": dict(stop="buffer"), "C1": dict(stop="swing", chop="range"),
        "C2": dict(stop="swing", chop="whipsaw"), "W1": dict(stop="swing", warmup=True)}
LABEL = {"E0": "mac-v1", "S1": "ATR stop 1.5x", "S2": "swing + 0.5 ATR buffer", "C1": "skip tight 60-min range",
         "C2": "skip after 2+ crosses in 60 min", "W1": "warm-up state entry", "X": "combined"}


def load_days():
    data = []
    for u in ("NIFTY", "SENSEX"):
        mins, exps = lab.index_minutes(u), lab.expiries(u)
        for d in sorted(mins):
            if d < "2024-10-01" or d in lab.HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue
            data.append((u, d, mins[d], lab.three_minute_states(mins[d]), exps[d]))
    return data


def run_cfg(name, cfg, data):
    out = []
    for u, d, mm, st, e in data:
        opt_for = lambda ty, spot, u=u, d=d, e=e: lab.option_bars(u, d, e, round(spot / lab.STEP[u]) * lab.STEP[u], ty)
        for t in simulate(cfg, mm, st, opt_for):
            t.update(v=name, u=u, d=d); out.append(t)
    return out


def tot(out, v, p, cost=None):
    xs = [t for t in out if t["v"] == v and p[1] <= t["d"] <= p[2]]
    if cost is None:
        return sum(t["rs"] for t in xs), xs
    return sum((t["px_out"] * (1 - cost) / (t["px_in"] * (1 + cost)) - 1) * lab.BUDGET for t in xs), xs


def main():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    data = load_days()
    out = []
    for name, cfg in CFGS.items():
        print(f"{name} ...", file=sys.stderr)
        out += run_cfg(name, cfg, data)
    design = lambda v: tot(out, v, PERIODS[0])[0] + tot(out, v, PERIODS[1])[0]
    best_stop = max(("E0", "S1", "S2"), key=design)
    best_chop = max(("C1", "C2"), key=design)
    xcfg = dict(stop=CFGS[best_stop]["stop"], chop=CFGS[best_chop]["chop"], warmup=True)
    out += run_cfg("X", xcfg, data)
    pickle.dump(out, open(CACHE, "wb"))
    print(f"A-222 ma-cross entry / stop lab (exits = mac-v1's; ₹ at a fixed ₹2.5 L premium, ±0.75 % a side)\n")
    print(f"X = stop {best_stop} + chop {best_chop} + W1 (chosen on design)\n")
    print("| variant | " + " | ".join(f"{p[0]}: Rs (n, PF, win)" for p in PERIODS) + " | validation − E0 (95 % CI) | ±0.25 %: H1 / H2 / val | rule |")
    print("|---|---|---|---|---|---|---|")
    base_days = defaultdict(float)
    for t in out:
        if t["v"] == "E0" and PERIODS[2][1] <= t["d"] <= PERIODS[2][2]:
            base_days[(t["u"], t["d"])] += t["rs"]
    keys = sorted({(t["u"], t["d"]) for t in out if PERIODS[2][1] <= t["d"] <= PERIODS[2][2]})
    for v in list(CFGS) + ["X"]:
        cells, beats = [], []
        for p in PERIODS:
            s, xs = tot(out, v, p)
            w = sum(t["rs"] for t in xs if t["rs"] > 0); l = -sum(t["rs"] for t in xs if t["rs"] < 0)
            cells.append(f"{s:+,.0f} ({len(xs)}, {w / l if l else 0:.2f}, {sum(t['rs'] > 0 for t in xs) / max(1, len(xs)):.0%})")
            beats.append(s - tot(out, "E0", p)[0])
        vd = defaultdict(float)
        for t in out:
            if t["v"] == v and PERIODS[2][1] <= t["d"] <= PERIODS[2][2]:
                vd[(t["u"], t["d"])] += t["rs"]
        diffs = [vd[k] - base_days[k] for k in keys]
        rng, means = random.Random(222), []
        for _ in range(10000):
            means.append(sum(diffs[rng.randrange(len(diffs))] for _ in diffs))
        means.sort()
        lo, hi = means[250], means[9749]
        low = " / ".join(f"{tot(out, v, p, 0.0025)[0]:+,.0f}" for p in PERIODS)
        ok = all(b > 0 for b in beats) and lo > 0
        tag = "—" if v == "E0" else ("ENGINE TEST" if ok else ("positive, not significant" if all(b > 0 for b in beats) else "no"))
        print(f"| {v} {LABEL[v]} | " + " | ".join(cells) + f" | {beats[2]:+,.0f} [{lo:+,.0f}, {hi:+,.0f}] | {low} | {tag} |")
    w = [t for t in out if t["v"] == "W1" and t["kind"] == "warmup"]
    print(f"\nW1 warm-up entries: {len(w)}, Rs {sum(t['rs'] for t in w):+,.0f}, win {sum(t['rs'] > 0 for t in w) / max(1, len(w)):.0%}; "
          + "  ".join(f"{p[0]} {sum(t['rs'] for t in w if p[1] <= t['d'] <= p[2]):+,.0f}" for p in PERIODS))
    for v in list(CFGS) + ["X"]:
        c = defaultdict(lambda: [0, 0.0])
        for t in out:
            if t["v"] == v:
                c[t["why"]][0] += 1; c[t["why"]][1] += t["rs"]
        print(f"  {v:3s} exits: " + ", ".join(f"{k} {n} ({s:+,.0f})" for k, (n, s) in sorted(c.items(), key=lambda kv: -kv[1][0])))


def a224():
    data = []
    for u in ("NIFTY", "SENSEX"):
        mins, futs, exps = lab.index_minutes(u), lab.futures_minutes(u), lab.expiries(u)
        days = sorted(mins)
        for i, d in enumerate(days):
            if d < "2024-10-01" or d in lab.HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue
            pc = mins[days[i - 1]][max(mins[days[i - 1]])][3] if i > 0 else None
            data.append((u, d, mins[d], lab.three_minute_states(mins[d]), exps[d], lab.vwap_proxy(futs.get(d, {}), mins[d]), pc))
    out = []
    for name, cfg in (("E0", dict(stop="swing")), ("F", dict(stop="swing", filters=True)), ("D1", dict(stop="swing", data_stop=True)),
                      ("F+D1", dict(stop="swing", filters=True, data_stop=True))):
        for u, d, mm, st, e, vw, pc in data:
            opt_for = lambda ty, spot, u=u, d=d, e=e: lab.option_bars(u, d, e, round(spot / lab.STEP[u]) * lab.STEP[u], ty)
            for t in simulate(cfg, mm, st, opt_for, vw, pc):
                t.update(v=name, u=u, d=d); out.append(t)
    print("A-224 lab (IN-SAMPLE: the filters were found on these periods; reported, not judged). ₹ at a fixed ₹2.5 L premium.\n")
    print("| variant | " + " | ".join(f"{p[0]} ±0.75 %: Rs (n, PF, win)" for p in PERIODS) + " | ±0.25 %: H1 / H2 / val |\n|---|---|---|---|---|")
    for v in ("E0", "F", "D1", "F+D1"):
        cells = []
        for p in PERIODS:
            s_, xs = tot(out, v, p)
            w = sum(t["rs"] for t in xs if t["rs"] > 0); l = -sum(t["rs"] for t in xs if t["rs"] < 0)
            cells.append(f"{s_:+,.0f} ({len(xs)}, {w / l if l else 0:.2f}, {sum(t['rs'] > 0 for t in xs) / max(1, len(xs)):.0%})")
        print(f"| {v} | " + " | ".join(cells) + " | " + " / ".join(f"{tot(out, v, p, 0.0025)[0]:+,.0f}" for p in PERIODS) + " |")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "a224":
        a224()
    else:
        main()
