#!/bin/bash
# Runs ON zt-autotrade (sudo), started by bin/sync/daily-sync.sh on the Mac before it exports a day. Prints
# "<running> <pending>" for one session DAY: running = trade.session rows still RUNNING; pending = the shadow strategy
# files of the running trading-core image's application.yml with no finished (non-STOPPED) SHADOW-1 session yet (the rule
# of ShadowRunner.pending), or "?" when the list cannot be read. Reads only; changes nothing.
# Usage: vm-day-status.sh YYYY-MM-DD
set -u
DAY=$1
AT="docker exec autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
R=$($AT -c "select count(*) from trade.session where status = 'RUNNING' and session_date = '$DAY'" 2>/dev/null) || R='?'
J=$(mktemp); P='?'
if docker cp autotrade-trading-core:/app/app.jar "$J" 2>/dev/null; then
  F=$(python3 - "$J" <<'EOF'
import re, sys, zipfile
y = zipfile.ZipFile(sys.argv[1]).read("BOOT-INF/classes/application.yml").decode()
m = re.search(r"^    shadow-strategy-files:\n((?:(?:      .*)?\n)+)", y, re.M)
print(" ".join(f.rsplit("/", 1)[1] for f in re.findall(r"^      - (config/strategy/\S+\.yaml)", m.group(1), re.M)) if m else "")
EOF
  ) && {
    P=0
    for f in $F; do
      ran=$($AT -c "select exists (select 1 from trade.session where account = 'SHADOW-1' and session_date = '$DAY' \
        and status <> 'STOPPED' and jsonb_exists(configs, '$f'))" 2>/dev/null)
      [ "$ran" = t ] || P=$((P + 1))
    done
  }
fi
rm -f "$J"
echo "${R:-?} $P"
