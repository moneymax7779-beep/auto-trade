#!/usr/bin/env python3
"""A-197: A-196's weekly NIFTY short strangle on own-capture ticks (rules unchanged; fixed in the ledger before this ran).

Decisions on the 1-minute closing mid; fills at that minute's closing bid (sell) / ask (buy); charges 0.15 % of the
premium per order; legs open at expiry settle at intrinsic against the synthetic spot at 15:29. Picks on the edge of the
captured strike range are flagged.
Usage: python3 2026-10-24-weekly-strangle-ticks.py check      (expiries 6, 13, 19 Oct 2026)
       python3 2026-10-24-weekly-strangle-ticks.py forward    (cycles from 28 Oct 2026; equity on Rs 20 L)
"""
import csv, io, os, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
QTY, ENTRY_PX, ADJ, CHARGES = 650, 30.0, 0.875, 0.0015
CAPITAL, DD_STOP = 2_000_000, 540_000
OUT = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a197"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def mn(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


DAYS = [r[0] for r in q("select distinct session_date::text from md.load_manifest where status = 'ACTIVE' order by 1")]


def expiries():
    return [r[0] for r in q("select distinct expiry::text from md.option_tick where underlying = 'NIFTY' and expiry >= '2026-09-29' "
                            "and session_date >= '2026-09-28' order by 1")]


def load(e, start):
    rows = q(f"select strike, option_type, session_date::text, to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), "
             f"last(bid_px[1], recv_ts), last(ask_px[1], recv_ts) from md.option_tick where underlying = 'NIFTY' and expiry = '{e}' "
             f"and session_date between '{start}' and '{e}' and recv_ts >= session_date + time '03:45' and recv_ts < session_date + time '10:00' "
             f"and bid_px[1] > 0 and ask_px[1] > 0 group by 1, 2, 3, 4")
    px = defaultdict(dict)
    for k, ty, d, t, bd, ak in rows:
        px[(d, mn(t))][(float(k), ty)] = (float(bd), float(ak))
    return px


def run_cycle(e, start):
    px = load(e, start)
    days = [d for d in DAYS if start <= d <= e]
    if not days or e not in days and e <= DAYS[-1]:
        return None
    last = {}
    legs, pnl, orders, notes, base, picks, edges = {}, 0.0, 0, [], None, {}, 0
    state = {"open": True}

    def mid(key):
        b, a = last[key]
        return (b + a) / 2

    def nearest(side, target):
        cands = [(abs(mid(key) - target), key[0]) for key in last if key[1] == side]
        return min(cands)[1] if cands else None

    def on_edge(side, k):
        ks = [key[0] for key in last if key[1] == side]
        return k in (min(ks), max(ks))

    def sell(side, k, why, when):
        nonlocal pnl, orders, edges
        p = last[(k, side)][0]
        pnl += p * QTY * (1 - CHARGES)
        orders += 1
        legs[side] = {"k": k, "sell": p}
        edge = on_edge(side, k)
        edges += edge
        notes.append(f"{when} SELL {side} {int(k)} @ {p:.2f} bid ({why}){' EDGE' if edge else ''}")

    def buy(side, when, why, price=None):
        nonlocal pnl, orders
        p = last[(legs[side]["k"], side)][1] if price is None else price
        pnl -= p * QTY * (1 + CHARGES)
        orders += 1
        notes.append(f"{when} BUY  {side} {int(legs[side]['k'])} @ {p:.2f} {'ask' if price is None else 'intrinsic'} ({why})")
        del legs[side]

    for d in days:
        for m in range(mn("09:15"), mn("15:29") + 1):
            last.update(px.get((d, m), {}))
            when = f"{d} {m // 60:02d}:{m % 60:02d}"
            if not legs and d == start and m == mn("09:50"):
                for side in ("PE", "CE"):
                    k = nearest(side, ENTRY_PX)
                    if k is None:
                        return None
                    picks[side] = mid((k, side))
                    sell(side, k, "entry ~Rs 30", when)
                continue
            if not legs:
                continue
            cur = {s: mid((legs[s]["k"], s)) for s in legs}
            if base is not None:
                if cur["PE"] + cur["CE"] > 1.10 * base:
                    for s in ("PE", "CE"):
                        buy(s, when, f"straddle +10 % (base {base:.2f})")
                    return dict(pnl=pnl, end=d, stopped=True, orders=orders, notes=notes, picks=picks, edges=edges, open=False)
            else:
                for side in ("PE", "CE"):
                    other = "CE" if side == "PE" else "PE"
                    if cur[side] <= 0.5 * legs[side]["sell"] or (m == mn("15:15") and cur[side] < 0.5 * cur[other]):
                        why = "<= 50 % of sell" if cur[side] <= 0.5 * legs[side]["sell"] else "15:15 < half the other leg"
                        buy(side, when, why)
                        k = nearest(side, ADJ * cur[other])
                        sell(side, k, f"87.5 % of {other} {cur[other]:.2f}", when)
                        cur[side] = mid((k, side))
                if legs["PE"]["k"] >= legs["CE"]["k"]:
                    base = mid((legs["PE"]["k"], "PE")) + mid((legs["CE"]["k"], "CE"))
                    notes.append(f"{when} straddle mode, base {base:.2f}")
            if d == e and m == mn("15:29"):
                ks = sorted({key[0] for key in last})
                atm = min(ks, key=lambda k: abs(mid((k, "CE")) - mid((k, "PE"))) if (k, "CE") in last and (k, "PE") in last else 1e9)
                spot = atm + mid((atm, "CE")) - mid((atm, "PE"))
                for s in ("PE", "CE"):
                    k = legs[s]["k"]
                    buy(s, when, f"expiry settle, synthetic spot {spot:.1f}", max(0.0, (spot - k) if s == "CE" else (k - spot)))
                return dict(pnl=pnl, end=d, stopped=False, orders=orders, notes=notes, picks=picks, edges=edges, open=False)
    # data ends before expiry: mark the open legs at their asks (descriptive only)
    mtm = pnl - sum(last[(legs[s]["k"], s)][1] * QTY for s in legs)
    return dict(pnl=mtm, end=days[-1], stopped=False, orders=orders, notes=notes, picks=picks, edges=edges, open=True)


