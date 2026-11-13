#!/usr/bin/env python3
"""A-383 between-prints option aggressor flow (definitions and criteria fixed in the ledger first).

Near-ATM (ATM +/- 2) option ticks of the nearest expiry; each tick's volume increase is signed buy/sell against the previous
tick's mid (tick rule when at the mid); flow60 = calls (buy - sell) - puts (buy - sell) over the last 60 s, imbalance =
flow60 / band volume. Samples every 15 s 09:20-15:10 IST; outcome = index move over the next 300 s in bp. Local database only.
Also reported: the nowcast question (does aggressor net volume between two OI prints tell the print's OI change?).
Usage: python3 2026-11-13-option-aggressor-flow.py [day ...]   (default: all own-capture days 28 Sep - 12 Nov)
"""
import bisect, csv, io, json, random, statistics, subprocess, sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
        "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21",
        "2026-10-22", "2026-10-23", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-11-02",
        "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-09", "2026-11-11", "2026-11-12"]
HALF = "2026-10-21"                      # first half ends here (16 days), second 22 Oct - 12 Nov (15)
STEP = {"NIFTY": 50, "SENSEX": 100}
BAND = 2


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def ms(d, hhmmss):
    return int(datetime.fromisoformat(f"{d} {hhmmss}").replace(tzinfo=IST).timestamp() * 1000)


def index_series(u, d):
    rows = q(f"select (extract(epoch from recv_ts) * 1000)::bigint, price from md.index_tick where session_date = '{d}' "
             f"and underlying = '{u}' and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:20+05:30' order by recv_ts, src_seq")
    t, p = [], []
    for a, b in rows:
        if float(b) > 0:
            t.append(int(a)); p.append(float(b))
    return t, p


def at(t, p, x):
    i = bisect.bisect_right(t, x) - 1
    return p[i] if i >= 0 else None


def option_ticks(u, d, lo, hi):
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts >= '{d} 10:00+05:30' and recv_ts < '{d} 10:02+05:30'")
    if not exp or not exp[0][0]:
        return None, []
    rows = q(f"select (extract(epoch from recv_ts) * 1000)::bigint, strike, option_type, ltp, volume, oi, bid_px[1], ask_px[1] "
             f"from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{exp[0][0]}' "
             f"and strike between {lo} and {hi} and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:20+05:30' "
             f"order by strike, option_type, recv_ts, src_seq")
    return exp[0][0], rows


def classify(rows):
    """Per contract: events (ms, strike, side 'CE'/'PE', signed dv: +buy / -sell / 0 unclassified with |dv| kept apart),
    and OI prints (ms, strike, side, oi change)."""
    ev, prints = [], []
    key = None
    for r in rows:
        t, k, s, ltp, vol, oi, bid, ask = int(r[0]), float(r[1]), r[2], float(r[3]), r[4], r[5], r[6], r[7]
        vol = int(vol) if vol else None
        oi = float(oi) if oi else None
        bid = float(bid) if bid else 0.0
        ask = float(ask) if ask else 0.0
        if (k, s) != key:
            key = (k, s); pv = None; pmid = None; last_ltp = None; diff_ltp = None; poi = None
        if vol is not None and pv is not None and vol > pv:
            dv = vol - pv
            side = 0
            if pmid is not None and ltp > pmid + 1e-9:
                side = 1
            elif pmid is not None and ltp < pmid - 1e-9:
                side = -1
            elif diff_ltp is not None:
                side = 1 if ltp > diff_ltp else -1 if ltp < diff_ltp else 0
            ev.append((t, k, s, side, dv))
        if oi is not None and poi is not None and oi != poi:
            prints.append((t, k, s, oi - poi))
        if last_ltp is not None and ltp != last_ltp:
            diff_ltp = last_ltp
        last_ltp = ltp
        if vol is not None:
            pv = vol if pv is None else max(pv, vol)
        if bid > 0 and ask > bid:
            pmid = (bid + ask) / 2
        if oi is not None:
            poi = oi
    return ev, prints


