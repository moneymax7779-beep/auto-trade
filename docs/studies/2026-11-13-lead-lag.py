"""A-384 lead-lag study (rules: the A-384 ledger row, registered before this ran). Read-only on the local DB.
Do the heavyweight stocks move before the near-month NIFTY future? Times are recv_ts (what the system knew).
Usage: python3 docs/studies/2026-11-13-lead-lag.py > .local/a384/result.md  (per-day cache in .local/a384/cache)"""
import csv, io, json, math, os, random, subprocess, sys
from collections import defaultdict

STOCKS = ['HDFCBANK', 'ICICIBANK', 'RELIANCE', 'INFY', 'BHARTIARTL', 'LT', 'TCS', 'AXISBANK', 'SBIN', 'KOTAKBANK']
T0, T1 = 9 * 3600 + 15 * 60, 15 * 3600 + 20 * 60          # per-second grid, seconds after IST midnight
S0, S1 = 9 * 3600 + 20 * 60, 15 * 3600 + 10 * 60          # sample window
H1_END = '2026-10-21'
CACHE = '.local/a384/cache'

def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))

SEC = "(extract(epoch from (recv_ts at time zone 'Asia/Kolkata')::time))::int"

def load(d):
    f = f"{CACHE}/{d}.json"
    if os.path.exists(f):
        return json.load(open(f))
    out = {}
    syms = ",".join(f"'{s}'" for s in STOCKS)
    for r in sql(f"select symbol k, {SEC} s, (array_agg(price order by recv_ts desc))[1] v from md.constituent_tick "
                 f"where session_date = '{d}' and symbol in ({syms}) and price > 0 group by 1, 2"):
        out.setdefault(r['k'], {})[r['s']] = float(r['v'])
    for u in ('NIFTY', 'BANKNIFTY', 'SENSEX'):
        ex = sql(f"select expiry::text e from md.future_tick where session_date = '{d}' and underlying = '{u}' "
                 f"group by 1 order by count(*) desc limit 1")
        if ex:
            for r in sql(f"select {SEC} s, (array_agg((bid_px[1] + ask_px[1]) / 2 order by recv_ts desc))[1] v "
                         f"from md.future_tick where session_date = '{d}' and underlying = '{u}' and expiry = '{ex[0]['e']}' "
                         f"and bid_px[1] > 0 and ask_px[1] > 0 group by 1"):
                out.setdefault(u + '_FUT', {})[r['s']] = float(r['v'])
        for r in sql(f"select {SEC} s, (array_agg(price order by recv_ts desc))[1] v from md.index_tick "
                     f"where session_date = '{d}' and underlying = '{u}' and price > 0 group by 1"):
            out.setdefault(u + '_IDX', {})[r['s']] = float(r['v'])
    os.makedirs(CACHE, exist_ok=True)
    json.dump(out, open(f, 'w'))
    return out

def grid(series):
    """forward-filled log price per second T0..T1 (None before the first value)"""
    g, last = [], None
    for s in range(T0, T1 + 1):
        v = series.get(str(s), series.get(s))
        if v: last = math.log(v)
        g.append(last)
    return g

def ret(g, a, b):                                       # bp, log return between seconds a and b
    x, y = g[a - T0], g[b - T0]
    return None if x is None or y is None else (y - x) * 1e4

days_all = [r['d'] for r in sql("select distinct session_date::text d from md.future_tick where underlying = 'NIFTY' order by 1")]
DATA, DAYS = {}, []
for d in days_all:
    raw = load(d)
    if not all(s in raw for s in STOCKS) or 'NIFTY_FUT' not in raw:
        print(f"<!-- skip {d}: missing {[s for s in STOCKS + ['NIFTY_FUT'] if s not in raw]} -->", file=sys.stderr); continue
    DATA[d] = {k: grid(v) for k, v in raw.items()}; DAYS.append(d)

def basket(G, a, b):
    xs = [ret(G[s], a, b) for s in STOCKS]
    return None if any(x is None for x in xs) else sum(xs) / len(xs)

