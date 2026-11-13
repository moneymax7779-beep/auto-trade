#!/usr/bin/env python3
"""A-388: does the closing auction's indicative close lead the options' synthetic index? (rules fixed in the ledger first)

Inputs: the production chart series /api/close-auction (CloseAuctionController, close-auction-v2) of the local app, every
15 s: [time, synthetic, indicative, weight covered %], plus close and closeAt. Cached in .local/a388/.
Usage: python3 2026-11-13-auction-lead.py [base_url]   (default http://localhost:8098)
"""
import json, os, random, statistics, sys, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8098"
CACHE = ".local/a388"
DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
        "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21",
        "2026-10-22", "2026-10-23", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-11-02",
        "2026-11-04", "2026-11-06", "2026-11-11"]          # 3, 5, 9, 12 Nov left out: A-194 forward sample
HALF = "2026-10-21"
FROM, UNTIL = "15:20:00", "15:28:00"
MIN_GAP = 0.0005                                         # abs(G) >= 0.05 % of the index
MIN_COVER = 80.0


def fetch(d, u):
    os.makedirs(CACHE, exist_ok=True)
    p = f"{CACHE}/{d}-{u}.json"
    if not os.path.exists(p):
        with urllib.request.urlopen(f"{BASE}/api/close-auction?date={d}&underlying={u}", timeout=120) as r:
            body = r.read()
        with open(p, "wb") as f:
            f.write(body)
    with open(p) as f:
        return json.load(f)


def secs(hms):
    h, m, s = hms.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def events(j):
    close, close_at = j.get("close"), j.get("closeAt")
    if close is None or close_at is None:
        return [], None
    ca = secs(close_at)
    syn = {secs(r[0]): r[1] for r in j["series"] if r[1] is not None}
    out = []
    for r in j["series"]:
        t = secs(r[0])
        if t < secs(FROM) or t > secs(UNTIL) or t >= ca:
            continue
        s, ind, cov = r[1], r[2], r[3]
        if s is None or ind is None or cov is None or cov < MIN_COVER:
            continue
        g = ind - s
        if abs(g) / s < MIN_GAP:
            continue
        sg = 1 if g > 0 else -1
        yc = sg * (close - s) / s * 1e4
        up = close > s
        y60 = None
        if t + 60 in syn and t + 60 < ca:
            y60 = sg * (syn[t + 60] - s) / s * 1e4
        out.append({"t": r[0], "g_bp": g / s * 1e4, "yc": yc, "hit": yc > 0, "always_up_hit": up, "y60": y60})
    return out, (close - syn.get(secs(UNTIL), float("nan"))) / close * 1e4 if secs(UNTIL) in syn else None


def boot(groups, stat, n=2000, seed=7):
    rnd = random.Random(seed)
    keys = list(groups)
    vals = []
    for _ in range(n):
        ev = [e for k in (rnd.choice(keys) for _ in keys) for e in groups[k]]
        if ev:
            vals.append(stat(ev))
    vals.sort()
    return round(vals[int(0.025 * len(vals))], 3), round(vals[int(0.975 * len(vals)) - 1], 3)


def summary(groups):
    ev = [e for g in groups.values() for e in g]
    if not ev:
        return {"n": 0}
    hit = lambda e: sum(x["hit"] for x in e) / len(e)
    mean = lambda e: statistics.mean(x["yc"] for x in e)
    y60 = [x["y60"] for x in ev if x["y60"] is not None]
    return {"events": len(ev), "index_days": len(groups), "hit": round(hit(ev), 3), "hit_ci": boot(groups, hit),
            "mean_bp": round(mean(ev), 2), "mean_ci": boot(groups, mean),
            "always_up_hit": round(sum(x["always_up_hit"] for x in ev) / len(ev), 3),
            "y60_n": len(y60), "y60_hit": round(sum(v > 0 for v in y60) / len(y60), 3) if y60 else None,
            "y60_mean_bp": round(statistics.mean(y60), 2) if y60 else None}


def main():
    groups, bias, per_minute, meta = {}, {}, {}, {}
    for d in DAYS:
        for u in ("NIFTY", "SENSEX", "BANKNIFTY"):
            j = fetch(d, u)
            ev, b = events(j)
            exp = j.get("expiry") == d
            meta[(d, u)] = {"expiry": exp, "closeAt": j.get("closeAt"), "events": len(ev)}
            if b is not None:
                bias[(d, u)] = b
            if ev:
                groups[(d, u)] = ev
                for e in ev:
                    per_minute.setdefault((u != "BANKNIFTY", e["t"][:5]), []).append(e)
    main_g = {k: v for k, v in groups.items() if k[1] != "BANKNIFTY"}
    sel = lambda f: {k: v for k, v in main_g.items() if f(k)}
    res = {
        "all": summary(main_g),
        "half1": summary(sel(lambda k: k[0] <= HALF)),
        "half2": summary(sel(lambda k: k[0] > HALF)),
        "NIFTY": summary(sel(lambda k: k[1] == "NIFTY")),
        "SENSEX": summary(sel(lambda k: k[1] == "SENSEX")),
        "expiry": summary(sel(lambda k: meta[k]["expiry"])),
        "non_expiry": summary(sel(lambda k: not meta[k]["expiry"])),
        "BANKNIFTY": summary({k: v for k, v in groups.items() if k[1] == "BANKNIFTY"}),
    }
    # without the best calendar day (both indices)
    contrib = {}
    for (d, u), ev in main_g.items():
        contrib[d] = contrib.get(d, 0) + sum(e["yc"] for e in ev)
    best = max(contrib, key=contrib.get)
    res["without_best_day"] = {"day": best, **summary(sel(lambda k: k[0] != best))}
    res["by_minute"] = {m: {"n": len(v), "hit": round(sum(e["hit"] for e in v) / len(v), 3),
                            "mean_bp": round(statistics.mean(e["yc"] for e in v), 2)}
                        for (main_idx, m), v in sorted(per_minute.items()) if main_idx}
    bb = [v for k, v in bias.items() if k[1] != "BANKNIFTY" and v == v]
    res["close_minus_synthetic_15_28_bp"] = {"n": len(bb), "median": round(statistics.median(bb), 2),
                                            "share_close_above": round(sum(v > 0 for v in bb) / len(bb), 3)}
    a, h1, h2 = res["all"], res["half1"], res["half2"]
    crit = [a["hit"] >= 0.60 and a["hit_ci"][0] > 0.5,
            a["mean_bp"] >= 3,
            h1["hit"] > 0.5 and h1["mean_bp"] > 0 and h2["hit"] > 0.5 and h2["mean_bp"] > 0,
            h1["hit"] - h1["always_up_hit"] > 0 and h2["hit"] - h2["always_up_hit"] > 0,
            res["without_best_day"]["mean_bp"] > 0]
    res["criteria"] = crit
    res["verdict"] = "PASS" if all(crit) else "FAIL"
    res["per_day"] = {f"{d} {u}": {**meta[(d, u)], "hit": round(sum(e["hit"] for e in groups[(d, u)]) / len(groups[(d, u)]), 2),
                                   "mean_bp": round(statistics.mean(e["yc"] for e in groups[(d, u)]), 1)}
                      for (d, u) in groups}
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
