#!/usr/bin/env python3
"""A-252: obc (A-250 continuation) variants on 1-minute bars Oct 2024 - Oct 2026. Rules fixed in the ledger first.
B  = A-250 continuation (fresh EMA cross within 3 min + 1/3-min ATM +- 2 burst, first event of the day, continuation),
     exits: 10-minute extreme stop (1-minute lows / highs), 25 % premium stop, 12:30.
X  = B's entries; exits: 1-minute close beyond the invalidation level +- 0.1 % (puts: nearest of PDH / ORH above the
     entry, else the day high; calls: PDL / ORL below, else the day low), counter-burst, 50 % premium stop, 12:30.
E  = entries without a fresh cross (close on the burst's side of the EMA), continuation, B's exits.
EX = E's entries, X's exits.
"""
import importlib.util, os, random, statistics, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a232", os.path.join(HERE, "2026-10-26-oi-flip-reversal.py"))
a232 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a232)

COST, BUF = 0.0075, 0.001
FROM, TO, EXIT = 9 * 60 + 20, 11 * 60, 12 * 60 + 30
T = {"NIFTY": 0.06121, "SENSEX": 0.08155}               # A-250 P1 95th percentiles
PERIODS = [("P1", "2024-10-01", "2025-06-30"), ("P2", "2025-07-01", "2025-12-31"), ("P3", "2026-01-01", "2026-10-06")]
IST = a232.IST


def period(d):
    return next((n for n, a, b in PERIODS if a <= d <= b), None)


def load(u, d):
    q, hm, rng = a232.q, a232.hm, a232.rng
    idx = {hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(bar_start {IST},'HH24:MI'), open, high, low, close from hist.candle where underlying = '{u}' "
        f"and kind = 'INDEX' and bar_start between {rng(d)}")}
    if len(idx) < 300:
        return None
    e = q(f"select min(expiry)::text from hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry >= '{d}' "
          f"and bar_start between ('{d} 09:15+05:30')::timestamptz and ('{d} 09:20+05:30')::timestamptz")
    if not e or not e[0] or not e[0][0]:
        return None
    op = idx[min(idx)][0]
    px, oi = defaultdict(dict), defaultdict(dict)
    for t, k, ty, c, o in q(f"select to_char(bar_start {IST},'HH24:MI'), strike, option_type, close, coalesce(oi, 0) from "
                            f"hist.candle where underlying = '{u}' and kind = 'OPTION' and expiry = '{e[0][0]}' and strike "
                            f"between {op * 0.96} and {op * 1.04} and bar_start between {rng(d)}"):
        if c and float(c) > 0:
            px[(float(k), ty)][hm(t)] = float(c)
        if float(o) > 0:
            oi[(float(k), ty)][hm(t)] = float(o)
    return idx, px, oi


def last_at(series, minute, back=5):
    for m in range(minute, minute - back - 1, -1):
        if m in series:
            return series[m]
    return None


def share(oi, m, w, atm, step):
    dp = dc = tot = 0.0
    for j in range(-2, 3):
        for ty in ("PE", "CE"):
            s = oi.get((float(atm + j * step), ty), {})
            a, b = last_at(s, m), last_at(s, m - w)
            if a is None or b is None:
                return None
            if ty == "PE":
                dp += a - b
            else:
                dc += a - b
            tot += a
    return (dp - dc) / tot if tot > 0 else None


def walk(px, key, m, side, idx, oi, step, u, exits):
    """Option return from the entry at m + 1 under 'B' or 'X' exits."""
    s = px.get(key, {})
    buy = last_at(s, m)
    if buy is None:
        return None
    if exits == "B":
        lo = min(idx[x][2] for x in range(m - 9, m + 1) if x in idx)
        hi = max(idx[x][1] for x in range(m - 9, m + 1) if x in idx)
        prem_stop = 0.75
    else:
        prem_stop = 0.50
        before = [idx[x] for x in sorted(idx) if x <= m]
        orh = max(b[1] for b in before[:15]) if before else None
        orl = min(b[2] for b in before[:15]) if before else None
        dayh, dayl = max(b[1] for b in before), min(b[2] for b in before)
        c = idx[m][3]
        if side < 0:
            above = [x for x in (exits["pdh"], orh) if x is not None and x > c]
            level = (min(above) if above else dayh) * (1 + BUF)
        else:
            below = [x for x in (exits["pdl"], orl) if x is not None and x < c]
            level = (max(below) if below else dayl) * (1 - BUF)
    sell_minute = EXIT - 1
    for x in range(m + 1, EXIT):
        if x not in idx:
            continue
        if exits == "B":
            if (side > 0 and idx[x][2] < lo) or (side < 0 and idx[x][1] > hi):
                sell_minute = x
                break
        else:
            if (side > 0 and idx[x][3] < level) or (side < 0 and idx[x][3] > level):
                sell_minute = x
                break
            atm = round(idx[x][3] / step) * step
            s3 = share(oi, x, 3, atm, step)
            if s3 is not None and s3 * side <= -T[u]:
                sell_minute = x
                break
        p = s.get(x)
        if p is not None and p <= prem_stop * buy:
            sell_minute = x
            break
    sell = last_at(s, sell_minute) or buy
    return sell * (1 - COST) / (buy * (1 + COST)) - 1


