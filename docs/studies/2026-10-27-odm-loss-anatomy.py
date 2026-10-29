#!/usr/bin/env python3
"""odm loss anatomy (27 Oct 2026, descriptive; follows A-230 on the same 391 bar-day trades: odm-v8 Oct 2024-Dec 2025 and
odm-v1 2026, engine replays, the index bars end 6 Oct 2026). Questions A-230 did not ask:
  1. calls vs puts by period;
  2. which exits make and lose the money;
  3. for the trades stopped at the session extreme: how far the index went their way first (MFE in R, R = the stop
     distance) and where it was at 12:00 relative to the entry (in R) - is the stop cutting trades that later worked?
"""
import collections, importlib.util, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a230", os.path.join(HERE, "2026-10-25-odm-mac-timing.py"))
a230 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a230)

raw = a230.trades("a172-99619b08", "opening-drive-momentum.v8.yaml", "opening-drive-momentum", True)
raw += [t for t in a230.trades("9570ad4-a85", "opening-drive-momentum.v1.yaml", "opening-drive-momentum", True)
        if t["d"] >= "2026-01-01"]
idx, _ = a230.load_bars()


def line(label, ts):
    if not ts:
        return f"  {label:28s} n   0"
    net = sum(t["net"] for t in ts)
    return (f"  {label:28s} n {len(ts):3d}  win {sum(t['net'] > 0 for t in ts) / len(ts):4.0%}  mean {statistics.mean(t['pct'] for t in ts):+6.1f} % "
            f"of premium  net ₹{net:+12,.0f}")


print(f"odm trades: {len(raw)}")
print("\n1. calls vs puts")
for p, _, _ in a230.PERIODS:
    for side, s in (("calls", 1), ("puts", -1)):
        print(line(f"{p} {side}", [t for t in raw if a230.period(t["d"]) == p and t["sign"] == s]))

print("\n2. exits")
by = collections.defaultdict(list)
for t in raw:
    by[t["reason"] or "?"].append(t)
for k, ts in sorted(by.items(), key=lambda kv: -len(kv[1])):
    print(line(k, ts))
    for p, _, _ in a230.PERIODS:
        print("    " + line(p, [t for t in ts if a230.period(t["d"]) == p]).strip())

print("\n3. stopped trades: favourable excursion before the stop, and the index at 12:00 vs entry (both in R)")
rows = []
for t in raw:
    if t["reason"] != "STOP":
        continue
    bars = idx.get((t["u"], t["d"]))
    if not bars:
        continue
    m = t["entry"]
    before = [bars[x] for x in a230.mins("09:15", m) if x in bars]
    prev_min = a230.shift(m, -1)
    if not before or prev_min not in bars:
        continue
    spot = bars[prev_min][3]
    stop = min(b[2] for b in before) if t["sign"] > 0 else max(b[1] for b in before)
    r = abs(spot - stop)
    if r <= 0:
        continue
    held = [bars[x] for x in a230.mins(m, t["exit"]) if x in bars]
    if not held:
        continue
    mfe = max((b[1] - spot) if t["sign"] > 0 else (spot - b[2]) for b in held) / r
    noon = bars.get("12:00") or bars.get("11:59")
    after = (noon[3] - spot) * t["sign"] / r if noon else None
    minutes = len(list(a230.mins(m, t["exit"])))
    rows.append(dict(t, mfe=mfe, noon=after, minutes=minutes, r_pct=r / spot * 100))
print(f"  stopped trades with bars: {len(rows)}; net ₹{sum(x['net'] for x in rows):+,.0f}; "
      f"median minutes to the stop {statistics.median(x['minutes'] for x in rows):.0f}; median stop distance "
      f"{statistics.median(x['r_pct'] for x in rows):.2f} % of the index")
for lo, hi, label in ((-9, 0.25, "MFE < 0.25R (wrong from the start)"), (0.25, 0.5, "MFE 0.25-0.5R"),
                      (0.5, 1.0, "MFE 0.5-1R (was working)"), (1.0, 99, "MFE >= 1R (was clearly working)")):
    g = [x for x in rows if lo <= x["mfe"] < hi]
    if g:
        print(f"  {label:36s} n {len(g):3d}  net ₹{sum(x['net'] for x in g):+11,.0f}  median minutes {statistics.median(x['minutes'] for x in g):4.0f}")
noon = [x for x in rows if x["noon"] is not None]
back = [x for x in noon if x["noon"] > 0]
print(f"  at 12:00 the index was back on the trade's side of the entry for {len(back)} of {len(noon)} stopped trades "
      f"({len(back) / len(noon):.0%}); beyond +1R for {sum(x['noon'] >= 1 for x in noon)}; "
      f"further against (below -1R, the stop was right) for {sum(x['noon'] <= -1 for x in noon)}")
for p, _, _ in a230.PERIODS:
    g = [x for x in noon if a230.period(x["d"]) == p]
    if g:
        print(f"    {p}: n {len(g)}  back above entry by 12:00 {sum(x['noon'] > 0 for x in g) / len(g):.0%}  "
              f"below -1R {sum(x['noon'] <= -1 for x in g) / len(g):.0%}  median at 12:00 {statistics.median(x['noon'] for x in g):+.2f}R")
for side, s in (("calls", 1), ("puts", -1)):
    g = [x for x in noon if x["sign"] == s]
    if g:
        print(f"    {side}: n {len(g)}  back above entry by 12:00 {sum(x['noon'] > 0 for x in g) / len(g):.0%}  "
              f"median MFE {statistics.median(x['mfe'] for x in g):.2f}R  median at 12:00 {statistics.median(x['noon'] for x in g):+.2f}R")
