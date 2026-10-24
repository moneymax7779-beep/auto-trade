#!/usr/bin/env python3
"""A-177 premium selling: hedged iron butterfly (definitions and criteria fixed in the ledger before this ran).

10:00 close: sell ATM CE + PE (nearest expiry), buy wings 2 % of spot away; exit 15:15, or at the first 1-min close where
the cost to close >= 2 x credit. P&L in index points per unit of each leg, 1.5 % of each leg's entry premium as cost.
Index bars come from the A-176 loader, so each phase reads only its own date range.
Usage: python3 2026-10-23-premium-selling.py discovery|validation|confirmation
"""
import importlib.util, os, statistics, sys

spec = importlib.util.spec_from_file_location("a176", os.path.join(os.path.dirname(__file__), "2026-10-23-new-setup-discovery.py"))
a176 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a176)
q, minute, IST, STEP, PHASES, HELD_OUT = a176.q, a176.minute, a176.IST, a176.STEP, a176.PHASES, a176.HELD_OUT

ENTRY, EXIT, COST, WING, STOP = minute("10:00"), minute("15:15"), 0.015, 0.02, 2.0


def legs(u, d, exp, k, w):
    rows = q(f"select strike, option_type, to_char(bar_start {IST},'HH24:MI'), close from hist.candle "
             f"where underlying = '{u}' and kind = 'OPTION' and expiry = '{exp}' "
             f"and ((strike = {k} and option_type in ('CE','PE')) or (strike = {k + w} and option_type = 'CE') "
             f"or (strike = {k - w} and option_type = 'PE')) "
             f"and bar_start between '{d} 09:58+05:30' and '{d} 15:15+05:30'")
    px = {}
    for s, ty, t, c in rows:
        if c and float(c) > 0:
            px.setdefault((float(s), ty), {})[minute(t)] = float(c)
    return px


def trade(u, d, b, exp):
    spot = b["c"][ENTRY]
    k = round(spot / STEP[u]) * STEP[u]
    w = max(STEP[u], round(spot * WING / STEP[u]) * STEP[u])
    px = legs(u, d, exp, k, w)
    keys = [(float(k), "CE"), (float(k), "PE"), (float(k + w), "CE"), (float(k - w), "PE")]
    if any(key not in px for key in keys):
        return None
    last, entry = {}, {}
    for key in keys:
        p = next((px[key][m] for m in (ENTRY, ENTRY + 1, ENTRY - 1, ENTRY + 2, ENTRY - 2) if m in px[key]), None)
        if p is None:
            return None
        entry[key] = last[key] = p
    credit = entry[keys[0]] + entry[keys[1]] - entry[keys[2]] - entry[keys[3]]
    if credit <= 0:
        return None
    out_m, cost_close = EXIT, None
    for m in range(ENTRY + 1, EXIT + 1):
        for key in keys:
            if m in px[key]:
                last[key] = px[key][m]
        cost_close = last[keys[0]] + last[keys[1]] - last[keys[2]] - last[keys[3]]
        if cost_close >= STOP * credit:
            out_m = m
            break
    pnl = credit - cost_close - COST * sum(entry.values())
    return {"u": u, "d": d, "pnl": pnl, "risk_pct": pnl / (w - credit) if w > credit else None, "credit": credit,
            "stopped": out_m < EXIT, "expiry_day": exp == d, "w": w}


def run(phase):
    lo, hi = PHASES[phase]
    days, vix, expiries = a176.load(lo, hi)
    trades, skipped = [], 0
    for u in ("NIFTY", "SENSEX"):
        ds = sorted(d for (uu, d) in days if uu == u)
        ranges = []
        for d in ds:
            b = days[(u, d)]
            if any(b[x][i] is None for x in "hlc" for i in range(ENTRY + 1)):
                continue
            r = (max(b["h"][:ENTRY + 1]) - min(b["l"][:ENTRY + 1])) / b["c"][ENTRY]
            prior = ranges[-10:]
            ranges.append(r)
            if not (lo <= d <= hi) or d in HELD_OUT:
                continue
            exp = next((x for x in expiries[u] if x >= d), None)
            t = trade(u, d, b, exp) if exp else None
            if t is None:
                skipped += 1
                continue
            t["or_ratio"] = r / statistics.mean(prior) if len(prior) >= 5 else None
            trades.append(t)
    print(f"A-177 {phase} {lo}..{hi}  iron fly 10:00 -> 15:15, wings {WING:.0%}, stop {STOP:g}x credit, cost {COST:.1%}/leg  skipped {skipped}")
    report("all", trades, phase, verdict=True)
    for label, f in (("NIFTY", lambda t: t["u"] == "NIFTY"), ("SENSEX", lambda t: t["u"] == "SENSEX"),
                     ("expiry day", lambda t: t["expiry_day"]), ("not expiry", lambda t: not t["expiry_day"]),
                     ("OR < 0.8x", lambda t: t["or_ratio"] is not None and t["or_ratio"] < 0.8),
                     ("OR 0.8-1.2x", lambda t: t["or_ratio"] is not None and 0.8 <= t["or_ratio"] <= 1.2),
                     ("OR > 1.2x", lambda t: t["or_ratio"] is not None and t["or_ratio"] > 1.2)):
        report(label, [t for t in trades if f(t)], phase)


def report(label, ts, phase, verdict=False):
    if not ts:
        print(f"  {label:11s} no trades")
        return
    p = [t["pnl"] for t in ts]
    n, mean = len(p), statistics.mean(p)
    tstat = mean / (statistics.stdev(p) / n ** 0.5) if n > 1 and statistics.stdev(p) > 0 else 0
    hit = sum(x > 0 for x in p) / n
    wins = [x for x in p if x > 0]
    mw = statistics.mean(wins) if wins else 0
    rp = [t["risk_pct"] for t in ts if t["risk_pct"] is not None]
    line = (f"  {label:11s} n={n:3d} mean {mean:+7.1f} pts (t {tstat:+.2f}) hit {hit:.0%} worst {min(p):+.1f} mean win {mw:+.1f} "
            f"stopped {sum(t['stopped'] for t in ts) / n:.0%} credit {statistics.mean(t['credit'] for t in ts):.1f} "
            f"on risk {statistics.mean(rp):+.1%}")
    if verdict:
        ok = (mean > 0 and tstat >= 2 and hit >= 0.6 and min(p) >= -3 * mw) if phase != "confirmation" else mean > 0
        line += f"  -> {'PASS' if ok else 'FAIL'}"
    print(line)


if __name__ == "__main__":
    run(sys.argv[1])
