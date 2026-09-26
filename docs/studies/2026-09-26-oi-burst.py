"""Event study registered in docs/studies/2026-09-26-volume-oi-level.md."""
import bisect, csv, datetime as dt, glob, io, math, random, statistics, subprocess, sys

ZTQ = '/private/tmp/claude-501/-Users-satyasiripuram-Desktop-Dev-Code-auto-trade/ddf6219f-3a48-4750-bf50-2fd6e67d7513/scratchpad/ztq.sh'
SESSIONS = ['2026-09-11', '2026-09-15', '2026-09-16', '2026-09-17', '2026-09-18',
            '2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25']
KEY = {'NIFTY': 'NSE:INDEX:NIFTY', 'SENSEX': 'BSE:INDEX:SENSEX'}
import os
if os.environ.get('ONLY'):
    KEY = {u: KEY[u] for u in os.environ['ONLY'].split(',')}
if os.environ.get('EXCLUDE'):
    SESSIONS = [s for s in SESSIONS if s not in os.environ['EXCLUDE'].split(',')]
LOT = {'NIFTY': 65, 'SENSEX': 20}
EXCH = {'NIFTY': 0.0003503, 'SENSEX': 0.000325}
HORIZONS = [5, 10, 15, 30]
RULES = ['MOMENTUM', 'FADE', 'OI_WRITERS', 'LEVEL_SIDE', 'STRADDLE']
CONT = ('OPENING_DISCOVERY', 'TREND_WINDOW', 'MIDDAY', 'AFTERNOON', 'LATE')
T0, T1 = dt.time(9, 30), dt.time(14, 45)

def num(r, k):
    try:
        x = float(r.get(k) or '')
        return x if math.isfinite(x) else None
    except ValueError:
        return None

def costs(u, buy, sell, qty):
    b, s = buy * qty, sell * qty
    exch = (b + s) * EXCH[u]
    sebi = (b + s) * 0.000001
    brokerage = 40.0
    return brokerage + s * 0.001 + exch + sebi + (brokerage + exch + sebi) * 0.18 + b * 0.00003

# ---------------------------------------------------------------- features
minutes = []   # dicts per continuous minute
for s in SESSIONS:
    for u in KEY:
        path = f'.local/features-cal5/{s}-{u}.csv'
        for r in csv.DictReader(open(path)):
            if r['phase'] not in CONT:
                continue
            t = dt.time.fromisoformat(r['time'][:5])
            if not (T0 <= t < T1):
                continue
            atr = num(r, 'structure.atr3m')
            spot = num(r, 'spot')
            dists = [num(r, k) for k in ('structure.distOrhAtr', 'structure.distOrlAtr', 'structure.distPdhAtr',
                                         'structure.distPdlAtr')]
            for lvl in ('structure.lastSwingHigh', 'structure.lastSwingLow'):
                v = num(r, lvl)
                dists.append((spot - v) / atr if v and atr and spot else None)
            dists = [d for d in dists if d is not None]
            nearest = min(dists, key=abs) if dists else None
            ce, pe = num(r, 'options.ceOiChange1m'), num(r, 'options.peOiChange1m')
            ce3, pe3 = num(r, 'options.ceOiChange3m'), num(r, 'options.peOiChange3m')
            def burst(one, three):
                if one is None or three is None or three == 0:
                    return 0.0
                rate = three / 3
                return abs(one) if one * rate > 0 and one / rate >= 1.5 else 0.0
            minutes.append(dict(s=s, u=u, t=t, rvol=num(r, 'futures.rvolTod'),
                                oi=max(burst(ce, ce3), burst(pe, pe3)) if ce is not None and pe is not None else None,
                                oi1=max(abs(ce), abs(pe)) if ce is not None and pe is not None else None,
                                ce=ce, pe=pe, mom=num(r, 'futures.momentum3m'), near=nearest,
                                atm=num(r, 'options.atmStrike')))

# ---------------------------------------------------------------- option quotes
quotes = {}  # (s,u) -> {(strike, side): ([times], [(bid, ask)])}
for s in SESSIONS:
    for u in KEY:
        sql = (f"copy (select captured_at::time, strike_price, side, bid_price, ask_price from option_tick_snapshots "
               f"where underlying_key='{KEY[u]}' and captured_at::date='{s}' and nearest_expiry "
               f"and bid_price > 0 and ask_price > 0 order by captured_at) to stdout with csv")
        out = subprocess.run([ZTQ, '-c', sql], capture_output=True, text=True, check=True).stdout
        book = {}
        for row in csv.reader(io.StringIO(out)):
            t = dt.time.fromisoformat(row[0][:8])
            k = (float(row[1]), row[2])
            book.setdefault(k, ([], []))
            book[k][0].append(t)
            book[k][1].append((float(row[3]), float(row[4])))
        quotes[(s, u)] = book

def quote_at(s, u, strike, side, t):
    book = quotes[(s, u)].get((strike, side))
    if not book:
        return None
    i = bisect.bisect_left(book[0], t)
    if i >= len(book[0]):
        return None
    if (dt.datetime.combine(dt.date.today(), book[0][i]) - dt.datetime.combine(dt.date.today(), t)).seconds > 120:
        return None  # no quote within 2 minutes: skip rather than fill on a stale price
    return book[1][i]