def sequence(first_start, first_exp_min):
    exps = expiries()
    out, start = [], first_start
    for e in exps:
        if e < first_exp_min or start is None or start > e:
            continue
        if start > DAYS[-1]:
            break
        r = run_cycle(e, start)
        if not r:
            break
        r.update(expiry=e, start=start)
        out.append(r)
        if r["open"]:
            break
        i = DAYS.index(r["end"]) + 1
        start = DAYS[i] if i < len(DAYS) else None
    return out


def show(r):
    tag = "OPEN (marked at asks)" if r["open"] else ("straddle stop" if r["stopped"] else "expiry")
    print(f"\n== expiry {r['expiry']} from {r['start']}: Rs {r['pnl']:+,.0f} ({tag}), {r['orders']} orders, "
          f"entry mids PE {r['picks'].get('PE', 0):.2f} CE {r['picks'].get('CE', 0):.2f}, edge picks {r['edges']}")
    for n in r["notes"]:
        print(f"   {n}")


if __name__ == "__main__":
    which = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    if which == "check":
        rs = sequence("2026-09-30", "2026-10-06")
        rs = [r for r in rs if r["expiry"] in ("2026-10-06", "2026-10-13", "2026-10-19")]
        for r in rs:
            show(r)
        print(f"\nchecked cycles: {len(rs)}, total Rs {sum(r['pnl'] for r in rs):+,.0f}")
    elif which == "forward":
        rs = sequence("2026-10-28", "2026-10-28")
        eq, peak, dd = 0.0, 0.0, 0.0
        with open(f"{OUT}/forward.csv", "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["expiry", "start", "end", "pnl", "status", "equity_on_20L", "drawdown"])
            for r in rs:
                if not r["open"]:
                    eq += r["pnl"]; peak = max(peak, eq); dd = max(dd, peak - eq)
                w.writerow([r["expiry"], r["start"], r["end"], round(r["pnl"]), "open" if r["open"] else "closed",
                            round(CAPITAL + eq), round(dd)])
        for r in rs:
            show(r)
        closed = [r["pnl"] for r in rs if not r["open"]]
        print(f"\nforward: {len(closed)} closed cycles, total Rs {sum(closed):+,.0f}, equity Rs {CAPITAL + eq:,.0f} on Rs 20 L, "
              f"max drawdown Rs {dd:,.0f}{'  ** DRAWDOWN STOP (> Rs 5.4 L) **' if dd > DD_STOP else ''}; read at >= 20 cycles")
