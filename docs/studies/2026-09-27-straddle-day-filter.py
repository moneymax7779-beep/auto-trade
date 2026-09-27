"""docs/studies/2026-09-27-straddle-day-filter.md: rules exactly as registered."""
import bisect, csv, datetime as dt, math, statistics, sys
from collections import defaultdict

D = sys.argv[1]
SESSIONS = ['2026-09-03', '2026-09-08', '2026-09-10', '2026-09-15', '2026-09-17', '2026-09-18', '2026-09-22',
            '2026-09-23', '2026-09-24', '2026-09-25']
EXPIRY = {('2026-09-03', 'SENSEX'), ('2026-09-08', 'NIFTY'), ('2026-09-10', 'SENSEX'), ('2026-09-15', 'NIFTY'),
          ('2026-09-17', 'SENSEX'), ('2026-09-22', 'NIFTY'), ('2026-09-24', 'SENSEX')}
NEXT_EXPIRY = {'2026-09-03': {'NIFTY': '2026-09-08', 'SENSEX': '2026-09-03'},
               '2026-09-08': {'NIFTY': '2026-09-08', 'SENSEX': '2026-09-10'},
               '2026-09-10': {'NIFTY': '2026-09-15', 'SENSEX': '2026-09-10'},
               '2026-09-15': {'NIFTY': '2026-09-15', 'SENSEX': '2026-09-17'},
               '2026-09-17': {'NIFTY': '2026-09-22', 'SENSEX': '2026-09-17'},
               '2026-09-18': {'NIFTY': '2026-09-22', 'SENSEX': '2026-09-24'},
               '2026-09-22': {'NIFTY': '2026-09-22', 'SENSEX': '2026-09-24'},
               '2026-09-23': {'NIFTY': '2026-09-29', 'SENSEX': '2026-09-24'},
               '2026-09-24': {'NIFTY': '2026-09-29', 'SENSEX': '2026-09-24'},
               '2026-09-25': {'NIFTY': '2026-09-29', 'SENSEX': '2026-10-01'}}
STEP = {'NIFTY': 50, 'SENSEX': 100}
LOT = {'NIFTY': 65, 'SENSEX': 20}
EXCH = {'NIFTY': 0.0003503, 'SENSEX': 0.000325}
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
CAPITAL, BUDGET = 500000, 25000


def ms_at(day, hhmm):
    h, m = map(int, hhmm.split(':'))
    return int(dt.datetime.fromisoformat(day).replace(hour=h, minute=m, tzinfo=IST).timestamp() * 1000)


def ist(ms):
    return dt.datetime.fromtimestamp(ms / 1000, IST).strftime('%H:%M:%S')


def costs(u, buy, sell, qty):
    b, s = buy * qty, sell * qty
    exch = (b + s) * EXCH[u]
    sebi = (b + s) * 0.000001
    brokerage = 40.0
    return brokerage + s * 0.001 + exch + sebi + (brokerage + exch + sebi) * 0.18 + b * 0.00003


def minutes_to_expiry(day, expiry):
    d, e = dt.date.fromisoformat(day), dt.date.fromisoformat(expiry)
    later = sum(1 for n in range(1, (e - d).days + 1) if (d + dt.timedelta(n)).weekday() < 5)
    return 270 + 375 * later


def load_cash(day, u):
    t, p = [], []
    for row in csv.reader(open(f'{D}/f/cash-{day}-{u}.csv')):
        try:
            a, b = int(row[0]), float(row[1])
        except (ValueError, IndexError):
            continue
        if b > 0:
            t.append(a); p.append(b)
    return t, p


def load_book(day, u):
    book = defaultdict(lambda: ([], [], []))
    for row in csv.reader(open(f'{D}/f/opt-{day}-{u}.csv')):
        try:
            t, k, typ, bid, ask = int(row[0]), float(row[1]), row[2], float(row[3]), float(row[4])
        except (ValueError, IndexError):
            continue
        if bid > 0 and ask > 0:
            b = book[(k, typ)]
            b[0].append(t); b[1].append(bid); b[2].append(ask)
    return book


