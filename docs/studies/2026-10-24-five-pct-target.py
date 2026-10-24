#!/usr/bin/env python3
"""A-187: the A-183 (impulse a+b+c) and A-185 (day-extreme a+b) triggers with a 5 % target only (fixed in the ledger first).

E5a = +5 % / -5 % / 15 min, E5b = +5 % / -10 % / 15 min; pricing as A-183. Usage: python3 2026-10-24-five-pct-target.py ticks|bars26|design
"""
import importlib.util, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


imp = load("a183", "2026-10-24-impulse-scalp.py")
ext = load("a185", "2026-10-24-day-extreme-break.py")
EXITS = {"E5a": (0.05, 0.05, 15), "E5b": (0.05, 0.10, 15)}
for m in (imp, ext.s):                          # the A-185 module trades through its own copy of A-183
    m.EXITS = EXITS
imp.CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a187/impulse"
ext.CACHE = "/Users/satyasiripuram/Desktop/Dev/Code/auto-trade-deploy/.local/a187/extreme"


def show(title, res, variant, s):
    print(f"\n== {title}  [{variant}]")
    for ex in EXITS:
        sm = s.summary(res.get(variant, []), ex)
        ci = s.boot(res.get(variant, []), ex)
        print(f"  {ex} {EXITS[ex]}  {s.fmt(sm)}" + (f"  CI95 [{ci[0]:+.2%}, {ci[1]:+.2%}]" if ci else ""))


if __name__ == "__main__":
    which = sys.argv[1]
    if which in ("ticks", "design"):
        days = imp.TICK_DAYS if which == "ticks" else imp.DESIGN
        show(f"A-183 impulse trigger, {which} ({len(days)} days)", imp.run(days, "ticks"), "a+b+c", imp)
        show(f"A-185 day-extreme trigger, {which} ({len(days)} days)", ext.run(days, "ticks"), "a+b", ext.s)
    elif which == "bars26":
        days = imp.bar_days("2026-01-01", "2026-09-30")
        show(f"A-183 impulse trigger, bars Jan-Sep 2026 ({len(days)} days)", imp.run(days, "bars"), "a+b+c", imp)
        show(f"A-185 day-extreme trigger, bars Jan-Sep 2026 ({len(days)} days)", ext.run(days, "bars"), "a+b", ext.s)
