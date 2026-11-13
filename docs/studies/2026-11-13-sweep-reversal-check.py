"""A-391: check of a pasted diagnosis of the user's 11 NIFTY replay charts (Part A, descriptive) and its liquidity-sweep
reversal (B1) and coil-breakout (B2) rules (Part B, registered). Rules: the A-391 ledger row, registered before this ran.
Local DB read-only; option prices from the A-386 cache (.local/a386/cache); minute data cached in .local/a391/cache.
Usage: python3 docs/studies/2026-11-13-sweep-reversal-check.py > .local/a391/result.md"""
import csv, importlib.util, io, json, os, subprocess, sys
from collections import defaultdict

spec = importlib.util.spec_from_file_location('a386', 'docs/studies/2026-11-13-oi-mtf-trend.py')
A386 = importlib.util.module_from_spec(spec); spec.loader.exec_module(A386)
costs, LOT, STEP = A386.costs, A386.LOT, A386.STEP

CACHE = '.local/a391/cache'
H1_END = '2026-10-21'
CHART_DAYS = {'2026-11-09', '2026-11-04', '2026-11-03', '2026-10-28', '2026-10-27', '2026-10-26', '2026-10-22',
              '2026-10-05', '2026-10-06', '2026-10-07', '2026-10-16'}
CHART_SESSIONS = [35502, 35489, 35486, 35469, 35465, 35461, 35453, 35406, 35409, 35413, 35441]
SCALE = {'NIFTY': 1.0, 'SENSEX': 3.25}        # the pasted rules are in NIFTY points

def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))

def hm(s): return int(s[:2]) * 60 + int(s[3:5])
def mm(m): return f"{m // 60:02d}:{m % 60:02d}"
TB = A386.TB

# ------------------------------------------------------------------ data
def load(d, u):
    f = f"{CACHE}/{d}_{u}.json"
    if os.path.exists(f):
        return json.load(open(f))
    M = "to_char(date_trunc('minute', recv_ts at time zone 'Asia/Kolkata'), 'HH24:MI')"
    spot = {r['m']: [float(r['o']), float(r['h']), float(r['l']), float(r['c'])] for r in sql(
        f"select {M} m, (array_agg(price order by recv_ts))[1] o, max(price) h, min(price) l, (array_agg(price order by recv_ts desc))[1] c "
        f"from md.index_tick where session_date = '{d}' and {TB(d)} and underlying = '{u}' and price > 0 "
        f"and (recv_ts at time zone 'Asia/Kolkata')::time between '09:15' and '15:29:59' group by 1")}
    exp = sql(f"select min(expiry)::text e from md.future_tick where session_date = '{d}' and {TB(d)} and underlying = '{u}' and expiry >= '{d}'")[0]['e']
    fut = {r['m']: [float(r['p']), float(r['v']) if r['v'] else None] for r in sql(
        f"select {M} m, (array_agg(price order by recv_ts desc))[1] p, (array_agg(session_vwap order by recv_ts desc) filter (where session_vwap > 0))[1] v "
        f"from md.future_tick where session_date = '{d}' and {TB(d)} and underlying = '{u}' and expiry = '{exp}' and price > 0 "
        f"and (recv_ts at time zone 'Asia/Kolkata')::time between '09:15' and '15:29:59' group by 1")}
    vix = sql(f"select open from ref.index_candle where symbol = 'INDIA_VIX' and timeframe = '1minute' and session_date = '{d}' order by bar_start limit 1")
    out = dict(spot=spot, fut=fut, vix=float(vix[0]['open']) if vix else None)
    os.makedirs(CACHE, exist_ok=True); json.dump(out, open(f, 'w'))
    return out

