"""Premise test registered in docs/CALIBRATION-egb.md: does 'long gamma has been paying' predict ATM buying?"""
import bisect, csv, datetime as dt, math, random, statistics
S = '/private/tmp/claude-501/-Users-satyasiripuram-Desktop-Dev-Code-auto-trade/90ead7d7-953d-4c9d-bc29-cabd8028a9b7/scratchpad/ticks/'
DAYS = [('2026-09-15', 'NIFTY'), ('2026-09-17', 'SENSEX'), ('2026-09-22', 'NIFTY'), ('2026-09-24', 'SENSEX')]
LOT = {'NIFTY': 65, 'SENSEX': 20}
EXCH = {'NIFTY': 0.0003503, 'SENSEX': 0.000325}
H = [5, 10, 15]

def num(r, k):
    try:
        x = float(r.get(k) or '')
        return x if math.isfinite(x) else None
    except ValueError:
        return None

def costs(u, buy, sell, q):
    b, s = buy * q, sell * q
    ex = (b + s) * EXCH[u]; sebi = (b + s) * 1e-6; br = 40.0
    return br + s * 0.001 + ex + sebi + (br + ex + sebi) * 0.18 + b * 0.00003

def secs(t):
    return t.hour * 3600 + t.minute * 60 + t.second + t.microsecond / 1e6

rows = {'CE': [], 'PE': []}
for day, u in DAYS:
    book = {}
    for r in csv.reader(open(S + f'{day}-{u}.csv')):
        if not r[3] or not r[4] or float(r[3]) <= 0 or float(r[4]) <= 0:
            continue
        k = (float(r[1]), r[2]); book.setdefault(k, ([], []))
        book[k][0].append(secs(dt.time.fromisoformat(r[0]))); book[k][1].append((float(r[3]), float(r[4])))
    def quote(strike, side, target):
        b = book.get((strike, side))
        if not b:
            return None
        i = bisect.bisect_left(b[0], target)
        return None if i >= len(b[0]) or b[0][i] - target > 10 else b[1][i]
    for r in csv.DictReader(open(f'.local/features-egb/{day}-{u}.csv')):
        t = r['time'][:5]
        if not ('09:30' <= t < '14:45') or r['regime.dteTradingDays'] != '0':
            continue
        base = secs(dt.time.fromisoformat(t))
        for side in ('CE', 'PE'):
            strike = num(r, f'gamma.atm{side[0]}{side[1].lower()}Strike') or num(r, 'options.atmStrike')
            pnl = num(r, f'gamma.gammaPnl{side[0]}{side[1].lower()}')
            if strike is None or pnl is None:
                continue
            outs = {}
            a = quote(strike, side, base + 0.25)
            for h in H:
                b = quote(strike, side, base + 60 * h)
                outs[h] = None if a is None or b is None else (b[0] - a[1]) * LOT[u] - costs(u, a[1], b[0], LOT[u])
            rows[side].append((day, u, t, pnl > 0, outs))

rng = random.Random(11)
print(f"{'side':4s} {'H':>3s} {'n all':>6s} {'mean all':>9s} {'n paying':>9s} {'mean paying':>12s} {'diff':>7s} {'95% CI of diff':>18s} {'win% paying':>11s}")
for side in ('CE', 'PE'):
    for h in H:
        allv = [o[h] for *_, p, o in rows[side] if o[h] is not None]
        pay = [o[h] for *_, p, o in rows[side] if p and o[h] is not None]
        rest = [o[h] for *_, p, o in rows[side] if not p and o[h] is not None]
        if len(pay) < 5:
            print(f"{side:4s} {h:3d} too few paying minutes ({len(pay)})"); continue
        diff = statistics.mean(pay) - statistics.mean(rest)
        boots = []
        for _ in range(3000):
            bp = [rng.choice(pay) for _ in pay]; br = [rng.choice(rest) for _ in rest]
            boots.append(statistics.mean(bp) - statistics.mean(br))
        boots.sort()
        lo, hi = boots[int(0.025 * len(boots))], boots[int(0.975 * len(boots))]
        print(f"{side:4s} {h:3d} {len(allv):6d} {statistics.mean(allv):9.0f} {len(pay):9d} {statistics.mean(pay):12.0f} "
              f"{diff:7.0f} [{lo:7.0f}, {hi:7.0f}] {100 * sum(x > 0 for x in pay) / len(pay):10.0f}%")
share = {s: sum(p for *_, p, o in rows[s]) / max(1, len(rows[s])) for s in rows}
print(f"minutes with gamma paying: CE {100*share['CE']:.0f}%, PE {100*share['PE']:.0f}% of {len(rows['CE'])}/{len(rows['PE'])} minutes")
