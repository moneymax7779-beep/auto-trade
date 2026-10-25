#!/usr/bin/env python3
"""A-219: what the index did after ma-cross's 60-minute time exits, and holding to the opposite EMA9/EMA20 cross instead
(rules fixed in the ledger first). Index points in the trade's direction; not rupees.
Usage: python3 2026-10-25-mac-time-exit.py
"""
import importlib.util, os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a214", os.path.join(HERE, "2026-10-24-odm-level-break.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
HALF2, FLAT = "2026-10-12", 914


def ema(xs, n):
    k, out, v = 2 / (n + 1), [], None
    for x in xs:
        v = x if v is None else v + k * (x - v)
        out.append(v)
    return out


def main():
    trades = a.q("select s.session_date::text, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), "
                 "to_char(p.closed_at at time zone 'Asia/Kolkata','HH24:MI'), p.exit_reason, p.net from trade.session s "
                 "join trade.\"position\" p on p.session_id = s.id where s.id between 14753 and 14779 and p.strategy_id = 'ma-cross' order by 1, 4")
    mins = {u: a.minutes(u) for u in ("NIFTY", "SENSEX")}
    by = defaultdict(float)
    for r in trades:
        by[r[5]] += float(r[6])
    print("A-219 ma-cross exits on the 27 tick days (control):", ", ".join(f"{k} {v:+,.0f}" for k, v in sorted(by.items(), key=lambda kv: kv[1])))
    print("\nTIME_60 trades: index points in the trade's direction after the time exit\n")
    print(f"{'day':10s} {'idx':6s} side entry exit   net Rs     entry→exit  exit→opp.cross (time)  worst before it  exit→15:15")
    rows = []
    for d, u, side, t_in, t_out, why, net in trades:
        if why != "TIME_60":
            continue
        mm = mins[u].get(d)
        if not mm:
            print(f"{d} {u} no index minutes"); continue
        ms = sorted(m for m in mm if 555 <= m <= FLAT)
        closes = [mm[m][3] for m in ms]
        e9, e20 = ema(closes, 9), ema(closes, 20)
        sgn = 1 if side == "CE" else -1
        i_in, i_out = a.hm(t_in), a.hm(t_out)
        px = lambda m: mm[max(x for x in ms if x <= m)][3]
        p_in, p_out = px(i_in), px(i_out)
        cross_t = None
        for k in range(1, len(ms)):
            if ms[k] <= i_out:
                continue
            d0, d1 = e9[k - 1] - e20[k - 1], e9[k] - e20[k]
            if (sgn > 0 and d0 >= 0 > d1) or (sgn < 0 and d0 <= 0 < d1):
                cross_t = ms[k]; break
        end = cross_t or FLAT
        p_end, p_flat = px(end), px(FLAT)
        worst = min(sgn * (mm[m][2 if sgn > 0 else 1] - p_out) for m in ms if i_out < m <= end) if end > i_out else 0.0
        row = dict(d=d, gain=sgn * (p_end - p_out), flat=sgn * (p_flat - p_out))
        rows.append(row)
        print(f"{d} {u:6s} {side}   {t_in} {t_out}  {float(net):+9,.0f}  {sgn*(p_out-p_in):+9.1f}   {row['gain']:+9.1f} ({'15:15' if not cross_t else f'{cross_t//60:02d}:{cross_t%60:02d}'})   "
              f"{worst:+9.1f}      {row['flat']:+8.1f}")
    for lab, sel in (("all", rows), ("2 Sep-9 Oct", [r for r in rows if r["d"] < HALF2]), ("12-23 Oct", [r for r in rows if r["d"] >= HALF2])):
        if sel:
            g = sum(r["gain"] for r in sel); f = sum(r["flat"] for r in sel)
            print(f"\n{lab:12s}: {len(sel)} TIME_60 trades; hold to the opposite cross {g:+.1f} index points in total "
                  f"({sum(r['gain'] > 0 for r in sel)} better, {sum(r['gain'] < 0 for r in sel)} worse); hold to 15:15 {f:+.1f}")


if __name__ == "__main__":
    main()
