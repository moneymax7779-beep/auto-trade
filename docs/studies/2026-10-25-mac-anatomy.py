#!/usr/bin/env python3
"""A-223: ma-cross trade anatomy (descriptive; registered in the ledger before this ran).
mac-v1 trades from the A-220 replica, with their full index path: excursions, time to target, exit efficiency, what came
after the exit, the entry context (time, levels, VWAP, day type, ATR regime, crosses so far) and the sequence in the day.
Findings are counted only when the design (Oct 2024-Dec 2025) and validation (Jan-Sep 2026) periods agree in direction.
Usage: python3 2026-10-25-mac-anatomy.py
"""
import importlib.util, math, os, pickle, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("lab", os.path.join(HERE, "2026-10-25-mac-exit-lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
CACHE = os.path.join(HERE, "..", "..", ".local", "a223", "anatomy.pkl")
SPLIT = "2026-01-01"


def trades_for_day(u, d, mm, st, vw, lv, prev_close, opt_for):
    """mac-v1 replica (as lab V0) with path statistics per trade."""
    out, pos, prev_diff, crosses = [], None, float("nan"), 0
    day_open = mm[min(mm)][0]
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
                    fav = sgn * (p - pos["entry"]) / pos["r"]
                    if fav > pos["mfe"]:
                        pos["mfe"], pos["mfe_t"] = fav, m - pos["t"]
                    pos["mae"] = min(pos["mae"], fav)
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
                exit_px = pos["target"] if reason == "TARGET" else pos["stop"] if reason == "STOP" else mm[m][3]
                # the unconstrained path from entry (stop and target only, to 15:15): did 2R come first, and when?
                first, first_t = None, None
                for x in range(pos["t"] + 1, lab.FLAT):
                    if x not in mm:
                        continue
                    for p in lab.path(mm[x]):
                        if sgn * (p - pos["target"]) >= 0:
                            first, first_t = "target", x - pos["t"]; break
                        if sgn * (p - pos["stop"]) < 0:
                            first, first_t = "stop", x - pos["t"]; break
                    if first:
                        break
                after = [mm[x][3] for x in range(m + 1, min(m + 61, lab.END)) if x in mm]
                realised_r = sgn * (exit_px - pos["entry"]) / pos["r"]
                pos.update(why=reason, x=m, net=net, rs=net * lab.BUDGET, realised_r=realised_r,
                           first=first or "neither", first_t=first_t,
                           after60_r=(sgn * (after[-1] - exit_px) / pos["r"]) if after else float("nan"),
                           after_best_r=(max(sgn * (a - exit_px) for a in after) / pos["r"]) if after else float("nan"))
                del pos["opt"]
                out.append(pos)
                pos = None
        if close3 is None:
            continue
        diff = close3["ema9"] - close3["ema20"]
        up = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff < 0 < diff
        dn = not math.isnan(diff) and not math.isnan(prev_diff) and prev_diff > 0 > diff
        prev_diff = diff if not math.isnan(diff) else prev_diff
        if up or dn:
            crosses += 1
        if held or pos or not (up or dn) or not lab.FIRST <= m < lab.LAST:
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
        seen = [mm[x] for x in range(lab.OPEN, m) if x in mm]
        day_hi, day_lo = max(b[1] for b in seen), min(b[2] for b in seen)
        ahead = [abs(v - spot) / atr for v in lv.values() if not math.isnan(v) and sgn * (v - spot) > 0]
        behind = [abs(v - spot) / atr for v in lv.values() if not math.isnan(v) and sgn * (v - spot) <= 0]
        vwp = vw.get(m - 1)
        pos = dict(u=u, d=d, side="CE" if up else "PE", sgn=sgn, t=m, entry=spot, stop=stop, r=risk, target=spot + sgn * 2 * risk,
                   prem=ob[m][0], opt=ob, mfe=0.0, mae=0.0, mfe_t=0,
                   ctx=dict(minute=m, risk_atr=risk / atr, atr_pct=atr / spot * 100,
                            gap_pct=(day_open - prev_close) / prev_close * 100 * sgn if prev_close else float("nan"),
                            trend_pct=(spot - day_open) / day_open * 100 * sgn,
                            range_pct=(day_hi - day_lo) / day_open * 100,
                            pos_in_range=((spot - day_lo) / (day_hi - day_lo) if day_hi > day_lo else 0.5) if sgn > 0
                            else ((day_hi - spot) / (day_hi - day_lo) if day_hi > day_lo else 0.5),
                            level_ahead_atr=min(ahead) if ahead else float("inf"),
                            level_behind_atr=min(behind) if behind else float("inf"),
                            level_ahead_within_target=bool(ahead and min(ahead) < 2 * risk / atr),
                            vwap_side=(sgn * (spot - vwp) / atr) if vwp is not None else float("nan"),
                            crosses_before=crosses - 1))
    for i, t in enumerate(out):
        t["seq"] = i + 1
        t["prev"] = None if i == 0 else ("win" if out[i - 1]["rs"] > 0 else "loss")
        t["prev2_losses"] = i >= 2 and out[i - 1]["rs"] <= 0 and out[i - 2]["rs"] <= 0
    return out


def build():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    rows = []
    for u in ("NIFTY", "SENSEX"):
        mins, futs, exps = lab.index_minutes(u), lab.futures_minutes(u), lab.expiries(u)
        days = sorted(mins)
        for i, d in enumerate(days):
            if d < "2024-10-01" or d in lab.HELD_OUT or len(mins[d]) < 300 or d not in exps or exps[d] == d:
                continue
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            mm = mins[d]
            prev_close = mins[days[i - 1]][max(mins[days[i - 1]])][3] if i > 0 else None
            e = exps[d]
            opt_for = lambda ty, spot: lab.option_bars(u, d, e, round(spot / lab.STEP[u]) * lab.STEP[u], ty)
            rows += trades_for_day(u, d, mm, lab.three_minute_states(mm), lab.vwap_proxy(futs.get(d, {}), mm),
                                   lab.levels(days, mins, d), prev_close, opt_for)
    print(file=sys.stderr)
    pickle.dump(rows, open(CACHE, "wb"))
    return rows


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else float("nan")


def bucket_table(rows, name, key, edges, labels):
    print(f"\n### {name}\n\n| bucket | design n | design Rs/trade | design win | validation n | validation Rs/trade | validation win | agree |\n|---|---|---|---|---|---|---|---|")
    res = []
    base_d = statistics.mean(t["rs"] for t in rows if t["d"] < SPLIT)
    base_v = statistics.mean(t["rs"] for t in rows if t["d"] >= SPLIT)
    for lo, hi, lab_ in zip(edges[:-1], edges[1:], labels):
        sel = [t for t in rows if lo <= key(t) < hi]
        dd = [t["rs"] for t in sel if t["d"] < SPLIT]; vv = [t["rs"] for t in sel if t["d"] >= SPLIT]
        if not dd or not vv:
            print(f"| {lab_} | {len(dd)} | | | {len(vv)} | | | |"); continue
        md, mv = statistics.mean(dd), statistics.mean(vv)
        agree = "better" if md > base_d and mv > base_v else "worse" if md < base_d and mv < base_v else "mixed"
        print(f"| {lab_} | {len(dd)} | {md:+,.0f} | {sum(x > 0 for x in dd) / len(dd):.0%} | {len(vv)} | {mv:+,.0f} | "
              f"{sum(x > 0 for x in vv) / len(vv):.0%} | {agree} |")


def report(rows):
    d_ = [t for t in rows if t["d"] < SPLIT]; v_ = [t for t in rows if t["d"] >= SPLIT]
    print("# A-223 ma-cross trade anatomy (mac-v1 replica, ₹ at a fixed ₹2.5 L premium, ±0.75 % a side)\n")
    print(f"Trades: design {len(d_)} (mean {statistics.mean(t['rs'] for t in d_):+,.0f}), validation {len(v_)} "
          f"(mean {statistics.mean(t['rs'] for t in v_):+,.0f}); 'agree' = the bucket is better (worse) than that period's average in both.")
    print("\n## 1. Path: how trades behave after entry\n")
    for lab_, sel in (("design", d_), ("validation", v_)):
        win = [t for t in sel if t["why"] == "TARGET"]; stp = [t for t in sel if t["why"] == "STOP"]
        print(f"**{lab_}**: {len(win)} targets, {len(stp)} stops, {sum(t['why'] == 'TIME_60' for t in sel)} time exits.")
        print(f"- Target trades went against first by MAE (R): median {pct([t['mae'] for t in win], .5):+.2f}, 75th pct "
              f"{pct([t['mae'] for t in win], .25):+.2f}, 90th pct {pct([t['mae'] for t in win], .10):+.2f}.")
        print(f"- Stopped trades had first gone in favour by MFE (R): median {pct([t['mfe'] for t in stp], .5):+.2f}; "
              f"{sum(t['mfe'] >= 1 for t in stp)} of {len(stp)} had been ≥ +1R, {sum(t['mfe'] >= 0.5 for t in stp)} ≥ +0.5R.")
        reached = [t for t in sel if t["first"] == "target"]
        print(f"- Unconstrained (stop/target only to 15:15): target first {len(reached)}, stop first {sum(t['first'] == 'stop' for t in sel)}, "
              f"neither {sum(t['first'] == 'neither' for t in sel)}; minutes to target: median {pct([t['first_t'] for t in reached], .5)}, "
              f"75th {pct([t['first_t'] for t in reached], .75)}, 90th {pct([t['first_t'] for t in reached], .9)}.")
        te = [t for t in sel if t["why"] == "TIME_60"]
        if te:
            print(f"- Time exits: realised {statistics.mean(t['realised_r'] for t in te):+.2f}R on average; in the next 60 min the index went "
                  f"a further {statistics.mean(t['after60_r'] for t in te if not math.isnan(t['after60_r'])):+.2f}R (best {statistics.mean(t['after_best_r'] for t in te if not math.isnan(t['after_best_r'])):+.2f}R).")
        print(f"- Exit efficiency of winners (realised R ÷ best R reached): median "
              f"{pct([t['realised_r'] / t['mfe'] for t in sel if t['rs'] > 0 and t['mfe'] > 0], .5):.0%}.")
        print()
    print("## 2. Entry context\n")
    bucket_table(rows, "Time of day (entry)", lambda t: t["ctx"]["minute"], [570, 600, 660, 720, 780, 870],
                 ["09:30-10:00", "10:00-11:00", "11:00-12:00", "12:00-13:00", "13:00-14:30"])
    bucket_table(rows, "Stop distance (risk) in ATR", lambda t: t["ctx"]["risk_atr"], [0, 0.75, 1.25, 2, 3.01],
                 ["< 0.75", "0.75-1.25", "1.25-2", "2-3"])
    bucket_table(rows, "Trend from the day's open in the trade's direction (%)", lambda t: t["ctx"]["trend_pct"],
                 [-9, -0.3, -0.1, 0.1, 0.3, 9], ["< -0.3 (against)", "-0.3..-0.1", "-0.1..+0.1", "+0.1..+0.3", "> +0.3 (with)"])
    bucket_table(rows, "Day's range so far (% of open)", lambda t: t["ctx"]["range_pct"], [0, 0.4, 0.7, 1.0, 9],
                 ["< 0.4", "0.4-0.7", "0.7-1.0", "> 1.0"])
    bucket_table(rows, "Position in the day's range (0 = against side, 1 = trade side)", lambda t: t["ctx"]["pos_in_range"],
                 [-1, 0.25, 0.5, 0.75, 2], ["< 0.25", "0.25-0.5", "0.5-0.75", "> 0.75"])
    bucket_table(rows, "Nearest major level ahead (ATR)", lambda t: min(t["ctx"]["level_ahead_atr"], 1e10), [0, 1, 2, 4, 1e9, 1e11],
                 ["< 1", "1-2", "2-4", "> 4", "none ahead"])
    bucket_table(rows, "Side of VWAP at entry (ATR, + = trade side)", lambda t: t["ctx"]["vwap_side"] if not math.isnan(t["ctx"]["vwap_side"]) else -99,
                 [-98, -1, 0, 1, 99], ["< -1 (against)", "-1..0", "0..+1", "> +1 (with)"])
    bucket_table(rows, "Gap at the open in the trade's direction (%)", lambda t: t["ctx"]["gap_pct"] if not math.isnan(t["ctx"]["gap_pct"]) else 0,
                 [-9, -0.3, 0.3, 9], ["gap against < -0.3", "small", "gap with > +0.3"])
    bucket_table(rows, "ATR regime (ATR3m as % of price)", lambda t: t["ctx"]["atr_pct"], [0, 0.06, 0.09, 0.13, 9],
                 ["< 0.06", "0.06-0.09", "0.09-0.13", "> 0.13"])
    bucket_table(rows, "Crosses earlier that day", lambda t: t["ctx"]["crosses_before"], [0, 1, 2, 4, 99], ["0", "1", "2-3", "4+"])
    print("\n## 3. Sequence (the system's behaviour)\n")
    bucket_table(rows, "Trade number in the day (per index)", lambda t: t["seq"], [1, 2, 3, 99], ["1st", "2nd", "3rd+"])
    bucket_table(rows, "After the previous trade", lambda t: {None: 0, "win": 1, "loss": 2}[t["prev"]], [0, 1, 2, 3], ["first trade", "after a win", "after a loss"])
    bucket_table(rows, "After two losses in a row", lambda t: 1 if t["prev2_losses"] else 0, [0, 1, 2], ["no", "yes"])


if __name__ == "__main__":
    rows = pickle.load(open(CACHE, "rb")) if (len(sys.argv) > 1 and sys.argv[1] == "report") else build()
    report(rows)