def samples(d, L, H, signal, target='NIFTY_FUT'):
    """[(signal value, outcome)] for one day; signal(G, t, L) -> value or None"""
    G = DATA[d]; out = []
    if target not in G: return out
    for t in range(S0, S1 + 1, 15):
        if t + H > T1: break
        x = signal(G, t, L); y = ret(G[target], t, t + H)
        if x is not None and y is not None: out.append((x, y))
    return out

def gap_sig(G, t, L):
    b, f = basket(G, t - L, t), ret(G['NIFTY_FUT'], t - L, t)
    return None if b is None or f is None else b - f
def ref_sig(G, t, L):
    f = ret(G['NIFTY_FUT'], t - L, t); return None if f is None else -f
def basket_sig(G, t, L): return basket(G, t - L, t)
def stock_sig(s):
    def f(G, t, L):
        a, b = ret(G[s], t - L, t), ret(G['NIFTY_FUT'], t - L, t)
        return None if a is None or b is None else a - b
    return f
def bn_sig(G, t, L):
    if 'BANKNIFTY_FUT' not in G: return None
    a, b = ret(G['BANKNIFTY_FUT'], t - L, t), ret(G['NIFTY_FUT'], t - L, t)
    return None if a is None or b is None else a - b

def strong_signed(d, L, H, signal, target='NIFTY_FUT'):
    """signed outcomes of the day's strong samples (|signal| in the top 20 % of that day's samples)"""
    xs = [s for s in samples(d, L, H, signal, target) if s[0] != 0]
    if len(xs) < 10: return []
    cut = sorted(abs(x) for x, _ in xs)[int(0.8 * len(xs))]
    return [math.copysign(1, x) * y for x, y in xs if abs(x) >= cut]

def summary(by_day, days):
    v = [y for d in days for y in by_day.get(d, [])]
    return (sum(v) / len(v), sum(1 for y in v if y > 0) / len(v), len(v)) if v else (float('nan'), float('nan'), 0)

def boot(by_day, days, n=10000, seed=7, diff=None):
    """day-bootstrap of the pooled mean (or of the difference of two pooled means)"""
    rnd = random.Random(seed); out = []
    S = {d: (sum(by_day.get(d, [])), len(by_day.get(d, []))) for d in days}
    D = {d: (sum(diff.get(d, [])), len(diff.get(d, []))) for d in days} if diff is not None else None
    for _ in range(n):
        pick = [rnd.choice(days) for _ in days]
        a, c = sum(S[d][0] for d in pick), sum(S[d][1] for d in pick)
        m = a / c if c else 0
        if D is not None:
            a2, c2 = sum(D[d][0] for d in pick), sum(D[d][1] for d in pick); m -= a2 / c2 if c2 else 0
        out.append(m)
    out.sort(); return out[int(0.025 * n)], out[int(0.975 * n)]

H1 = [d for d in DAYS if d <= H1_END]; H2 = [d for d in DAYS if d > H1_END]
f = lambda x: f"{x:+.2f}"
print(f"# A-384 result: lead-lag, heavyweight basket vs the NIFTY future\n")
print(f"{len(DAYS)} days {DAYS[0]} - {DAYS[-1]} (H1 {len(H1)} to {H1_END}, H2 {len(H2)}). Samples every 15 s 09:20-15:10, recv_ts.\n")

