#!/usr/bin/env python3
"""A-157 near-ATM OI bursts over 1 / 3 / 5 minutes, with level breaks and futures volume (definitions fixed in the
ledger before this ran).

Reads the local autotrade database only (hist.candle bars; md.* own capture) through `docker exec autotrade-postgres
psql`. Minute data per index-day is cached under .local/a157/. Usage:
  python3 2026-10-21-atm-oi-burst.py main      selection + validation + forward (held-out and 21 Oct not read)
  python3 2026-10-21-atm-oi-burst.py heldout   the chosen variant on 22-25 Sep, once
  python3 2026-10-21-atm-oi-burst.py design    the chosen variant on 21 Oct (reported apart)
"""
import csv, io, json, os, pickle, random, statistics, subprocess, sys
from collections import defaultdict

ROOT = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-atmoi/.local/a157"
UNDERLYINGS = ("NIFTY", "SENSEX")
WINDOWS = (1, 3, 5)
PCTS = (90, 95, 98)
COMBOS = ("OI", "OI+B", "OI+V", "OI+B+V")
BAND = 2                      # ATM +- 2 strikes
FIRST, LAST = 9 * 60 + 30, 14 * 60 + 45
HORIZON, HORIZON2, GAP = 30, 15, 15
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
FORWARD = ["2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15",
           "2026-10-16", "2026-10-19"]
DESIGN = ["2026-10-21"]
OWN_FROM = "2026-09-28"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


IST = "at time zone 'Asia/Kolkata'"


# ---------------------------------------------------------------- loading (one index-day -> minute dicts)

def load_bars(u, d):
    idx = {hm(t): (float(h), float(l), float(c)) for t, h, l, c in q(
        f"select to_char(bar_start {IST},'HH24:MI'), high, low, close from hist.candle where underlying = '{u}' "
        f"and kind = 'INDEX' and (bar_start {IST})::date = '{d}' and (bar_start {IST})::time between '09:15' and '15:29'")}
    if not idx:
        return None
    exp = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
            f"and (bar_start {IST})::date = '{d}'")
    if not exp or not exp[0][0]:
        return None
    lo = min(v[1] for v in idx.values()); hi = max(v[0] for v in idx.values())
    span = 1000 if u == "SENSEX" else 500
    opt = defaultdict(dict); px = defaultdict(dict)
    for ty, k, t, oi, c in q(f"select option_type, strike, to_char(bar_start {IST},'HH24:MI'), oi, close from hist.candle "
                             f"where underlying = '{u}' and kind = 'OPTION' and expiry = '{exp[0][0]}' "
                             f"and (bar_start {IST})::date = '{d}' and strike between {lo - span} and {hi + span}"):
        if oi and float(oi) > 0:
            opt[(ty, float(k))][hm(t)] = float(oi)
        if c:
            px[(ty, float(k))][hm(t)] = float(c)
    fexp = q(f"select expiry::text from hist.candle where underlying = '{u}' and kind = 'FUTURE' and (bar_start {IST})::date = '{d}' "
             f"group by 1 order by sum(volume) desc limit 1")
    fut = {}
    if fexp:
        fut = {hm(t): float(v) for t, v in q(
            f"select to_char(bar_start {IST},'HH24:MI'), volume from hist.candle where underlying = '{u}' and kind = 'FUTURE' "
            f"and expiry = '{fexp[0][0]}' and (bar_start {IST})::date = '{d}'")}
    return {"idx": idx, "opt": dict(opt), "px": dict(px), "fut": fut}


def load_own(u, d):
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
    opt = defaultdict(dict)
    for ty, k, t, oi in q(f"select option_type, strike, to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), "
                          f"last(oi, recv_ts) from md.option_tick where session_date = '{d}' and underlying = '{u}' "
                          f"and expiry = '{exp[0][0]}' and oi > 0 and strike between {lo - span} and {hi + span} "
                          f"and recv_ts >= '{d} 09:14+05:30' and recv_ts < '{d} 15:30+05:30' group by 1, 2, 3"):
        opt[(ty, float(k))][hm(t)] = float(oi)
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
    return {"idx": idx, "opt": dict(opt), "px": {}, "fut": fut}


def day(u, d, source):
    os.makedirs(ROOT, exist_ok=True)
    path = f"{ROOT}/{source}-{u}-{d}.pkl"
    if os.path.exists(path):
        return pickle.load(open(path, "rb"))
    data = load_bars(u, d) if source == "bars" else load_own(u, d)
    pickle.dump(data, open(path, "wb"))
    return data


def bar_days():
    return [r[0] for r in q(f"select d::text from (select distinct (bar_start {IST})::date d from hist.candle "
                            f"where underlying = 'NIFTY' and kind = 'INDEX' and bar_start >= '2026-01-01') x order by d")]


def own_days():
    return [r[0] for r in q(f"select distinct session_date::text from md.index_tick where session_date >= '{OWN_FROM}' order by 1")]


# ---------------------------------------------------------------- minute features of one index-day

