"""Descriptive splits of zone events (registered in docs/studies/2026-09-30-zone-events.md; not filters)."""
import csv, statistics, sys

rows = [r for r in csv.DictReader(open(sys.argv[1])) if r['priced'] == 'True']
group = sys.argv[2] if len(sys.argv) > 2 else 'test'
H = sys.argv[3] if len(sys.argv) > 3 else 'net30'


def f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def line(label, rs):
    v = [f(r[H]) for r in rs if f(r[H]) is not None]
    if not v:
        return f'    {label:34s} n=  0'
    w = sum(1 for x in v if x > 0)
    flag = '  (small)' if len(v) < 10 else ''
    return f'    {label:34s} n={len(v):3d} win {100 * w / len(v):3.0f}% mean {statistics.mean(v):7.0f} ' \
           f'median {statistics.median(v):7.0f} total {sum(v):8.0f}{flag}'


def bucket(t):
    return '09:30-11:00' if t < '11:00' else '11:00-13:00' if t < '13:00' else '13:00-14:45'


print(f'group {group}, measure {H}')
for kind in ('BREAK', 'FAIL', 'ACCEPT', 'SECOND_BREAK', 'HOLD'):
    ev = [r for r in rows if r['group'] == group and r['kind'] == kind]
    print(f'  {kind}')
    print(line('all', ev))
    for u in ('NIFTY', 'SENSEX'):
        print(line(u, [r for r in ev if r['u'] == u]))
    print(line('zone strength 1', [r for r in ev if r['strength'] == '1']))
    print(line('zone strength >= 2', [r for r in ev if r['strength'] != '1']))
    print(line('expiry day', [r for r in ev if r['expiry'] == 'True']))
    print(line('not expiry day', [r for r in ev if r['expiry'] != 'True']))
    for b in ('09:30-11:00', '11:00-13:00', '13:00-14:45'):
        print(line(b, [r for r in ev if bucket(r['time']) == b]))
    print(line('futures OI rising (3 m)', [r for r in ev if (f(r['fut_oi3m']) or 0) > 0]))
    print(line('futures OI falling (3 m)', [r for r in ev if (f(r['fut_oi3m']) or 0) < 0]))
    print(line('ATM IV rising (3 m)', [r for r in ev if (f(r['iv3m']) or 0) > 0]))
    print(line('ATM IV falling (3 m)', [r for r in ev if (f(r['iv3m']) or 0) < 0]))
    print(line('breadth agrees with direction', [r for r in ev if (f(r['breadth']) or 0) * int(r['dir']) > 0]))
    print(line('breadth disagrees', [r for r in ev if (f(r['breadth']) or 0) * int(r['dir']) < 0]))
    print(line('premium response >= 1', [r for r in ev if (f(r['resp']) or 0) >= 1]))
    print(line('premium response < 1', [r for r in ev if f(r['resp']) is not None and f(r['resp']) < 1]))
    print(line('OI wall in zone', [r for r in ev if r['wall_in_zone'] == 'True']))
    print(line('no OI wall in zone', [r for r in ev if r['wall_in_zone'] == 'False']))