def quote_at(book, leg, ms):
    """Last quote at or before ms (for the 11:00 mid)."""
    times, bids, asks = book.get(leg, ([], [], []))
    i = bisect.bisect_right(times, ms) - 1
    return (bids[i], asks[i]) if i >= 0 and ms - times[i] <= 60000 else None


def decide(day, u, cash, book):
    t, p = cash
    open_i = bisect.bisect_left(t, ms_at(day, '09:15'))
    dec_ms = ms_at(day, '11:00')
    end_i = bisect.bisect_right(t, dec_ms)
    morning = p[open_i:end_i]
    spot = morning[-1]
    rm = max(morning) - min(morning)
    atm = round(spot / STEP[u]) * STEP[u]
    ce, pe = quote_at(book, (atm, 'CE'), dec_ms), quote_at(book, (atm, 'PE'), dec_ms)
    if ce is None or pe is None:
        return None
    s = (ce[0] + ce[1]) / 2 + (pe[0] + pe[1]) / 2
    T = minutes_to_expiry(day, NEXT_EXPIRY[day][u])
    need = 2 * math.sqrt(105 / T)
    eff = abs(spot - morning[0]) / rm if rm > 0 else 0
    return dict(spot=spot, open=morning[0], rm=rm, straddle=s, ratio=rm / s, need=need, T=T, eff=eff,
                F1=rm / s >= need, F2=eff >= 0.5, atm=atm)


