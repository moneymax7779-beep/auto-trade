"""A-386: multi-timeframe OI trend + level break, scalp and runner exits, and the missed-move anatomy.
Rules: the A-386 ledger row (registered before this ran). Read-only on the local DB; per-day cache in .local/a386/cache.
Usage: python3 docs/studies/2026-11-13-oi-mtf-trend.py > .local/a386/result.md"""
import csv, io, json, math, os, subprocess, sys
from collections import defaultdict

CACHE = '.local/a386/cache'
STEP = {'NIFTY': 50, 'SENSEX': 100}
IDEA = ('2026-11-12', 'NIFTY')
H1_END = '2026-10-21'
WS = (1, 3, 5, 15, 30)

def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))

M = "to_char(date_trunc('minute', recv_ts at time zone 'Asia/Kolkata'), 'HH24:MI')"
def hm(s): return int(s[:2]) * 60 + int(s[3:5])
def TB(d): return f"recv_ts >= '{d} 00:00+05:30' and recv_ts < '{d} 00:00+05:30'::timestamptz + interval '1 day'"

def prior_levels(d, u):
    rows = sql(f"select max(session_date)::text p from md.index_tick where underlying = '{u}' and session_date < '{d}' and recv_ts < '{d} 00:00+05:30' and recv_ts > '{d} 00:00+05:30'::timestamptz - interval '10 days'")
    prev = rows[0]['p'] if rows else None   # a lone NULL comes back as a blank CSV line, which DictReader skips
    if prev:
        r = sql(f"""select max(price) h, min(price) l, (array_agg(price order by recv_ts desc))[1] c,
            max(price) filter (where (recv_ts at time zone 'Asia/Kolkata')::time < '09:30') orh,
            min(price) filter (where (recv_ts at time zone 'Asia/Kolkata')::time < '09:30') orl
            from md.index_tick where underlying = '{u}' and session_date = '{prev}' and {TB(prev)} and price > 0
            and (recv_ts at time zone 'Asia/Kolkata')::time between '09:15' and '15:30'""")[0]
    else:   # 28 Sep: the prior day comes from the Upstox 1-minute index bars
        r = sql(f"""with b as (select * from hist.candle where kind = 'INDEX' and underlying = '{u}'
              and (bar_start at time zone 'Asia/Kolkata')::date = (select max((bar_start at time zone 'Asia/Kolkata')::date)
              from hist.candle where kind = 'INDEX' and underlying = '{u}' and (bar_start at time zone 'Asia/Kolkata')::date < '{d}'))
            select max(high) h, min(low) l, (array_agg(close order by bar_start desc))[1] c,
            max(high) filter (where (bar_start at time zone 'Asia/Kolkata')::time < '09:30') orh,
            min(low) filter (where (bar_start at time zone 'Asia/Kolkata')::time < '09:30') orl from b""")[0]
    return {k: float(v) for k, v in r.items()}

def load(d, u):
    f = f"{CACHE}/{d}_{u}.json"
    if os.path.exists(f):
        return json.load(open(f))
    spot = {r['m']: float(r['p']) for r in sql(
        f"select {M} m, (array_agg(price order by recv_ts desc))[1] p from md.index_tick where session_date = '{d}' and {TB(d)} "
        f"and underlying = '{u}' and price > 0 and (recv_ts at time zone 'Asia/Kolkata')::time between '09:15' and '15:30' group by 1")}
    o = spot[min(spot)]
    exp = sql(f"select min(expiry)::text e from md.option_tick where session_date = '{d}' and {TB(d)} and underlying = '{u}' and expiry >= '{d}'")[0]['e']
    chain = defaultdict(dict)       # "strike type" -> minute -> [oi, bid_last, bid_lo, bid_hi, ask_first]
    for r in sql(f"""select {M} m, strike, option_type t,
            (array_agg(oi order by recv_ts desc) filter (where oi > 0))[1] oi,
            (array_agg(bid_px[1] order by recv_ts desc) filter (where bid_px[1] > 0))[1] bl,
            min(bid_px[1]) filter (where bid_px[1] > 0) blo, max(bid_px[1]) filter (where bid_px[1] > 0) bhi,
            (array_agg(ask_px[1] order by recv_ts) filter (where ask_px[1] > 0))[1] af
            from md.option_tick where session_date = '{d}' and {TB(d)} and underlying = '{u}' and expiry = '{exp}'
            and strike between {o * 0.97} and {o * 1.03} group by 1, 2, 3"""):
        chain[f"{int(float(r['strike']))} {r['t']}"][r['m']] = [float(r[k]) if r[k] else None for k in ('oi', 'bl', 'blo', 'bhi', 'af')]
    out = dict(spot=spot, expiry=exp, chain=chain, prior=prior_levels(d, u))
    os.makedirs(CACHE, exist_ok=True)
    json.dump(out, open(f, 'w'))
    return out

