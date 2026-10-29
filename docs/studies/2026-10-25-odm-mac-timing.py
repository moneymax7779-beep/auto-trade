"""A-230: timing and context anatomy of opening-drive-momentum (odm) and ma-cross (mac). Registered before computing.

Fixed before any number was seen (ledger A-230):

odm trades: odm-v8 alone, image a172-99619b08 (Oct 2024-Dec 2025; = odm-v4 without the breadth filter, which bars before
Jul 2026 cannot feed); odm-v1 alone, image 9570ad4-a85 (2026 bar days; same entries as v8, no peak trail).
mac trades: mac-v1 inside the live set, image 86f806567430-local (A-199).
Periods: P1 2024-10-01..2025-06-30, P2 2025-07-01..2025-12-31, P3 2026-01-01..2026-10-06 (the index bars end 6 Oct).

odm factors: F1 entry check time; F2 gap at the open in the trade's direction (%); F3 opened beyond PDH (calls) / PDL
(puts); F4 drive size at entry (% from the open); F5 stop distance (entry to the session's opposite extreme, % of price);
F6 the day open reclaimed by a 1-minute close within 20 minutes of entry; F7 futures volume 09:15->entry vs the same
window over the previous 10 sessions; F8 weekday. Exit timing: the index move in R (R = the stop distance; -1 once the
stop is touched) at 10:00, 10:30, 11:00, 12:00 (odm's flat), 13:00, 14:00, 15:15, and the minute of the best close.
mac factors (a new kind of input only): M1 the other index's EMA9 vs EMA20 (3-minute closes from 09:15) at entry;
M2 the other index crossed the same way within the 15 minutes before entry; M3 futures volume in the 15 minutes before
entry vs the same window over the previous 10 sessions.

Rule: a bucket is a CANDIDATE only if its mean net % of premium is above (below) its period's mean in all three periods
with n >= 15 in each. An exit time is a candidate only if its mean R beats 12:00 in all three periods. Candidates earn a
registered engine replay on days none of this used; nothing changes live from this script.
"""
import collections
import datetime as dt
import statistics
import subprocess
import sys

PERIODS = [("P1", "2024-10-01", "2025-06-30"), ("P2", "2025-07-01", "2025-12-31"), ("P3", "2026-01-01", "2026-10-06")]
OUT = sys.argv[1] if len(sys.argv) > 1 else ".local/a230/report.md"


def psql(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-X", "-q",
                          "-At", "-F", "\t", "-c", sql], capture_output=True, text=True, check=True).stdout
    return [line.split("\t") for line in out.split("\n") if line]


def period(d):
    for name, a, b in PERIODS:
        if a <= d <= b:
            return name
    return None


def trades(code_version, config_key, strategy, alone):
    who = f"and s.strategy_id = '{strategy}'" if alone else ""
    rows = psql(f"""
        with ss as (select max(s.id) id, s.session_date from trade.session s
                    where s.code_version = '{code_version}' and s.configs ? '{config_key}' and s.mode = 'PAPER_REPLAY' {who}
                    and s.status = 'DONE' group by s.session_date)
        select ss.session_date, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata', 'HH24:MI'),
               to_char(p.closed_at at time zone 'Asia/Kolkata', 'HH24:MI'), coalesce(p.exit_reason, ''), p.net,
               p.average_cost * p.quantity
        from ss join trade.position p on p.session_id = ss.id
        where p.strategy_id = '{strategy}' and p.quantity > 0 and ss.session_date <= '2026-10-06'
        order by 1, 4""")
    out = []
    for d, u, side, o, c, reason, net, prem in rows:
        if period(d) and float(prem) > 0:
            out.append(dict(d=d, u=u, sign=1 if side == "CE" else -1, entry=o, exit=c, reason=reason,
                            net=float(net), pct=float(net) / float(prem) * 100))
    return out


# ------------------------------------------------------------------ bars
def load_bars():
    idx = collections.defaultdict(dict)       # (u, d) -> {minute: (o, h, l, c)}
    for u, d, m, o, h, l, c in psql("""
            select underlying, to_char(bar_start at time zone 'Asia/Kolkata', 'YYYY-MM-DD'),
                   to_char(bar_start at time zone 'Asia/Kolkata', 'HH24:MI'), open, high, low, close
            from hist.candle where kind = 'INDEX' and underlying in ('NIFTY', 'SENSEX')
              and bar_start >= '2024-09-01' and (bar_start at time zone 'Asia/Kolkata')::time between '09:15' and '15:29'"""):
        idx[(u, d)][m] = (float(o), float(h), float(l), float(c))
    fut = collections.defaultdict(dict)       # (u, d) -> {minute: volume}
    for u, d, m, v in psql("""
            select underlying, to_char(bar_start at time zone 'Asia/Kolkata', 'YYYY-MM-DD'),
                   to_char(bar_start at time zone 'Asia/Kolkata', 'HH24:MI'), sum(volume)
            from hist.candle where kind = 'FUTURE' and underlying in ('NIFTY', 'SENSEX')
              and bar_start >= '2024-09-01' and (bar_start at time zone 'Asia/Kolkata')::time between '09:15' and '15:29'
            group by 1, 2, 3"""):
        fut[(u, d)][m] = float(v or 0)
    return idx, fut