def trade(day, u, book, dec, structure, cap):
    step = STEP[u]
    atm = dec['atm']
    legs = [(atm, 'CE'), (atm, 'PE')] if structure == 'STRADDLE' else [(atm + step, 'CE'), (atm - step, 'PE')]
    start = ms_at(day, '11:00') + 250
    entry = []
    for leg in legs:
        times, bids, asks = book.get(leg, ([], [], []))
        i = bisect.bisect_left(times, start)
        if i >= len(times) or times[i] - start > 60000:
            return None
        entry.append((i, asks[i], times[i]))
    cost = entry[0][1] + entry[1][1]
    lots = max(1, int(BUDGET // (cost * LOT[u])))
    q = lots * LOT[u]
    end = ms_at(day, '15:15')
    events = []
    for n, leg in enumerate(legs):
        times, bids, _ = book[leg]
        j = entry[n][0]
        while j < len(times) and times[j] <= end:
            events.append((times[j], n, bids[j]))
            j += 1
    events.sort()
    last = [book[legs[n]][1][entry[n][0]] for n in range(2)]
    reason, exit_ms, best, worst = 'TIME_1515', end, 0.0, 0.0
    for t, n, bid in events:
        last[n] = bid
        v = last[0] + last[1]
        best, worst = max(best, v / cost - 1), min(worst, v / cost - 1)
        if v >= 1.30 * cost:
            reason, exit_ms = 'TARGET_30', t
            break
        if v <= cap * cost:
            reason, exit_ms = f'STOP_{round((1 - cap) * 100)}', t
            break
    gross = sum((last[n] - entry[n][1]) * q for n in range(2))
    fees = sum(costs(u, entry[n][1], last[n], q) for n in range(2))
    return dict(day=day, u=u, structure=structure, strikes=f'{int(legs[0][0])}/{int(legs[1][0])}', lots=lots, qty=q,
                ce_ask=entry[0][1], pe_ask=entry[1][1], paid=cost * q, entry=ist(max(e[2] for e in entry)),
                exit=ist(exit_ms), reason=reason, ce_bid=last[0], pe_bid=last[1], ret=(sum(last) / cost - 1) * 100,
                best=best * 100, worst=worst * 100, gross=gross, fees=fees, net=gross - fees)


decisions, trades = {}, defaultdict(list)
for day in SESSIONS:
    for u in ('NIFTY', 'SENSEX'):
        book = load_book(day, u)
        dec = decide(day, u, load_cash(day, u), book)
        decisions[(day, u)] = dec
        if dec is None:
            continue
        for structure in ('STRADDLE', 'STRANGLE'):
            for cap in (0.80, 0.85):
                r = trade(day, u, book, dec, structure, cap)
                if r:
                    r['sample'] = 'A expiry' if (day, u) in EXPIRY else 'B non-expiry'
                    trades[(structure, cap)].append(r)

print('=== 11:00 decisions (F1: morning range / straddle >= 2*sqrt(105/T); F2: efficiency >= 0.5) ===')
print(f"{'day':10s} {'idx':6s} {'sample':12s} {'spot':>9s} {'range':>6s} {'straddle':>8s} {'ratio':>6s} {'need':>5s} {'F1':>3s} {'eff':>5s} {'F2':>3s}")
for (day, u), d in decisions.items():
    if d is None:
        print(f'{day} {u:6s} no quote at 11:00'); continue
    s = 'A expiry' if (day, u) in EXPIRY else 'B non-expiry'
    print(f"{day} {u:6s} {s:12s} {d['spot']:9.1f} {d['rm']:6.1f} {d['straddle']:8.2f} {d['ratio']:6.2f} {d['need']:5.2f} "
          f"{'yes' if d['F1'] else 'no':>3s} {d['eff']:5.2f} {'yes' if d['F2'] else 'no':>3s}")


def filt(r, name):
    d = decisions[(r['day'], r['u'])]
    return True if name == 'none' else d['F1'] if name == 'F1' else d['F2']


def report(rs, label):
    if not rs:
        print(f'{label:44s} no trades'); return
    by_day = defaultdict(float)
    for r in rs:
        by_day[r['day']] += r['net']
    equity, peak, dd = 0.0, 0.0, 0.0
    for day in SESSIONS:
        equity += by_day.get(day, 0.0)
        peak = max(peak, equity); dd = min(dd, equity - peak)
    net = sum(r['net'] for r in rs)
    reasons = defaultdict(int)
    for r in rs:
        reasons[r['reason'][:6]] += 1
    print(f"{label:44s} n={len(rs):2d} target {reasons['TARGET']:2d} stop {reasons['STOP_2'] + reasons['STOP_1']:2d} "
          f"time {reasons['TIME_1']:2d}  net ₹{net:>8,.0f} ({100 * net / CAPITAL:+5.2f}% of capital)  "
          f"worst trade ₹{min(r['net'] for r in rs):>7,.0f}  max DD ₹{dd:>8,.0f}  avg paid ₹{statistics.mean(r['paid'] for r in rs):,.0f}")


for cap in (0.80, 0.85):
    print(f"\n=== loss cap -{round((1 - cap) * 100)}% | +30% target | else 15:15 | 5% of ₹5,00,000 premium per trade ===")
    for structure in ('STRADDLE', 'STRANGLE'):
        for sample in ('A expiry', 'B non-expiry', None):
            for f in ('none', 'F1', 'F2'):
                rs = [r for r in trades[(structure, cap)] if (sample is None or r['sample'] == sample) and filt(r, f)]
                report(rs, f"{structure} {sample or 'A+B all'} filter={f}")

print('\n=== every trade: straddle, loss cap -20% (primary) ===')
for r in trades[('STRADDLE', 0.80)]:
    d = decisions[(r['day'], r['u'])]
    print(f"{r['day']} {r['u']:6s} {r['sample']:12s} F1 {'Y' if d['F1'] else 'n'} F2 {'Y' if d['F2'] else 'n'} | {r['strikes']:12s} "
          f"{r['lots']} lots ({r['qty']}) CE {r['ce_ask']:7.2f} PE {r['pe_ask']:7.2f} paid ₹{r['paid']:>7,.0f} in {r['entry']} "
          f"| out {r['exit']} {r['reason']:9s} CE {r['ce_bid']:7.2f} PE {r['pe_bid']:7.2f} {r['ret']:+6.1f}% "
          f"| best {r['best']:+6.1f}% worst {r['worst']:+6.1f}% | costs ₹{r['fees']:,.0f} net ₹{r['net']:>7,.0f}")
