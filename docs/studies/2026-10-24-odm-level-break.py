#!/usr/bin/env python3
"""A-214: does a level break separate odm-v4's winning and losing opening drives? (gates fixed in the ledger before this ran)

L1  a put only if the index at entry is below the previous session's low (a call above its high).
L2  between 09:15 and entry the index traded beyond a major level in the trade's direction that the 09:15 open had not
    passed (PDH/PDL, previous session's 09:15-09:30 ORH/ORL, previous week's and month's high/low; for a put any of them
    below the open, for a call any above).
Also: odm-v9's fresh-drive rule (A-172; a new session extreme in the trade's direction in the 3 minutes before entry).
Trades: odm-v4 in the A-210 arm A tick-day replays (sessions 14698-14724). Index minutes: hist.candle before 28 Sep, own
md.index_tick from 28 Sep.
Usage: python3 2026-10-24-odm-level-break.py
"""
import csv, io, subprocess
from collections import defaultdict
from datetime import date, timedelta

IST = "at time zone 'Asia/Kolkata'"
OWN_FROM = "2026-09-28"
HALF2 = "2026-10-12"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def minutes(u):
    """{day: {minute: (o, h, l, c)}} 09:15-15:29."""
    out = defaultdict(dict)
    for d, t, o, h, l, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                              f"from hist.candle where underlying = '{u}' and kind = 'INDEX' and bar_start >= '2026-06-25' "
                              f"and bar_start < '{OWN_FROM}'"):
        if 555 <= hm(t) < 930:
            out[d][hm(t)] = tuple(map(float, (o, h, l, c)))
    for d, t, o, h, l, c in q(f"select session_date::text, to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), "
                              f"first(price, recv_ts), max(price), min(price), last(price, recv_ts) from md.index_tick "
                              f"where underlying = '{u}' and price > 0 and session_date >= '{OWN_FROM}' group by 1, 2"):
        if 555 <= hm(t) < 930:
            out[d][hm(t)] = tuple(map(float, (o, h, l, c)))
    return {d: v for d, v in sorted(out.items()) if len(v) > 200}


def levels(mins, d):
    days = sorted(mins)
    prev = [x for x in days if x < d]
    p = mins[prev[-1]]
    hi = lambda ds: max(b[1] for x in ds for b in mins[x].values())
    lo = lambda ds: min(b[2] for x in ds for b in mins[x].values())
    dd = date.fromisoformat(d)
    wk = [x for x in prev if date.fromisoformat(x).isocalendar()[:2] == (dd - timedelta(days=7)).isocalendar()[:2]]
    pm = (dd.year, dd.month - 1) if dd.month > 1 else (dd.year - 1, 12)
    mo = [x for x in prev if (date.fromisoformat(x).year, date.fromisoformat(x).month) == pm]
    lv = {"PDH": max(b[1] for b in p.values()), "PDL": min(b[2] for b in p.values()),
          "pORH": max(b[1] for m, b in p.items() if m < 570), "pORL": min(b[2] for m, b in p.items() if m < 570)}
    if wk:
        lv.update(PWH=hi(wk), PWL=lo(wk))
    if mo:
        lv.update(PMH=hi(mo), PML=lo(mo))
    return lv


def main():
    trades = q("select s.session_date::text, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), "
               "p.net from trade.session s join trade.\"position\" p on p.session_id = s.id where s.id between 14698 and 14724 "
               "and p.strategy_id = 'opening-drive-momentum' order by 1, 2")
    mins = {u: minutes(u) for u in ("NIFTY", "SENSEX")}
    rows = []
    for d, u, side, t, net in trades:
        mm, e = mins[u][d], hm(t)
        put = side == "PE"
        lv = levels(mins[u], d)
        op = mm[min(mm)][0]
        px = mm[max(m for m in mm if m < e)][3]                                # index at the decision (last close before entry)
        path_lo = min(b[2] for m, b in mm.items() if m < e)
        path_hi = max(b[1] for m, b in mm.items() if m < e)
        l1 = px < lv["PDL"] if put else px > lv["PDH"]
        broken = [k for k, v in lv.items() if (op > v and path_lo < v if put else op < v and path_hi > v)]
        l2 = bool(broken)
        ext = lambda upto: (min if put else max)(mm[m][2 if put else 1] for m in mm if m < upto)
        fresh = ext(e) < ext(e - 3) if put else ext(e) > ext(e - 3)
        near = sorted(((k, v) for k, v in lv.items() if (v < op if put else v > op)), key=lambda kv: abs(kv[1] - op))[:2]
        rows.append(dict(d=d, u=u, side=side, t=t, net=float(net), op=op, px=px, l1=l1, l2=l2, broken=broken, fresh=fresh,
                         pdl=lv["PDL"], pdh=lv["PDH"], near=near))
    print("A-214 odm-v4 tick-day trades (A-210 arm A, sessions 14698-14724) and the level gates\n")
    print(f"{'day':10s} {'idx':6s} {'side':4s} {'entry':5s} {'net Rs':>10s}  {'open':>9s} {'at entry':>9s} {'PDL':>9s} {'PDH':>9s}  L1  L2  fresh  levels broken by the drive / nearest ahead")
    for r in rows:
        print(f"{r['d']:10s} {r['u']:6s} {r['side']:4s} {r['t']:5s} {r['net']:>+10,.0f}  {r['op']:9.1f} {r['px']:9.1f} {r['pdl']:9.1f} {r['pdh']:9.1f}  "
              f"{'Y' if r['l1'] else '-':2s}  {'Y' if r['l2'] else '-':2s}  {'Y' if r['fresh'] else '-':5s}  "
              + (",".join(r["broken"]) if r["broken"] else "none; ahead " + ", ".join(f"{k} {v:.0f}" for k, v in r["near"])))
    tot = sum(r["net"] for r in rows)
    print(f"\nall odm-v4: {len(rows)} trades, Rs {tot:+,.0f}")
    for g in ("l1", "l2", "fresh"):
        removed = [r for r in rows if not r[g]]
        kept = [r for r in rows if r[g]]
        s = sum(r["net"] for r in removed)
        by_day = defaultdict(float)
        for r in removed:
            by_day[r["d"]] += r["net"]
        worst = min(by_day.values()) if by_day else 0.0
        h1 = sum(r["net"] for r in removed if r["d"] < HALF2)
        h2 = sum(r["net"] for r in removed if r["d"] >= HALF2)
        ok = removed and s < 0 and s - worst < 0 and h1 < 0 and h2 < 0
        label = {"l1": "L1 index beyond PDL/PDH at entry", "l2": "L2 drive broke a level", "fresh": "fresh drive (odm-v9, reported)"}[g]
        print(f"{label:34s}: removes {len(removed)} trades Rs {s:+,.0f} (without the worst removed day {s - worst:+,.0f}; halves "
              f"{h1:+,.0f} / {h2:+,.0f}); keeps {len(kept)} Rs {sum(r['net'] for r in kept):+,.0f}"
              + ("" if g == "fresh" else f"  -> {'PASS' if ok else 'FAIL'}"))


if __name__ == "__main__":
    main()
