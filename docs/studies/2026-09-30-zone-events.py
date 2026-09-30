"""Zone map and zone events, as registered in docs/studies/2026-09-30-zone-events.md.

Inputs: engine feature snapshots (features v7, one CSV per index-day) and zt option snapshots (opt.csv).
Usage: python3 zone_events.py FEAT_DIR OPT_CSV OUT_DIR [merge_atr] [window]
"""
import bisect, csv, datetime as dt, math, os, statistics, sys
from collections import defaultdict

FEAT, OPT, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
MERGE = float(sys.argv[4]) if len(sys.argv) > 4 else 0.5
WIN = int(sys.argv[5]) if len(sys.argv) > 5 else 10
# post-results data check (not in the registration): a snapshot whose last index tick is older than STALE s
# counts as missing; 0 = as registered
STALE = float(sys.argv[6]) if len(sys.argv) > 6 else 0

TEST = ['2026-09-11', '2026-09-15', '2026-09-16', '2026-09-17', '2026-09-18', '2026-09-21', '2026-09-28', '2026-09-29']
HELD = ['2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25']
DESIGN = ['2026-09-30']
GROUP = {**{d: 'test' for d in TEST}, **{d: 'held-out' for d in HELD}, **{d: 'design' for d in DESIGN}}
KEY = {'NIFTY': 'NSE:INDEX:NIFTY', 'SENSEX': 'BSE:INDEX:SENSEX'}
STEP = {'NIFTY': 50, 'SENSEX': 100}
LOT = {'NIFTY': 65, 'SENSEX': 20}
EXCH = {'NIFTY': 0.0003503, 'SENSEX': 0.000325}
LEVELS = [('ORH', 'structure.orHigh'), ('ORL', 'structure.orLow'), ('PDH', 'structure.pdh'), ('PDL', 'structure.pdl'),
          ('pORH', 'structure.prevOrHigh'), ('pORL', 'structure.prevOrLow'), ('OPEN', 'structure.dayOpen'),
          ('PCLOSE', 'structure.prevClose')]
HORIZONS = [5, 15, 30]
T_START, T_END = dt.time(9, 30), dt.time(14, 45)


def num(v):
    try:
        x = float(v)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def costs(u, buy, sell, q):
    """costs v1 (india-index-options-costs.v1.yaml): ₹20 per order, STT 0.1 % sell, exchange, SEBI, stamp, GST."""
    b, s = buy * q, sell * q
    brokerage = 40.0
    exch = (b + s) * EXCH[u]
    sebi = (b + s) * 0.000001
    return brokerage + s * 0.001 + exch + sebi + (brokerage + exch + sebi) * 0.18 + b * 0.00003


def minute(t):
    return t.hour * 60 + t.minute


# ------------------------------------------------------------------ option snapshots
book = defaultdict(lambda: defaultdict(lambda: ([], [])))   # (day, u) -> (strike, side) -> (secs, (bid, ask))
inv = {v: k for k, v in KEY.items()}
for r in csv.reader(open(OPT)):
    ts = dt.datetime.fromisoformat(r[0])
    bid, ask = num(r[5]), num(r[6])
    if bid is None or ask is None or bid <= 0 or ask < bid:
        continue
    d = ts.date().isoformat()
    secs = ts.hour * 3600 + ts.minute * 60 + ts.second + ts.microsecond / 1e6
    b = book[(d, inv[r[1]])][(float(r[2]), r[3])]
    b[0].append(secs)
    b[1].append((bid, ask))
for per in book.values():
    for k, (s, q) in list(per.items()):
        order = sorted(range(len(s)), key=s.__getitem__)
        per[k] = ([s[i] for i in order], [q[i] for i in order])


def first_after(series, secs, within=None):
    i = bisect.bisect_left(series[0], secs)
    if i >= len(series[0]):
        return None
    if within is not None and series[0][i] - secs > within:
        return None
    return i


def outcome(d, u, t_close, direction, spot):
    """Buy the ATM option of the event's direction 0-90 s after the close; exits at +5/+15/+30 minutes."""
    side = 'CE' if direction > 0 else 'PE'
    strike = round(spot / STEP[u]) * STEP[u]
    series = book.get((d, u), {}).get((float(strike), side))
    if not series:
        return None
    t0 = t_close.hour * 3600 + t_close.minute * 60
    i = first_after(series, t0, 90)
    if i is None:
        return None
    entry_secs, (_, ask) = series[0][i], series[1][i]
    res = {'strike': strike, 'side': side, 'entry': ask, 'entry_time': entry_secs}
    j30 = first_after(series, entry_secs + 30 * 60)
    gap_ok = j30 is not None and all(series[0][k + 1] - series[0][k] <= 120 for k in range(i, j30))
    for h in HORIZONS:
        j = first_after(series, entry_secs + h * 60)
        ok = j is not None and all(series[0][k + 1] - series[0][k] <= 120 for k in range(i, j)) \
            and series[0][j] - (entry_secs + h * 60) <= 120
        if ok:
            bid = series[1][j][0]
            res[f'ret{h}'] = bid / ask - 1
            res[f'net{h}'] = (bid - ask) * LOT[u] - costs(u, ask, bid, LOT[u])
        else:
            res[f'ret{h}'] = res[f'net{h}'] = None
    if gap_ok:
        bids = [series[1][k][0] for k in range(i + 1, j30 + 1)]
        res['mfe'] = max(bids) / ask - 1
        res['mae'] = min(bids) / ask - 1
    else:
        res['mfe'] = res['mae'] = None
    return res


