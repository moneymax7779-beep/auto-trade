#!/bin/bash
# A-308 (registered 3 Nov 2026, ledger bf2395f): the forward record of mac-v9 (fresh-extreme momentum cross, frozen at
# c380d76, image autotrade-trading-core:a307-local) against mac-v1, each alone, Rs 5 L, risk v17, on the own capture the
# daily sync has just loaded. Runs the image directly (a launchd job cannot read ~/Desktop), with a frozen copy of the
# config in $A308/config. A day already recorded is not replayed again. Read ONCE at >= 20 sessions (see the ledger).
# Usage: 2026-11-03-a308-mac-forward.sh YYYY-MM-DD   (prints one summary line last)
set -u
DAY=$1
A308="${A308_OUT:-$HOME/.autotrade-sync/a308}"
CSV="$A308/forward.csv"; TRADES="$A308/trades.csv"; mkdir -p "$A308/logs" "$A308/local"
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
if grep -q "^$DAY,mac-v9," "$CSV"; then
  echo "A-308 forward: $DAY already recorded ($(($(grep -c ',mac-v9,' "$CSV"))) sessions so far)"; exit 0
fi
docker image inspect autotrade-trading-core:a307-local >/dev/null 2>&1 || { echo "A-308 forward: image a307-local missing"; exit 1; }
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
for v in v1 v9; do
  LOGF="$A308/logs/$DAY-mac-$v.log"
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A308/config:/app/config:ro" -v "$A308/local:/app/.local" \
    autotrade-trading-core:a307-local --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files=config/strategy/ma-cross.$v.yaml > "$LOGF" 2>&1 < /dev/null
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-308 forward: $DAY mac-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW=$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID")
  eval "R_$v='$DAY,mac-$v,$SID,$ROW'"
  $AT -F, -c "select '$DAY', 'mac-$v', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position where session_id = $SID order by opened_at" >> "$TRADES.$DAY.tmp"
done
echo "$R_v1" >> "$CSV"; echo "$R_v9" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep -c ',mac-v9,' "$CSV")
echo "A-308 forward $DAY: mac-v1 ${R_v1#*,mac-v1,*,} | mac-v9 ${R_v9#*,mac-v9,*,} (trades,won,net,costs); sessions $N of 20 (read once at 20)"
