#!/usr/bin/env python3
"""A-235: cross-index divergence filter (claims fixed in the ledger before this ran).
Events = A-234's first PDH / PDL breaks (its cache .local/a234/events.pkl). NIFTY BANK confirms if its last 1-minute close
at the event is beyond its own previous-session high (low) in the same direction. B1: naked 30 min, confirmed minus
diverged. B2: after a diverged break, the opposite ATM option, 30 min, mean > 0.
Usage: python3 2026-10-26-cross-index.py
"""
import csv, io, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
STEP = {"NIFTY": 50, "SENSEX": 100}
COST = 0.0075
HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS = os.path.join(HERE, "..", "..", ".local", "a234", "events.pkl")
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]


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


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def boot(groups, fn, n=10000, seed=235):
    days = sorted({d for g in groups for d, _ in g})
    by = [defaultdict(list) for _ in groups]
    for i, g in enumerate(groups):
        for d, v in g:
            by[i][d].append(v)
    r, vals = random.Random(seed), []
    for _ in range(n):
        pick = [r.choice(days) for _ in days]
        samples = [[v for d in pick for v in b.get(d, [])] for b in by]
        if all(samples):
            vals.append(fn(*[mean(s) for s in samples]))
    vals.sort()
    return (vals[int(0.025 * len(vals))], vals[int(0.975 * len(vals)) - 1]) if vals else None


def main():
    ev = pickle.load(open(EVENTS, "rb"))
    bank_days = {}
    rows = q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle "
             f"where underlying = 'BANKNIFTY' and kind = 'INDEX' and bar_start >= '2024-09-15'")
    for d, t, h, l, c in rows:
        bank_days.setdefault(d, {})[hm(t)] = (float(h), float(l), float(c))
    ds = sorted(bank_days)
    prev = {ds[i]: ds[i - 1] for i in range(1, len(ds))}
    print(f"NIFTY BANK bars: {len(ds)} days ({ds[0] if ds else '-'} .. {ds[-1] if ds else '-'})", file=sys.stderr)
    out = []
    for e in ev:
        d = e["d"]
        if d not in bank_days or d not in prev or len(bank_days[d]) < 300:
            continue
        p = bank_days[prev[d]]
        pdh = max(b[0] for m, b in p.items() if m < 915)
        pdl = min(b[1] for m, b in p.items() if m < 915)
        bc = last_at({m: b[2] for m, b in bank_days[d].items()}, e["minute"] - 1)
        if bc is None:
            continue
        confirm = bc > pdh if e["ty"] == "CE" else bc < pdl
        # the opposite ATM option at the event
        opp = "PE" if e["ty"] == "CE" else "CE"
        idx_close = last_at({hm(t): float(c) for t, c in q(
            f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{e['u']}' and kind = 'INDEX' "
            f"and bar_start between {rng(d)}")}, e["minute"] - 1)
        exp = q(f"select min(expiry)::text from hist.candle where underlying = '{e['u']}' and kind = 'OPTION' and expiry >= '{d}' "
                f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
        opp_res = {}
        if idx_close and exp and exp[0] and exp[0][0]:
            k = round(idx_close / STEP[e["u"]]) * STEP[e["u"]]
            s = {hm(t): float(c) for t, c in q(
                f"select to_char(bar_start {IST},'HH24:MI'), close from hist.candle where underlying = '{e['u']}' and kind = 'OPTION' "
                f"and expiry = '{exp[0][0]}' and option_type = '{opp}' and strike = {k} and bar_start between {rng(d)}") if c and float(c) > 0}
            b0 = last_at(s, e["minute"] - 1)
            for hold in (30, 60):
                b1 = last_at(s, e["minute"] - 1 + hold)
                if b0 and b1:
                    opp_res[hold] = b1 * (1 - COST) / (b0 * (1 + COST)) - 1
        out.append(dict(e, confirm=confirm, opp=opp_res))
    print(f"\rA-235 cross-index divergence filter: {len(out)} of {len(ev)} A-234 events have NIFTY BANK bars\n")
    for u in ("NIFTY", "SENSEX", "both"):
        sel = [x for x in out if u == "both" or x["u"] == u]
        c = [x for x in sel if x["confirm"]]
        dv = [x for x in sel if not x["confirm"]]
        print(f"{u}: confirmed n {len(c)} naked 30 {mean([x['res'][30][0] for x in c]):+.1%}, 60 "
              f"{mean([x['res'][60][0] for x in c if 60 in x['res']]):+.1%} | diverged n {len(dv)} naked 30 "
              f"{mean([x['res'][30][0] for x in dv]):+.1%}, 60 {mean([x['res'][60][0] for x in dv if 60 in x['res']]):+.1%} | "
              f"opposite after divergence 30 {mean([x['opp'][30] for x in dv if 30 in x['opp']]):+.1%}, 60 "
              f"{mean([x['opp'][60] for x in dv if 60 in x['opp']]):+.1%}")
    print()
    for name, fn in (("B1 confirmed minus diverged (naked 30)", None), ("B2 opposite option after divergence (30)", None)):
        halves_ok = []
        for lab, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
            sub = [x for x in out if lo <= x["d"] <= hi]
            if name.startswith("B1"):
                a = [(x["d"], x["res"][30][0]) for x in sub if x["confirm"]]
                b = [(x["d"], x["res"][30][0]) for x in sub if not x["confirm"]]
                val = mean([v for _, v in a]) - mean([v for _, v in b])
                n = min(len(a), len(b))
                ci = boot([a, b], lambda x, y: x - y) if lab == "pooled" else None
            else:
                a = [(x["d"], x["opp"][30]) for x in sub if not x["confirm"] and 30 in x["opp"]]
                val, n = mean([v for _, v in a]), len(a)
                ci = boot([a], lambda x: x) if lab == "pooled" else None
            print(f"  {name} {lab}: {val:+.1%} (n {n})" + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
            if lab != "pooled":
                halves_ok.append(None if n < 30 else val > 0)
            else:
                pooled = ci
        verdict = ("INSUFFICIENT" if any(h is None for h in halves_ok) else
                   "HOLDS" if all(halves_ok) and pooled and pooled[0] > 0 else "NOT SUPPORTED")
        print(f"  -> {verdict}\n")


if __name__ == "__main__":
    main()
