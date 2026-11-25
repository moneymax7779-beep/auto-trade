#!/bin/bash
# A-317 (registered 4 Nov 2026, ledger 019c10d): the forward record of mac-v14 (skip a cross more than 0.758 ATR on the wrong
# side of VWAP; image autotrade-trading-core:a317-local = research/a317-mac-vwap-side 27aff92) against mac-v1, each alone,
# Rs 5 L, risk v17, on the own capture the daily sync has just loaded. mac-v1 is A-308's row for the day (same image
# a307-local and config); if A-308 did not record the day, mac-v1 is replayed here on a307-local. Days from 5 Nov 2026 only.
# Read ONCE at >= 20 sessions (see the ledger). Usage: 2026-11-04-a317-mac-vwap-forward.sh YYYY-MM-DD
set -u
DAY=$1
A317="${A317_OUT:-$HOME/.autotrade-sync/a317}"; A308="${A308_OUT:-$HOME/.autotrade-sync/a308}"
CSV="$A317/forward.csv"; TRADES="$A317/trades.csv"; mkdir -p "$A317/logs" "$A317/local"
[[ "$DAY" < "2026-11-05" ]] && { echo "A-317 forward: $DAY is before the first forward day (5 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
if grep -q "^$DAY,mac-v14," "$CSV"; then
  echo "A-317 forward: $DAY already recorded ($(grep -c ',mac-v14,' "$CSV") sessions so far)"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
run() {   # variant image -> sets ROW, appends trades to the day's tmp file
  local v=$1 img=$2 LOGF="$A317/logs/$DAY-mac-$1.log"
  docker image inspect "autotrade-trading-core:$img" >/dev/null 2>&1 || { echo "A-317 forward: image $img missing"; exit 1; }
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A317/config:/app/config:ro" -v "$A317/local:/app/.local" \
    "autotrade-trading-core:$img" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files=config/strategy/ma-cross.$v.yaml > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-317 forward: $DAY mac-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,mac-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID")"
  $AT -F, -c "select '$DAY', 'mac-$v', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position where session_id = $SID order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
if grep -q "^$DAY,mac-v1," "$A308/forward.csv" 2>/dev/null; then
  R_v1=$(grep "^$DAY,mac-v1," "$A308/forward.csv" | tail -1)
  grep "^$DAY,mac-v1," "$A308/trades.csv" >> "$TRADES.$DAY.tmp"
else
  run v1 a307-local; R_v1=$ROW
fi
run v14 a317-local; R_v14=$ROW
echo "$R_v1" >> "$CSV"; echo "$R_v14" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep -c ',mac-v14,' "$CSV")
echo "A-317 forward $DAY: mac-v1 ${R_v1#*,mac-v1,*,} | mac-v14 ${R_v14#*,mac-v14,*,} (trades,won,net,costs); sessions $N of 20 (read once at 20)"
