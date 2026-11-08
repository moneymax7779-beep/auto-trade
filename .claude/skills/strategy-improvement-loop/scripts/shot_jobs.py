#!/usr/bin/env python3
"""Build the screenshot job list for every trade of the given labels (entry + exit), plus one page per session.

usage: shot_jobs.py RUN_DIR SHOTS_DIR LABEL [LABEL ...] [--base http://127.0.0.1:8095]
  RUN_DIR    a replay_batch.sh folder (reads its progress.txt for label -> day -> session id)
  SHOTS_DIR  output root; writes SHOTS_DIR/<label>/{NN_<day>_session-<sid>_net<net>.png, trades/*.png, trades/index.tsv}
             and SHOTS_DIR/jobs.json for fullshots.mjs
Trade images use the session page's trade-moment link (/sessions/<sid>?position=<id>&moment=entry|exit): the whole day,
pinned at that minute, with the Trades table, stage changes and orders below (needs a UI build with that feature on 8095).
"""
import argparse, json, os, re, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("shots"); ap.add_argument("labels", nargs="+"); ap.add_argument("--base", default="http://127.0.0.1:8095")
a = ap.parse_args()
RX = re.compile(r"^\S+ (\S+) (\d{4}-\d\d-\d\d) (\d+) \{")
runs = {}
for line in open(f"{a.run}/progress.txt"):
    m = RX.match(line)
    if m and m.group(1) in a.labels:
        runs.setdefault(m.group(1), {})[m.group(2)] = m.group(3)
get = lambda path: json.load(urllib.request.urlopen(a.base + path))
jobs = []
for lab in a.labels:
    root = f"{a.shots}/{lab}"; os.makedirs(f"{root}/trades", exist_ok=True)
    n = 0; index = []
    for i, (day, sid) in enumerate(sorted(runs.get(lab, {}).items()), 1):
        s = get(f"/api/sessions/{sid}")
        net = round(float((s.get("summary") or {}).get("net") or 0))
        jobs.append([f"{root}/{i:02d}_{day}_session-{sid}_net{net:+d}.png", f"{a.base}/sessions/{sid}"])
        for k, p in enumerate(get(f"/api/sessions/{sid}/positions"), 1):
            if not (p.get("quantity") or 0) > 0:
                continue
            n += 1; pn = round(p["net"])
            for moment, ts in (("1-entry", p["opened_at"]), ("2-exit", p["closed_at"] or p["opened_at"])):
                why = (p["exit_reason"] or "OPEN") if moment == "2-exit" else p["option_side"]
                f = f"{n:03d}_{day}_s{sid}-#{k}_{p['underlying']}-{p['option_side']}_{moment}_{ts[11:16].replace(':', '')}_{why}_net{pn:+d}.png"
                jobs.append([f"{root}/trades/{f}", f"{a.base}/sessions/{sid}?position={p['id']}&moment={moment[2:]}"])
            index.append("\t".join([f"{n:03d}", day, f"session {sid} #{k}", f"{p['underlying']} {p['option_side']} {p['symbol']}",
                                    str(p["quantity"]), f"{p.get('average_cost')}", f"{p.get('average_exit')}",
                                    f"{p['opened_at'][11:19]} -> {(p['closed_at'] or '')[11:19]}", str(p["exit_reason"]), f"{pn:+,d}"]))
    open(f"{root}/trades/index.tsv", "w").write(
        "#\tdate\tsession\ttrade\tqty\tentry_px\texit_px\tentry -> exit\texit reason\tnet\n" + "\n".join(index) + "\n")
    print(lab, "sessions", len(runs.get(lab, {})), "trades", n)
json.dump(jobs, open(f"{a.shots}/jobs.json", "w"))
print("jobs", len(jobs), "->", f"{a.shots}/jobs.json")