def costs(buy, sell, u):
    brk = min(20, 0.0003 * buy) + min(20, 0.0003 * sell)
    exch = (0.0003503 if u == 'NIFTY' else 0.000325) * (buy + sell); sebi = 1e-6 * (buy + sell)
    return brk + 0.001 * sell + exch + sebi + 0.00003 * buy + 0.18 * (brk + exch + sebi)
LOT = {'NIFTY': 65, 'SENSEX': 20}

class Day:
    def __init__(self, d, u):
        self.d, self.u = d, u
        raw = load(d, u)
        self.exp = raw['expiry']; self.prior = raw['prior']
        mins = list(range(9 * 60 + 15, 15 * 60 + 31))
        sp, last = {}, None
        for t in mins:
            v = raw['spot'].get(f"{t // 60:02d}:{t % 60:02d}")
            if v: last = v
            sp[t] = last
        self.sp = sp
        self.chain = {}
        for k, mm in raw['chain'].items():
            ser, lo = {}, None
            byt = {hm(m): v for m, v in mm.items()}
            self.chain[k] = byt
            oi = {}; lastoi = None
            for t in mins:
                v = byt.get(t)
                if v and v[0]: lastoi = v[0]
                oi[t] = lastoi
            self.chain[k + ' oi'] = oi
        self.open = sp[9 * 60 + 15]
        orw = [sp[t] for t in range(9 * 60 + 15, 9 * 60 + 30) if sp[t]]
        self.orh, self.orl = max(orw), min(orw)
    def levels(self, t):
        p = self.prior
        lv = [p['h'], p['l'], p['c'], p['orh'], p['orl'], self.open]
        if t >= 9 * 60 + 30: lv += [self.orh, self.orl]
        return lv
    def atm(self, t): return round(self.sp[t] / STEP[self.u]) * STEP[self.u]
    def net(self, t, w):
        a = self.atm(t); s = STEP[self.u]; tot = 0.0
        for k in range(-5, 6):
            st = a + k * s
            for typ, sign in (('PE', 1), ('CE', -1)):
                ser = self.chain.get(f"{st} {typ} oi")
                if not ser: continue
                if t - w < 9 * 60 + 15: return None
                x, y = ser.get(t - w), ser.get(t)
                if x is None or y is None: continue
                tot += sign * (y - x)
        return tot
    def oi_trend(self, t):
        n = {w: self.net(t, w) for w in WS}
        if any(v is None for v in n.values()): return 0
        if n[1] >= 0 and n[3] >= 0 and all(n[w] > 0 for w in (5, 15, 30)): return 1
        if n[1] <= 0 and n[3] <= 0 and all(n[w] < 0 for w in (5, 15, 30)): return -1
        return 0
    def price_trend(self, t):
        p = self.sp.get(t)
        if p is None or any(self.sp.get(t - w) is None for w in (5, 15, 30)): return 0
        if all(p > self.sp[t - w] for w in (5, 15, 30)): return 1
        if all(p < self.sp[t - w] for w in (5, 15, 30)): return -1
        return 0
    def level_break(self, t):
        """+1 / -1 if a 1-minute close crossed a level that way within the last 5 minutes and is still beyond it"""
        res = 0
        for lv in self.levels(t):
            for k in range(0, 5):
                a, b = self.sp.get(t - k - 1), self.sp.get(t - k)
                if a is None or b is None: continue
                if a <= lv < b and self.sp[t] > lv: res = res or 1
                if a >= lv > b and self.sp[t] < lv: res = res or -1
        return res

