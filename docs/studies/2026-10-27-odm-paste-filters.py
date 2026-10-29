#!/usr/bin/env python3
"""Descriptive check (27 Oct 2026) of three filters pasted by the user against odm-v4's 13 tick-day trades (A-249 v4 arm):
F1 option bid-ask spread at entry > 1.5 % of the premium -> block; F2 (puts) block unless the 3-minute ATM +- 5 put OI
change < 0 or call OI added >= 2 x put OI added (mirror for calls); F3 block when |index - day open| > 1.2 x the 1-minute
ATR14 at entry. Which trades each would have blocked, winners and losers. 13 trades: small, in sample, not a test.
"""
import csv, io, json, re, subprocess

IST = "at time zone 'Asia/Kolkata'"


def q(sql, db="autotrade"):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", db, "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True,
                         stdin=subprocess.DEVNULL).stdout
    return list(csv.reader(io.StringIO(out)))


sids = []
for line in open(".local/a249/progress.txt"):
    m = re.match(r"\S+ V4 \w+ (\S+) sid=(\d+)", line)
    if m:
        sids.append(m.group(2))
trades = q("select p.session_id, s.session_date::text, p.underlying, p.option_side, p.symbol, "
           f"to_char(p.opened_at {IST},'HH24:MI:SS'), p.average_cost, p.exit_reason, round(p.net) from trade.position p "
           f"join trade.session s on s.id = p.session_id where p.quantity > 0 and p.session_id in ({','.join(sids)}) order by p.opened_at")


def index_minutes(u, d):
    if d <= "2026-10-06":
        rows = q(f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where kind = 'INDEX' and "
                 f"underlying = '{u}' and (bar_start {IST})::date = '{d}'")
    else:
        rows = q(f"select to_char(date_trunc('minute', exch_ts {IST}),'HH24:MI'), (array_agg(price order by exch_ts))[1], max(price), "
                 f"min(price), (array_agg(price order by exch_ts desc))[1] from md.index_tick where underlying = '{u}' and "
                 f"exch_ts >= timestamptz '{d} 09:15+05:30' and exch_ts < timestamptz '{d} 15:30+05:30' group by 1")
    return {t: tuple(map(float, v)) for t, *v in rows}


def prev_day(u, d):
    return q(f"select max(session_date)::text from md.index_tick where underlying = '{u}' and session_date < '{d}'")[0][0] \
        if d > "2026-10-06" else q(f"select max((bar_start {IST})::date)::text from hist.candle where kind = 'INDEX' and "
                                   f"underlying = '{u}' and (bar_start {IST})::date < '{d}'")[0][0]


def spread(d, symbol, t):
    """(bid, ask) of the option at the last quote before the entry second."""
    if d >= "2026-09-28":
        r = q(f"select bid_px[1], ask_px[1] from md.option_tick where symbol = '{symbol}' and exch_ts >= timestamptz "
              f"'{d} 09:15+05:30' and exch_ts <= timestamptz '{d} {t}+05:30' and bid_px[1] > 0 and ask_px[1] > 0 "
              f"order by exch_ts desc limit 1")
        return tuple(map(float, r[0])) if r else None
    r = q(f"select payload_json from market_tick_records where session_date = '{d}' and symbol like '%{symbol.split()[1]}%"
          f"{symbol.split()[2]}%' and received_at <= timestamptz '{d} {t}+05:30' order by received_at desc limit 1", db="zt_archive")
    if not r:
        return None
    p = json.loads(r[0][0])
    def first(*keys):
        for k in keys:
            v = p.get(k)
            if isinstance(v, (int, float)) and v > 0:
                return float(v)
        return None
    b, a = first("bid_price", "best_bid_price", "bidPrice", "bp"), first("ask_price", "best_ask_price", "askPrice", "sp")
    return (b, a) if b and a else None


print(f"{'day':10s} {'index':6s} {'side':4s} {'entry':8s} {'exit':10s} {'net':>10s} | F1 spread % | F2 put/call OI 5m (ctx) | F3 dist/ATR1m")
blocked = {"F1": [], "F2": [], "F3": []}
for sid, d, u, side, symbol, t, cost, why, net in trades:
    net = float(net)
    ctx = json.loads(q(f"select context from trade.candidate where session_id = {sid} and strategy_id = 'opening-drive-momentum' "
                       f"and action = 'ENTER' and underlying = '{u}' order by at limit 1")[0][0])
    ba = spread(d, symbol, t)
    sp = (ba[1] - ba[0]) / ((ba[1] + ba[0]) / 2) * 100 if ba else float("nan")
    ce, pe = ctx.get("ceOiChange5m"), ctx.get("peOiChange5m")
    if side == "PE":
        f2 = not ((pe is not None and pe < 0) or (ce is not None and pe is not None and ce > 0 and ce >= 2 * max(pe, 0)))
    else:
        f2 = not ((ce is not None and ce < 0) or (ce is not None and pe is not None and pe > 0 and pe >= 2 * max(ce, 0)))
    bars = index_minutes(u, d)
    pbars = index_minutes(u, prev_day(u, d))
    hm = t[:5]
    seq = [pbars[k] for k in sorted(pbars)] + [bars[k] for k in sorted(bars) if k < hm]
    trs = []
    for i in range(1, len(seq)):
        h, l, pc = seq[i][1], seq[i][2], seq[i - 1][3]
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    atr1 = sum(trs[-14:]) / 14
    open_ = bars[min(bars)][0]
    spot = bars[max(k for k in bars if k < hm)][3]
    ratio = abs(spot - open_) / atr1
    f1, f3 = sp > 1.5, ratio > 1.2
    for k, flag in (("F1", f1), ("F2", f2), ("F3", f3)):
        if flag:
            blocked[k].append(net)
    print(f"{d} {u:6s} {side:4s} {t:8s} {why:10s} {net:+10,.0f} | {sp:5.2f} {'BLOCK' if f1 else '     '} | "
          f"{pe if pe is None else round(pe):>9} / {ce if ce is None else round(ce):>9} {'BLOCK' if f2 else '     '} | "
          f"{ratio:5.1f} {'BLOCK' if f3 else ''}")
total = sum(float(x[8]) for x in trades)
for k, v in blocked.items():
    print(f"{k}: blocks {len(v)} of {len(trades)} trades, net of the blocked {sum(v):+,.0f} "
          f"(winners {sum(1 for x in v if x > 0)}, losers {sum(1 for x in v if x < 0)}); odm net {total:+,.0f} -> {total - sum(v):+,.0f}")