class Day:
    def __init__(self, d, u):
        self.d, self.u = d, u
        raw = load(d, u); a = A386.load(d, u)
        self.chain, self.prior, self.vix = a['chain'], a['prior'], raw['vix']
        self.mins = list(range(hm('09:15'), hm('15:30')))
        sp = raw['spot']; last = None; self.ohlc = {}
        for m in self.mins:
            k = mm(m)
            if k in sp: last = sp[k][3]; self.ohlc[m] = sp[k]
            elif last is not None: self.ohlc[m] = [last] * 4
        # chart VWAP: futures session VWAP minus the trailing 15-minute mean basis
        self.vwap, basis = {}, []
        for m in self.mins:
            k = mm(m); f = raw['fut'].get(k)
            if f is None or m not in self.ohlc: continue
            basis.append(f[0] - self.ohlc[m][3]); basis = basis[-15:]
            if f[1]: self.vwap[m] = f[1] - sum(basis) / len(basis)
        # 3-minute bars from 09:15; bar i closes at the end of minute 09:15 + 3i + 2
        self.bars = []
        for i in range(125):
            ms = [m for m in range(hm('09:15') + 3 * i, hm('09:15') + 3 * i + 3) if m in self.ohlc]
            if not ms: continue
            self.bars.append(dict(i=i, end=ms[-1], o=self.ohlc[ms[0]][0], h=max(self.ohlc[m][1] for m in ms),
                                  l=min(self.ohlc[m][2] for m in ms), c=self.ohlc[ms[-1]][3]))
        e = None; k = 2 / 21
        for n, b in enumerate(self.bars):
            e = b['c'] if e is None else e + (b['c'] - e) * k
            b['ema'] = e if n >= 19 else None
            b['vwap'] = self.vwap.get(b['end'])
        oh = [self.ohlc[m] for m in self.mins if m in self.ohlc and m < hm('09:30')]
        self.orh, self.orl = max(x[1] for x in oh), min(x[2] for x in oh)
        self.zones = self._zones()

    def ema3_at(self, m):      # EMA20 of 3-min closes, last bar completed before minute m
        v = [b['ema'] for b in self.bars if b['end'] < m and b['ema'] is not None]
        return v[-1] if v else None

    def _zones(self):           # port of LevelsService.zones on minute bars, with the time of every touch's confirmation
        bars = [(m, *self.ohlc[m]) for m in self.mins if m in self.ohlc]
        cl = []
        for i in range(3, len(bars)):
            now = bars[i]; p = i - 3
            if p >= 3:
                pv = bars[p]; hi = lo = True
                for j in range(p - 3, p + 4):
                    if j < p: hi &= pv[2] > bars[j][2]; lo &= pv[3] < bars[j][3]
                    elif j > p: hi &= pv[2] >= bars[j][2]; lo &= pv[3] <= bars[j][3]
                hi &= pv[0] < hm('15:15'); lo &= pv[0] < hm('15:15')
                for kind, ok, price in (('R', hi, pv[2]), ('S', lo, pv[3])):
                    if not ok: continue
                    tol = 0.0004 * price
                    for c in cl:
                        if c['k'] == kind and c['broken'] is None and abs(price - sum(c['p']) / len(c['p'])) <= tol:
                            if pv[0] >= c['t'][-1] + 5:
                                c['p'].append(price); c['t'].append(pv[0]); c['conf'].append(now[0])
                                if c['formed'] is None: c['formed'] = now[0]
                            break
                    else:
                        cl.append(dict(k=kind, p=[price], t=[pv[0]], conf=[now[0]], formed=None, broken=None))
            for c in cl:
                if c['formed'] is None or c['broken'] is not None or now[0] <= c['formed']: continue
                tol = 0.0004 * now[4]
                if (now[4] > max(c['p']) + tol) if c['k'] == 'R' else (now[4] < min(c['p']) - tol): c['broken'] = now[0]
        return cl

    def zones_at(self, m, kind, min_touches):
        out = []
        for c in self.zones:
            if c['k'] != kind or c['formed'] is None or c['formed'] > m or (c['broken'] is not None and c['broken'] <= m): continue
            n = sum(1 for t in c['conf'] if t <= m)
            if n >= min_touches:
                ps = [p for p, t in zip(c['p'], c['conf']) if t <= m]
                out.append((min(ps), max(ps)))
        return out

    def opt(self, strike, side, m):
        return self.chain.get(f"{strike} {side}", {}).get(mm(m))

