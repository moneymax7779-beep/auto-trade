"""A-395: does a wide 09:45 opening range (in VIX expected-move units) mark a trend day after the opening range?
Rules: the A-395 ledger row (registered before this ran). Upstox one-minute bars in hist.candle, read-only.
Usage: python3 docs/studies/2026-11-13-opening-range-trend.py > .local/a395/result.md"""
import csv, io, math, random, subprocess
from collections import defaultdict

def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))

END = '2026-09-25'
T = "(bar_start at time zone 'Asia/Kolkata')"

def load():
    q = f"""with b as (select underlying u, {T}::date d, {T}::time t, open, high, low, close from hist.candle
             where kind = 'INDEX' and underlying in ('NIFTY', 'SENSEX') and {T}::date <= '{END}'
             and {T}::time between '09:15' and '15:14')
        select u, d::text, (array_agg(open order by t))[1] o,
          max(high) filter (where t < '09:45') - min(low) filter (where t < '09:45') orw,
          (array_agg(close order by t desc) filter (where t < '09:45'))[1] p0,
          (array_agg(close order by t desc) filter (where t = '15:14'))[1] p1,
          max(high) filter (where t >= '09:45') rh, min(low) filter (where t >= '09:45') rl,
          count(*) filter (where t < '09:45') n_or
        from b group by 1, 2"""
    rows = sql(q)
    vix = {r['d']: float(r['v']) for r in sql(
        f"""select {T}::date::text d, (array_agg(close order by bar_start desc))[1] v from hist.candle
            where kind = 'VIX' and {T}::date <= '{END}' and {T}::time <= '09:44' group by 1""")}
    out, drop = [], defaultdict(int)
    for r in rows:
        if not r['p1']: drop['no 15:14 bar'] += 1; continue
        if not r['orw'] or int(r['n_or']) < 20: drop['short opening range'] += 1; continue
        if r['d'] not in vix: drop['no VIX'] += 1; continue
        o, orw, p0, p1, rh, rl, v = (float(r[k]) for k in ('o', 'orw', 'p0', 'p1', 'rh', 'rl')), None, None, None, None, None, None
        o, orw, p0, p1, rh, rl = float(r['o']), float(r['orw']), float(r['p0']), float(r['p1']), float(r['rh']), float(r['rl'])
        v = vix[r['d']]; em = o * v / 100 / math.sqrt(252)
        mv = p1 - p0; rng = rh - rl
        out.append(dict(u=r['u'], d=r['d'], r=orw / em, trend=(rng > 0 and abs(mv) >= 0.5 * rng and abs(mv) / p0 >= 0.003),
                        rest=rng / em, vix=v))
    return out, drop

def rate(xs): return sum(x['trend'] for x in xs) / len(xs) if xs else float('nan')

def main():
    X, drop = load()
    print(f"# A-395 result\n\n{len(X)} index-days ({len(set(x['d'] for x in X))} dates, {min(x['d'] for x in X)} to {max(x['d'] for x in X)}); dropped: {dict(drop)}\n")
    print(f"Base TREND_REST rate {rate(X):.1%}\n")
    per = lambda x: x['d'][:4]
    print("| period | index-days | R_OR > 0.65: n / trend | R_OR <= 0.65: n / trend | difference |\n|---|---|---|---|---|")
    diffs = {}
    for p in ('2024', '2025', '2026', 'all'):
        xs = [x for x in X if p == 'all' or per(x) == p]
        w = [x for x in xs if x['r'] > 0.65]; n = [x for x in xs if x['r'] <= 0.65]
        diffs[p] = rate(w) - rate(n)
        print(f"| {p} | {len(xs)} | {len(w)} / {rate(w):.1%} | {len(n)} / {rate(n):.1%} | {diffs[p]:+.1%} |")
    # day bootstrap (dates resampled, both indices of a date together)
    byd = defaultdict(list)
    for x in X: byd[x['d']].append(x)
    dates = list(byd); random.seed(395); bs = []
    for _ in range(2000):
        xs = [x for d in random.choices(dates, k=len(dates)) for x in byd[d]]
        w = [x for x in xs if x['r'] > 0.65]; n = [x for x in xs if x['r'] <= 0.65]
        bs.append(rate(w) - rate(n))
    bs.sort(); lo, hi = bs[50], bs[1949]
    ok = diffs['all'] > 0 and lo > 0 and all(diffs[p] > 0 for p in ('2024', '2025', '2026'))
    print(f"\nPooled difference {diffs['all']:+.1%}, 95 % day-bootstrap CI [{lo:+.1%}, {hi:+.1%}]\n\n**H1: {'PASS' if ok else 'FAIL'}**\n")
    print("A-394 buckets:\n\n| R_OR | index-days | TREND_REST | rest-of-day range / expected move (median) |\n|---|---|---|---|")
    for lab, f in (('< 0.45', lambda r: r < 0.45), ('0.45-0.65', lambda r: 0.45 <= r <= 0.65), ('> 0.65', lambda r: r > 0.65)):
        xs = [x for x in X if f(x['r'])]; rs = sorted(x['rest'] for x in xs)
        print(f"| {lab} | {len(xs)} | {rate(xs):.1%} | {rs[len(rs) // 2]:.2f} |")
    print("\nQuintiles of R_OR:\n\n| quintile | R_OR range | index-days | TREND_REST | rest range / EM (median) |\n|---|---|---|---|---|")
    S = sorted(X, key=lambda x: x['r']); k = len(S) // 5
    for q in range(5):
        xs = S[q * k:(q + 1) * k] if q < 4 else S[4 * k:]
        rs = sorted(x['rest'] for x in xs)
        print(f"| {q + 1} | {xs[0]['r']:.2f}-{xs[-1]['r']:.2f} | {len(xs)} | {rate(xs):.1%} | {rs[len(rs) // 2]:.2f} |")
    for u in ('NIFTY', 'SENSEX'):
        xs = [x for x in X if x['u'] == u]
        print(f"\n{u}: > 0.65 {rate([x for x in xs if x['r'] > 0.65]):.1%} (n {sum(1 for x in xs if x['r'] > 0.65)}) vs <= 0.65 {rate([x for x in xs if x['r'] <= 0.65]):.1%}")

if __name__ == '__main__':
    main()
