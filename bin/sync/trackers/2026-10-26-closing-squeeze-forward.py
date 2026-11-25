#!/usr/bin/env python3
"""A-243: forward record of A-242's S1 retest-bounce (calls and the put mirror) on the own tick capture, frozen rules.

For DAY and each index, builds minute bars from md.index_tick and per-minute option price / OI from md.option_tick (the
same shapes A-242's bar study uses), evaluates the previous session's closing-squeeze precondition and S1 with A-242's
own functions (imported from the study file next to this one), and appends one row per trade to $A243_OUT/forward.csv
(day, index, side, conditioned, own expiry, entry, strike, exit, return). Read once at 40 trades (see the ledger).
Usage: python3 2026-10-26-closing-squeeze-forward.py DAY [--check]   (--check prints without recording)
"""
import csv, importlib.util, io, os, subprocess, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("A243_OUT", os.path.expanduser("~/.autotrade-sync/a243"))
START, READ_AT = "2026-10-27", 40

spec = importlib.util.spec_from_file_location("a242", os.path.join(HERE, "2026-10-26-closing-squeeze.py"))
a242 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a242)


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True,
                         stdin=subprocess.DEVNULL).stdout
    return list(csv.reader(io.StringIO(out)))


IST = "at time zone 'Asia/Kolkata'"


def load_day(u, d):
    """A-242's day shape from the own capture: idx minute -> (o, h, l, c); opts expiry -> (strike, type) -> minute -> (px, oi)."""
    idx = {a242.hm(t): tuple(map(float, v)) for t, *v in q(
        f"select to_char(date_trunc('minute', exch_ts {IST}),'HH24:MI') t, (array_agg(price order by exch_ts))[1], "
        f"max(price), min(price), (array_agg(price order by exch_ts desc))[1] from md.index_tick where session_date = '{d}' "
        f"and underlying = '{u}' and (exch_ts {IST})::time between '09:15' and '15:29:59' group by 1")}
    if len(idx) < 300:
        return None
    op = idx[min(idx)][0]
    opts = defaultdict(lambda: defaultdict(dict))
    for t, e, k, ty, px, oi in q(
            f"select to_char(date_trunc('minute', exch_ts {IST}),'HH24:MI'), expiry::text, strike, option_type, "
            f"(array_agg(ltp order by exch_ts desc) filter (where ltp > 0))[1], "
            f"(array_agg(oi order by exch_ts desc) filter (where oi > 0))[1] from md.option_tick where session_date = '{d}' "
            f"and underlying = '{u}' and strike between {op * 0.95} and {op * 1.05} "
            f"and (exch_ts {IST})::time between '09:15' and '15:29:59' group by 1, 2, 3, 4"):
        opts[e][(float(k), ty)][a242.hm(t)] = (float(px or 0), float(oi or 0))
    return dict(idx=idx, fut={}, opts={e: dict(v) for e, v in opts.items()})


def evaluate(day):
    out = []
    for u in ("NIFTY", "SENSEX"):
        prev = q(f"select max(session_date)::text from md.index_tick where underlying = '{u}' and session_date < '{day}'")
        if not prev or not prev[0][0]:
            continue
        d0, d1 = load_day(u, prev[0][0]), load_day(u, day)
        if not d0 or not d1:
            out.append(dict(u=u, note="no capture"))
            continue
        # The squeeze needs the next expiry's OI on the previous session; the capture holds the nearest expiry only, so
        # after an expiry day the flag is unknown (None) and S1 is still recorded on PDH / PDL.
        pre = a242.precondition(d0, u, prev[0][0])
        h = max(v[1] for v in d0["idx"].values())
        l = min(v[2] for v in d0["idx"].values())
        exps = sorted(e for e in d1["opts"] if e >= day)
        if not exps:
            out.append(dict(u=u, note="no options captured"))
            continue
        a242.opts_all = d1["opts"][exps[0]]
        for bull in (True, False):
            t = a242.s1(u, d1, h, l, bull)
            cond = None if pre is None else (pre["bull"] if bull else pre["bear"])
            out.append(dict(u=u, side="CALL" if bull else "PUT", cond=cond,
                            zero_dte=exps[0] == day, prev=prev[0][0], pdh=h, pdl=l, trade=t))
    return out


def main(day, check):
    res = evaluate(day)
    hm = lambda m: f"{(m + 1) // 60:02d}:{(m + 1) % 60:02d}"
    for r in res:
        if "note" in r:
            print(f"{day} {r['u']}: {r['note']}")
        else:
            t = r["trade"]
            print(f"{day} {r['u']} S1 {r['side']}: PDH {r['pdh']:.2f} PDL {r['pdl']:.2f} (prev {r['prev']}), squeeze "
                  f"{'unknown' if r['cond'] is None else 'yes' if r['cond'] else 'no'}, own expiry {'yes' if r['zero_dte'] else 'no'} -> "
                  + (f"entry {hm(t['minute'])}, exit {t['why']}, {t['ret']:+.1%}" if t else "no trade"))
    if check or day < START:
        print("check only: nothing recorded")
        return
    os.makedirs(OUT, exist_ok=True)
    path = f"{OUT}/forward.csv"
    rows = list(csv.reader(open(path))) if os.path.exists(path) else [
        ["day", "index", "side", "squeeze", "own_expiry", "entry", "exit", "ret"]]
    rows = [rows[0]] + [x for x in rows[1:] if x[0] != day]       # rerunning a day replaces it
    for r in res:
        if "note" not in r and r["trade"]:
            t = r["trade"]
            rows.append([day, r["u"], r["side"], "" if r["cond"] is None else int(r["cond"]), int(r["zero_dte"]), hm(t["minute"]), t["why"],
                         f"{t['ret']:.4f}"])
    csv.writer(open(path, "w", newline="")).writerows(rows)
    days_path = f"{OUT}/days.txt"
    days = set(open(days_path).read().split()) if os.path.exists(days_path) else set()
    days.add(day)
    open(days_path, "w").write("\n".join(sorted(days)) + "\n")
    body = rows[1:]
    def summ(sel):
        xs = [float(x[7]) for x in body if sel(x)]
        return f"{len(xs)} trades, mean {sum(xs) / len(xs):+.1%}" if xs else "0 trades"
    print(f"A-243 S1 forward ({len(days)} sessions from {START}): all {summ(lambda x: True)}; own expiry "
          f"{summ(lambda x: x[4] == '1')}; after a squeeze {summ(lambda x: x[3] == '1')}; read at {READ_AT} trades")


if __name__ == "__main__":
    main(sys.argv[1], "--check" in sys.argv[2:])