def signals(day, kind):
    out = {}
    for t in range(9 * 60 + 30, 14 * 60 + 46):
        pt = day.price_trend(t)
        if kind == 'OI_ONLY':
            ot = day.oi_trend(t)
            if ot: out[t] = ot
            continue
        if kind == 'OI_REV':
            ot = day.oi_trend(t)
            lb = day.level_break(t)
            if lb and pt == lb and ot == -lb: out[t] = lb
            continue
        lb = day.level_break(t)
        if not lb or pt != lb: continue
        if kind == 'C': out[t] = lb
        elif kind == 'S' and day.oi_trend(t) == lb: out[t] = lb
    return out

def simulate(day, sig, arm):
    trades, busy_until = [], -1
    for t in sorted(sig):
        if t <= busy_until: continue
        dirn = sig[t]; typ = 'CE' if dirn > 0 else 'PE'
        strike = day.atm(t); bars = day.chain.get(f"{strike} {typ}")
        if not bars: continue
        e = bars.get(t + 1)
        if not e or not e[4]: continue
        entry = e[4]; peak = None; xt, xp, why = None, None, None
        for m in range(t + 1, 15 * 60 + 16):
            b = bars.get(m)
            if not b or b[1] is None: continue
            lo, hi, last = b[2], b[3], b[1]
            if arm == 'scalp':
                if lo <= entry * 0.90: xt, xp, why = m, entry * 0.90, 'STOP'; break
                if hi >= entry * 1.10: xt, xp, why = m, entry * 1.10, 'TARGET'; break
                if m >= t + 1 + 15: xt, xp, why = m, last, 'TIME'; break
            else:
                if lo <= entry * 0.80 and (peak is None or peak < entry * 1.20): xt, xp, why = m, entry * 0.80, 'STOP'; break
                # conservative inside a minute: the trail is checked against the peak before this minute's high
                if peak is not None and peak >= entry * 1.20 and lo <= peak * 0.85: xt, xp, why = m, min(peak * 0.85, hi), 'TRAIL'; break
                peak = max(peak or hi, hi)
            if m >= 15 * 60 + 15: xt, xp, why = m, last, 'FLAT'; break
        if xt is None: continue
        q = LOT[day.u]; buy, sell = entry * q, xp * q
        trades.append(dict(d=day.d, u=day.u, t=t, xt=xt, dir=dirn, strike=strike, entry=entry, exit=xp, why=why,
                           net=sell - buy - costs(buy, sell, day.u), pct=(xp / entry - 1) * 100, expiry=day.exp == day.d))
        busy_until = xt + 10
    return trades

def fmt_t(t): return f"{t // 60:02d}:{t % 60:02d}"
L = lambda v: f"{v:+,.0f}"
def stats(ts):
    if not ts: return dict(n=0, net=0, pf=0, win=0, per=0)
    w = sum(x['net'] for x in ts if x['net'] > 0); l = -sum(x['net'] for x in ts if x['net'] < 0)
    return dict(n=len(ts), net=sum(x['net'] for x in ts), pf=w / l if l else float('inf'),
                win=sum(x['net'] > 0 for x in ts) / len(ts), per=sum(x['net'] for x in ts) / len(ts))