def mins(a, b):
    """Minutes 'HH:MM' from a (inclusive) to b (exclusive)."""
    t = dt.datetime.strptime(a, "%H:%M")
    end = dt.datetime.strptime(b, "%H:%M")
    while t < end:
        yield t.strftime("%H:%M")
        t += dt.timedelta(minutes=1)


def shift(m, k):
    return (dt.datetime.strptime(m, "%H:%M") + dt.timedelta(minutes=k)).strftime("%H:%M")


def vol_ratio(fut, days_by_u, u, d, a, b):
    """Futures volume in [a, b) on d vs the mean of the same window over the previous 10 sessions with data."""
    def window(day):
        bars = fut.get((u, day))
        return sum(bars.get(m, 0) for m in mins(a, b)) if bars else None
    today = window(d)
    days = days_by_u[u]
    i = days.index(d) if d in days else -1
    prev = [w for w in (window(x) for x in days[max(0, i - 10):i]) if w]
    if not today or len(prev) < 5:
        return None
    return today / statistics.mean(prev)


def ema_series(closes, n):
    out, e = [], None
    k = 2 / (n + 1)
    for c in closes:
        e = c if e is None else e + k * (c - e)
        out.append(e)
    return out


def three_min(bars):
    """3-minute closes from 09:15 as [(end minute, close)]; a bar ends when its last minute is in."""
    out = []
    t = "09:15"
    while t <= "15:27":
        last = shift(t, 2)
        if last in bars:
            out.append((shift(t, 3), bars[last][3]))
        t = shift(t, 3)
    return out


# ------------------------------------------------------------------ report helpers
lines = []


def p(s=""):
    lines.append(s)


def table(title, rows, key, value="pct"):
    """rows: dicts with 'period' and the bucket under key; prints n / mean / win per period and the verdict."""
    p(f"\n### {title}\n")
    p("| bucket | " + " | ".join(f"{n} n | {n} mean % | {n} win" for n, _, _ in PERIODS) + " | verdict |")
    p("|---|" + "---|---|---|" * len(PERIODS) + "---|")
    base = {n: statistics.mean([r[value] for r in rows if r["period"] == n] or [0]) for n, _, _ in PERIODS}
    buckets = sorted({r[key] for r in rows if r[key] is not None}, key=str)
    found = []
    for b in buckets:
        cells, signs = [], []
        for n, _, _ in PERIODS:
            v = [r[value] for r in rows if r["period"] == n and r[key] == b]
            if v:
                m = statistics.mean(v)
                cells.append(f"{len(v)} | {m:+.1f} | {sum(x > 0 for x in v) / len(v):.0%}")
                signs.append(None if len(v) < 15 else (1 if m > base[n] else -1))
            else:
                cells.append("0 | | ")
                signs.append(None)
        verdict = ("CANDIDATE better" if all(s == 1 for s in signs) else "CANDIDATE worse" if all(s == -1 for s in signs)
                   else "mixed" if None not in signs else "n < 15")
        if verdict.startswith("CANDIDATE"):
            found.append((title, b, verdict))
        p(f"| {b} | " + " | ".join(cells) + f" | {verdict} |")
    p("| period mean | " + " | ".join(f" | {base[n]:+.1f} | " for n, _, _ in PERIODS) + " | |")
    return found


def bucket(x, edges, labels):
    if x is None:
        return None
    for e, lab in zip(edges, labels):
        if x < e:
            return lab
    return labels[-1]


