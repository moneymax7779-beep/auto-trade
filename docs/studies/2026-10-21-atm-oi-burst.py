#!/usr/bin/env python3
"""A-157 near-ATM OI bursts over 1 / 3 / 5 minutes, strong relative to the day, with level breaks and futures volume
(definitions fixed in the ledger before this ran; amended twice before any result, see the ledger).

October own-capture tick days only (60-s NIFTY OI clock; BSE ~162 s). Reads the local autotrade database (md.*) through
`docker exec autotrade-postgres psql`; minute data per index-day is cached under .local/a157/. Usage:
  python3 2026-10-21-atm-oi-burst.py main      selection 1-9 Oct + validation 12-19 Oct
  python3 2026-10-21-atm-oi-burst.py design    the chosen variant on 21 Oct (reported apart, never counted)
"""
import csv, io, json, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

ROOT = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-atmoi/.local/a157"
UNDERLYINGS = ("NIFTY", "SENSEX")
WINDOWS = (1, 3, 5)
KS = (3, 5, 8)                      # burst: |share_w| >= k x the day's mean |share_w| so far
COMBOS = ("OI", "OI+B", "OI+V", "OI+B+V")
BAND = 2                            # ATM +- 2 strikes
FIRST, LAST = 9 * 60 + 30, 14 * 60 + 45
HORIZON, HORIZON2, GAP = 30, 15, 15
MIN_HISTORY = 10                    # earlier minutes of the day needed for the day-relative mean
SELECTION = ["2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09"]
VALIDATION = ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19"]
DESIGN = ["2026-10-21"]
IST = "at time zone 'Asia/Kolkata'"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def load(u, d):
    """One index-day of own capture as minute dicts: index (high, low, last), option last OI / last price, futures volume."""
    idx = {hm(t): (float(h), float(l), float(c)) for t, h, l, c in q(
        f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), max(price), min(price), last(price, recv_ts) "
        f"from md.index_tick where session_date = '{d}' and underlying = '{u}' and price > 0 "
        f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:30+05:30' group by 1")}
    if not idx:
        return None
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}'")
    if not exp or not exp[0][0]:
        return None
    lo = min(v[1] for v in idx.values()); hi = max(v[0] for v in idx.values())
    span = 1000 if u == "SENSEX" else 500
    opt, px = defaultdict(dict), defaultdict(dict)
    for ty, k, t, oi, ltp in q(f"select option_type, strike, to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), "
                               f"last(oi, recv_ts) filter (where oi > 0), last(ltp, recv_ts) filter (where ltp > 0) "
                               f"from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{exp[0][0]}' "
                               f"and strike between {lo - span} and {hi + span} "
                               f"and recv_ts >= '{d} 09:14+05:30' and recv_ts < '{d} 15:30+05:30' group by 1, 2, 3"):
        if oi:
            opt[(ty, float(k))][hm(t)] = float(oi)
        if ltp:
            px[(ty, float(k))][hm(t)] = float(ltp)
    fexp = q(f"select min(expiry)::text from md.future_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}'")
    fut = {}
    if fexp and fexp[0][0]:
        last = None
        for t, v in q(f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), max(volume) from md.future_tick "
                      f"where session_date = '{d}' and underlying = '{u}' and expiry = '{fexp[0][0]}' and volume > 0 "
                      f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:30+05:30' group by 1 order by 1"):
            v = float(v)
            fut[hm(t)] = v - last if last is not None else 0.0      # day-cumulative volume -> per minute
            last = v
    return {"idx": idx, "opt": dict(opt), "px": dict(px), "fut": fut}


def day(u, d):
    os.makedirs(ROOT, exist_ok=True)
    path = f"{ROOT}/own-{u}-{d}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    data = load(u, d)
    pickle.dump(data, open(path, "wb"))
    return data


def previous_day(d):
    r = q(f"select max(session_date)::text from md.index_tick where session_date < '{d}'")
    return r[0][0] if r and r[0][0] else None


def carried(series, minutes):
    out, last, keys, i = {}, None, sorted(series), 0
    for m in minutes:
        while i < len(keys) and keys[i] <= m:
            last = series[keys[i]]; i += 1
        out[m] = last
    return out