# ------------------------------------------------------------------ Part A
def part_a(trades, days):
    print("# A-391 result\n\n## Part A: the pasted claims and filters, on all trades of the 29-day replay batch\n")
    tot = sum(t['net'] for t in trades)
    print(f"Batch: {len(trades)} trades, net {tot:+,.0f} on 29 days (sessions 35390-35502).\n")
    print("### The 11 charts\n\n| # | date | session net (all indices) | NIFTY net | NIFTY trades | VIX open |\n|---|---|---|---|---|---|")
    for n, s in enumerate(CHART_SESSIONS, 1):
        tt = [t for t in trades if t['sid'] == s]
        if not tt and not any(t['sid'] == s for t in trades):
            d = sql(f"select session_date::text d from trade.session where id = {s}")[0]['d']
        else: d = tt[0]['day']
        tt = [t for t in trades if t['day'] == d]
        nf = [t for t in tt if t['u'] == 'NIFTY']
        desc = '; '.join(f"{t['st'][:3]} {t['side']} {t['open'][:5]} {t['net'] / 1e3:+.1f}K" for t in nf)
        print(f"| {n} | {d} | {sum(t['net'] for t in tt):+,.0f} | {sum(t['net'] for t in nf):+,.0f} | {desc} | {days[(d, 'NIFTY')].vix} |")
    other = [t for t in trades if t['day'] not in CHART_DAYS]
    chart = [t for t in trades if t['day'] in CHART_DAYS]
    print(f"\nThe 11 chart days: {len(chart)} trades {sum(t['net'] for t in chart):+,.0f}; the other 18 days: {len(other)} trades {sum(t['net'] for t in other):+,.0f}\n")

    def score(name, pred):
        rem = [t for t in trades if pred(t)]
        h1 = sum(t['net'] for t in rem if t['day'] <= H1_END); h2 = sum(t['net'] for t in rem if t['day'] > H1_END)
        w = sum(1 for t in rem if t['net'] > 0)
        ch = sum(t['net'] for t in rem if t['day'] in CHART_DAYS)
        print(f"| {name} | {len(rem)} | {w} | {sum(t['net'] for t in rem):+,.0f} | {h1:+,.0f} | {h2:+,.0f} | {ch:+,.0f} | {sum(t['net'] for t in rem) - ch:+,.0f} |")
    print("### Filters (money removed: positive = the filter removes net PROFIT, so it hurts)\n")
    print("| filter | trades removed | winners | net removed | H1 | H2 | on chart days | on other days |\n|---|---|---|---|---|---|---|---|")
    BO = ('break-retest-runner', 'oi-burst-cross')
    score("F1 brt/obc entry before 10:00", lambda t: t['st'] in BO and t['tmin'] < hm('10:00'))
    score("F1 brt/obc entry before 10:15", lambda t: t['st'] in BO and t['tmin'] < hm('10:15'))
    for th in (0.35, 0.50):
        score(f"F2 > {th} % beyond VWAP or EMA20 3m", lambda t, th=th: (t['xv'] is not None and t['xv'] > th) or (t['xe'] is not None and t['xe'] > th))
        score(f"F2 > {th} % beyond VWAP only", lambda t, th=th: t['xv'] is not None and t['xv'] > th)
    for pts in (15, 20):
        score(f"F3 within {pts} pts of a 4x+ wall / PDH-ORH (PDL-ORL)", lambda t, pts=pts: t['wall'] is not None and t['wall'] <= pts * SCALE[t['u']])
    print()
    hv = [t for t in trades if t['vix'] is not None and t['vix'] > 16]
    for f in (0.5, 0.7):
        chg = sum(t['net'] * (f - 1) for t in hv)
        h1 = sum(t['net'] * (f - 1) for t in hv if t['day'] <= H1_END); h2 = chg - h1
        print(f"F4 VIX open > 16 -> size x {f}: set net change {chg:+,.0f} (H1 {h1:+,.0f} / H2 {h2:+,.0f}) on {len(hv)} trades")
    print("\nDay nets by VIX at the open (all 29 days):\n\n| VIX open | days | trades | net | per day | loss days |\n|---|---|---|---|---|---|")
    dn = defaultdict(float); dv = {}
    for t in trades: dn[t['day']] += t['net']; dv[t['day']] = t['vix']
    for lo, hi in ((0, 14), (14, 15), (15, 16), (16, 17), (17, 99)):
        ds = [d for d in dn if dv[d] is not None and lo <= dv[d] < hi]
        if ds:
            print(f"| {lo}-{hi} | {len(ds)} | {sum(1 for t in trades if t['day'] in ds)} | {sum(dn[d] for d in ds):+,.0f} | {sum(dn[d] for d in ds) / len(ds):+,.0f} | {sum(1 for d in ds if dn[d] < 0)} |")
    print("\nPer-trade detail on the chart days (NIFTY):\n\n| date | strategy | side | open | net | ext VWAP % | ext EMA3 % | nearest wall pts |\n|---|---|---|---|---|---|---|---|")
    for t in trades:
        if t['day'] in CHART_DAYS and t['u'] == 'NIFTY':
            f = lambda x: '-' if x is None else f"{x:+.2f}"
            print(f"| {t['day']} | {t['st']} | {t['side']} | {t['open'][:5]} | {t['net']:+,.0f} | {f(t['xv'])} | {f(t['xe'])} | {'-' if t['wall'] is None else round(t['wall'])} |")

