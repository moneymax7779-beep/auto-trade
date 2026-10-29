"""A-228 (descriptive, registered before computing): does the near-ATM option-OI drift since the open, read at a
checkpoint, say which way the rest of the day goes? From the 25 Oct chart anatomy: on the trend days of the 27 tick
days the chart's "OI added since 09:15, calls - puts" line and the PCR of the 09:15 chain diverged monotonically by
10:30; on the chop days they hugged zero / 1.0 and crossed repeatedly. A-094 used the 10-minute OI change at the
checkpoint (not the cumulative drift) and FAILED; this is the cumulative version, the one input A-094 did not try.

Data: the per-minute feature snapshots stored by the A-094 replays (feat.snapshot, run code f75550eb4877-a94:
149 bar days 23 Jan-31 Aug 2026 + 24 tick days 2 Sep-13 Oct). Point-in-time: only snapshots at or before T.
  drift(T) = sum over minutes 09:16..T of (options.peOiChange1m - options.ceOiChange1m)   (weighted ATM-band sums)
  strength(T) = |drift| / sum |peOiChange1m| + |ceOiChange1m| over the same minutes   (0..1)
  call: UP if drift > 0 (puts written faster), DOWN if drift < 0; "confident" if strength >= S.
Label L1: sign(spot at 15:29 - spot at T). Splits as A-094: train <= 30 Jun, test Jul-Aug, final = tick days.
Baseline: side of VWAP at T (A-094's best price rule).

Pass criterion (fixed now): on the test rows (Jul-Aug) at T = 10:30 the confident call (S = 0.3) has >= 20 rows
and a hit rate >= 60 %, and on the final (tick) rows the same; and the confident hit rate beats the side-of-VWAP
rule on both. Anything less: FAIL, and no regime engine is built on it (A-094's conclusion stands).
Reported but not gated: T = 11:00 and 12:00, S = 0.2 / 0.5, the unconditional call, per-index.
"""
import collections, math, subprocess, sys

SQL = """
with runs as (select s.session_date, s.underlying, max(s.run_id) run_id from feat.snapshot s join research.run r on r.id = s.run_id
              where r.code_version = 'f75550eb4877-a94' group by 1, 2)
select s.session_date, s.underlying, to_char(s.snap_time at time zone 'Asia/Kolkata', 'HH24:MI'), s.spot,
       s.features->>'options.peOiChange1m', s.features->>'options.ceOiChange1m', s.features->>'structure.distVwapAtr'
from runs join feat.snapshot s using (session_date, underlying, run_id)
where (s.snap_time at time zone 'Asia/Kolkata')::time between '09:15' and '15:29' order by 1, 2, 3"""
TICK = {"2026-09-02", "2026-09-03", "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-15", "2026-09-17",
        "2026-09-18", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-28", "2026-09-29", "2026-09-30",
        "2026-10-01", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13"}


def split(d):
    if d in TICK:
        return "final"
    if d <= "2026-06-30":
        return "train"
    if "2026-07-01" <= d <= "2026-08-31":
        return "test"
    return None


def num(v):
    try:
        x = float(v)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-X", "-q", "-At",
                      "-F", "\t", "-c", SQL], capture_output=True, text=True, check=True).stdout
days = collections.defaultdict(list)
for line in out.split("\n"):
    if line:
        d, u, t, spot, pe, ce, vwap = line.split("\t")
        days[(d, u)].append((t, num(spot), num(pe), num(ce), num(vwap)))

rows = []   # (split, index, T, call, strength, vwap_call, label)
for (d, u), mins in days.items():
    sp = split(d)
    if sp is None:
        continue
    close = next((m[1] for m in reversed(mins) if m[1] is not None), None)
    if close is None:
        continue
    seen = set()
    drift = absum = 0.0
    for t, spot, pe, ce, vwap in mins:
        if t in seen or t <= "09:15":
            continue
        seen.add(t)
        if pe is not None and ce is not None:
            drift += pe - ce
            absum += abs(pe) + abs(ce)
        if t in ("10:30", "11:00", "12:00") and spot is not None:
            label = 1 if close > spot else -1 if close < spot else 0
            strength = abs(drift) / absum if absum > 0 else 0.0
            call = 1 if drift > 0 else -1 if drift < 0 else 0
            vc = 1 if vwap is not None and vwap > 0 else -1 if vwap is not None and vwap < 0 else 0
            rows.append((sp, u, t, call, strength, vc, label))

print("rows:", len(rows), " index-days:", len(rows) // 3)
for T in ("10:30", "11:00", "12:00"):
    for sp in ("train", "test", "final"):
        rs = [r for r in rows if r[0] == sp and r[2] == T and r[6] != 0]
        if not rs:
            continue
        allc = [r for r in rs if r[3] != 0]
        hit_all = sum(r[3] == r[6] for r in allc) / len(allc) if allc else float("nan")
        vw = [r for r in rs if r[5] != 0]
        hit_vw = sum(r[5] == r[6] for r in vw) / len(vw) if vw else float("nan")
        line = f"T={T} {sp:5s} n={len(rs):3d} drift-call {hit_all:5.1%} (n={len(allc)}) | side-of-VWAP {hit_vw:5.1%} (n={len(vw)})"
        for S in (0.2, 0.3, 0.5):
            cf = [r for r in allc if r[4] >= S]
            h = sum(r[3] == r[6] for r in cf) / len(cf) if cf else float("nan")
            line += f" | S>={S}: {h:5.1%} (n={len(cf)})"
        print(line)
print()
for u in ("NIFTY", "SENSEX"):
    for sp in ("test", "final"):
        cf = [r for r in rows if r[0] == sp and r[1] == u and r[2] == "10:30" and r[3] != 0 and r[4] >= 0.3 and r[6] != 0]
        h = sum(r[3] == r[6] for r in cf) / len(cf) if cf else float("nan")
        print(f"10:30 S>=0.3 {u:6s} {sp:5s} hit {h:5.1%} (n={len(cf)})")