def features(data, prev_hl):
    """Per minute m in [FIRST, LAST]: for each w (share, rel, break, volume ratio, index change), and the outcomes."""
    idx = data["idx"]
    minutes = list(range(9 * 60 + 14, 15 * 60 + 30))
    strikes = sorted({k for (_, k) in data["opt"]})
    if len(strikes) < 5 or not idx:
        return None
    step = min(b - a for a, b in zip(strikes, strikes[1:]) if b > a)
    oi = {key: carried(s, minutes) for key, s in data["opt"].items()}
    close = carried({m: v[2] for m, v in idx.items()}, minutes)
    orb = [idx[m] for m in range(9 * 60 + 15, 9 * 60 + 30) if m in idx]
    if not orb:
        return None
    ups = [max(b[0] for b in orb)] + ([prev_hl[0]] if prev_hl else [])
    downs = [min(b[1] for b in orb)] + ([prev_hl[1]] if prev_hl else [])
    fut = data["fut"]
    wsum = {w: {e: sum(fut.get(x, 0.0) for x in range(e - w + 1, e + 1)) for e in range(9 * 60 + 14 + w, 15 * 60 + 30)}
            for w in WINDOWS}
    seen = {w: [] for w in WINDOWS}                     # |share_w| of the day's earlier minutes
    out = {}
    for m in range(9 * 60 + 16, LAST + 1):
        c = close.get(m)
        if c is None:
            continue
        atm = round(c / step) * step
        band = [atm + i * step for i in range(-BAND, BAND + 1)]
        row = {}
        for w in WINDOWS:
            dce = dpe = tot = 0.0; ok = True
            for k in band:
                for ty in ("CE", "PE"):
                    s = oi.get((ty, k))
                    a = s.get(m - w) if s else None; b = s.get(m) if s else None
                    if a is None or b is None:
                        ok = False; break
                    tot += a
                    if ty == "CE":
                        dce += b - a
                    else:
                        dpe += b - a
                if not ok:
                    break
            share = (dpe - dce) / tot if ok and tot > 0 else None
            mean = statistics.mean(seen[w]) if len(seen[w]) >= MIN_HISTORY else None
            rel = abs(share) / mean if share is not None and mean else None
            if share is not None:
                seen[w].append(abs(share))
            c0 = close.get(m - w)
            brk = 0
            if c0 is not None:
                if any(c0 <= x < c for x in ups):
                    brk = 1
                elif any(c < x <= c0 for x in downs):
                    brk = -1
            base = [wsum[w][e] for e in range(9 * 60 + 14 + w, m) if e in wsum[w]]
            med = statistics.median(base) if base else 0
            ratio = wsum[w].get(m, 0.0) / med if med > 0 else None
            row[w] = (share, rel, brk, ratio, (c - c0) if c0 is not None else 0.0)
        if m < FIRST or m not in idx:
            continue
        e, x30, x15 = close.get(m + 1), close.get(m + 1 + HORIZON), close.get(m + 1 + HORIZON2)
        row["ret30"] = (x30 / e - 1) * 1e4 if e and x30 else None
        row["ret15"] = (x15 / e - 1) * 1e4 if e and x15 else None
        row["opt"] = {}
        if e:
            k = round(e / step) * step
            for ty in ("CE", "PE"):
                p = carried(data["px"].get((ty, k), {}), [m + 1, m + 1 + HORIZON])
                a, b = p[m + 1], p[m + 1 + HORIZON]
                row["opt"][ty] = (b / a - 1) * 100 if a and b else None
        out[m] = row
    return out


def build(days):
    feats = {}
    for u in UNDERLYINGS:
        for d in days:
            data = day(u, d)
            pd = previous_day(d)
            prev = day(u, pd) if pd else None
            hv = [v for m, v in prev["idx"].items() if 9 * 60 + 15 <= m <= 15 * 60 + 29] if prev else []
            f = features(data, (max(v[0] for v in hv), min(v[1] for v in hv)) if hv else None) if data else None
            if f:
                feats[(u, d)] = f
            print(f"\r{u} {d}   ", end="", file=sys.stderr)
    print(file=sys.stderr)
    return feats


def events(feats, variant):
    kind, w, k = variant
    out = []
    for (u, d), rows in feats.items():
        last = {1: -999, -1: -999}
        for m in sorted(rows):
            share, rel, brk, ratio, chg = rows[m][w]
            if kind.startswith("OI"):
                if rel is None or rel < k:
                    continue
                sign = 1 if share > 0 else -1
            elif kind in ("B", "B+V"):
                sign = brk
            else:                                            # V alone: the direction of the index over the window
                sign = 1 if chg > 0 else -1 if chg < 0 else 0
            if sign == 0:
                continue
            if "B" in kind and brk != sign:
                continue
            if "V" in kind and (ratio is None or ratio < 1.5):
                continue
            if m - last[sign] < GAP or rows[m]["ret30"] is None:
                continue
            last[sign] = m
            o = rows[m]["opt"].get("CE" if sign > 0 else "PE")
            out.append((u, d, m, sign, sign * rows[m]["ret30"],
                        sign * rows[m]["ret15"] if rows[m]["ret15"] is not None else None, o))
    return out