def batch_trades(days_cache):
    rows = sql("""select p.session_id sid, s.session_date::text as day, p.underlying u, p.option_side side, p.strategy_id st,
           to_char(p.opened_at at time zone 'Asia/Kolkata', 'HH24:MI:SS') open, p.net::float net
           from trade.position p join trade.session s on s.id = p.session_id where s.mode = 'PAPER_REPLAY' and s.id between 35390 and 35502
           and s.strategy_hash = (select strategy_hash from trade.session where id = 35441) order by p.opened_at""")
    out = []
    for r in rows:
        d, u = r['day'], r['u']
        if (d, u) not in days_cache: days_cache[(d, u)] = Day(d, u)
        D = days_cache[(d, u)]
        t = dict(sid=int(r['sid']), day=d, u=u, side=r['side'], st=r['st'], open=r['open'], net=float(r['net']))
        t['tmin'] = hm(r['open']); dr = 1 if r['side'] == 'CE' else -1
        prev = [m for m in D.ohlc if m < t['tmin']]
        spot = D.ohlc[max(prev)][3] if prev else D.ohlc[min(D.ohlc)][0]
        vw = D.vwap.get(max(prev)) if prev else None
        e3 = D.ema3_at(t['tmin'])
        t['xv'] = dr * (spot - vw) / spot * 100 if vw else None
        t['xe'] = dr * (spot - e3) / spot * 100 if e3 else None
        m = max(prev) if prev else t['tmin']
        if dr == 1:
            lv = [z[0] for z in D.zones_at(m, 'R', 4)] + [D.prior['h']] + ([D.orh] if m >= hm('09:30') else [])
            ds = [x - spot for x in lv if x - spot >= 0]
        else:
            lv = [z[1] for z in D.zones_at(m, 'S', 4)] + [D.prior['l']] + ([D.orl] if m >= hm('09:30') else [])
            ds = [spot - x for x in lv if spot - x >= 0]
        t['wall'] = min(ds) if ds else None
        t['vix'] = D.vix
        out.append(t)
    return out

# ------------------------------------------------------------------ Part B
def b1_signals(D, as_written=False):
    b = D.bars; n = len(b); sc = SCALE[D.u]; eps, room_min = 4 * sc, 25 * sc
    H = [x['h'] for x in b]; L = [x['l'] for x in b]
    sh = [None] * n; sl = [None] * n
    for i in range(3, n - 3):
        if H[i] == max(H[i - 3:i + 4]): sh[i] = H[i]
        if L[i] == min(L[i - 3:i + 4]): sl[i] = L[i]
    out = []
    for t in range(60 if as_written else 1, n):
        vs, ve = max(0, t - 60), t - 3
        o, h, l, c = b[t]['o'], b[t]['h'], b[t]['l'], b[t]['c']
        rng = h - l
        if rng == 0 or ve < vs: continue
        vw, em = b[t]['vwap'], b[t]['ema']
        if vw is None or em is None: continue
        up_w, lo_w = h - max(o, c), min(o, c) - l
        hs = [(sh[j], j) for j in range(vs, ve + 1) if sh[j] is not None]
        if hs:
            lh = max(x for x, _ in hs); idx = max(j for x, j in hs if x == lh)
            if t - idx >= 6 and max(h, H[t - 1]) >= lh - eps and c < lh and (up_w / rng >= 0.45 or (c < o and c < L[t - 1])) \
                    and c - max(vw, em) >= room_min:
                out.append(dict(bar=t, m=b[t]['end'], dir=-1, level=lh, stop=max(h, H[t - 1]) + 5 * sc, target=max(vw, em))); continue
        ls = [(sl[j], j) for j in range(vs, ve + 1) if sl[j] is not None]
        if ls:
            ll = min(x for x, _ in ls); idx = max(j for x, j in ls if x == ll)
            if t - idx >= 6 and min(l, L[t - 1]) <= ll + eps and c > ll and (lo_w / rng >= 0.45 or (c > o and c > H[t - 1])) \
                    and min(vw, em) - c >= room_min:
                out.append(dict(bar=t, m=b[t]['end'], dir=1, level=ll, stop=min(l, L[t - 1]) - 5 * sc, target=min(vw, em)))
    return out