if __name__ == '__main__':
    days = [r['d'] for r in sql("select distinct session_date::text d from md.index_tick where underlying = 'NIFTY' order by 1")]
    DAYS = []
    for d in days:
        for u in ('NIFTY', 'SENSEX'):
            try:
                DAYS.append(Day(d, u))
            except Exception as ex:
                print(f"<!-- skip {d} {u}: {ex} -->", file=sys.stderr)
        print(d, file=sys.stderr, flush=True)
    KINDS = ('S', 'C', 'OI_ONLY', 'OI_REV')
    SIG = {k: {(x.d, x.u): signals(x, k) for x in DAYS} for k in KINDS}
    RES = {(k, arm): [tr for x in DAYS for tr in simulate(x, SIG[k][(x.d, x.u)], arm)] for k in KINDS for arm in ('scalp', 'runner')}
    json.dump({f"{k}|{a}": v for (k, a), v in RES.items()}, open('.local/a386/trades.json', 'w'))
    judged = lambda ts: [x for x in ts if (x['d'], x['u']) != IDEA]
    print("# A-386 result: multi-timeframe OI trend + level break\n")
    print(f"{len(DAYS)} index-days ({len({x.d for x in DAYS})} days); idea day {IDEA[0]} {IDEA[1]} reported apart. 1 lot, after costs. "
          f"Halves to {H1_END} / after.\n")
    print("| signal | exit | trades | win | net | PF | per trade | H1 | H2 | w/o best day | expiry days | other days | idea day |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for k in KINDS:
        for arm in ('scalp', 'runner'):
            ts = judged(RES[(k, arm)]); s = stats(ts)
            byday = defaultdict(float)
            for x in ts: byday[x['d']] += x['net']
            best = max(byday.values()) if byday else 0
            h1 = sum(x['net'] for x in ts if x['d'] <= H1_END); h2 = s['net'] - h1
            ex = sum(x['net'] for x in ts if x['expiry']); idea = sum(x['net'] for x in RES[(k, arm)] if (x['d'], x['u']) == IDEA)
            print(f"| {k} | {arm} | {s['n']} | {s['win']:.0%} | {L(s['net'])} | {s['pf']:.2f} | {L(s['per'])} | {L(h1)} | {L(h2)} | "
                  f"{L(s['net'] - best)} | {L(ex)} | {L(s['net'] - ex)} | {L(idea)} |")
    print("\n## Registered rule (S against C)\n")
    for arm in ('scalp', 'runner'):
        S = judged(RES[('S', arm)]); C = judged(RES[('C', arm)]); s = stats(S)
        byday = defaultdict(float)
        for x in S: byday[x['d']] += x['net']
        h = {}
        for name, f in (('H1', lambda x: x['d'] <= H1_END), ('H2', lambda x: x['d'] > H1_END)):
            a = [x for x in S if f(x)]; b = [x for x in C if f(x)]
            h[name] = (sum(x['net'] for x in a), stats(a)['per'], stats(b)['per'], len(a), len(b))
        c1 = s['n'] >= 30; c2 = s['net'] > 0 and s['pf'] >= 1.3; c3 = h['H1'][0] > 0 and h['H2'][0] > 0
        c4 = s['net'] - (max(byday.values()) if byday else 0) > 0; c5 = h['H1'][1] > h['H1'][2] and h['H2'][1] > h['H2'][2]
        print(f"- **{arm}**: (1) trades {s['n']} {'ok' if c1 else 'no'}; (2) net {L(s['net'])}, PF {s['pf']:.2f} {'ok' if c2 else 'no'}; "
              f"(3) H1 {L(h['H1'][0])} / H2 {L(h['H2'][0])} {'ok' if c3 else 'no'}; (4) w/o best day {'ok' if c4 else 'no'}; "
              f"(5) per trade S vs C: H1 {L(h['H1'][1])} vs {L(h['H1'][2])} ({h['H1'][3]} / {h['H1'][4]} trades), "
              f"H2 {L(h['H2'][1])} vs {L(h['H2'][2])} ({h['H2'][3]} / {h['H2'][4]}) {'ok' if c5 else 'no'} "
              f"-> **{'PASS' if all((c1, c2, c3, c4, c5)) else 'FAIL'}**")
    print("\n## How often the OI trend is on (share of 09:30-14:45 minutes)\n")
    for u in ('NIFTY', 'SENSEX'):
        xs = [x for x in DAYS if x.u == u]; n = sum(len(range(9 * 60 + 30, 14 * 60 + 46)) for _ in xs)
        on = sum(len(SIG['OI_ONLY'][(x.d, x.u)]) for x in xs)
        print(f"- {u}: OI trend on {on / n:.0%} of minutes; S signals {sum(len(SIG['S'][(x.d, x.u)]) for x in xs)}, "
              f"C signals {sum(len(SIG['C'][(x.d, x.u)]) for x in xs)} (minutes, before the one-position rule)")
    # ---- missed-move anatomy
    print("\n## Missed-move anatomy: swings >= 0.4 % (0.2 % reversals), 09:30-15:15\n")
    live = json.load(open('.local/trend-exit/trades.json')) if os.path.exists('.local/trend-exit/trades.json') else []
    rows = []
    for x in DAYS:
        pts = [(t, x.sp[t]) for t in range(9 * 60 + 30, 15 * 60 + 16) if x.sp[t]]
        piv = [pts[0]]; hi = lo = pts[0]; d0 = 0
        for t, p in pts[1:]:
            if p > hi[1]: hi = (t, p)
            if p < lo[1]: lo = (t, p)
            if d0 >= 0 and p <= hi[1] * (1 - 0.002) and hi[1] >= piv[-1][1]:
                if d0 == 1 or hi[1] > piv[-1][1]: piv.append(hi)
                d0 = -1; lo = (t, p); hi = (t, p)
            elif d0 <= 0 and p >= lo[1] * (1 + 0.002) and lo[1] <= piv[-1][1]:
                if d0 == -1 or lo[1] < piv[-1][1]: piv.append(lo)
                d0 = 1; hi = (t, p); lo = (t, p)
        piv.append(hi if d0 == 1 else lo if d0 == -1 else pts[-1])
        for (t0, p0), (t1, p1) in zip(piv, piv[1:]):
            mv = (p1 - p0) / p0 * 100
            if abs(mv) < 0.4: continue
            dirn = 1 if mv > 0 else -1
            def first(kind):
                ts = [t for t, s in SIG[kind][(x.d, x.u)].items() if t0 <= t < t1 and s == dirn]
                return min(ts) if ts else None
            fs, fc = first('S'), first('C')
            done = lambda t: (x.sp[t] - p0) / (p1 - p0) if t else None
            held = None
            if x.d <= '2026-11-06' and live:
                held = any(tr['d'] == x.d and tr['u'] == x.u and (1 if tr['side'] == 'CE' else -1) == dirn
                           and hm(tr['o']) < t0 + 0.5 * (t1 - t0) and hm(tr['c']) > t0 for tr in live)
            rows.append(dict(d=x.d, u=x.u, t0=t0, t1=t1, mv=mv, fs=fs, fc=fc, ds=done(fs), dc=done(fc), held=held))
    n = len(rows)
    print(f"{n} swings. S fired inside {sum(r['fs'] is not None for r in rows)} ({sum(r['fs'] is not None for r in rows) / n:.0%}); "
          f"C inside {sum(r['fc'] is not None for r in rows)} ({sum(r['fc'] is not None for r in rows) / n:.0%}).")
    ds = sorted(r['ds'] for r in rows if r['ds'] is not None); dc = sorted(r['dc'] for r in rows if r['dc'] is not None)
    if ds: print(f"When S fired, the median swing was already {ds[len(ds) // 2]:.0%} done (quartiles {ds[len(ds) // 4]:.0%} / {ds[3 * len(ds) // 4]:.0%}).")
    if dc: print(f"When C fired, the median swing was already {dc[len(dc) // 2]:.0%} done (quartiles {dc[len(dc) // 4]:.0%} / {dc[3 * len(dc) // 4]:.0%}).")
    lv = [r for r in rows if r['held'] is not None]
    if lv: print(f"Live five (alone replays, swings to 6 Nov): held the swing's direction before its midpoint in {sum(r['held'] for r in lv)} of {len(lv)} ({sum(r['held'] for r in lv) / len(lv):.0%}).")
    # false alarms: S signals not inside any same-direction swing
    allS = [(x.d, x.u, t, s) for x in DAYS for t, s in SIG['S'][(x.d, x.u)].items()]
    inside = sum(1 for d, u, t, s in allS if any(r['d'] == d and r['u'] == u and r['t0'] <= t < r['t1'] and (1 if r['mv'] > 0 else -1) == s for r in rows))
    print(f"S signal minutes inside a same-direction >= 0.4 % swing: {inside} of {len(allS)} ({inside / max(1, len(allS)):.0%}).\n")
    print("| day | idx | swing | move % | S first (done) | C first (done) | live held |\n|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['d']} | {r['u']} | {fmt_t(r['t0'])}-{fmt_t(r['t1'])} | {r['mv']:+.2f} | "
              f"{fmt_t(r['fs']) + f' ({r[chr(100)+chr(115)]:.0%})' if r['fs'] else '-'} | {fmt_t(r['fc']) + f' ({r[chr(100)+chr(99)]:.0%})' if r['fc'] else '-'} | "
              f"{'-' if r['held'] is None else ('yes' if r['held'] else 'no')} |")
