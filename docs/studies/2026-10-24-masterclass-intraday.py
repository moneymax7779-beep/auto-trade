#!/usr/bin/env python3
"""A-205..A-207: intraday rules from the Udemy "Options Trading MasterClass" Section 6 on own-capture tick days
(rules fixed in the ledger before this ran).

A-205 U1  5-min EMA5/EMA10 + Bollinger(20,2) expansion from narrow + RSI(14) 50 + candle colour -> ATM option;
          exit when EMA5 crosses back, or 15:15.
A-206 U2  A-205 only when the completed hourly and 15-min candles agree (SMA10 vs SMA20 and RSI 50).
A-207     descriptive: U4 RSI-divergence exit, U6 opening-candle-outside-band wait, U2 split of ma-cross's tick-day trades.
Index candles: hist.candle bars for 25 Aug-21 Sep (warm-up and ma-cross classification; held-out 22-25 Sep skipped), own
md.index_tick minutes from 28 Sep. Options: ask in, bid out (md.option_tick), 0.15 % charges a side.
Usage: python3 2026-10-24-masterclass-intraday.py
"""
import csv, io, math, random, statistics, subprocess
from collections import defaultdict

IST = "at time zone 'Asia/Kolkata'"
TICK_DAYS = ["2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
             "2026-10-09", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-21",
             "2026-10-22", "2026-10-23"]
HELD_OUT = {"2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"}
STEP = {"NIFTY": 50, "SENSEX": 100}
CHG = 0.0015
FIRST_HALF_END = "2026-10-09"


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def hm(t):
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def fmt(m):
    return f"{m // 60:02d}:{m % 60:02d}"


def minutes(u):
    """{date: {minute: (o, h, l, c)}}: bars before 28 Sep, own ticks from 28 Sep."""
    out = defaultdict(dict)
    for d, t, o, h, l, c in q(f"select (bar_start {IST})::date::text, to_char(bar_start {IST},'HH24:MI'), open, high, low, close "
                              f"from hist.candle where underlying = '{u}' and kind = 'INDEX' and bar_start >= '2026-08-25' "
                              f"and bar_start < '2026-09-22'"):
        m = hm(t)
        if 555 <= m < 930:
            out[d][m] = tuple(map(float, (o, h, l, c)))
    for d in TICK_DAYS:
        for t, o, h, l, c in q(f"select to_char(time_bucket('1 minute', recv_ts) {IST},'HH24:MI'), first(price, recv_ts), max(price), "
                               f"min(price), last(price, recv_ts) from md.index_tick where session_date = '{d}' and underlying = '{u}' "
                               f"and price > 0 and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 15:30+05:30' group by 1"):
            out[d][hm(t)] = tuple(map(float, (o, h, l, c)))
    return {d: v for d, v in sorted(out.items()) if d not in HELD_OUT}


def candles(mins, size):
    """[(date, start, end_minute, o, h, l, c)] with end = start + size (the decision minute)."""
    out = []
    for d, mm in mins.items():
        for s in range(555, 930, size):
            bars = [mm[m] for m in range(s, min(s + size, 930)) if m in mm]
            if not bars:
                continue
            out.append((d, s, min(s + size, 930), bars[0][0], max(b[1] for b in bars), min(b[2] for b in bars), bars[-1][3]))
    return out


def ema(xs, n):
    a, out, v = 2 / (n + 1), [], None
    for x in xs:
        v = x if v is None else v + a * (x - v)
        out.append(v)
    return out


def sma(xs, n):
    return [statistics.mean(xs[i - n + 1:i + 1]) if i >= n - 1 else None for i in range(len(xs))]


def rsi(xs, n=14):
    out, ag, al = [None], None, None
    for i in range(1, len(xs)):
        ch = xs[i] - xs[i - 1]
        g, l = max(ch, 0), max(-ch, 0)
        if ag is None:
            ag, al = g, l
        else:
            ag, al = (ag * (n - 1) + g) / n, (al * (n - 1) + l) / n
        out.append(100.0 if al == 0 else 100 - 100 / (1 + ag / al))
    return out


def bands(xs, n=20):
    up, lo, wd = [], [], []
    for i in range(len(xs)):
        if i < n - 1:
            up.append(None); lo.append(None); wd.append(None); continue
        w = xs[i - n + 1:i + 1]
        mu, sd = statistics.mean(w), statistics.pstdev(w)
        up.append(mu + 2 * sd); lo.append(mu - 2 * sd); wd.append(4 * sd)
    return up, lo, wd


