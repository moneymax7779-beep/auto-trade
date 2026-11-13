"""A-394: the pasted VIX + opening-range-width regime controller, scored on all 140 trades of the 29-day live-five replay
batch (descriptive). Rules: the A-394 ledger row. Reuses the A-391 loaders (local DB read-only).
Usage: python3 docs/studies/2026-11-13-regime-filter-check.py > .local/a394/result.md"""
import importlib.util, math
from collections import defaultdict

spec = importlib.util.spec_from_file_location('a391', 'docs/studies/2026-11-13-sweep-reversal-check.py')
M = importlib.util.module_from_spec(spec); spec.loader.exec_module(M)
hm, sql, CHART_DAYS, H1 = M.hm, M.sql, M.CHART_DAYS, '2026-10-19'
NAME = {'expiry-trend-rider': 'etr', 'ma-cross': 'mac', 'oi-burst-cross': 'obc', 'wall-break-rider': 'wbr', 'break-retest-runner': 'brtr'}

def vix945(d):
    r = sql(f"select close from ref.index_candle where symbol = 'INDIA_VIX' and timeframe = '1minute' and session_date = '{d}' "
            f"and (bar_start at time zone 'Asia/Kolkata')::time <= '09:45' order by bar_start desc limit 1")
    return float(r[0]['close']) if r else None

def regime(t, D, vix, table=False):
    if t['tmin'] < hm('09:45'): return 'PRE_OR_LOCKOUT', set(), 0.0
    if t['tmin'] >= hm('14:45'): return 'LATE_LOCKOUT', set(), 0.0
    ms = [m for m in D.ohlc if m < hm('09:45')]
    orw = max(D.ohlc[m][1] for m in ms) - min(D.ohlc[m][2] for m in ms)
    spot_open = D.ohlc[min(D.ohlc)][0]
    r = orw / max(spot_open * vix / 100 / math.sqrt(252), 1.0)
    exp = M.A386.load(D.d, D.u)['expiry'] == D.d
    if r > 0.65: return 'EARLY_EXHAUSTION', {'sweep'}, 0.5
    if vix >= 15.5 and r < 0.45: return 'HIGH_VIX_CHOP', {'sweep'}, 0.5
    if vix < 15.5 and r < 0.35:
        s = {'wbr', 'brtr', 'etr'} if table else {'wbr', 'brtr', 'obc'}
        if exp and not table: s.add('etr')
        return 'COILED', s, 1.0
    s = {'etr', 'obc', 'brtr'} if table else {'brtr', 'obc', 'mac'}
    if exp and not table: s.add('etr')
    return 'BALANCED', s, 0.75 if vix >= 15.5 else 1.0

def main():
    days = {}
    T = M.batch_trades(days)
    V = {d: vix945(d) for d in sorted(set(t['day'] for t in T))}
    print("# A-394 result\n")
    # day regimes (NIFTY) for orientation
    print("| day | VIX 09:45 | NIFTY R_OR | SENSEX R_OR | day net | chart day |\n|---|---|---|---|---|---|")
    dn = defaultdict(float)
    for t in T: dn[t['day']] += t['net']
    for d in sorted(V):
        rr = []
        for u in ('NIFTY', 'SENSEX'):
            D = days.get((d, u)) or M.Day(d, u); days[(d, u)] = D
            ms = [m for m in D.ohlc if m < hm('09:45')]
            orw = max(D.ohlc[m][1] for m in ms) - min(D.ohlc[m][2] for m in ms)
            rr.append(orw / (D.ohlc[min(D.ohlc)][0] * V[d] / 100 / math.sqrt(252)))
        print(f"| {d} | {V[d]:.2f} | {rr[0]:.2f} | {rr[1]:.2f} | {dn[d]:+,.0f} | {'yes' if d in CHART_DAYS else ''} |")
    for label, table in (('CODE as pasted (primary)', False), ('TABLE version (reported)', True)):
        rows = []
        for t in T:
            D = days[(t['day'], t['u'])]
            name, allowed, mult = regime(t, D, V[t['day']], table)
            s = NAME[t['st']]; keep = s in allowed
            f = mult if keep else 0.0
            if table and keep and name == 'BALANCED' and t['xe'] is not None and abs(t['xe']) > 0.45: f = 0.0
            rows.append((t, name, f))
        new = sum(t['net'] * f for t, _, f in rows); old = sum(t['net'] for t in T)
        ch = lambda flt: sum(t['net'] * (f - 1) for t, _, f in rows if flt(t))
        print(f"\n## {label}\n\nSet net {old:+,.0f} -> {new:+,.0f}: change {new - old:+,.0f} | H1 {ch(lambda t: t['day'] <= H1):+,.0f} / H2 {ch(lambda t: t['day'] > H1):+,.0f} "
              f"| chart days {ch(lambda t: t['day'] in CHART_DAYS):+,.0f} / other 18 days {ch(lambda t: t['day'] not in CHART_DAYS):+,.0f}")
        print(f"Trades kept at full size {sum(1 for *_, f in rows if f == 1)}, scaled {sum(1 for *_, f in rows if 0 < f < 1)}, removed {sum(1 for *_, f in rows if f == 0)}\n")
        print("| regime | trades | of which removed | their net (full size) | net after the rule | change |\n|---|---|---|---|---|---|")
        g = defaultdict(list)
        for t, n, f in rows: g[n].append((t, f))
        for n, v in sorted(g.items()):
            print(f"| {n} | {len(v)} | {sum(1 for _, f in v if f == 0)} | {sum(t['net'] for t, _ in v):+,.0f} | {sum(t['net'] * f for t, f in v):+,.0f} | {sum(t['net'] * (f - 1) for t, f in v):+,.0f} |")
        print("\nBy strategy (full -> after): " + "; ".join(
            f"{s} {sum(t['net'] for t, _, f in rows if NAME[t['st']] == s) / 1e5:+.2f} L -> {sum(t['net'] * f for t, _, f in rows if NAME[t['st']] == s) / 1e5:+.2f} L"
            for s in ('etr', 'mac', 'obc', 'wbr', 'brtr')))
        top = sorted(T, key=lambda t: -t['net'])[:10]
        fmap = {id(t): (n, f) for t, n, f in rows}
        print("\nThe 10 biggest winners under the rule: " + "; ".join(f"{t['day']} {t['u'][:1]} {NAME[t['st']]} {t['open'][:5]} {t['net'] / 1e5:+.2f} L -> {fmap[id(t)][0]} x{fmap[id(t)][1]}" for t in top))
    # claim checks used in the reply
    print("\n## Claim checks (NIFTY trades on the chart days)\n")
    for t in T:
        if t['day'] in CHART_DAYS and t['u'] == 'NIFTY':
            print(f"- {t['day']} {NAME[t['st']]} {t['side']} {t['open'][:5]} net {t['net']:+,.0f}; VWAP ext {t['xv'] if t['xv'] is None else round(t['xv'], 2)} %, EMA3 ext {t['xe'] if t['xe'] is None else round(t['xe'], 2)} %, nearest 4x+/PD/OR level ahead {t['wall'] if t['wall'] is None else round(t['wall'])} pts")
    early = [t for t in T if t['tmin'] < hm('09:45')]
    print(f"\nEntries before 09:45: {len(early)} trades, net {sum(t['net'] for t in early):+,.0f}, winners {sum(1 for t in early if t['net'] > 0)}; "
          f"on chart days {sum(t['net'] for t in early if t['day'] in CHART_DAYS):+,.0f}, other days {sum(t['net'] for t in early if t['day'] not in CHART_DAYS):+,.0f}")

if __name__ == '__main__':
    main()
