#!/usr/bin/env python3
"""A-234: India VIX and option buying (claims fixed in the ledger before this ran).

Event: the first 5-minute close of the day above the previous session's high (call) or below its low (put), 09:45-14:30,
NIFTY and SENSEX. Each event: naked ATM option and a debit spread (buy ATM, sell 2 strikes OTM), held 30 and 60 minutes;
VIX at the event and the VIX change since 09:15. Each leg +-0.75 %.
Usage: python3 2026-10-26-vix-option-buying.py [report]
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", ".local", "a234", "events.pkl")
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]
BUCKETS = [("<11", 0, 11), ("11-13", 11, 13), ("13-15", 13, 15), ("15-18", 15, 18), ("18-20", 18, 20), (">20", 20, 99)]


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def rng(d):
    return f"('{d} 09:15+05:30')::timestamptz and ('{d} 15:30+05:30')::timestamptz"


def last_at(s, m, back=5):
    for x in range(m, m - back - 1, -1):
        if x in s:
            return s[x]
    return None


def run():
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    days = [r[0] for r in q(f"select distinct (bar_start {IST})::date::text from hist.candle where underlying = 'NIFTY' and kind = 'INDEX' "
                            f"and bar_start >= '2024-09-20' order by 1")]
    out = []
    for u in ("NIFTY", "SENSEX"):
        prev_hl = None
        for d in days:
            print(f"\r{u} {d}  ", end="", file=sys.stderr)
            idx = {hm(t): tuple(map(float, v)) for t, *v in q(
                f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
                f"and kind = 'INDEX' and bar_start between {rng(d)}")}
            if len(idx) < 300:
                continue
            hl = (max(b[1] for m, b in idx.items() if m < 915), min(b[2] for m, b in idx.items() if m < 915))
            pdh, pdl = prev_hl if prev_hl else (None, None)
            prev_hl = hl
            if pdh is None or d < "2024-10-01" or d > "2026-09-30" or d in HELD_OUT:
                continue
            ms = sorted(idx)
            event = None
            for dm in range(585, 871, 5):
                closes = [m for m in ms if m < dm]
                if not closes:
                    continue
                c = idx[closes[-1]][3]
                # "first close above / below": the previous 5-minute closes were not already beyond the level
                earlier = [idx[m][3] for m in ms if m < dm - 5 and (m - 555) % 5 == 4]
                if c > pdh and all(x <= pdh for x in earlier):
                    event = ("CE", dm, c)
                    break
                if c < pdl and all(x >= pdl for x in earlier):
                    event = ("PE", dm, c)
                    break
            if not event:
                continue
            ty, dm, c = event
            e = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
                  f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
            if not e or not e[0] or not e[0][0]:
                continue
            step = STEP[u]
            atm = round(c / step) * step
            far = atm + 2 * step if ty == "CE" else atm - 2 * step
            px = defaultdict(dict)
            for t, k, close in q(f"select to_char(bar_start {IST},'HH24:MI'), strike, close from hist.candle where underlying = '{u}' "
                                 f"and kind = 'OPTION' and expiry = '{e[0][0]}' and option_type = '{ty}' and strike in ({atm}, {far}) "
                                 f"and bar_start between {rng(d)}"):
                if close and float(close) > 0:
                    px[float(k)][hm(t)] = float(close)
            vix = {hm(t): float(v) for t, v in q(f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle "
                                                 f"where underlying = 'INDIA_VIX' and kind = 'VIX' and bar_start between {rng(d)}")}
            v_now, v_open = last_at(vix, dm - 1, 10), last_at(vix, 556, 3) or (vix[min(vix)] if vix else None)
            a0, f0 = last_at(px[float(atm)], dm - 1), last_at(px[float(far)], dm - 1)
            if a0 is None or f0 is None or v_now is None or v_open is None:
                continue
            res = {}
            for hold in (30, 60):
                a1, f1 = last_at(px[float(atm)], dm - 1 + hold), last_at(px[float(far)], dm - 1 + hold)
                if a1 is None or f1 is None:
                    continue
                naked = a1 * (1 - COST) / (a0 * (1 + COST)) - 1
                debit = a0 * (1 + COST) - f0 * (1 - COST)
                value = a1 * (1 - COST) - f1 * (1 + COST)
                spread = value / debit - 1 if debit > 0 else None
                res[hold] = (naked, spread)
            if 30 in res:
                out.append(dict(u=u, d=d, ty=ty, minute=dm, vix=v_now, vix_chg=v_now - v_open, res=res))
    print(file=sys.stderr)
    pickle.dump(out, open(CACHE, "wb"))
    return out


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def boot_diff(a, b, n=10000, seed=234):
    """Day-bootstrap 95 % CI of mean(a) - mean(b); a, b = lists of (day, value)."""
    da, db = defaultdict(list), defaultdict(list)
    for d, v in a:
        da[d].append(v)
    for d, v in b:
        db[d].append(v)
    ka, kb = list(da), list(db)
    if len(ka) < 2 or len(kb) < 2:
        return None
    r, diffs = random.Random(seed), []
    for _ in range(n):
        sa = [v for k in (r.choice(ka) for _ in ka) for v in da[k]]
        sb = [v for k in (r.choice(kb) for _ in kb) for v in db[k]]
        diffs.append(mean(sa) - mean(sb))
    diffs.sort()
    return diffs[250], diffs[9749]


def boot_paired(pairs, n=10000, seed=234):
    by = defaultdict(list)
    for d, v in pairs:
        by[d].append(v)
    ks = list(by)
    if len(ks) < 2:
        return None
    r, ms = random.Random(seed), []
    for _ in range(n):
        s = [v for k in (r.choice(ks) for _ in ks) for v in by[k]]
        ms.append(mean(s))
    ms.sort()
    return ms[250], ms[9749]


def report(ev):
    print("A-234 India VIX and option buying: first PDH / PDL break 09:45-14:30, NIFTY + SENSEX, Oct 2024-Sep 2026 (bars, +-0.75 % a leg)\n")
    print(f"events: {len(ev)} (calls {sum(e['ty'] == 'CE' for e in ev)}, puts {sum(e['ty'] == 'PE' for e in ev)})\n")
    print("| VIX at the break | n | naked 30 min | spread 30 min | naked 60 min | spread 60 min | naked win 30 |\n|---|---|---|---|---|---|---|")
    for lab, lo, hi in BUCKETS:
        b = [e for e in ev if lo <= e["vix"] < hi]
        n30 = [e["res"][30][0] for e in b]
        s30 = [e["res"][30][1] for e in b if e["res"][30][1] is not None]
        n60 = [e["res"][60][0] for e in b if 60 in e["res"]]
        s60 = [e["res"][60][1] for e in b if 60 in e["res"] and e["res"][60][1] is not None]
        print(f"| {lab} | {len(b)} | {mean(n30):+.1%} | {mean(s30):+.1%} | {mean(n60):+.1%} | {mean(s60):+.1%} | "
              f"{(sum(x > 0 for x in n30) / len(n30)) if n30 else float('nan'):.0%} |")
    print()

    def claim(name, a_sel, b_sel, val_a, val_b, paired=False):
        rows = []
        ok_halves = []
        for lab, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
            sub = [e for e in ev if lo <= e["d"] <= hi]
            if paired:
                pairs = [(e["d"], val_a(e) - val_b(e)) for e in sub if a_sel(e) and val_b(e) is not None]
                diff, n = mean([p[1] for p in pairs]), len(pairs)
                ci = boot_paired(pairs) if lab == "pooled" else None
                rows.append(f"{lab}: diff {diff:+.1%} (n {n})" + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
            else:
                a = [(e["d"], val_a(e)) for e in sub if a_sel(e)]
                b = [(e["d"], val_b(e)) for e in sub if b_sel(e)]
                diff, n = mean([x[1] for x in a]) - mean([x[1] for x in b]), (len(a), len(b))
                ci = boot_diff(a, b) if lab == "pooled" else None
                rows.append(f"{lab}: diff {diff:+.1%} (n {n[0]} vs {n[1]})" + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
            if lab != "pooled":
                small = (n < 30) if paired else (min(n) < 30)
                ok_halves.append(None if small else diff > 0)
            else:
                pooled_ci = ci
        holds = ("INSUFFICIENT" if any(o is None for o in ok_halves) else
                 "HOLDS" if all(ok_halves) and pooled_ci and pooled_ci[0] > 0 else "NOT SUPPORTED")
        print(f"{name}: {holds}\n   " + "\n   ".join(rows))

    nk = lambda e: e["res"][30][0]
    claim("C1 naked 30 min: VIX < 13 minus VIX > 18", lambda e: e["vix"] < 13, lambda e: e["vix"] > 18, nk, nk)
    claim("C2 VIX > 18: spread minus naked (30 min)", lambda e: e["vix"] > 18, None, lambda e: e["res"][30][1] if e["res"][30][1] is not None else 0,
          lambda e: e["res"][30][0] if e["res"][30][1] is not None else None, paired=True)
    claim("C3 naked 30 min: VIX rose since the open minus VIX fell", lambda e: e["vix_chg"] > 0, lambda e: e["vix_chg"] < 0, nk, nk)
    low = [e for e in ev if e["vix"] < 11]
    print(f"C4 naked 30 min with VIX < 11: n {len(low)}, mean {mean([nk(e) for e in low]):+.1%}"
          + ("  (INSUFFICIENT, n < 20)" if len(low) < 20 else ("  -> loses (holds)" if mean([nk(e) for e in low]) < 0 else "  -> does not lose (not supported)")))
    print("\nBy direction (naked 30 min): " + ", ".join(f"{t} {mean([nk(e) for e in ev if e['ty'] == t]):+.1%} (n {sum(e['ty'] == t for e in ev)})" for t in ("CE", "PE")))
    print("VIX rising vs falling, calls / puts (naked 30): " + ", ".join(
        f"{t} {'rise' if r else 'fall'} {mean([nk(e) for e in ev if e['ty'] == t and (e['vix_chg'] > 0) == r]):+.1%}"
        for t in ("CE", "PE") for r in (True, False)))


if __name__ == "__main__":
    report(pickle.load(open(CACHE, "rb")) if sys.argv[1:] == ["report"] else run())