def carried(series, minutes):
    out, last = {}, None
    keys = sorted(series)
    i = 0
    for m in minutes:
        while i < len(keys) and keys[i] <= m:
            last = series[keys[i]]; i += 1
        out[m] = last
    return out


def features(data, prev_hl, vol_hist):
    """Per minute m in [FIRST, LAST]: share_w, break_w (+1 / -1 / 0), vol_w (ratio) for w in WINDOWS; plus outcomes."""
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
    orh, orl = max(b[0] for b in orb), min(b[1] for b in orb)
    pdh, pdl = prev_hl if prev_hl else (float("nan"), float("nan"))
    ups = [x for x in (orh, pdh) if x == x]; downs = [x for x in (orl, pdl) if x == x]
    fut = data["fut"]
    out = {}
    for m in range(FIRST, LAST + 1):
        c = close.get(m)
        if c is None or m not in idx:
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
            c0 = close.get(m - w)
            brk = 0
            if c0 is not None:
                if any(c0 <= x < c for x in ups):
                    brk = 1
                elif any(c < x <= c0 for x in downs):
                    brk = -1
            vol = sum(fut.get(x, 0.0) for x in range(m - w + 1, m + 1))
            base = [sum(h.get(x, 0.0) for x in range(m - w + 1, m + 1)) for h in vol_hist]
            ratio = vol / statistics.median(base) if len(base) >= 5 and statistics.median(base) > 0 else None
            chg = (c - c0) if c0 is not None else 0.0
            row[w] = (share, brk, ratio, chg)
        e, x30, x15 = close.get(m + 1), close.get(m + 1 + HORIZON), close.get(m + 1 + HORIZON2)
        row["ret30"] = (x30 / e - 1) * 1e4 if e and x30 else None
        row["ret15"] = (x15 / e - 1) * 1e4 if e and x15 else None
        if data["px"] and e:
            k = round(e / step) * step
            row["opt"] = {}
            for ty in ("CE", "PE"):
                p = carried(data["px"].get((ty, k), {}), [m + 1, m + 1 + HORIZON])
                a, b = p[m + 1], p[m + 1 + HORIZON]
                row["opt"][ty] = (b / a - 1) * 100 if a and b else None
        out[m] = row
    return out


def build(days_by_source):
    """{(u, d): minute features}; previous-day high/low and the futures-volume history come from the same source."""
    feats = {}
    for source, days, history_days in days_by_source:
        for u in UNDERLYINGS:
            prev = None; hist = []
            for d in history_days:
                data = day(u, d, source)
                if data and d in days:
                    f = features(data, prev, hist[-20:])
                    if f:
                        feats[(u, d)] = f
                if data:
                    hv = [v for m, v in data["idx"].items() if 9 * 60 + 15 <= m <= 15 * 60 + 29]
                    prev = (max(v[0] for v in hv), min(v[1] for v in hv)) if hv else None
                    hist.append(data["fut"])
                print(f"\r{source} {u} {d}   ", end="", file=sys.stderr)
    print(file=sys.stderr)
    return feats


# ---------------------------------------------------------------- events and scoring

def events(feats, variant, thresholds):
    kind, w, pct = variant
    out = []
    for (u, d), rows in feats.items():
        last = {1: -999, -1: -999}
        for m in sorted(rows):
            share, brk, ratio, chg = rows[m][w]
            if kind.startswith("OI"):
                if share is None or abs(share) < thresholds[(u, w, pct)]:
                    continue
                sign = 1 if share > 0 else -1
            elif kind in ("B", "B+V"):
                if brk == 0:
                    continue
                sign = brk
            else:                                            # V alone: the direction of the index over the window
                sign = 1 if chg > 0 else -1 if chg < 0 else 0
            if "B" in kind and brk != sign:
                continue
            if "V" in kind and (ratio is None or ratio < 1.5):
                continue
            if sign == 0 or m - last[sign] < GAP or rows[m]["ret30"] is None:
                continue
            last[sign] = m
            o = rows[m].get("opt", {}).get("CE" if sign > 0 else "PE") if rows[m].get("opt") else None
            out.append((u, d, m, sign, sign * rows[m]["ret30"], sign * rows[m]["ret15"] if rows[m]["ret15"] is not None else None, o))
    return out


def stats(ev):
    if not ev:
        return {"n": 0}
    r = [e[4] for e in ev]
    o = [e[6] for e in ev if e[6] is not None]
    return {"n": len(r), "mean": statistics.mean(r), "hit": sum(1 for x in r if x > 0) / len(r),
            "mean15": statistics.mean([e[5] for e in ev if e[5] is not None]) if any(e[5] is not None for e in ev) else None,
            "opt": statistics.mean(o) if o else None, "days": len({e[1] for e in ev})}


def boot(ev, n=10000, seed=157):
    by = defaultdict(list)
    for e in ev:
        by[e[1]].append(e[4])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for k in (rng.choice(keys) for _ in keys) for v in by[k]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def fmt(s):
    if not s or s["n"] == 0:
        return "n=0"
    opt = f", ATM option {s['opt']:+.1f} %" if s.get("opt") is not None else ""
    return f"n={s['n']:4d} days={s['days']:3d} mean30 {s['mean']:+6.1f} bp  hit {s['hit']:.0%}  mean15 {s['mean15']:+6.1f} bp{opt}"


