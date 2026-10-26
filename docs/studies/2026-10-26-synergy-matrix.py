#!/usr/bin/env python3
"""A-238: the 3-column synergy matrix (rows fixed in the ledger before this ran).
Events: first 5-minute close 09:45-14:30 beyond PDH/PDL and beyond today's ORH/ORL (09:15-09:30), NIFTY and SENSEX.
NIFTY BANK confirms if beyond its own same level. VIX low/high vs its previous-20-session median close; rising/dropping
vs its 09:15 value; rising rapidly >= +5 %. R1 up+confirm+low&rising -> call; R2 up+lag+high&dropping -> calls lose;
R3 down+confirm+VIX >= +5 % -> put; R4 no break by 11:00, within +-0.3 % of open, VIX < 11 -> 11:00 call and put lose.
Naked ATM, 30 / 60 min, +-0.75 % a leg.
Usage: python3 2026-10-26-synergy-matrix.py [report]
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a238", "events.pkl")
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def last_at(s, m, back=5):
    for x in range(m, m - back - 1, -1):
        if x in s:
            return s[x]
    return None


def minutes(u, kind="INDEX"):
    out = defaultdict(dict)
    for d, t, o, h, l, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                              f"from hist.candle where underlying = '{u}' and kind = '{kind}' and bar_start >= '2024-09-01'"):
        m = hm(t)
        if 555 <= m < 930:
            out[d][m] = (float(o), float(h), float(l), float(c))
    return out


def levels(day_bars, prev_bars):
    pdh = max(b[1] for m, b in prev_bars.items() if m < 915)
    pdl = min(b[2] for m, b in prev_bars.items() if m < 915)
    orh = max(b[1] for m, b in day_bars.items() if m < 570)
    orl = min(b[2] for m, b in day_bars.items() if m < 570)
    return {"PDH": pdh, "PDL": pdl, "ORH": orh, "ORL": orl}


def option_series(u, d, ty, k):
    e = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
    if not e or not e[0] or not e[0][0]:
        return {}
    return {hm(t): float(c) for t, c in q(
        f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{u}' and kind = 'OPTION' "
        f"and expiry = '{e[0][0]}' and option_type = '{ty}' and strike = {k} "
        f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 15:30+05:30')::timestamptz") if c and float(c) > 0}


def naked(s, entry_minute):
    b0 = last_at(s, entry_minute - 1)
    res = {}
    for hold in (30, 60):
        b1 = last_at(s, entry_minute - 1 + hold)
        if b0 and b1:
            res[hold] = b1 * (1 - COST) / (b0 * (1 + COST)) - 1
    return res


def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    bank, vix = minutes("BANKNIFTY"), minutes("INDIA_VIX", "VIX")
    vix_close = {d: b[max(b)][3] for d, b in vix.items() if b}
    vix_days = sorted(vix_close)
    out, r4 = [], []
    for u in ("NIFTY", "SENSEX"):
        idx = minutes(u)
        ds = sorted(idx)
        for i, d in enumerate(ds):
            if i == 0 or d < "2024-10-01" or d > "2026-09-30" or d in HELD_OUT or len(idx[d]) < 300:
                continue
            pd_ = ds[i - 1]
            if d not in bank or len(bank[d]) < 300 or pd_ not in bank or d not in vix or len(idx[pd_]) < 300:
                continue
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            lv, blv = levels(idx[d], idx[pd_]), levels(bank[d], bank[pd_])
            prev_vix = [vix_close[x] for x in vix_days if x < d][-20:]
            if len(prev_vix) < 15:
                continue
            vmed = statistics.median(prev_vix)
            v_open = last_at({m: b[3] for m, b in vix[d].items()}, 556, 3)
            ms = sorted(idx[d])
            ic = {m: b[3] for m, b in idx[d].items()}
            bk = {m: b[3] for m, b in bank[d].items()}
            vx = {m: b[3] for m, b in vix[d].items()}
            closes5 = {m: idx[d][m][3] for m in ms if (m - 555) % 5 == 4}
            broke_by_11 = False
            for name, up in (("PDH", True), ("PDL", False), ("ORH", True), ("ORL", False)):
                level, blevel = lv[name], blv[name]
                for dm in range(585, 871, 5):
                    c = last_at(ic, dm - 1)
                    if c is None:
                        continue
                    before = [v for m, v in closes5.items() if m < dm - 1]
                    beyond = c > level if up else c < level
                    first = all((v <= level) if up else (v >= level) for v in before)
                    if not (beyond and first):
                        continue
                    if dm <= 660:
                        broke_by_11 = True
                    bc = last_at(bk, dm - 1)
                    vn = last_at(vx, dm - 1, 10)
                    if bc is None or vn is None or v_open is None:
                        break
                    ty = "CE" if up else "PE"
                    k = round(c / STEP[u]) * STEP[u]
                    res = naked(option_series(u, d, ty, k), dm)
                    if 30 in res:
                        out.append(dict(u=u, d=d, level=name, up=up, minute=dm, confirm=(bc > blevel if up else bc < blevel),
                                        vix=vn, vmed=vmed, vchg=(vn / v_open - 1) * 100, res=res))
                    break
            # R4: consolidating at 11:00 with VIX < 11
            c11 = last_at(ic, 659)
            v11 = last_at(vx, 659, 10)
            op = idx[d][ms[0]][0]
            if c11 and v11 is not None and not broke_by_11 and abs(c11 / op - 1) <= 0.003:
                k = round(c11 / STEP[u]) * STEP[u]
                for ty in ("CE", "PE"):
                    res = naked(option_series(u, d, ty, k), 660)
                    if 60 in res:
                        r4.append(dict(u=u, d=d, ty=ty, vix=v11, res=res))
    print(file=sys.stderr)
    pickle.dump((out, r4), open(CACHE, "wb"))
    return out, r4


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def boot(a, n=10000, seed=238):
    by = defaultdict(list)
    for d, v in a:
        by[d].append(v)
    ks = list(by)
    if len(ks) < 2:
        return None
    r, vals = random.Random(seed), []
    for _ in range(n):
        s = [v for k in (r.choice(ks) for _ in ks) for v in by[k]]
        vals.append(mean(s))
    vals.sort()
    return vals[250], vals[9749]


def verdict(name, events, hold, want_positive, compare=None):
    ok = []
    for lab, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
        sub = [(e["d"], e["res"][hold]) for e in events if lo <= e["d"] <= hi and hold in e["res"]]
        m = mean([v for _, v in sub])
        ci = boot(sub) if lab == "pooled" else None
        extra = ""
        if compare is not None:
            cs = [e["res"][hold] for e in compare if lo <= e["d"] <= hi and hold in e["res"]]
            extra = f" | others {mean(cs):+.1%} (n {len(cs)})"
        print(f"  {name} {hold} min {lab}: {m:+.1%} (n {len(sub)}, win {sum(v > 0 for _, v in sub) / len(sub) if sub else float('nan'):.0%})"
              + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else "") + extra)
        if lab != "pooled":
            ok.append(None if len(sub) < 20 else (m > 0) == want_positive)
        else:
            pooled = ci
            pooled_mean = m
            others = mean([e["res"][hold] for e in compare if hold in e["res"]]) if compare is not None else None
    good = pooled and (pooled[0] > 0 if want_positive else pooled[1] < 0)
    if compare is not None:
        good = good and pooled_mean > others
    return "INSUFFICIENT" if any(o is None for o in ok) else ("HOLDS" if all(ok) and good else "NOT SUPPORTED")


def report(data):
    ev, r4 = data
    low = lambda e: e["vix"] < e["vmed"]
    rising = lambda e: e["vchg"] > 0
    r1 = [e for e in ev if e["up"] and e["confirm"] and low(e) and rising(e)]
    r2 = [e for e in ev if e["up"] and not e["confirm"] and not low(e) and not rising(e)]
    r3 = [e for e in ev if not e["up"] and e["confirm"] and e["vchg"] >= 5]
    print(f"A-238 synergy matrix: {len(ev)} level-break events (PDH/PDL + ORH/ORL, NIFTY + SENSEX), naked ATM, +-0.75 % a leg\n")
    print("All cells (30 min): up/down x bank confirms/lags x VIX low/high x rising/dropping")
    for up in (True, False):
        for conf in (True, False):
            for lw in (True, False):
                for rs in (True, False):
                    c = [e["res"][30] for e in ev if e["up"] == up and e["confirm"] == conf and low(e) == lw and rising(e) == rs]
                    print(f"  {'resistance' if up else 'support   '} bank {'confirms' if conf else 'lags    '} VIX {'low ' if lw else 'high'} "
                          f"{'rising ' if rs else 'falling'}: n {len(c):3d}  {mean(c):+6.1%}")
    print()
    for name, rows, want, comp in (("R1 call (confirm, VIX low & rising)", r1, True, [e for e in ev if e["up"] and e not in r1]),
                                   ("R2 call (lags, VIX high & dropping)", r2, False, None),
                                   ("R3 put (confirm, VIX +5 %)", r3, True, [e for e in ev if not e["up"] and e not in r3])):
        print(name)
        for hold in (30, 60):
            v = verdict(name.split()[0], rows, hold, want, comp)
            print(f"  -> {hold} min: {v}")
        print()
    low11 = [e for e in r4 if e["vix"] < 11]
    print(f"R4 consolidating at 11:00 (no break, within 0.3 % of open): {len(r4) // 2} index-days; with VIX < 11: {len(low11) // 2}")
    print(f"  all consolidating days, 11:00 call + put 60 min: {mean([e['res'][60] for e in r4]):+.1%} (n {len(r4)})")
    v = verdict("R4", low11, 60, False)
    print(f"  -> R4 (VIX < 11): {v}")


if __name__ == "__main__":
    report(pickle.load(open(CACHE, "rb")) if sys.argv[1:] == ["report"] else run())