# ------------------------------------------------------------------ odm
def odm_rows(idx, fut, days_by_u):
    raw = trades("a172-99619b08", "opening-drive-momentum.v8.yaml", "opening-drive-momentum", True)
    raw += [t for t in trades("9570ad4-a85", "opening-drive-momentum.v1.yaml", "opening-drive-momentum", True)
            if t["d"] >= "2026-01-01"]
    rows, skipped = [], 0
    for t in raw:
        u, d, s = t["u"], t["d"], t["sign"]
        bars = idx.get((u, d))
        days = days_by_u[u]
        if not bars or "09:15" not in bars or d not in days or days.index(d) == 0:
            skipped += 1
            continue
        prev = idx[(u, days[days.index(d) - 1])]
        pclose = prev[max(prev)][3]
        pdh, pdl = max(b[1] for b in prev.values()), min(b[2] for b in prev.values())
        m = t["entry"]
        before = [bars[x] for x in mins("09:15", m) if x in bars]
        if not before or shift(m, -1) not in bars:
            skipped += 1
            continue
        spot = bars[shift(m, -1)][3]
        o = bars["09:15"][0]
        hi, lo = max(b[1] for b in before), min(b[2] for b in before)
        stop = lo if s > 0 else hi
        r = abs(spot - stop)
        if r <= 0:
            skipped += 1
            continue
        # the path from entry: R at each exit time, stop-aware; the best close and its minute
        stopped_at, best, best_t, reclaim = None, -9.0, None, None
        at = {}
        for x in mins(m, "15:15"):
            b = bars.get(x)
            if b is None:
                continue
            if stopped_at is None and (b[2] <= stop if s > 0 else b[1] >= stop):
                stopped_at = x
            move = -1.0 if stopped_at else s * (b[3] - spot) / r
            if stopped_at is None and move > best:
                best, best_t = move, x
            if reclaim is None and x < shift(m, 20) and stopped_at is None and s * (b[3] - o) <= 0:
                reclaim = (x, move)
            for T in ("10:00", "10:30", "11:00", "12:00", "13:00", "14:00", "15:15"):
                if shift(x, 1) == T or (T == "15:15" and x == "15:14"):
                    at[T] = move
        last = -1.0 if stopped_at else s * (bars[max(k for k in bars if k < "15:15")][3] - spot) / r
        for T in ("10:00", "10:30", "11:00", "12:00", "13:00", "14:00", "15:15"):
            at.setdefault(T, -1.0 if stopped_at and stopped_at < T else last)
        rows.append(dict(
            period=period(d), d=d, u=u, pct=t["pct"], net=t["net"], reason=t["reason"],
            F1=m,
            F2=bucket(s * (o - pclose) / pclose * 100, [-0.3, 0.3], ["against < -0.3", "small", "with > +0.3"]),
            F3="opened beyond PDH/PDL" if (o > pdh if s > 0 else o < pdl) else "opened inside",
            F4=bucket(abs(spot - o) / o * 100, [0.3, 0.5], ["0.2-0.3", "0.3-0.5", "> 0.5"]),
            F5=bucket(r / spot * 100, [0.3, 0.5], ["< 0.3", "0.3-0.5", "> 0.5"]),
            F6="open reclaimed within 20 min" if reclaim else "not reclaimed",
            F6r=reclaim[1] if reclaim else None,
            F7=bucket(vol_ratio(fut, days_by_u, u, d, "09:15", m), [0.8, 1.5], ["< 0.8", "0.8-1.5", "> 1.5"]),
            F8=dt.date.fromisoformat(d).strftime("%a"),
            at=at, best=best, best_t=best_t))
    return rows, skipped


# ------------------------------------------------------------------ mac
def mac_rows(idx, fut, days_by_u):
    raw = trades("86f806567430-local", "ma-cross.v1.yaml", "ma-cross", False)
    rows, skipped = [], 0
    cache = {}
    for t in raw:
        u, d, s, m = t["u"], t["d"], t["sign"], t["entry"]
        other = "SENSEX" if u == "NIFTY" else "NIFTY"
        if (other, d) not in cache:
            ob = idx.get((other, d))
            if not ob:
                cache[(other, d)] = None
            else:
                tm = three_min(ob)
                closes = [c for _, c in tm]
                diff = [a - b for a, b in zip(ema_series(closes, 9), ema_series(closes, 20))]
                cache[(other, d)] = [(end, df, i) for (end, _), df, i in zip(tm, diff, range(len(tm)))]
        series = cache[(other, d)]
        if not series:
            skipped += 1
            continue
        done = [x for x in series if x[0] <= m]
        if len(done) < 20:
            m1 = "other not ready"
        else:
            m1 = "other agrees" if s * done[-1][1] > 0 else "other against"
        crossed = False
        for (e0, d0, _), (e1, d1, _) in zip(done, done[1:]):
            if shift(m, -15) <= e1 <= m and len(done) >= 20 and s * d0 < 0 < s * d1:
                crossed = True
        rows.append(dict(period=period(d), d=d, u=u, pct=t["pct"], net=t["net"],
                         M1=m1, M2="other crossed same way <= 15 min" if crossed else "no",
                         M3=bucket(vol_ratio(fut, days_by_u, u, d, shift(m, -15), m), [0.8, 1.5],
                                   ["< 0.8", "0.8-1.5", "> 1.5"])))
    return rows, skipped