# ------------------------------------------------------------------ index-days
events, baseline, maps, dropped = [], [], [], []
for d in TEST + HELD + DESIGN:
    for u in KEY:
        path = os.path.join(FEAT, f'{d}-{u}.csv')
        if not os.path.exists(path):
            dropped.append((d, u, 'no feature file'))
            continue
        rows = list(csv.DictReader(open(path)))
        snaps = {}
        for r in rows:
            t = dt.time.fromisoformat(r['time'][:5])
            snaps[minute(t)] = r
        # 1-minute close of the bar ending at minute m = spot of the snapshot at m
        close = {m: num(r['spot']) for m, r in snaps.items() if num(r['spot']) is not None
                 and not (STALE and (num(r.get('secondsSinceSpot')) or 0) > STALE)}
        missing = [m for m in range(minute(T_START), minute(T_END) + 1) if m not in close]
        z0 = next((r for m, r in sorted(snaps.items()) if m >= minute(T_START)
                   and r.get('structure.orComplete') == 'true'), None)
        atr = num(z0['structure.atr3m']) if z0 else None
        if len(missing) > 5 or z0 is None or atr is None or num(z0['structure.orHigh']) is None:
            dropped.append((d, u, f'missing minutes {len(missing)}, OR/ATR at 09:30 '
                                  f'{"ok" if z0 is not None and atr is not None else "missing"}'))
            continue
        lv = sorted([(num(z0[c]), n) for n, c in LEVELS if num(z0[c]) is not None])
        zones = []
        for price, name in lv:
            if zones and price - zones[-1]['hi'] <= MERGE * atr:
                zones[-1]['hi'] = price
                zones[-1]['names'].append(name)
            else:
                zones.append({'lo': price, 'hi': price, 'names': [name]})
        band = 0.25 * atr
        maps.append((d, u, atr, zones))
        expiry = num(z0.get('regime.dteTradingDays')) == 0

        def attrs(m, direction):
            r = snaps.get(m, {})
            side = 'Ce' if direction > 0 else 'Pe'
            wall = None
            return {
                'fut_oi3m': num(r.get('futures.oiChange3m')), 'iv3m': num(r.get('options.atmIvChange3m')),
                'resp': num(r.get(f'options.premiumResponse{side}')), 'breadth': num(r.get('breadth.moverBreadth')),
                'rvol': num(r.get('futures.rvolTod')), 'call_wall': num(r.get('options.callBarrierStrike')),
                'put_wall': num(r.get('options.putSupportStrike')),
            }

        def emit(kind, zi, m, direction, extra=''):
            t = dt.time(m // 60, m % 60)
            if not (minute(T_START) < m <= minute(T_END)):
                return
            z = zones[zi]
            spot = close[m]
            o = outcome(d, u, t, direction, spot)
            moves = {}
            for h in HORIZONS:
                c2 = close.get(m + h)
                moves[f'idx{h}'] = None if c2 is None else (c2 - spot) * direction
            a = attrs(m, direction)
            wall_in = None
            if direction > 0 and a['call_wall'] is not None:
                wall_in = z['lo'] - STEP[u] <= a['call_wall'] <= z['hi'] + STEP[u]
            if direction < 0 and a['put_wall'] is not None:
                wall_in = z['lo'] - STEP[u] <= a['put_wall'] <= z['hi'] + STEP[u]
            events.append({'day': d, 'u': u, 'group': GROUP[d], 'expiry': expiry, 'kind': kind, 'extra': extra,
                           'time': t.strftime('%H:%M'), 'dir': direction, 'zone': '/'.join(z['names']),
                           'zlo': z['lo'], 'zhi': z['hi'], 'strength': len(z['names']), 'spot': spot, 'atr': atr,
                           'wall_in_zone': wall_in, **a, **moves, **(o or {}), 'priced': o is not None})

        ms = sorted(close)
        for zi, z in enumerate(zones):
            lo, hi = z['lo'], z['hi']
            failed = {1: False, -1: False}
            hold_wait = None                  # (direction, start minute) of a running test
            for m in ms:
                if m < minute(T_START) or m > minute(T_END):
                    continue
                prev = [close.get(m - k) for k in range(1, 11)]
                if any(p is None for p in prev):
                    continue
                c = close[m]
                # breaks
                for direction, beyond, stayed in ((1, c > hi, all(p <= hi for p in prev)),
                                                  (-1, c < lo, all(p >= lo for p in prev))):
                    if not (beyond and stayed):
                        continue
                    emit('BREAK', zi, m, direction)
                    if failed[direction]:
                        emit('SECOND_BREAK', zi, m, direction)
                    fail_at = None
                    for k in range(1, WIN + 1):
                        c2 = close.get(m + k)
                        if c2 is not None and ((direction > 0 and c2 <= hi) or (direction < 0 and c2 >= lo)):
                            fail_at = m + k
                            break
                    if fail_at is not None:
                        failed[direction] = True
                        emit('FAIL', zi, fail_at, -direction, extra=f'break {m // 60:02d}:{m % 60:02d}')
                    else:
                        emit('ACCEPT', zi, m + WIN, direction, extra=f'break {m // 60:02d}:{m % 60:02d}')
                # holds (tests from one side that do not close through the zone)
                if hold_wait is None:
                    if all(p > hi + band for p in prev) and lo <= c <= hi + band:
                        hold_wait = (1, m)
                    elif all(p < lo - band for p in prev) and lo - band <= c <= hi:
                        hold_wait = (-1, m)
                else:
                    direction, start = hold_wait
                    if direction > 0 and c < lo or direction < 0 and c > hi:
                        hold_wait = None
                    elif direction > 0 and c >= hi + 2 * band or direction < 0 and c <= lo - 2 * band:
                        emit('HOLD', zi, m, direction, extra=f'test {start // 60:02d}:{start % 60:02d}')
                        hold_wait = None
                    elif m - start >= WIN:
                        hold_wait = None

        # baseline: every 5th minute 09:35-14:45, both directions
        for m in range(minute(dt.time(9, 35)), minute(T_END) + 1, 5):
            if m not in close:
                continue
            for direction in (1, -1):
                o = outcome(d, u, dt.time(m // 60, m % 60), direction, close[m])
                if o:
                    baseline.append({'day': d, 'u': u, 'group': GROUP[d], 'dir': direction, **o})

# ------------------------------------------------------------------ output
os.makedirs(OUT, exist_ok=True)
cols = ['group', 'day', 'u', 'expiry', 'kind', 'extra', 'time', 'dir', 'zone', 'zlo', 'zhi', 'strength', 'spot', 'atr',
        'strike', 'side', 'entry', 'idx5', 'idx15', 'idx30', 'ret5', 'ret15', 'ret30', 'net5', 'net15', 'net30', 'mfe',
        'mae', 'fut_oi3m', 'iv3m', 'resp', 'breadth', 'rvol', 'wall_in_zone', 'priced']
with open(os.path.join(OUT, 'events.csv'), 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=cols, extrasaction='ignore')
    w.writeheader()
    for e in events:
        w.writerow(e)
with open(os.path.join(OUT, 'zones.txt'), 'w') as f:
    for d, u, atr, zones in maps:
        f.write(f'{d} {u} ATR3m {atr:.1f}: ' + '  '.join(
            f"[{z['lo']:.1f}-{z['hi']:.1f} {'/'.join(z['names'])}]" for z in zones) + '\n')
    for x in dropped:
        f.write(f'DROPPED {x}\n')


def summary(rows, key):
    v = [r[key] for r in rows if r.get(key) is not None]
    if not v:
        return '   n=0'
    wins = sum(1 for x in v if x > 0)
    return f'n={len(v):3d} win {wins:3d} ({100 * wins / len(v):3.0f}%) mean {statistics.mean(v):8.0f} ' \
           f'median {statistics.median(v):7.0f} total {sum(v):9.0f}'


print(f'merge {MERGE} x ATR3m, window {WIN} min, stale check {STALE or "off"}; index-days used {len(maps)}, dropped {len(dropped)}')
for x in dropped:
    print('  dropped', x)
for g in ('test', 'held-out', 'design'):
    print(f'\n=== {g}')
    base = [b for b in baseline if b['group'] == g]
    print(f'  {"BASELINE":13s} +15: {summary(base, "net15")}\n  {"":13s} +30: {summary(base, "net30")}')
    for kind in ('BREAK', 'FAIL', 'ACCEPT', 'SECOND_BREAK', 'HOLD'):
        ev = [e for e in events if e['group'] == g and e['kind'] == kind]
        pr = [e for e in ev if e['priced']]
        print(f'  {kind:13s} events {len(ev):3d} priced {len(pr):3d}')
        for h in (5, 15, 30):
            print(f'  {"":13s} +{h:2d}: {summary(pr, f"net{h}")}')
