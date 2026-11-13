#!/usr/bin/env python3
"""A-387 expiry-day OI changes 15:20-15:30 (descriptive; questions fixed in the ledger first).

Own-capture md.option_tick, expiring series only (expiry = session date), 14:20-15:40 IST. Local database only.
A-194 freeze: on FROZEN days no option price at or after 15:20:00 is read (the SQL nulls ltp/bid/ask there), so those days
give OI and volume only; the price questions (Q4) use the unfrozen days.
Usage: python3 2026-11-13-expiry-close-oi.py [cache_dir]
"""
import bisect, csv, io, json, os, statistics, subprocess, sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
DAYS = [("2026-09-29", "NIFTY"), ("2026-10-01", "SENSEX"), ("2026-10-06", "NIFTY"), ("2026-10-08", "SENSEX"),
        ("2026-10-13", "NIFTY"), ("2026-10-15", "SENSEX"), ("2026-10-19", "NIFTY"), ("2026-10-22", "SENSEX"),
        ("2026-10-27", "NIFTY"), ("2026-10-29", "SENSEX"), ("2026-10-27", "BANKNIFTY"),
        ("2026-11-03", "NIFTY"), ("2026-11-05", "SENSEX"), ("2026-11-09", "NIFTY"), ("2026-11-12", "SENSEX")]
FROZEN = {"2026-11-03", "2026-11-05", "2026-11-09", "2026-11-12"}     # A-194 forward sample: no prices >= 15:20
STEP = {"NIFTY": 50, "SENSEX": 100, "BANKNIFTY": 100}
CACHE = sys.argv[1] if len(sys.argv) > 1 else ".local/a387"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def ms(d, hhmmss):
    return int(datetime.fromisoformat(f"{d} {hhmmss}").replace(tzinfo=IST).timestamp() * 1000)


def hms(t):
    return datetime.fromtimestamp(t / 1000, IST).strftime("%H:%M:%S")


def load(d, u):
    os.makedirs(CACHE, exist_ok=True)
    path = f"{CACHE}/{d}-{u}.csv"
    if not os.path.exists(path):
        cut = f"'{d} 15:20+05:30'"
        price = (lambda c: f"case when recv_ts >= {cut} then null else {c} end") if d in FROZEN else (lambda c: c)
        rows = q(f"select (extract(epoch from recv_ts) * 1000)::bigint, strike, option_type, {price('ltp')}, "
                 f"{price('bid_px[1]')}, {price('ask_px[1]')}, volume, oi from md.option_tick "
                 f"where underlying = '{u}' and expiry = '{d}' and recv_ts >= '{d} 14:20+05:30' "
                 f"and recv_ts < '{d} 15:40+05:30' order by strike, option_type, recv_ts, src_seq")
        with open(path, "w", newline="") as f:
            csv.writer(f).writerows(rows)
    by = defaultdict(list)                      # (strike, side) -> [(t, ltp, bid, ask, vol, oi)]
    with open(path) as f:
        for r in csv.reader(f):
            fl = lambda x: float(x) if x not in ("", None) else None
            by[(float(r[1]), r[2])].append((int(r[0]), fl(r[3]), fl(r[4]), fl(r[5]), fl(r[6]), fl(r[7])))
    return by


def last_before(series, t, idx):
    """Last row with time < t (series sorted by time)."""
    i = bisect.bisect_left([s[0] for s in series], t) - 1 if idx is None else bisect.bisect_left(idx, t) - 1
    return series[i] if i >= 0 else None


class Day:
    def __init__(self, d, u):
        self.d, self.u, self.step = d, u, STEP[u]
        self.by = load(d, u)
        self.times = {k: [r[0] for r in v] for k, v in self.by.items()}
        self.strikes = sorted({k for k, _ in self.by})

    def row(self, k, side, t):
        key = (k, side)
        if key not in self.by:
            return None
        return last_before(self.by[key], t, self.times[key])

    def oi(self, k, side, t):
        r = self.row(k, side, t)
        return r[5] if r and r[5] is not None else None

    def vol(self, k, side, t):
        r = self.row(k, side, t)
        return r[4] if r and r[4] is not None else None

    def mid(self, k, side, t):
        r = self.row(k, side, t)
        if not r or r[2] is None or r[3] is None or r[2] <= 0 or r[3] <= 0 or r[3] < r[2]:
            return None
        if t - r[0] > 30_000:                     # stale quote
            return None
        return (r[2] + r[3]) / 2

    def spot(self, t):
        """Synthetic spot: K + C - P at the strike where |C - P| is smallest."""
        best = None
        for k in self.strikes:
            c, p = self.mid(k, "CE", t), self.mid(k, "PE", t)
            if c is None or p is None:
                continue
            if best is None or abs(c - p) < best[0]:
                best = (abs(c - p), k + c - p)
        return best[1] if best else None