def add(t, m):
    return (dt.datetime.combine(dt.date.today(), t) + dt.timedelta(minutes=m)).time()

def trade(m, side, h):
    """Net rupees per lot for buying `side` (CE/PE/STRADDLE) at m's minute and exiting h minutes later."""
    sides = ['CE', 'PE'] if side == 'STRADDLE' else [side]
    total = 0.0
    for sd in sides:
        a = quote_at(m['s'], m['u'], m['atm'], sd, m['t'])
        b = quote_at(m['s'], m['u'], m['atm'], sd, add(m['t'], h))
        if a is None or b is None:
            return None
        buy, sell = a[1], b[0]
        q = LOT[m['u']]
        total += (sell - buy) * q - costs(m['u'], buy, sell, q)
    return total

def side_of(rule, m):
    if rule == 'STRADDLE':
        return 'STRADDLE'
    if rule in ('MOMENTUM', 'FADE'):
        if m['mom'] is None or m['mom'] == 0:
            return None
        up = m['mom'] > 0
        return ('CE' if up else 'PE') if rule == 'MOMENTUM' else ('PE' if up else 'CE')
    if rule == 'OI_WRITERS':
        if m['ce'] is None or m['pe'] is None:
            return None
        return 'CE' if m['pe'] > m['ce'] else 'PE'
    if rule == 'LEVEL_SIDE':
        return None if m['near'] is None else ('CE' if m['near'] > 0 else 'PE')

def pct(v, p):
    v = sorted(v); k = (len(v) - 1) * p / 100; lo, hi = math.floor(k), math.ceil(k)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)

def run(rvol_min, near_max, label):
    thr = {u: pct([m['oi1'] for m in minutes if m['u'] == u and m['oi1'] is not None], 80) for u in KEY}
    signals, last = [], {}
    for m in minutes:
        ok = (m['oi'] is not None and m['oi'] >= thr[m['u']] and m['atm']
              and (rvol_min is None or (m['rvol'] is not None and m['rvol'] >= rvol_min))
              and (near_max is None or (m['near'] is not None and abs(m['near']) <= near_max)))
        if not ok:
            continue
        key = (m['s'], m['u'])
        if key in last and (dt.datetime.combine(dt.date.today(), m['t'])
                            - dt.datetime.combine(dt.date.today(), last[key])).seconds < 900:
            continue
        last[key] = m['t']
        signals.append(m)
    per = {}
    for m in signals:
        per[(m['s'], m['u'])] = per.get((m['s'], m['u']), 0) + 1
    pool = {}
    for m in minutes:
        if m['atm']:
            pool.setdefault((m['s'], m['u']), []).append(m)
    print(f"\n=== {label}: RVOL >= {rvol_min}, near level <= {near_max} ATR, OI burst |dOI1m| >= p80 and >= 1.5x 3m pace "
          f"({', '.join(f'{u} {v:,.0f}' for u, v in thr.items())}) -> {len(signals)} signals in "
          f"{len(per)} index-sessions")
    print(f"{'rule':11s} {'H':>3s} {'n':>3s} {'win%':>5s} {'mean ₹':>8s} {'median':>8s} {'t':>5s} {'rand p50':>9s} {'pctile':>6s}")
    rng = random.Random(7)
    for rule in RULES:
        for h in HORIZONS:
            res = [x for x in (trade(m, side_of(rule, m), h) if side_of(rule, m) else None for m in signals) if x is not None]
            if len(res) < 2:
                print(f"{rule:11s} {h:3d} {len(res):3d}  too few")
                continue
            mean = statistics.mean(res)
            t = mean / (statistics.stdev(res) / math.sqrt(len(res))) if statistics.stdev(res) > 0 else float('nan')
            rand_means = []
            for _ in range(2000):
                draws = []
                for key, n in per.items():
                    for m in rng.sample(pool[key], min(n, len(pool[key]))):
                        sd = side_of(rule, m)
                        x = trade(m, sd, h) if sd else None
                        if x is not None:
                            draws.append(x)
                if draws:
                    rand_means.append(statistics.mean(draws))
            below = sum(r < mean for r in rand_means) / len(rand_means) * 100
            win = 100 * sum(x > 0 for x in res) / len(res)
            print(f"{rule:11s} {h:3d} {len(res):3d} {win:5.0f} {mean:8.0f} {statistics.median(res):8.0f} {t:5.2f} "
                  f"{statistics.median(rand_means):9.0f} {below:6.0f}")
    return signals

main = run(1.5, 0.25, 'Run A: OI burst + volume + level')
for m in main:
    print(f"   {m['s']} {m['u']:6s} {m['t']} rvol {m['rvol']:.2f} near {m['near']:+.2f} ATR mom {m['mom']} ce1m {m['ce']:+,.0f} pe1m {m['pe']:+,.0f}")
run(None, None, 'Run B: OI burst alone')
