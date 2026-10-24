#!/usr/bin/env python3
"""A-209: forward record of live mac (ma-cross) trades split by the previous session's India VIX close (registered in the
ledger before the first session; read once after 30 sessions from 26 Oct 2026).

Input on stdin: GCP's PAPER_LIVE ma-cross positions for DAY as CSV
  session_date,session_id,underlying,option_side,opened_hhmm,net,premium_paid
(the daily sync pipes them from a read-only query over IAP ssh). The previous session's VIX close comes from the local
ref.index_candle (INDIA_VIX, GCP's own capture, synced nightly). Appends to $A209_OUT/forward.csv and logs the day in
days.txt, then prints the running totals.
Usage: ... | python3 2026-10-24-mac-vix-forward.py DAY
"""
import csv, io, os, statistics, subprocess, sys

OUT = os.environ.get("A209_OUT", os.path.expanduser("~/.autotrade-sync/a209"))
START, READ_AT = "2026-10-26", 30


def q(sql):
    out = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-Atc",
                          f"copy ({sql}) to stdout with csv"], capture_output=True, text=True, check=True).stdout
    return list(csv.reader(io.StringIO(out)))


def prev_vix(day):
    r = q(f"select session_date::text, close from ref.index_candle where symbol = 'INDIA_VIX' and session_date < '{day}' "
          f"order by session_date desc, bar_start desc limit 1")
    return (r[0][0], float(r[0][1])) if r and r[0] else (None, None)


def main(day):
    os.makedirs(OUT, exist_ok=True)
    if day < START:
        print(f"A-209: {day} is before {START}, nothing recorded")
        return
    pday, vix = prev_vix(day)
    path = f"{OUT}/forward.csv"
    rows = list(csv.reader(open(path))) if os.path.exists(path) else [["day", "session_id", "underlying", "side", "opened",
                                                                       "net", "premium", "net_pct", "prev_day", "prev_vix", "group"]]
    seen = {(r[0], r[1], r[2], r[3], r[4]) for r in rows[1:]}
    added = 0
    for r in csv.reader(sys.stdin):
        if len(r) != 7 or r[0] != day:
            continue
        d, sid, u, side, t, net, prem = r
        if (d, sid, u, side, t) in seen:
            continue
        net, prem = float(net), float(prem)
        grp = "n/a" if vix is None else ("lt15" if vix < 15 else "ge15")
        rows.append([d, sid, u, side, t, f"{net:.0f}", f"{prem:.0f}", f"{net / prem:.4f}" if prem else "", pday or "",
                     f"{vix:.2f}" if vix is not None else "", grp])
        added += 1
    csv.writer(open(path, "w", newline="")).writerows(rows)
    days_path = f"{OUT}/days.txt"
    days = set(open(days_path).read().split()) if os.path.exists(days_path) else set()
    days.add(day)
    open(days_path, "w").write("\n".join(sorted(days)) + "\n")
    body = rows[1:]
    parts = []
    for g in ("lt15", "ge15"):
        xs = [r for r in body if r[10] == g]
        if xs:
            pct = [float(r[7]) for r in xs if r[7]]
            parts.append(f"{g} n={len(xs)} Rs {sum(float(r[5]) for r in xs):+,.0f} mean {statistics.mean(pct):+.1%}")
        else:
            parts.append(f"{g} n=0")
    n = len(days)
    note = "  ** READ NOW (30 sessions) **" if n == READ_AT else ""
    print(f"A-209 {day}: +{added} mac trade(s), prev VIX {vix if vix is not None else 'n/a'}; sessions {n}/{READ_AT}; "
          + "; ".join(parts) + note)


if __name__ == "__main__":
    main(sys.argv[1])
