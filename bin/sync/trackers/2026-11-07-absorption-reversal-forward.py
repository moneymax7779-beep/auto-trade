#!/usr/bin/env python3
"""A-344 forward shadow (user 7 Nov: "i want this to run in shadow"): the frozen A-344 rules on one new own-capture day.

Runs nightly from ~/.autotrade-sync/daily-sync.sh after the day's md.future_tick has loaded. Imports the frozen study
(2026-11-07-absorption-reversal.py, next to this file) and applies it to DAY only: prior value from the two previous
sessions, slot medians from the previous 10, roll days skipped, arms 'confirm' and 'control'. Writes
  $A344_OUT/forward-trades.csv  one row per trade (the day's rows are replaced on a rerun)
  $A344_OUT/forward-days.csv    one row per index-day (regime / skip reason and trade counts)
and prints a one-line running summary last. Forward days start 2026-11-09; read once at 20 confirmed trades.
Local database only, read-only. Usage: python3 2026-11-07-absorption-reversal-forward.py DAY
"""
import csv, importlib.util, os, statistics, sys

FROM = "2026-11-09"
READ_AT = 20
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a344", os.path.join(HERE, "2026-11-07-absorption-reversal.py"))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)

TRADE_COLS = ["day", "u", "arm", "dir", "A", "B", "entry_ts", "exit_ts", "reason", "entry_base", "stop", "target",
              "pts_base", "pts_stressed", "R_base", "R_stressed"]
DAY_COLS = ["day", "u", "status", "n_confirm", "n_control"]


def sessions(u, day):
    return [r[0] for r in a.q(f"select distinct session_date::text from md.future_tick where underlying = '{u}' "
                              f"and session_date <= '{day}' order by 1")]


def replace_rows(path, cols, day, rows):
    old = []
    if os.path.exists(path):
        with open(path) as f:
            old = [r for r in csv.DictReader(f) if r["day"] != day]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols); w.writeheader()
        for r in sorted(old + rows, key=lambda r: (r["day"], r["u"], r.get("arm", ""), r.get("entry_ts", ""))):
            w.writerow({k: r.get(k, "") for k in cols})


def day_run(u, day):
    ds = sessions(u, day)
    if not ds or ds[-1] != day:
        return "NO_DATA", []
    n = len(ds) - 1
    if n < 2:
        return "TOO_FEW_PRIOR", []
    cache = {}
    for d in ds[max(0, n - 10):n + 1]:
        exp, tk = a.load(u, d); cl = a.classify(tk)
        cache[d] = {"exp": exp, "tk": tk, "cl": cl, "va": a.value_area(u, tk, cl),
                    "slot_vol": {a.slot(b, d): b["vol"] for b in a.bars(u, d, tk, cl, "09:15", "10:45")}}
    p1, p2, cur = cache[ds[n - 1]], cache[ds[n - 2]], cache[day]
    if p1["exp"] != cur["exp"] or p2["exp"] != cur["exp"]:
        return "SKIP_ROLL", []
    prev = [cache[x]["slot_vol"] for x in ds[max(0, n - 10):n]]
    med = {}
    for s in range(18):
        vals = [pv[s] for pv in prev if s in pv]
        if len(vals) >= 5:
            med[s] = statistics.median(vals)
    out = []; regime = None
    for arm in ("confirm", "control"):
        tr, regime = a.run_day(u, day, cur["tk"], cur["cl"], p1, p2, med, arm)
        for t in tr:
            t["day"] = day; t["arm"] = arm
            out.append({k: (round(v, 4) if isinstance(v, float) else v) for k, v in t.items()})
    return regime, out


def main():
    day = sys.argv[1]
    outdir = os.environ.get("A344_OUT", ".local/a344/forward")
    os.makedirs(outdir, exist_ok=True)
    if day < FROM and not os.environ.get("A344_FORCE"):
        print(f"A-344 forward {day}: before {FROM}, not recorded"); return
    trades, days = [], []
    for u in ("NIFTY", "SENSEX"):
        status, tr = day_run(u, day)
        trades += tr
        days.append({"day": day, "u": u, "status": status, "n_confirm": sum(t["arm"] == "confirm" for t in tr),
                     "n_control": sum(t["arm"] == "control" for t in tr)})
        for t in tr:
            print(f"{day} {u} {t['arm']} {'long' if t['dir'] > 0 else 'short'} {t['entry_ts']} -> {t['exit_ts']} "
                  f"{t['reason']} {t['R_stressed']:+.2f} R stressed ({t['pts_stressed']:+.1f} pts)")
    replace_rows(os.path.join(outdir, "forward-trades.csv"), TRADE_COLS, day, trades)
    replace_rows(os.path.join(outdir, "forward-days.csv"), DAY_COLS, day, days)
    with open(os.path.join(outdir, "forward-trades.csv")) as f:
        allt = list(csv.DictReader(f))
    fwd = [t for t in allt if t["day"] >= FROM]
    c = [float(t["R_stressed"]) for t in fwd if t["arm"] == "confirm"]
    k = [float(t["R_stressed"]) for t in fwd if t["arm"] == "control"]
    with open(os.path.join(outdir, "forward-days.csv")) as f:
        nd = sum(1 for r in csv.DictReader(f) if r["day"] >= FROM)
    avg = lambda x: f"{statistics.mean(x):+.2f} R" if x else "-"
    today = "; ".join(f"{d['u']} {d['status']} {d['n_confirm']}c/{d['n_control']}k" for d in days)
    print(f"A-344 forward {day}: {today} | to date {nd} index-days, confirmed {len(c)}/{READ_AT} avg {avg(c)}, "
          f"control {len(k)} avg {avg(k)}" + (" | READ DUE" if len(c) >= READ_AT else ""))


if __name__ == "__main__":
    main()