def htf_state(cs):
    """{(date, end): side} for completed candles: +1 SMA10 > SMA20 and RSI > 50, -1 opposite, 0 otherwise."""
    cl = [c[6] for c in cs]
    s10, s20, r = sma(cl, 10), sma(cl, 20), rsi(cl)
    out = {}
    for i, c in enumerate(cs):
        st = 0
        if s10[i] is not None and s20[i] is not None and r[i] is not None:
            st = 1 if s10[i] > s20[i] and r[i] > 50 else -1 if s10[i] < s20[i] and r[i] < 50 else 0
        out[(c[0], c[2])] = st
    return out


def htf_at(state, d, m):
    best = None
    for (dd, end), st in state.items():
        if dd == d and end <= m and (best is None or end > best[0]):
            best = (end, st)
    return best[1] if best else 0


def option_ticks(u, d, k, ty, start):
    exp = q(f"select min(expiry)::text from md.option_tick where session_date = '{d}' and underlying = '{u}' and expiry >= '{d}' "
            f"and recv_ts >= '{d} 09:15+05:30' and recv_ts < '{d} 09:20+05:30'")[0][0]
    rows = q(f"select extract(epoch from recv_ts - '{d} 00:00+05:30'::timestamptz)::float8 / 60, bid_px[1], ask_px[1] from md.option_tick "
             f"where session_date = '{d}' and underlying = '{u}' and expiry = '{exp}' and strike = {k} and option_type = '{ty}' "
             f"and recv_ts >= '{d} {fmt(start)}+05:30' and recv_ts < '{d} 15:30+05:30' order by recv_ts")
    return [(float(t), float(b or 0), float(a or 0)) for t, b, a in rows]


def fill(ticks, minute, side):
    for t, b, a in ticks:
        if t >= minute and (a if side == "ask" else b) > 0:
            return a if side == "ask" else b
    return None


def simulate(u, mins):
    c5 = candles(mins, 5)
    st15, st60 = htf_state(candles(mins, 15)), htf_state(candles(mins, 60))
    cl = [c[6] for c in c5]
    e5, e10, r = ema(cl, 5), ema(cl, 10), rsi(cl)
    up, lo, wd = bands(cl)
    # swing extremes for U4 (confirmed two candles later)
    trades = []
    pos = None
    blocked = {}                                    # U6: date -> still waiting for a candle opening inside the band
    for i, c in enumerate(c5):
        d, s, end, o, h, l, cc = c
        if d not in TICK_DAYS or wd[i - 1] is None or i < 22:
            continue
        if s == 555:
            blocked[d] = up[i] is not None and (cc > up[i] or cc < lo[i])
        elif blocked.get(d) and up[i - 1] is not None and lo[i - 1] <= o <= up[i - 1]:
            blocked[d] = False
        if pos:
            p = pos
            crossed = (e5[i] > e10[i]) if p["side"] < 0 else (e5[i] < e10[i])
            # U4: divergence against the trade, then the first opposite-colour candle
            if p["u4"] is None:
                if p["div"] and ((cc > o) if p["side"] < 0 else (cc < o)) and i > p["div"]:
                    p["u4"] = end
                else:
                    piv = [j for j in range(p["i0"] - 10, i - 2) if j >= 2 and
                           ((c5[j][5] == min(x[5] for x in c5[j - 2:j + 3])) if p["side"] < 0 else (c5[j][4] == max(x[4] for x in c5[j - 2:j + 3])))]
                    if piv and not p["div"]:
                        j = piv[-1]
                        if p["side"] < 0 and l < c5[j][5] and r[i] > r[j]:
                            p["div"] = i
                        if p["side"] > 0 and h > c5[j][4] and r[i] < r[j]:
                            p["div"] = i
            if crossed or end >= 915 or d != p["d"]:
                p["exit"] = min(end, 915)
                trades.append(p)
                pos = None
            continue
        if not (570 <= end <= 870):
            continue
        widening = wd[i] > wd[i - 1] and wd[i - 1] <= statistics.median(wd[i - 20:i])
        side = 0
        if widening and e5[i] < e10[i] and r[i] < 50 and cc < o:
            side = -1
        elif widening and e5[i] > e10[i] and r[i] > 50 and cc > o:
            side = 1
        if not side:
            continue
        agree = htf_at(st60, d, end) == side and htf_at(st15, d, end) == side
        pos = {"u": u, "d": d, "entry": end, "side": side, "agree": agree, "i0": i, "div": None, "u4": None,
               "u6_block": bool(blocked.get(d)) and end <= 615, "idx": cc}
    if pos:
        pos["exit"] = 915
        trades.append(pos)
    out = []
    for p in trades:
        k = round(p["idx"] / STEP[u]) * STEP[u]
        ty = "CE" if p["side"] > 0 else "PE"
        tk = option_ticks(u, p["d"], k, ty, p["entry"])
        a = fill(tk, p["entry"], "ask")
        b = fill(tk, p["exit"], "bid")
        b4 = fill(tk, p["u4"], "bid") if p["u4"] and p["u4"] < p["exit"] else b
        if not a or not b:
            continue
        p.update(k=k, ty=ty, ask=a, bid=b, net=b * (1 - CHG) / (a * (1 + CHG)) - 1, net_u4=b4 * (1 - CHG) / (a * (1 + CHG)) - 1)
        out.append(p)
    return out, st15, st60


