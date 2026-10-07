#!/usr/bin/env bash
# Chained replays: each day starts from the previous day's ending equity, as risk v8/v9 size live sessions.
#
#   bin/replay-chain.sh [--source own|zt|bars] [--equity 500000] [--from YYYY-MM-DD --to YYYY-MM-DD | DAY ...] [-- extra args]
#
# Weekdays without data (holidays) are skipped. Runs trading-core through docker compose; set COMPOSE for another
# host, e.g. on the GCP VM:
#   COMPOSE="docker compose -f docker-compose.yml -f docker-compose.gcp.yml" bin/replay-chain.sh --from 2026-09-29 --to 2026-10-06
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=own; EQUITY=500000; FROM=""; TO=""; DAYS=(); EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE=$2; shift 2 ;;
    --equity) EQUITY=$2; shift 2 ;;
    --from) FROM=$2; shift 2 ;;
    --to) TO=$2; shift 2 ;;
    --) shift; EXTRA=("$@"); break ;;
    *) DAYS+=("$1"); shift ;;
  esac
done
if [ -n "$FROM" ]; then
  d=$FROM
  while [[ "$d" < "$TO" || "$d" == "$TO" ]]; do
    dow=$(date -d "$d" +%u 2>/dev/null || date -j -f %Y-%m-%d "$d" +%u)
    [ "$dow" -le 5 ] && DAYS+=("$d")
    d=$(date -d "$d + 1 day" +%F 2>/dev/null || date -j -v+1d -f %Y-%m-%d "$d" +%F)
  done
fi
[ ${#DAYS[@]} -gt 0 ] || { echo "no days given" >&2; exit 2; }
COMPOSE=${COMPOSE:-docker compose}
START=$EQUITY
printf "%-10s %14s %12s %14s  %s\n" day equity_before net equity_after trades
for day in "${DAYS[@]}"; do
  log=$(mktemp)
  if $COMPOSE run --rm --no-deps trading-core --mode=replay --session="$day" --autotrade.trading.replay-source="$SOURCE" \
       --autotrade.trading.replay-equity="$EQUITY" ${EXTRA[@]+"${EXTRA[@]}"} >"$log" 2>&1 \
     && grep -q "session summary:" "$log"; then
    net=$(grep "session summary:" "$log" | tail -1 | sed -E 's/.*[ {]net=(-?[0-9.]+).*/\1/')
    pos=$(grep "session summary:" "$log" | tail -1 | sed -E 's/.*positions=([0-9]+).*/\1/')
    after=$(awk -v a="$EQUITY" -v n="$net" 'BEGIN { printf "%.0f", a + n }')
    printf "%-10s %14.0f %12.0f %14.0f  %s\n" "$day" "$EQUITY" "$net" "$after" "$pos"
    EQUITY=$after
  else
    printf "%-10s %14.0f %12s %14.0f  %s\n" "$day" "$EQUITY" "-" "$EQUITY" "no data (holiday or not captured)"
  fi
  rm -f "$log"
done
awk -v s="$START" -v e="$EQUITY" 'BEGIN { printf "start %.0f  end %.0f  multiple %.2fx\n", s, e, e / s }'