def baseline_of(kind):
    return {"OI": None, "OI+B": "B", "OI+V": "V", "OI+B+V": "B+V"}[kind]


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    bars = bar_days()
    sel_days = [d for d in bars[15:] if d <= "2026-06-30"]
    val_days = [d for d in bars if "2026-07-01" <= d <= "2026-09-30" and d not in HELD_OUT]
    own = own_days()
    sel = build([("bars", set(sel_days), bars)])
    thresholds = {}
    for u in UNDERLYINGS:
        for w in WINDOWS:
            vals = sorted(abs(r[w][0]) for (uu, _), rows in sel.items() if uu == u for r in rows.values() if r[w][0] is not None)
            for p in PCTS:
                thresholds[(u, w, p)] = vals[int(p / 100 * (len(vals) - 1))]
    variants = [(k, w, p) for w in WINDOWS for p in PCTS for k in COMBOS]
    scored = []
    for v in variants:
        s = stats(events(sel, v, thresholds))
        scored.append((v, s))
    eligible = [(v, s) for v, s in scored if s["n"] >= 60]
    chosen = max(eligible, key=lambda vs: vs[1]["mean"])[0]
    json.dump({"chosen": chosen, "thresholds": {f"{k[0]} w{k[1]} p{k[2]}": v for k, v in thresholds.items()}},
              open(f"{ROOT}/chosen.json", "w"), indent=1)
    if which == "main":
        print(f"selection: {len(sel_days)} bar days {sel_days[0]}..{sel_days[-1]}; thresholds |share| "
              + ", ".join(f"{u} w{w} p{p} {thresholds[(u, w, p)]:.4f}" for u in UNDERLYINGS for w in WINDOWS for p in PCTS))
        print("\n== selection, all 36 variants (sorted by mean30)")
        for v, s in sorted(scored, key=lambda vs: -(vs[1].get("mean") or -1e9)):
            print(f"  {v[0]:7s} w{v[1]} p{v[2]}  {fmt(s)}{'   <- chosen' if v == chosen else ''}")
        for w in WINDOWS:
            for k in ("B", "V", "B+V"):
                print(f"  baseline {k:4s} w{w}   {fmt(stats(events(sel, (k, w, 0), thresholds)))}")
        val = build([("bars", set(val_days), bars)])
        fwd = build([("own", set(FORWARD), own)])
        verdict = []
        for name, feats in (("validation", val), ("forward", fwd)):
            ev = events(feats, chosen, thresholds)
            s = stats(ev); ci = boot(ev)
            base = baseline_of(chosen[0])
            print(f"\n== {name} ({len({d for (_, d) in feats})} days): chosen {chosen}  {fmt(s)}"
                  + (f"  CI95 [{ci[0]:+.1f}, {ci[1]:+.1f}]" if ci else ""))
            bs = None
            if base:
                bs = stats(events(feats, (base, chosen[1], 0), thresholds))
                print(f"   no-OI baseline {base} w{chosen[1]}: {fmt(bs)}")
            for k in COMBOS:
                print(f"   (descriptive) {k:7s} w{chosen[1]} p{chosen[2]}: {fmt(stats(events(feats, (k, chosen[1], chosen[2]), thresholds)))}")
            for nu in UNDERLYINGS:
                print(f"   {nu}: {fmt(stats([e for e in ev if e[0] == nu]))}")
            if name == "validation":
                verdict.append(("(1) validation mean > 0 and CI low > 0", s["n"] > 0 and s["mean"] > 0 and ci is not None and ci[0] > 0))
                verdict.append(("(2) validation mean > no-OI baseline", s["n"] > 0 and (bs is None or bs["n"] == 0 or s["mean"] > bs["mean"])))
            else:
                verdict.append(("(3) forward mean > 0", s["n"] > 0 and s["mean"] > 0))
                for e in ev:
                    print(f"     {e[0]} {e[1]} {e[2] // 60:02d}:{e[2] % 60:02d} {'up' if e[3] > 0 else 'down'} {e[4]:+.1f} bp")
        print()
        for nme, ok in verdict:
            print(f"{'PASS' if ok else 'FAIL'}  {nme}")
        print("A-157:", "PASS" if all(ok for _, ok in verdict) else "FAIL")
    else:
        days = sorted(HELD_OUT) if which == "heldout" else DESIGN
        feats = build([("bars", set(days), bars)]) if which == "heldout" else build([("own", set(days), own)])
        ev = events(feats, chosen, thresholds)
        print(f"== {which}: chosen {chosen}  {fmt(stats(ev))}")
        for e in ev:
            print(f"   {e[0]} {e[1]} {e[2] // 60:02d}:{e[2] % 60:02d} {'up' if e[3] > 0 else 'down'} {e[4]:+.1f} bp")


if __name__ == "__main__":
    main()