def main():
    idx, fut = load_bars()
    days_by_u = {u: sorted(d for (uu, d) in idx if uu == u) for u in ("NIFTY", "SENSEX")}
    candidates = []
    p("# A-230: odm and mac timing / context anatomy (registered before computing)\n")
    p("Mean % = net as % of the premium paid, per trade (the engine's fills and costs). R = index move / stop distance.\n")

    odm, sk = odm_rows(idx, fut, days_by_u)
    p(f"## opening-drive-momentum: {len(odm)} trades ({sk} skipped: no bars)\n")
    for n, _, _ in PERIODS:
        v = [r for r in odm if r["period"] == n]
        if v:
            p(f"- {n}: {len(v)} trades, mean {statistics.mean(r['pct'] for r in v):+.1f} % of premium, "
              f"win {sum(r['pct'] > 0 for r in v) / len(v):.0%}, net ₹{sum(r['net'] for r in v):,.0f}")
    for key, title in [("F1", "F1 entry check time"), ("F2", "F2 gap at the open in the trade's direction (%)"),
                       ("F3", "F3 opened beyond the prior day's range"), ("F4", "F4 drive size at entry (% from open)"),
                       ("F5", "F5 stop distance (% of price)"), ("F6", "F6 day open reclaimed within 20 minutes"),
                       ("F7", "F7 futures volume 09:15 to entry vs 10-session normal"), ("F8", "F8 weekday")]:
        candidates += table(title, odm, key)

    p("\n### Exit timing: mean R of holding to each time (stop-aware, index points / stop distance)\n")
    p("| exit at | " + " | ".join(n for n, _, _ in PERIODS) + " | beats 12:00 in all three |")
    p("|---|" + "---|" * len(PERIODS) + "---|")
    base = {n: statistics.mean(r["at"]["12:00"] for r in odm if r["period"] == n) for n, _, _ in PERIODS}
    for T in ("10:00", "10:30", "11:00", "12:00", "13:00", "14:00", "15:15"):
        vals = {n: statistics.mean(r["at"][T] for r in odm if r["period"] == n) for n, _, _ in PERIODS}
        beat = T != "12:00" and all(vals[n] > base[n] for n, _, _ in PERIODS)
        if beat:
            candidates.append(("odm exit time", T, "CANDIDATE better"))
        p(f"| {T} | " + " | ".join(f"{vals[n]:+.2f}R" for n, _, _ in PERIODS) + f" | {'CANDIDATE' if beat else ''} |")
    p("\nMinute of the best close (before any stop), share of trades:\n")
    p("| best close in | " + " | ".join(n for n, _, _ in PERIODS) + " |")
    p("|---|" + "---|" * len(PERIODS))
    for a, b in [("09:21", "10:00"), ("10:00", "11:00"), ("11:00", "12:00"), ("12:00", "13:30"), ("13:30", "15:15")]:
        cells = []
        for n, _, _ in PERIODS:
            v = [r for r in odm if r["period"] == n and r["best_t"]]
            cells.append(f"{sum(a <= r['best_t'] < b for r in v) / len(v):.0%}" if v else "")
        p(f"| {a}-{b} | " + " | ".join(cells) + " |")
    p("\nF6 counterfactual: on trades where the open was reclaimed within 20 minutes, exit at that minute vs hold to 12:00\n")
    p("| period | n | R at the reclaim | R at 12:00 (as odm) | actual % of premium |")
    p("|---|---|---|---|---|")
    for n, _, _ in PERIODS:
        v = [r for r in odm if r["period"] == n and r["F6r"] is not None]
        if v:
            p(f"| {n} | {len(v)} | {statistics.mean(r['F6r'] for r in v):+.2f} | "
              f"{statistics.mean(r['at']['12:00'] for r in v):+.2f} | {statistics.mean(r['pct'] for r in v):+.1f} |")

    mac, sk = mac_rows(idx, fut, days_by_u)
    p(f"\n## ma-cross: {len(mac)} trades ({sk} skipped: no bars for the other index)\n")
    for n, _, _ in PERIODS:
        v = [r for r in mac if r["period"] == n]
        if v:
            p(f"- {n}: {len(v)} trades, mean {statistics.mean(r['pct'] for r in v):+.1f} % of premium, "
              f"win {sum(r['pct'] > 0 for r in v) / len(v):.0%}, net ₹{sum(r['net'] for r in v):,.0f}")
    for key, title in [("M1", "M1 the other index's EMA9 vs EMA20 at entry"),
                       ("M2", "M2 the other index crossed the same way within 15 minutes"),
                       ("M3", "M3 futures volume, 15 minutes before entry vs 10-session normal")]:
        candidates += table(title, mac, key)

    p("\n## Candidates (all three periods agree, n >= 15 each)\n")
    for c in candidates or [("none", "", "")]:
        p(f"- {c[0]}: **{c[1]}** {c[2]}")
    open(OUT, "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
