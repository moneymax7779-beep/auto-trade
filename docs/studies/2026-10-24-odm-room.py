#!/usr/bin/env python3
"""A-215: skip odm-v4 entries with a major level just ahead (within 1.0 x ATR(3m)); rules fixed in the ledger first.
Levels and trades as A-214 (2026-10-24-odm-level-break.py); ATR = the entry candidate's atr3m. 22 Oct = design day.
Usage: python3 2026-10-24-odm-room.py
"""
import importlib.util, os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a214", os.path.join(HERE, "2026-10-24-odm-level-break.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
DESIGN, HALF2, ROOM = "2026-10-22", "2026-10-12", 1.0


def main():
    trades = a.q("select s.session_date::text, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), "
                 "p.net, (select (c.context->>'atr3m')::float8 from trade.candidate c where c.session_id = s.id and "
                 "c.strategy_id = p.strategy_id and c.underlying = p.underlying and c.at <= p.opened_at and c.context ? 'atr3m' "
                 "order by c.at desc limit 1) from trade.session s join trade.\"position\" p on p.session_id = s.id "
                 "where s.id between 14698 and 14724 and p.strategy_id = 'opening-drive-momentum' order by 1, 2")
    mins = {u: a.minutes(u) for u in ("NIFTY", "SENSEX")}
    rows = []
    print("A-215 room to the next major level at odm-v4's entry (levels as A-214, ATR = atr3m)\n")
    for d, u, side, t, net, atr in trades:
        mm, e, put = mins[u][d], a.hm(t), side == "PE"
        px = mm[max(m for m in mm if m < e)][3]
        lv = a.levels(mins[u], d)
        ahead = sorted(((k, v) for k, v in lv.items() if (v < px if put else v > px)), key=lambda kv: abs(kv[1] - px))
        k, v = ahead[0] if ahead else ("none", None)
        room = abs(px - v) / float(atr) if v is not None and atr else None
        skip = room is not None and room < ROOM
        rows.append(dict(d=d, net=float(net), skip=skip))
        print(f"{d} {u:6s} {side} {t}  net {float(net):+10,.0f}  index {px:9.1f}  next level {k} {v if v is None else round(v, 1)}  "
              f"ATR {float(atr) if atr else float('nan'):5.1f}  room {'-' if room is None else f'{room:.2f}'} ATR  {'SKIP' if skip else 'take'}"
              + ("   (design day)" if d == DESIGN else ""))
    judged = [r for r in rows if r["d"] != DESIGN]
    removed = [r for r in judged if r["skip"]]
    s = sum(r["net"] for r in removed)
    by_day = defaultdict(float)
    for r in removed:
        by_day[r["d"]] += r["net"]
    worst = min(by_day.values()) if by_day else 0.0
    h1 = sum(r["net"] for r in removed if r["d"] < HALF2)
    h2 = sum(r["net"] for r in removed if r["d"] >= HALF2)
    ok = bool(removed) and s < 0 and s - worst < 0 and h1 < 0 and h2 < 0
    print(f"\njudged (22 Oct excluded): {len(judged)} trades Rs {sum(r['net'] for r in judged):+,.0f}; R1 removes {len(removed)} "
          f"Rs {s:+,.0f} (without the worst removed day {s - worst:+,.0f}; halves {h1:+,.0f} / {h2:+,.0f}) -> {'PASS' if ok else 'FAIL'}")
    dz = [r for r in rows if r["d"] == DESIGN]
    print(f"design day 22 Oct: {'skipped' if all(r['skip'] for r in dz) else 'taken'} ({sum(r['net'] for r in dz):+,.0f})")


if __name__ == "__main__":
    main()
