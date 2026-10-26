#!/usr/bin/env python3
"""A-237: global cues, daily only (claims fixed in the ledger before this ran).
Previous US session's change (Yahoo daily closes: DX-Y.NYB, BZ=F, ES=F, saved under .local/a236/macro-download) joined to
A-234's first PDH / PDL breaks. M1: NIFTY calls (PDH breaks) on days after DXY >= +0.3 % or Brent >= +2 % earn less than
on other days. M2: the naked ATM option earns more when the previous ES change agrees with the break's direction.
Usage: python3 -I 2026-10-26-global-cues.py
"""
import datetime, json, os, pickle, random, statistics
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..", ".local")
HALVES = [("Oct24-Jun25", "2024-10-01", "2025-06-30"), ("Jul25-Sep26", "2025-07-01", "2026-09-30")]


def series(name):
    j = json.load(open(os.path.join(ROOT, "a236", "macro-download", name + ".json")))
    r = j["chart"]["result"][0]
    rows = sorted((datetime.datetime.utcfromtimestamp(t).date().isoformat(), c)
                  for t, c in zip(r["timestamp"], r["indicators"]["quote"][0]["close"]) if c is not None)
    return [(d, c, (c / p - 1) * 100) for (d, c), (_, p) in zip(rows[1:], rows[:-1])]


def prev_change(s, india_day):
    """The latest US session dated strictly before the Indian session (US closes after India)."""
    best = None
    for d, _, chg in s:
        if d < india_day:
            best = chg
        else:
            break
    return best


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def boot_diff(a, b, n=10000, seed=237):
    days = sorted({d for d, _ in a} | {d for d, _ in b})
    da, db = defaultdict(list), defaultdict(list)
    for d, v in a:
        da[d].append(v)
    for d, v in b:
        db[d].append(v)
    r, vals = random.Random(seed), []
    for _ in range(n):
        pick = [r.choice(days) for _ in days]
        sa = [v for d in pick for v in da.get(d, [])]
        sb = [v for d in pick for v in db.get(d, [])]
        if sa and sb:
            vals.append(mean(sa) - mean(sb))
    vals.sort()
    return vals[int(0.025 * len(vals))], vals[int(0.975 * len(vals)) - 1]


def claim(name, ev, a_sel, b_sel, want_positive):
    ok = []
    for lab, lo, hi in HALVES + [("pooled", "2024-10-01", "2026-09-30")]:
        sub = [e for e in ev if lo <= e["d"] <= hi]
        a = [(e["d"], e["res"][30][0]) for e in sub if a_sel(e)]
        b = [(e["d"], e["res"][30][0]) for e in sub if b_sel(e)]
        diff = mean([v for _, v in a]) - mean([v for _, v in b])
        ci = boot_diff(a, b) if lab == "pooled" and a and b else None
        print(f"  {lab}: {diff:+.1%} (n {len(a)} vs {len(b)})" + (f", CI95 [{ci[0]:+.1%}, {ci[1]:+.1%}]" if ci else ""))
        if lab != "pooled":
            ok.append(None if min(len(a), len(b)) < 30 else (diff > 0) == want_positive)
        else:
            pooled = ci
    good = pooled and (pooled[0] > 0 if want_positive else pooled[1] < 0)
    print(f"  -> {name}: " + ("INSUFFICIENT" if any(o is None for o in ok) else "HOLDS" if all(ok) and good else "NOT SUPPORTED") + "\n")


def main():
    ev = pickle.load(open(os.path.join(ROOT, "a234", "events.pkl"), "rb"))
    dxy, brent, es = series("DX-Y_NYB"), series("BZ_F"), series("ES_F")
    for e in ev:
        e["dxy"], e["brent"], e["es"] = prev_change(dxy, e["d"]), prev_change(brent, e["d"]), prev_change(es, e["d"])
    ev = [e for e in ev if None not in (e["dxy"], e["brent"], e["es"])]
    print(f"A-237 global cues (daily, previous US session), {len(ev)} A-234 events with all three series\n")
    print("M1: NIFTY calls (PDH breaks), naked 30 min: after DXY >= +0.3 % or Brent >= +2 %  minus  other days (claim: negative)")
    calls = [e for e in ev if e["u"] == "NIFTY" and e["ty"] == "CE"]
    stress = lambda e: e["dxy"] >= 0.3 or e["brent"] >= 2.0
    claim("M1", calls, stress, lambda e: not stress(e), want_positive=False)
    print("M2: naked 30 min, previous ES change agrees with the break minus disagrees (claim: positive), NIFTY + SENSEX")
    agree = lambda e: (e["es"] > 0) == (e["ty"] == "CE")
    claim("M2", ev, agree, lambda e: not agree(e), want_positive=True)
    print("Descriptive: naked 30 min by previous ES change, calls / puts")
    for lab, f in (("ES <= -1 %", lambda e: e["es"] <= -1), ("-1..0", lambda e: -1 < e["es"] <= 0), ("0..+1", lambda e: 0 < e["es"] <= 1),
                   ("> +1 %", lambda e: e["es"] > 1)):
        print(f"  {lab:10s} calls {mean([e['res'][30][0] for e in ev if f(e) and e['ty'] == 'CE']):+.1%} "
              f"(n {sum(1 for e in ev if f(e) and e['ty'] == 'CE')}), puts {mean([e['res'][30][0] for e in ev if f(e) and e['ty'] == 'PE']):+.1%} "
              f"(n {sum(1 for e in ev if f(e) and e['ty'] == 'PE')})")


if __name__ == "__main__":
    main()