def oi_boost(D, s):
    st = STEP[D.u]; k = int(round(s['level'] / st) * st); side = 'CE' if s['dir'] == -1 else 'PE'
    ch = D.chain.get(f"{k} {side}", {})
    def oi_at(m):
        for x in range(m, m - 10, -1):
            v = ch.get(mm(x))
            if v and v[0]: return v[0]
    a, z = oi_at(s['m'] - 3), oi_at(s['m'])
    return a is not None and z is not None and z > a

def b2_signals(D):
    b = D.bars; sc = SCALE[D.u]; out = []
    for t in range(7, len(b)):
        w = b[t - 7:t + 1]
        if any(x['vwap'] is None or x['ema'] is None for x in w): continue
        hi, lo = max(x['h'] for x in w), min(x['l'] for x in w)
        if hi - lo >= 25 * sc: continue
        nxt_end = b[t + 1]['end'] if t + 1 < len(b) else hm('15:29')
        for dr, ok in ((1, all(x['c'] > x['vwap'] and x['c'] > x['ema'] for x in w)), (-1, all(x['c'] < x['vwap'] and x['c'] < x['ema'] for x in w))):
            if not ok: continue
            trig = hi + 3 * sc if dr == 1 else lo - 3 * sc
            for m in range(b[t]['end'] + 1, nxt_end + 1):
                o = D.ohlc.get(m)
                if o and ((dr == 1 and o[1] >= trig) or (dr == -1 and o[2] <= trig)):
                    out.append(dict(m=m, dir=dr, stop=lo if dr == 1 else hi, target=None, trail=True)); break
    return out

def simulate(D, sigs):
    trades, busy = [], -1
    for s in sorted(sigs, key=lambda x: x['m']):
        if s['m'] < busy: continue
        e = s['m'] + 1
        if e > hm('15:14'): continue
        prev = [m for m in D.ohlc if m <= s['m']]; spot = D.ohlc[max(prev)][3]
        st = STEP[D.u]; k = int(round(spot / st) * st); side = 'CE' if s['dir'] == 1 else 'PE'
        entry = None
        for x in (e, e + 1, e + 2):
            q = D.opt(k, side, x)
            if q and q[4]: entry, e = q[4], x; break
        if entry is None: continue
        xt = xp = why = None; peak = None
        for m in range(e + 1, hm('15:16')):
            o = D.ohlc.get(m); q = D.opt(k, side, m)
            if o is None or q is None or q[1] is None: continue
            bl, blo, bhi = q[1], q[2] or q[1], q[3] or q[1]
            if m >= hm('15:15'): xt, xp, why = m, bl, 'FLAT'; break
            hit_stop = (o[2] <= s['stop']) if s['dir'] == 1 else (o[1] >= s['stop'])
            if hit_stop: xt, xp, why = m, blo, 'STOP'; break
            if s.get('target') is not None and ((o[1] >= s['target']) if s['dir'] == 1 else (o[2] <= s['target'])):
                xt, xp, why = m, bl, 'TARGET'; break
            if s.get('trail'):
                if peak is not None and peak >= entry * 1.2 and blo <= peak * 0.85: xt, xp, why = m, min(peak * 0.85, bhi), 'TRAIL'; break
                peak = max(peak or bhi, bhi)
        if xt is None: continue
        lot = LOT[D.u]
        net = (xp - entry) * lot - costs(entry * lot, xp * lot, D.u)
        trades.append(dict(d=D.d, u=D.u, t=s['m'], e=e, xt=xt, dir=s['dir'], k=k, entry=entry, exit=xp, why=why, net=net,
                           boost=s.get('boost')))
        busy = xt + 1
    return trades