def run():
    days = a232.bar_days()
    events = defaultdict(list)
    for u in ("NIFTY", "SENSEX"):
        step = a232.STEP[u]
        prev_closes, prev_hl = [], (None, None)
        for d in days:
            if period(d) is None:
                continue
            got = load(u, d)
            if not got:
                continue
            idx, px, oi = got
            mins = sorted(idx)
            ema, k, sides = None, 2 / 21, []
            for c in prev_closes[-60:]:
                ema = c if ema is None else ema + k * (c - ema)
            open_ = idx[mins[0]][0]
            first_b = first_e = False
            for m in mins:
                c = idx[m][3]
                ema = c if ema is None else ema + k * (c - ema)
                sides.append((m, 1 if c > ema else -1))
                if not (FROM <= m < TO):
                    continue
                atm = round(c / step) * step
                s1, s3 = share(oi, m, 1, atm, step), share(oi, m, 3, atm, step)
                if s1 is None or s3 is None:
                    continue
                now = sides[-1][1]
                recent = [x for mm, x in sides if m - 3 <= mm <= m]
                crossed = now if len(set(recent)) > 1 else 0
                ok1 = lambda side: s1 * side > 0 if u == "NIFTY" else s1 * side >= 0
                cont = lambda side: side == (1 if c > open_ else -1)
                ex = {"pdh": prev_hl[0], "pdl": prev_hl[1]}
                if not first_b and crossed and s3 * crossed >= T[u] and ok1(crossed):
                    first_b = True
                    if cont(crossed):
                        key = (float(atm), "CE" if crossed > 0 else "PE")
                        for arm, x in (("B", "B"), ("X", ex)):
                            r = walk(px, key, m, crossed, idx, oi, step, u, x)
                            if r is not None:
                                events[arm].append((period(d), d, u, r))
                side = now
                if not first_e and s3 * side >= T[u] and ok1(side):
                    first_e = True
                    if cont(side):
                        key = (float(atm), "CE" if side > 0 else "PE")
                        for arm, x in (("E", "B"), ("EX", ex)):
                            r = walk(px, key, m, side, idx, oi, step, u, x)
                            if r is not None:
                                events[arm].append((period(d), d, u, r))
            prev_closes = [idx[x][3] for x in mins]
            cont_bars = [idx[x] for x in mins if x < 15 * 60 + 15]
            prev_hl = (max(b[1] for b in cont_bars), min(b[2] for b in cont_bars))
        print(u, "done", file=sys.stderr)
    return events


def boot(ev, n=5000):
    by = defaultdict(list)
    for p, d, u, x in ev:
        by[d].append(x)
    ks = list(by)
    random.seed(252)
    ms = sorted(statistics.mean([x for dd in random.choices(ks, k=len(ks)) for x in by[dd]]) for _ in range(n))
    return ms[int(0.025 * n)], ms[int(0.975 * n)]


if __name__ == "__main__":
    ev = run()
    base_late = [e for e in ev["B"] if e[0] in ("P2", "P3")]
    for arm in ("B", "X", "E", "EX"):
        xs = ev.get(arm, [])
        line = f"{arm:3s}"
        per = {}
        for p, _, _ in PERIODS:
            v = [x for pp, _, _, x in xs if pp == p]
            per[p] = v
            line += f" | {p} n {len(v):3d} {statistics.mean(v) * 100 if v else float('nan'):+6.1f} % win {sum(x > 0 for x in v) / len(v) if v else 0:4.0%}"
        late = [e for e in xs if e[0] in ("P2", "P3")]
        lo, hi = boot(late) if len(late) > 10 else (float("nan"), float("nan"))
        mean_late = statistics.mean(e[3] for e in late) if late else float("nan")
        line += f" | P2+P3 {mean_late * 100:+6.1f} % [95% CI {lo * 100:+.1f}, {hi * 100:+.1f}]"
        top = sorted((e[3] for e in xs), reverse=True)[:3]
        line += f" | top 3 trades {', '.join(f'{t * 100:+.0f} %' for t in top)}"
        print(line)
        if arm != "B":
            ok = (all(len(per[p]) >= 30 for p in ("P2", "P3")) and all(per[p] and statistics.mean(per[p]) > 0 for p in per)
                  and late and lo > 0 and mean_late > statistics.mean(e[3] for e in base_late))
            print(f"    -> {'PASS' if ok else 'FAIL / INSUFFICIENT'}")
