"""A-554 (ledger): the 15-min / 30-min ATM +-3 OI flow at every PAPER-1 live entry, from the Mac's own capture.
Point in time at the entry minute's start (data to the close of the previous minute), A-402's definition:
ATM = spot rounded to the step (NIFTY 50, SENSEX 100); ATM-3..ATM+3 of the nearest expiry on or after the day; each
contract's last OI at most 5 minutes old; flow = (d PE OI - d CE OI) / (CE + PE OI at the earlier time) x 100;
move15 = index change over 15 minutes in NIFTY points (SENSEX / 3.25); with15 / with30 = flow in the trade's direction.
Usage: python3 -I 2026-11-22-a554-entry-oi-flow.py DAY POSITIONS_CSV OUT_CSV
POSITIONS_CSV rows (no header): day,strategy,underlying,side,symbol,opened HH:MM:SS,closed HH:MM:SS,exit_reason,quantity,net
Prints one summary line (the day, then the running buckets of with15 over all recorded days).
"""
import csv, io, os, subprocess, sys
from collections import defaultdict

STEP = {'NIFTY': 50, 'SENSEX': 100}
SCALE = {'NIFTY': 1.0, 'SENSEX': 3.25}
MAX_AGE = 5
MIN = "(extract(hour from recv_ts at time zone 'Asia/Kolkata') * 60 + extract(minute from recv_ts at time zone 'Asia/Kolkata'))::int"
HEADER = ['day', 'strategy', 'underlying', 'side', 'symbol', 'opened', 'closed', 'exit_reason', 'quantity', 'net',
          'atm', 'expiry', 'flow15', 'flow30', 'move15', 'with15', 'with30']


def sql(q):
    r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAt",
                        "-c", "copy (" + q + ") to stdout csv header"], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))


class IndexDay:
    def __init__(self, d, u):
        self.u, self.step = u, STEP[u]
        tb = f"recv_ts between '{d} 09:00+05:30' and '{d} 15:35+05:30'"
        self.spot = {int(r['m']): float(r['p']) for r in sql(
            f"select {MIN} m, (array_agg(price order by recv_ts desc))[1] p from md.index_tick "
            f"where session_date = '{d}' and underlying = '{u}' and price > 0 and {tb} group by 1")}
        self.exp, self.oi = None, defaultdict(dict)
        if not self.spot:
            return
        e = sql(f"select min(expiry)::text e from md.option_tick where session_date = '{d}' and underlying = '{u}' "
                f"and expiry >= '{d}' and {tb}")
        self.exp = e[0]['e'] if e and e[0]['e'] else None
        if not self.exp:
            return
        lo, hi = min(self.spot.values()) - 5 * self.step, max(self.spot.values()) + 5 * self.step
        for r in sql(f"select {MIN} m, strike, option_type t, (array_agg(oi order by recv_ts desc))[1] oi from md.option_tick "
                     f"where session_date = '{d}' and underlying = '{u}' and expiry = '{self.exp}' and strike between {lo} and {hi} "
                     f"and oi > 0 and {tb} group by 1, 2, 3"):
            self.oi[(float(r['strike']), r['t'])][int(r['m'])] = float(r['oi'])

    def _last(self, series, m):                     # last value in minutes m-MAX_AGE .. m-1 (at or before m:00)
        if not series:
            return None
        for b in range(m - 1, m - 1 - MAX_AGE, -1):
            if b in series:
                return series[b]
        return None

    def flow(self, m, back):
        """(atm, flow %, move in NIFTY points) at minute m over `back` minutes; None where data is missing."""
        s1, s0 = self._last(self.spot, m), self._last(self.spot, m - back)
        if s1 is None or s0 is None or not self.exp:
            return None, None, None
        atm = round(s1 / self.step) * self.step
        ks = [(float(atm + i * self.step), t) for i in range(-3, 4) for t in ('CE', 'PE')]
        now = [self._last(self.oi.get(k), m) for k in ks]
        then = [self._last(self.oi.get(k), m - back) for k in ks]
        if any(v is None for v in now + then):
            return atm, None, (s1 - s0) / SCALE[self.u]
        ce1 = sum(v for k, v in zip(ks, now) if k[1] == 'CE'); pe1 = sum(v for k, v in zip(ks, now) if k[1] == 'PE')
        ce0 = sum(v for k, v in zip(ks, then) if k[1] == 'CE'); pe0 = sum(v for k, v in zip(ks, then) if k[1] == 'PE')
        tot = ce0 + pe0
        if tot <= 0:
            return atm, None, (s1 - s0) / SCALE[self.u]
        return atm, ((pe1 - pe0) - (ce1 - ce0)) / tot * 100, (s1 - s0) / SCALE[self.u]


def fmt(x):
    return '' if x is None else f'{x:.3f}'


def main():
    day, pos_csv, out_csv = sys.argv[1], sys.argv[2], sys.argv[3]
    rows = [r for r in csv.reader(open(pos_csv)) if r and r[0] == day]
    cache, new = {}, []
    for r in rows:
        _, strategy, u, side, symbol, opened, closed, why, qty, net = r[:10]
        if u not in STEP:
            continue
        if u not in cache:
            cache[u] = IndexDay(day, u)
        h, mi = int(opened[:2]), int(opened[3:5])
        m = h * 60 + mi
        atm, f15, mv = cache[u].flow(m, 15)
        _, f30, _ = cache[u].flow(m, 30)
        sign = 1 if side == 'CE' else -1
        new.append([day, strategy, u, side, symbol, opened, closed, why, qty, net, '' if atm is None else atm,
                    cache[u].exp or '', fmt(f15), fmt(f30), fmt(mv),
                    fmt(None if f15 is None else sign * f15), fmt(None if f30 is None else sign * f30)])
    old = []
    if os.path.exists(out_csv):
        old = [r for r in csv.reader(open(out_csv))][1:]
        old = [r for r in old if r and r[0] != day]
    with open(out_csv, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(HEADER)
        w.writerows(old + new)
    allrows = old + new
    b = defaultdict(lambda: [0, 0, 0.0])
    known = 0
    for r in allrows:
        if r[15] == '':
            continue
        known += 1
        x = float(r[15])
        k = 'against<-1.5' if x < -1.5 else '-1.5..0' if x < 0 else '0..1.5' if x <= 1.5 else 'with>1.5'
        b[k][0] += 1; b[k][1] += float(r[9]) > 0; b[k][2] += float(r[9])
    today_known = sum(1 for r in new if r[15] != '')
    parts = ', '.join(f"{k} {b[k][0]} trades {b[k][1]} won {b[k][2]:+,.0f}" for k in ('against<-1.5', '-1.5..0', '0..1.5', 'with>1.5'))
    print(f"A-554 {day}: {len(new)} entries ({today_known} with flow); since 23 Nov {known} of 30 with flow: {parts}")


if __name__ == '__main__':
    main()
