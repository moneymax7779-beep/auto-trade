"""A-396: regime-gate arms (g1 PASTED v24, g2 VIX_SIZE v25, g3 OR_SIZE v26) vs A-390's control (ctl, v19), set net."""
import csv, glob, io, re, subprocess
from collections import defaultdict

def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))

def sessions(path, label):
    out = {}
    for line in open(path):
        p = line.split()
        if len(p) >= 4 and p[1] == label and p[3].isdigit() and 'NO SUMMARY' not in line:
            out[p[2]] = int(p[3])
    return out

H1_END = '2026-10-19'
CHART = {'2026-11-09', '2026-11-04', '2026-11-03', '2026-10-28', '2026-10-27', '2026-10-26', '2026-10-22',
         '2026-10-05', '2026-10-06', '2026-10-07', '2026-10-16'}
A = '/Users/satyasiripuram/Desktop/Dev/Code/auto-trade/.local'
ctl = sessions(f'{A}/a390/tick/progress.txt', 'ctl')
arms = {g: sessions(f'{A}/a396/tick/progress.txt', g) for g in ('g1', 'g2', 'g3')}
NAMES = {'g1': 'G1 PASTED (v24)', 'g2': 'G2 VIX_SIZE (v25)', 'g3': 'G3 OR_SIZE (v26)'}
ids = list(ctl.values()) + [s for a in arms.values() for s in a.values()]
P = sql(f"""select p.session_id sid, p.underlying u, p.strategy_id st, p.option_side side,
    to_char(p.opened_at at time zone 'Asia/Kolkata', 'HH24:MI') o, p.quantity::int qty, p.net::float net, p.exit_reason why
    from trade.position p where p.session_id in ({','.join(map(str, ids))})""")
bys = defaultdict(list)
for p in P:
    p['net'] = float(p['net'] or 0); bys[int(p['sid'])].append(p)
net = lambda sid: sum(p['net'] for p in bys[sid])

def mdd(vals):
    eq = pk = dd = 0
    for v in vals: eq += v; pk = max(pk, eq); dd = min(dd, eq - pk)
    return dd

print("# A-396 result\n")
days_all = sorted(ctl)
cnet = {d: net(ctl[d]) for d in days_all}
print(f"Control (A-390 ctl, risk v19): {len(days_all)} days, {sum(len(bys[ctl[d]]) for d in days_all)} trades, net {sum(cnet.values()):+,.0f}, "
      f"worst day {min(cnet.values()):+,.0f}, max drawdown {mdd([cnet[d] for d in days_all]):+,.0f}\n")
for g, S in arms.items():
    days = [d for d in days_all if d in S]
    missing = [d for d in days_all if d not in S]
    diff = {d: net(S[d]) - cnet[d] for d in days}
    tot = sum(diff.values()); h1 = sum(v for d, v in diff.items() if d <= H1_END); h2 = tot - h1
    best = max(diff, key=diff.get) if diff else None
    nc = sum(v for d, v in diff.items() if d not in CHART); ch = tot - nc
    vnet = {d: net(S[d]) for d in days}
    c = [tot > 0, h1 > 0 and h2 > 0, best is not None and tot - diff[best] > 0, nc > 0]
    print(f"## {NAMES[g]}\n")
    if missing: print(f"MISSING days: {missing}\n")
    print(f"Set {sum(cnet[d] for d in days):+,.0f} -> {sum(vnet.values()):+,.0f}; **difference {tot:+,.0f}** (H1 {h1:+,.0f} / H2 {h2:+,.0f}); "
          f"without the best day ({best} {diff[best]:+,.0f}) {tot - diff[best]:+,.0f}; chart days {ch:+,.0f} / other 20 days {nc:+,.0f}; "
          f"changed days {sum(1 for v in diff.values() if abs(v) > 1)}; trades {sum(len(bys[S[d]]) for d in days)} vs {sum(len(bys[ctl[d]]) for d in days)}; "
          f"worst day {min(vnet.values()):+,.0f} vs {min(cnet[d] for d in days):+,.0f}; max drawdown {mdd([vnet[d] for d in days]):+,.0f} vs {mdd([cnet[d] for d in days]):+,.0f}\n")
    print(f"**Verdict**: (1) {'ok' if c[0] else 'no'}; (2) {'ok' if c[1] else 'no'}; (3) {'ok' if c[2] else 'no'}; (4) {'ok' if c[3] else 'no'} -> **{'PASS' if all(c) else 'FAIL'}**\n")
    st = defaultdict(lambda: [0.0, 0.0, 0, 0])
    for d in days:
        for p in bys[ctl[d]]: st[p['st']][0] += p['net']; st[p['st']][2] += 1
        for p in bys[S[d]]: st[p['st']][1] += p['net']; st[p['st']][3] += 1
    print("By strategy (control -> gated, trades): " + "; ".join(f"{s} {v[0] / 1e5:+.2f} L ({v[2]}) -> {v[1] / 1e5:+.2f} L ({v[3]})" for s, v in sorted(st.items())) + "\n")
    rej = defaultdict(int); rez = defaultdict(int)
    for f in glob.glob(f'{A}/a396/tick/logs/{g}-*.log'):
        for line in open(f, errors='ignore'):
            if 'regime gate' not in line: continue
            m = re.search(r'regime gate (\w+): ([A-Z_]+)', line)
            key = m.group(2) if m and m.group(1) == 'PASTED' else (m.group(1) if m else '?')
            if 'REJECTED' in line or 'below one lot' in line: rej[key] += 1
            elif 'ENTER' in line: rez[key] += 1
    print(f"Gate log lines: refused {dict(rej)}; resized {dict(rez)}\n")
    big = sorted(diff.items(), key=lambda kv: kv[1])
    print("Largest daily differences: " + ", ".join(f"{d} {v:+,.0f}" for d, v in big[:4]) + " ... " + ", ".join(f"{d} {v:+,.0f}" for d, v in big[-4:]) + "\n")
