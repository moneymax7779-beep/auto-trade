#!/usr/bin/env python3
"""List a strategy's live and shadow versions from application.yml at a git ref (default main), and the tick days.

usage: find_versions.py STRATEGY_ID [--ref main] [--repo /path/to/auto-trade]
prints: "live <file>" / "shadow <file>" lines (file relative to config/), then "tickdays <n> <first> <last>".
A strategy id is the file stem without the version, e.g. oi-burst-cross for config/strategy/oi-burst-cross.v13.yaml.
"""
import argparse, re, subprocess

ap = argparse.ArgumentParser()
ap.add_argument("strategy"); ap.add_argument("--ref", default="main")
ap.add_argument("--repo", default="/Users/satyasiripuram/Desktop/Dev/Code/auto-trade")
a = ap.parse_args()
yml = subprocess.run(["git", "-C", a.repo, "show", f"{a.ref}:app-trading-core/src/main/resources/application.yml"],
                     capture_output=True, text=True, check=True).stdout
section = None
pat = re.compile(r"^\s*-\s*config/(strategy/" + re.escape(a.strategy) + r"\.v[^/\s#]+\.yaml)")
for line in yml.splitlines():
    s = line.strip()
    if s.startswith("strategy-files:"): section = "live"
    elif s.startswith("shadow-strategy-files:"): section = "shadow"
    elif re.match(r"^[a-z-]+:", s) and not s.startswith("-"): section = None
    m = pat.match(line)
    if m and section: print(section, m.group(1))
q = "select count(distinct session_date), min(session_date), max(session_date) from md.load_manifest where kind = 'CAPTURE'"
r = subprocess.run(["docker", "exec", "autotrade-postgres", "psql", "-U", "autotrade", "-d", "autotrade", "-XAtF ", "-c", q],
                   capture_output=True, text=True).stdout.strip()
print("tickdays", r)