def prints(day, t0, t1):
    """OI change events per contract in [t0, t1): (t, strike, side, dOI)."""
    ev = []
    for key, rows in day.by.items():
        prev = None
        for r in rows:
            if r[5] is None:
                continue
            if prev is not None and r[5] != prev and t0 <= r[0] < t1:
                ev.append((r[0], key[0], key[1], r[5] - prev))
            prev = r[5]
    ev.sort()
    return ev


def cluster(ev, gap=20_000):
    """Group change events into exchange prints (a print rolls across strikes in ~10 s)."""
    out = []
    for e in ev:
        if out and e[0] - out[-1]["end"] <= gap:
            g = out[-1]; g["end"] = e[0]; g["n"] += 1; g["gross"] += abs(e[3]); g["net"] += e[3]
        else:
            out.append({"start": e[0], "end": e[0], "n": 1, "gross": abs(e[3]), "net": e[3]})
    return out


def bucket(k, side, s, step):
    """Moneyness in strikes: positive = ITM."""
    m = (s - k) / step if side == "CE" else (k - s) / step
    if abs(m) < 0.5:
        return "ATM"
    if m >= 2.5:
        return "deep ITM (3+)"
    if m > 0:
        return "near ITM (1-2)"
    if m > -2.5:
        return "near OTM (1-2)"
    if m > -10.5:
        return "far OTM (3-10)"
    return "beyond 10 OTM"


AGG_FINAL = defaultdict(lambda: [0.0, 0.0, 0.0])   # (u, side, bucket) -> [oi 15:20, change to stop, change stop->15:40]
BUCKETS = ["deep ITM (3+)", "near ITM (1-2)", "ATM", "near OTM (1-2)", "far OTM (3-10)", "beyond 10 OTM"]


def series_oi(day, t):
    return sum(day.oi(k, s, t) or 0 for k, s in day.by)


def series_vol(day, t):
    return sum(day.vol(k, s, t) or 0 for k, s in day.by)