def stats(ev):
    if not ev:
        return {"n": 0}
    r = [e[4] for e in ev]
    o = [e[6] for e in ev if e[6] is not None]
    r15 = [e[5] for e in ev if e[5] is not None]
    return {"n": len(r), "mean": statistics.mean(r), "hit": sum(1 for x in r if x > 0) / len(r),
            "mean15": statistics.mean(r15) if r15 else float("nan"), "opt": statistics.mean(o) if o else None,
            "days": len({e[1] for e in ev})}


def boot(ev, n=10000, seed=157):
    by = defaultdict(list)
    for e in ev:
        by[e[1]].append(e[4])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def fmt(s):
    if not s or s["n"] == 0:
        return "n=0"
    opt = f"  ATM option {s['opt']:+5.1f} %" if s.get("opt") is not None else ""
    return f"n={s['n']:4d} days={s['days']:2d} mean30 {s['mean']:+6.1f} bp  hit {s['hit']:.0%}  mean15 {s['mean15']:+6.1f} bp{opt}"


BASELINE = {"OI": None, "OI+B": "B", "OI+V": "V", "OI+B+V": "B+V"}


def chosen_variant(sel):
    scored = [(v, stats(events(sel, v))) for w in WINDOWS for k in KS for v in [(c, w, k) for c in COMBOS]]
    eligible = [(v, s) for v, s in scored if s["n"] >= 20]
    return max(eligible, key=lambda vs: vs[1]["mean"])[0], scored


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    sel = build(SELECTION)
    chosen, scored = chosen_variant(sel)
    json.dump({"chosen": chosen}, open(f"{ROOT}/chosen.json", "w"))
    if which == "design":
        feats = build(DESIGN)
        ev = events(feats, chosen)
        print(f"== design 21 Oct (not counted): chosen {chosen}  {fmt(stats(ev))}")
        for e in ev:
            o = f"  option {e[6]:+.1f} %" if e[6] is not None else ""
            print(f"   {e[0]} {e[2] // 60:02d}:{e[2] % 60:02d} {'up' if e[3] > 0 else 'down'} {e[4]:+.1f} bp{o}")
        return
    print(f"selection: {len(SELECTION)} October tick days {SELECTION[0]}..{SELECTION[-1]}")
    print("\n== selection, all 36 variants (sorted by mean30; >= 20 events eligible)")
    for v, s in sorted(scored, key=lambda vs: -(vs[1].get("mean") if vs[1]["n"] else -1e9)):
        print(f"  {v[0]:7s} w{v[1]} k{v[2]}  {fmt(s)}{'   <- chosen' if v == chosen else ''}")
    for w in WINDOWS:
        for b in ("B", "V", "B+V"):
            print(f"  baseline {b:4s} w{w}      {fmt(stats(events(sel, (b, w, 0))))}")
    val = build(VALIDATION)
    ev = events(val, chosen)
    s = stats(ev); ci = boot(ev)
    print(f"\n== validation ({len(VALIDATION)} days): chosen {chosen}  {fmt(s)}" + (f"  CI95 [{ci[0]:+.1f}, {ci[1]:+.1f}]" if ci else ""))
    base = BASELINE[chosen[0]]
    bs = stats(events(val, (base, chosen[1], 0))) if base else None
    if base:
        print(f"   no-OI baseline {base} w{chosen[1]}: {fmt(bs)}")
    for c in COMBOS:
        print(f"   (descriptive) {c:7s} w{chosen[1]} k{chosen[2]}: {fmt(stats(events(val, (c, chosen[1], chosen[2]))))}")
    for nu in UNDERLYINGS:
        print(f"   {nu}: {fmt(stats([e for e in ev if e[0] == nu]))}")
    for e in ev:
        o = f"  option {e[6]:+.1f} %" if e[6] is not None else ""
        print(f"     {e[0]} {e[1]} {e[2] // 60:02d}:{e[2] % 60:02d} {'up' if e[3] > 0 else 'down'} {e[4]:+.1f} bp{o}")
    print("\n== validation, all 36 variants (descriptive only; the verdict uses the chosen one)")
    for w in WINDOWS:
        for k in KS:
            for c in COMBOS:
                print(f"  {c:7s} w{w} k{k}  {fmt(stats(events(val, (c, w, k))))}")
    c1 = s["n"] > 0 and s["mean"] > 0 and ci is not None and ci[0] > 0
    c2 = s["n"] > 0 and (bs is None or bs["n"] == 0 or s["mean"] > bs["mean"])
    print(f"\n{'PASS' if c1 else 'FAIL'}  (1) validation mean > 0 and CI low > 0")
    print(f"{'PASS' if c2 else 'FAIL'}  (2) validation mean > no-OI baseline")
    print("A-157:", "PASS (thin, 12 days)" if c1 and c2 else "FAIL")


if __name__ == "__main__":
    main()