def day(u, d):
    it, ip = index_series(u, d)
    if len(it) < 1000:
        return [], [], {}
    step = STEP[u]
    lo = (min(ip) // step - BAND - 1) * step
    hi = (max(ip) // step + BAND + 2) * step
    exp, rows = option_ticks(u, d, lo, hi)
    if not rows:
        return [], [], {}
    ev, prints = classify(rows)
    ev.sort()
    et = [e[0] for e in ev]
    stats = {"dv": sum(e[4] for e in ev), "classified": sum(e[4] for e in ev if e[3] != 0), "expiry": exp == d}
    # print times of the band (any strike, any side) for the "since last print" window
    pt = sorted({p[0] // 1000 for p in prints})
    out = []
    start, end = ms(d, "09:20:00"), ms(d, "15:10:00")
    for s in range(start, end + 1, 15000):
        x0 = at(it, ip, s)
        x5 = at(it, ip, s + 300000)
        x1 = at(it, ip, s + 60000)
        xm = at(it, ip, s - 60000)
        if not x0 or not x5 or not xm:
            continue
        atm = round(x0 / step) * step

        def flow(a, b, band):
            i, j = bisect.bisect_right(et, a), bisect.bisect_right(et, b)
            f = tot = 0
            for e in ev[i:j]:
                if abs(e[1] - atm) > band * step + 1e-6:
                    continue
                tot += e[4]
                f += e[3] * e[4] * (1 if e[2] == "CE" else -1)
            return f, tot

        f60, v60 = flow(s - 60000, s, BAND)
        f1, v1 = flow(s - 60000, s, 1)
        k = bisect.bisect_right(pt, s // 1000) - 1
        lp = pt[k] * 1000 if k >= 0 and pt[k] * 1000 > s - 300000 else s - 60000
        fp, vp = flow(lp, s, BAND)
        out.append({"u": u, "d": d, "exp": exp == d, "imb": f60 / v60 if v60 else None, "v": v60,
                    "imb1": f1 / v1 if v1 else None, "imbp": fp / vp if vp else None,
                    "r300": (x5 / x0 - 1) * 1e4, "r60": (x1 / x0 - 1) * 1e4 if x1 else None, "mom": (x0 / xm - 1) * 1e4})
    # nowcast: per contract within ATM +/- 2 of the index at the print, aggressor net dv between its previous and this print
    byc = defaultdict(list)
    for p in prints:
        byc[(p[1], p[2])].append(p)
    evc = defaultdict(list)
    for e in ev:
        evc[(e[1], e[2])].append(e)
    nc = []
    for c, ps in byc.items():
        es = evc.get(c, [])
        ets = [e[0] for e in es]
        for a, b in zip(ps, ps[1:]):
            if b[0] - a[0] > 400000:
                continue
            x = at(it, ip, b[0])
            if not x or abs(c[0] - round(x / step) * step) > BAND * step + 1e-6:
                continue
            i, j = bisect.bisect_right(ets, a[0]), bisect.bisect_right(ets, b[0])
            net = sum(e[3] * e[4] for e in es[i:j]); vol = sum(e[4] for e in es[i:j])
            nc.append({"u": u, "d": d, "net": net, "vol": vol, "doi": b[3]})
    return out, nc, stats


def boot(days_vals, n=10000, seed=7):
    rnd = random.Random(seed); keys = list(days_vals)
    means = []
    for _ in range(n):
        pick = [rnd.choice(keys) for _ in keys]
        vals = [v for k in pick for v in days_vals[k]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def strong(rows, key="imb"):
    by = defaultdict(list)
    for r in rows:
        if r[key] is not None and r["v"] > 0:
            by[(r["u"], r["d"])].append(r)
    out = []
    for g in by.values():
        cut = sorted(abs(r[key]) for r in g)[int(0.8 * len(g))]
        out += [r for r in g if abs(r[key]) >= cut and r[key] != 0]
    return out


def signed(rows, key="imb", h="r300"):
    return [(1 if r[key] > 0 else -1) * r[h] for r in rows if r[h] is not None]


def pearson(a, b):
    if len(a) < 3:
        return float("nan")
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    sab = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    sa = sum((x - ma) ** 2 for x in a) ** 0.5; sb = sum((y - mb) ** 2 for y in b) ** 0.5
    return sab / (sa * sb) if sa and sb else float("nan")


def mean(v):
    return sum(v) / len(v) if v else float("nan")


def main():
    days = sys.argv[1:] or DAYS
    rows, nc, st = [], [], {}
    for d in days:
        for u in ("NIFTY", "SENSEX"):
            o, n, s = day(u, d)
            rows += o; nc += n; st[(u, d)] = s
            print(f"{d} {u}: samples {len(o)}, prints {len(n)}, classified {s.get('classified', 0) / max(1, s.get('dv', 0)):.0%}", flush=True)
    S = strong(rows)
    byday = defaultdict(list)
    for r in S:
        byday[r["d"]].append((1 if r["imb"] > 0 else -1) * r["r300"])
    m = mean([v for vs in byday.values() for v in vs])
    lo, hi = boot(byday)
    h1 = mean([v for k, vs in byday.items() if k <= HALF for v in vs])
    h2 = mean([v for k, vs in byday.items() if k > HALF for v in vs])
    dis = [r for r in S if r["mom"] != 0 and (r["imb"] > 0) != (r["mom"] > 0)]
    md = mean(signed(dis))
    res = {"samples": len(rows), "strong": len(S), "mean5m": m, "ci": [lo, hi], "hit": mean([1 if v > 0 else 0 for vs in byday.values() for v in vs]),
           "half1": h1, "half2": h2, "disagree_n": len(dis), "disagree_mean": md}
    crit = [lo > 0 and m > 0, h1 > 0 and h2 > 0, m >= 3.0, md > 0]
    res["criteria"] = crit; res["verdict"] = "PASS" if all(crit) else "FAIL"
    # reported
    rep = {}
    rep["r60"] = mean(signed(S, h="r60"))
    rep["all_samples_5m"] = mean(signed([r for r in rows if r["imb"]]))
    for u in ("NIFTY", "SENSEX"):
        rep[u] = mean(signed([r for r in S if r["u"] == u]))
    rep["expiry"] = mean(signed([r for r in S if r["exp"]])); rep["non_expiry"] = mean(signed([r for r in S if not r["exp"]]))
    rep["atm1"] = mean(signed(strong(rows, "imb1"), "imb1"))
    rep["since_print"] = mean(signed(strong(rows, "imbp"), "imbp"))
    mrows = [dict(r, v=1) for r in rows]
    rep["momentum_ref_5m"] = mean(signed(strong(mrows, "mom"), "mom"))
    rep["strong_agree_with_last_minute"] = mean([1 if (r["imb"] > 0) == (r["mom"] > 0) else 0 for r in S if r["mom"] != 0])
    rep["agree_mean"] = mean(signed([r for r in S if r["mom"] != 0 and (r["imb"] > 0) == (r["mom"] > 0)]))
    rep["classified_share"] = sum(s.get("classified", 0) for s in st.values()) / max(1, sum(s.get("dv", 0) for s in st.values()))
    # nowcast
    nz = [x for x in nc if x["doi"] != 0 and x["net"] != 0]
    agree = mean([1 if (x["net"] > 0) == (x["doi"] > 0) else 0 for x in nz])
    corr = pearson([x["net"] for x in nz], [x["doi"] for x in nz])
    corr_abs = pearson([x["vol"] for x in nc], [abs(x["doi"]) for x in nc])
    ratio = statistics.median([abs(x["doi"]) / x["vol"] for x in nc if x["vol"] > 0]) if nc else float("nan")
    rep["nowcast"] = {"intervals": len(nc), "sign_agree": agree, "corr_net_doi": corr, "corr_vol_absdoi": corr_abs,
                      "median_absdoi_over_vol": ratio}
    res["reported"] = rep
    print(json.dumps(res, indent=1, default=float))


if __name__ == "__main__":
    main()