def main():
    res = {}
    agg_bucket = defaultdict(lambda: defaultdict(lambda: [0.0, 0.0, 0.0]))   # (u, side) -> bucket -> [oi1520, d1530, d1540]
    q4 = []
    for d, u in DAYS:
        day = Day(d, u)
        t = lambda x: ms(d, x)
        s1930 = day.spot(t("15:19:30"))
        r = {"spot_15_19_30": s1930}
        # Q1 the clock
        ev = prints(day, t("15:00:00"), t("15:40:00"))
        cl = cluster(ev)
        r["prints_15_00_15_40"] = [(hms(g["start"]), hms(g["end"]), g["n"], round(g["net"]), round(g["gross"])) for g in cl]
        gaps = [(cl[i]["start"] - cl[i - 1]["start"]) / 1000 for i in range(1, len(cl))]
        r["print_gap_median_s"] = statistics.median(gaps) if gaps else None
        r["last_print_before_15_40"] = hms(cl[-1]["start"]) if cl else None
        # Q2 size of change: per-minute rates, series level
        # the cache starts at 14:20, so the afternoon baseline is 14:22 (58 minutes to 15:20)
        oi = {x: series_oi(day, t(x)) for x in ("14:22:00", "15:20:00", "15:25:00", "15:30:00", "15:39:59")}
        vol = {x: series_vol(day, t(x)) for x in ("14:22:00", "15:20:00", "15:30:00", "15:39:59")}
        ev_a = prints(day, t("14:22:00"), t("15:20:00"))
        ev_b = prints(day, t("15:20:00"), t("15:30:00"))
        r["series_oi"] = {k: round(v) for k, v in oi.items()}
        r["net_per_min_14_22_15_20"] = round((oi["15:20:00"] - oi["14:22:00"]) / 58)
        r["net_per_min_15_20_15_30"] = round((oi["15:30:00"] - oi["15:20:00"]) / 10)
        r["gross_per_min_14_22_15_20"] = round(sum(abs(e[3]) for e in ev_a) / 58)
        r["gross_per_min_15_20_15_30"] = round(sum(abs(e[3]) for e in ev_b) / 10)
        r["vol_per_min_14_22_15_20"] = round((vol["15:20:00"] - vol["14:22:00"]) / 58)
        r["vol_per_min_15_20_15_30"] = round((vol["15:30:00"] - vol["15:20:00"]) / 10)
        r["after_15_30_change"] = round(oi["15:39:59"] - oi["15:30:00"])
        r["after_15_30_volume"] = round(vol["15:39:59"] - vol["15:30:00"])
        # per minute 15:15-15:40: contracts whose OI changed, net and gross OI change, volume traded
        ev_m = prints(day, t("15:15:00"), t("15:40:00"))
        per = []
        for m in range(25):
            a = t("15:15:00") + m * 60_000
            e = [x for x in ev_m if a <= x[0] < a + 60_000]
            per.append((hms(a)[:5], len({(x[1], x[2]) for x in e}), round(sum(x[3] for x in e)),
                        round(sum(abs(x[3]) for x in e)), round(series_vol(day, a + 60_000) - series_vol(day, a))))
        r["per_minute"] = per
        # split at the cash closing-auction stop (index close = expiry settlement known from then on)
        stop = q(f"select (extract(epoch from min(event_ts)) * 1000)::bigint from md.session_phase_event where session_date = '{d}' "
                 f"and underlying = '{u}' and session_phase = 'UPSTOX_CAS_STOP'")
        stop = int(stop[0][0]) if stop and stop[0][0] else t("15:29:00")
        r["cas_stop"] = hms(stop)
        o_stop, v_stop = series_oi(day, stop), series_vol(day, stop)
        r["oi_change_before_stop"] = round(o_stop - oi["15:20:00"])
        r["oi_change_after_stop"] = round(oi["15:39:59"] - o_stop)
        r["vol_before_stop"] = round(v_stop - vol["15:20:00"])
        r["vol_after_stop"] = round(vol["15:39:59"] - v_stop)
        # by moneyness against the final close (unfrozen days only: the close is a post-15:20 price)
        if d not in FROZEN:
            cl_ = q(f"select price from md.index_tick where session_date = '{d}' and underlying = '{u}' "
                    f"and recv_ts < '{d} 15:40+05:30' and price > 0 order by recv_ts desc, src_seq desc limit 1")
            fc = float(cl_[0][0])
            fm = defaultdict(lambda: [0.0, 0.0, 0.0])
            for (k, sd) in day.by:
                a, b, c = day.oi(k, sd, t("15:20:00")), day.oi(k, sd, stop), day.oi(k, sd, t("15:39:59"))
                if a is None or b is None or c is None:
                    continue
                itm = (fc - k) if sd == "CE" else (k - fc)
                name = "ITM" if itm > 0 else ("OTM <= 1 step" if itm > -day.step else "OTM > 1 step")
                g = fm[(sd, name)]; g[0] += a; g[1] += b - a; g[2] += c - b
                g2 = AGG_FINAL[(u, sd, name)]; g2[0] += a; g2[1] += b - a; g2[2] += c - b
            r["close"] = fc
            r["by_final_moneyness"] = {f"{sd} {n}": [round(v[0]), round(100 * v[1] / v[0], 1), round(100 * v[2] / v[0], 1)]
                                       for (sd, n), v in sorted(fm.items()) if v[0]}
        # Q3 where, by moneyness at 15:19:30
        bk = defaultdict(lambda: [0.0, 0.0, 0.0])
        if s1930:
            for (k, s) in day.by:
                a, b, c = day.oi(k, s, t("15:20:00")), day.oi(k, s, t("15:30:00")), day.oi(k, s, t("15:39:59"))
                if a is None or b is None:
                    continue
                name = bucket(k, s, s1930, day.step)
                bk[(s, name)][0] += a; bk[(s, name)][1] += b - a; bk[(s, name)][2] += (c if c is not None else b) - a
                if u != "BANKNIFTY":
                    g = agg_bucket[(u, s)][name]; g[0] += a; g[1] += b - a; g[2] += (c if c is not None else b) - a
        r["by_moneyness"] = {f"{s} {n}": [round(v[0]), round(v[1]), round(100 * v[1] / v[0], 1) if v[0] else None]
                             for (s, n), v in sorted(bk.items(), key=lambda x: (x[0][0], BUCKETS.index(x[0][1])))}
        # Q4 price link, unfrozen days only
        if d not in FROZEN and u != "BANKNIFTY" and s1930:
            sp = {x: day.spot(t(x)) for x in ("15:20:00", "15:25:00", "15:29:50")}
            atm = round(s1930 / day.step) * day.step
            band = [atm + i * day.step for i in range(-3, 4)]

            def flow(a, b):
                f = 0.0
                for k in band:
                    for s, sign in (("PE", 1), ("CE", -1)):
                        x, y = day.oi(k, s, t(a)), day.oi(k, s, t(b))
                        if x is not None and y is not None:
                            f += sign * (y - x)
                return f
            f1, f2 = flow("15:20:00", "15:25:00"), flow("15:25:00", "15:29:59")
            close = q(f"select price from md.index_tick where session_date = '{d}' and underlying = '{u}' "
                      f"and recv_ts < '{d} 15:40+05:30' and price > 0 order by recv_ts desc, src_seq desc limit 1")
            close = float(close[0][0]) if close else None
            tot = {}
            for k in day.strikes:
                if abs(k - atm) <= 10 * day.step:
                    tot[k] = (day.oi(k, "CE", t("15:20:00")) or 0) + (day.oi(k, "PE", t("15:20:00")) or 0)
            maxk = max(tot, key=tot.get) if tot else None
            q4.append({"day": d, "u": u, "spot": {k: round(v, 1) if v else None for k, v in sp.items()},
                       "flow_20_25": round(f1), "flow_25_30": round(f2),
                       "move_20_25": round(sp["15:25:00"] - sp["15:20:00"], 1),
                       "move_25_30": round(sp["15:29:50"] - sp["15:25:00"], 1),
                       "close": close, "atm_15_20": atm, "max_oi_strike": maxk,
                       "close_dist_atm": round(abs(close - atm), 1) if close else None,
                       "close_dist_maxoi": round(abs(close - maxk), 1) if close and maxk else None})
        res[f"{d} {u}"] = r
        print(d, u, "spot 15:19:30", round(s1930, 1) if s1930 else None, "prints", len(cl), flush=True)
    sgn = lambda x: (x > 0) - (x < 0)
    summary = {
        "q4_days": len(q4),
        "q4_flow2025_same_sign_as_next_move": sum(sgn(x["flow_20_25"]) == sgn(x["move_25_30"]) for x in q4),
        "q4_flow2025_same_sign_as_own_move": sum(sgn(x["flow_20_25"]) == sgn(x["move_20_25"]) for x in q4),
        "q4_flow2530_same_sign_as_own_move": sum(sgn(x["flow_25_30"]) == sgn(x["move_25_30"]) for x in q4),
        "q4_close_nearer_maxoi_than_atm": sum(x["close_dist_maxoi"] < x["close_dist_atm"] for x in q4 if x["close"]),
        "q4_close_nearer_atm_than_maxoi": sum(x["close_dist_maxoi"] > x["close_dist_atm"] for x in q4 if x["close"]),
        "pooled_by_final_moneyness": {" ".join(k): [round(v[0]), round(100 * v[1] / v[0], 1), round(100 * v[2] / v[0], 1)]
                                      for k, v in sorted(AGG_FINAL.items()) if v[0]},
        "pooled_by_moneyness": {f"{u} {s} {n}": [round(v[0]), round(v[1]), round(100 * v[1] / v[0], 1) if v[0] else None,
                                                round(100 * v[2] / v[0], 1) if v[0] else None]
                                for (u, s), m in sorted(agg_bucket.items()) for n, v in
                                sorted(m.items(), key=lambda x: BUCKETS.index(x[0]))},
    }
    print(json.dumps({"days": res, "q4": q4, "summary": summary}, indent=1, default=str))


if __name__ == "__main__":
    main()