def report(name, tr):
    if not tr: print(f"| {name} | 0 | | | | | | | | |"); return
    net = sum(t['net'] for t in tr); w = sum(1 for t in tr if t['net'] > 0)
    gp = sum(t['net'] for t in tr if t['net'] > 0); gl = -sum(t['net'] for t in tr if t['net'] < 0)
    h1 = sum(t['net'] for t in tr if t['d'] <= H1_END); h2 = net - h1
    byd = defaultdict(float)
    for t in tr: byd[t['d']] += t['net']
    best = max(byd.values()); ch = sum(t['net'] for t in tr if t['d'] in CHART_DAYS)
    print(f"| {name} | {len(tr)} | {w / len(tr):.0%} | {net:+,.0f} | {gp / gl if gl else float('inf'):.2f} | {h1:+,.0f} | {h2:+,.0f} | {net - best:+,.0f} | {ch:+,.0f} | {net - ch:+,.0f} |")
    return dict(n=len(tr), net=net, pf=gp / gl if gl else 99, h1=h1, h2=h2, wob=net - best, other=net - ch)

def verdict(name, r):
    if not r: print(f"- **{name}**: no trades -> FAIL"); return
    c = [r['n'] >= 30, r['net'] > 0 and r['pf'] >= 1.3, r['h1'] > 0 and r['h2'] > 0, r['wob'] > 0, r['other'] > 0]
    print(f"- **{name}**: (1) trades {r['n']} {'ok' if c[0] else 'no'}; (2) net {r['net']:+,.0f} PF {r['pf']:.2f} {'ok' if c[1] else 'no'}; "
          f"(3) halves {r['h1']:+,.0f} / {r['h2']:+,.0f} {'ok' if c[2] else 'no'}; (4) w/o best day {r['wob']:+,.0f} {'ok' if c[3] else 'no'}; "
          f"(5) non-chart days {r['other']:+,.0f} {'ok' if c[4] else 'no'} -> **{'PASS' if all(c) else 'FAIL'}**")

def main():
    days = {}
    trades = batch_trades(days)
    part_a(trades, days)
    alldays = sorted(f[:10] for f in os.listdir(A386.CACHE) if f.endswith('_NIFTY.json'))
    R = defaultdict(list)
    for d in alldays:
        for u in ('NIFTY', 'SENSEX'):
            if not os.path.exists(f"{A386.CACHE}/{d}_{u}.json"): print(f"missing {d} {u}", file=sys.stderr); continue
            D = days.get((d, u)) or Day(d, u); days[(d, u)] = D
            s1 = b1_signals(D)
            for s in s1: s['boost'] = oi_boost(D, s)
            R['B1 sweep'] += simulate(D, s1)
            R['B1 as written (from 12:15)'] += simulate(D, b1_signals(D, as_written=True))
            R['B1 + OI booster (reported)'] += simulate(D, [s for s in s1 if s['boost']])
            R['B2 coil breakout'] += simulate(D, b2_signals(D))
    print(f"\n## Part B: the pasted rules on {len(alldays)} days x 2 indices (1 lot, after costs)\n")
    print("| rule | trades | win | net | PF | H1 | H2 | w/o best day | chart days | other days |\n|---|---|---|---|---|---|---|---|---|---|")
    res = {k: report(k, v) for k, v in R.items()}
    print("\n## Registered verdicts\n")
    verdict('B1 sweep', res['B1 sweep']); verdict('B2 coil breakout', res['B2 coil breakout'])
    for k, v in R.items():
        ex = defaultdict(int)
        for t in v: ex[t['why']] += 1
        print(f"\n{k} exits: {dict(ex)}; by index: " + ", ".join(f"{u} {sum(t['net'] for t in v if t['u'] == u):+,.0f} ({sum(1 for t in v if t['u'] == u)})" for u in ('NIFTY', 'SENSEX'))
             + "; by side: " + ", ".join(f"{s} {sum(t['net'] for t in v if t['dir'] == dv):+,.0f} ({sum(1 for t in v if t['dir'] == dv)})" for s, dv in (('CE', 1), ('PE', -1))))
    json.dump({k: v for k, v in R.items()}, open('.local/a391/trades.json', 'w'))
    print("\n### B1 trades on the chart days (NIFTY)\n\n| date | signal | side | entry | exit | why | net |\n|---|---|---|---|---|---|---|")
    for t in R['B1 sweep']:
        if t['d'] in CHART_DAYS and t['u'] == 'NIFTY':
            print(f"| {t['d']} | {mm(t['t'])} | {'CE' if t['dir'] == 1 else 'PE'} {t['k']} | {mm(t['e'])} {t['entry']:.1f} | {mm(t['xt'])} {t['exit']:.1f} | {t['why']} | {t['net']:+,.0f} |")

if __name__ == '__main__':
    main()