def boot(tr, key="net", n=10000, seed=205):
    by = defaultdict(list)
    for t in tr:
        by[t["d"]].append(t[key])
    keys = list(by)
    if len(keys) < 2:
        return None
    rng, means = random.Random(seed), []
    for _ in range(n):
        vals = [v for kk in (rng.choice(keys) for _ in keys) for v in by[kk]]
        means.append(sum(vals) / len(vals))
    means.sort()
    return means[int(0.025 * n)], means[int(0.975 * n)]


def line(tr, key="net"):
    if not tr:
        return "n=0"
    xs = [t[key] for t in tr]
    return f"n={len(xs):3d} days={len({t['d'] for t in tr}):2d} mean {statistics.mean(xs):+6.2%} median {statistics.median(xs):+6.2%} win {sum(x > 0 for x in xs) / len(xs):.0%}"


def block(name, tr, key="net"):
    print(f"\n== {name}")
    print(f"  28 Sep-9 Oct  {line([t for t in tr if t['d'] <= FIRST_HALF_END], key)}")
    print(f"  12-23 Oct     {line([t for t in tr if t['d'] > FIRST_HALF_END], key)}")
    ci = boot(tr, key)
    print(f"  all           {line(tr, key)}" + (f"  CI [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))
    for u in ("NIFTY", "SENSEX"):
        print(f"    {u:6s} {line([t for t in tr if t['u'] == u], key)}")


if __name__ == "__main__":
    allt, states = [], {}
    for u in ("NIFTY", "SENSEX"):
        tr, s15, s60 = simulate(u, minutes(u))
        allt += tr
        states[u] = (s15, s60)
    block("A-205 U1 (EMA5/10 + band expansion + RSI 50)", allt)
    block("A-206 U1 + U2 (hourly and 15-min agree)", [t for t in allt if t["agree"]])
    block("A-207 U1 with the U4 divergence exit", allt, "net_u4")
    block("A-207 U1 with U6 (no entry before the band re-entry)", [t for t in allt if not t["u6_block"]])
    print("\n  trades:")
    for t in sorted(allt, key=lambda t: (t["d"], t["entry"])):
        print(f"   {t['u']:6s} {t['d']} {fmt(t['entry'])}-{fmt(t['exit'])} {int(t['k'])} {t['ty']} ask {t['ask']:.2f} bid {t['bid']:.2f} "
              f"{t['net']:+6.1%}  u4 {t['net_u4']:+6.1%}  htf {'agree' if t['agree'] else '-'}{'  u6-block' if t['u6_block'] else ''}")
    # ma-cross tick-day trades split by U2 agreement at their entry time
    rows = q("select s.session_date::text, p.underlying, p.option_side, to_char(p.opened_at " + IST + ",'HH24:MI'), p.net, p.average_cost * p.quantity "
             "from trade.position p join trade.session s on s.id = p.session_id where s.id between 14141 and 14167 "
             "and p.strategy_id = 'ma-cross' and p.quantity > 0 order by 1, 4")
    split = defaultdict(list)
    for d, u, side, t, net, cost in rows:
        sd = 1 if side == "CE" else -1
        s15, s60 = states[u]
        ag = htf_at(s60, d, hm(t)) == sd and htf_at(s15, d, hm(t)) == sd
        split[ag].append(float(net) / float(cost))
    print("\n== A-207 ma-cross tick-day trades (v17 chain 14141-14167), net % of premium, by U2 agreement")
    for ag in (True, False):
        xs = split[ag]
        if xs:
            print(f"  {'agree' if ag else 'not  '}  n={len(xs):3d} mean {statistics.mean(xs):+6.2%} median {statistics.median(xs):+6.2%} "
                  f"win {sum(x > 0 for x in xs) / len(xs):.0%}")