g = {d: strong_signed(d, 30, 60, gap_sig) for d in DAYS}
r = {d: strong_signed(d, 30, 60, ref_sig) for d in DAYS}
m, hit, n = summary(g, DAYS); lo, hi = boot(g, DAYS)
m1, m2 = summary(g, H1)[0], summary(g, H2)[0]
rm = summary(r, DAYS)[0]; dlo, dhi = boot(g, DAYS, diff=r)
c1, c2, c3, c4 = lo > 0 and m > 0, m1 > 0 and m2 > 0, m >= 3.0, (m - rm) > 0 and dlo > 0
print("## Registered rule (strong samples, gap30 -> NIFTY future mid next 60 s)\n")
print(f"- signed mean **{f(m)} bp**, hit {hit:.0%}, n {n}, day-bootstrap 95 % [{f(lo)}, {f(hi)}] -> (1) {'ok' if c1 else 'no'}")
print(f"- halves H1 {f(m1)} / H2 {f(m2)} -> (2) {'ok' if c2 else 'no'}")
print(f"- mean >= 3.0 bp -> (3) {'ok' if c3 else 'no'}")
print(f"- own-reversal reference {f(rm)} bp; gap minus reference {f(m - rm)} [{f(dlo)}, {f(dhi)}] -> (4) {'ok' if c4 else 'no'}")
print(f"\n**{'PASS' if c1 and c2 and c3 and c4 else 'FAIL'}**\n")

print("## Reported (not part of the verdict)\n")
print("| signal | lookback | horizon | target | signed mean bp | hit | n | 95 % |\n|---|---|---|---|---|---|---|---|")
def row(name, sig, L, H, target='NIFTY_FUT', days=DAYS):
    bd = {d: strong_signed(d, L, H, sig, target) for d in days}
    m, hit, n = summary(bd, days)
    if not n: return
    lo, hi = boot(bd, days, n=2000)
    print(f"| {name} | {L} s | {H} s | {target} | {f(m)} | {hit:.0%} | {n} | [{f(lo)}, {f(hi)}] |")
for L in (10, 30, 60):
    for H in (30, 60, 180, 300):
        row('gap (basket - future)', gap_sig, L, H)
row('own reversal (reference)', ref_sig, 30, 60)
row('basket return alone', basket_sig, 30, 60)
row('basket return alone', basket_sig, 30, 300)
row('gap (basket - future)', gap_sig, 30, 60, 'NIFTY_IDX')
row('gap (basket - future)', gap_sig, 30, 60, 'SENSEX_IDX')
row('gap (basket - future)', gap_sig, 30, 60, 'SENSEX_FUT')
bn_days = [d for d in DAYS if 'BANKNIFTY_FUT' in DATA[d]]
row(f'BANKNIFTY future - NIFTY future ({len(bn_days)} days)', bn_sig, 30, 60, days=bn_days)
for s in STOCKS:
    row(f'{s} - future', stock_sig(s), 30, 60)

print("\n## Who leads: correlation of 1-s returns, basket(t) vs NIFTY future(t + k), pooled\n")
print("k > 0: the basket moves first; k < 0: the future moves first.\n")
acc = defaultdict(lambda: [0.0, 0.0, 0.0, 0.0, 0.0, 0])
for d in DAYS:
    G = DATA[d]; T = range(S0, S1)
    b1 = {t: basket(G, t - 1, t) for t in T}; f1 = {t: ret(G['NIFTY_FUT'], t - 1, t) for t in range(S0 - 31, S1 + 31)}
    for k in range(-30, 31, 1):
        a = acc[k]
        for t in T:
            x, y = b1[t], f1.get(t + k)
            if x is None or y is None: continue
            a[0] += x; a[1] += y; a[2] += x * x; a[3] += y * y; a[4] += x * y; a[5] += 1
def corr(a):
    sx, sy, sxx, syy, sxy, n = a
    return (sxy / n - sx / n * sy / n) / math.sqrt((sxx / n - (sx / n) ** 2) * (syy / n - (sy / n) ** 2))
print("| k (s) | corr |\n|---|---|")
for k in (-30, -10, -5, -3, -2, -1, 0, 1, 2, 3, 5, 10, 30):
    print(f"| {k:+d} | {corr(acc[k]):+.3f} |")
print("\n## Per day (strong gap30 samples, signed 60-s future move, bp)\n")
print("| day | n | mean |\n|---|---|---|")
for d in DAYS:
    v = g[d]; print(f"| {d} | {len(v)} | {f(sum(v) / len(v)) if v else '-'} |")
