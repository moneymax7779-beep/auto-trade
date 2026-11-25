#!/bin/bash
# A-484 (user 18 Nov 2026: "yes add the v34+VWAP arm"): the forward record of mac-v49 (= live mac-v34 + A-317's frozen
# VWAP-side rule: no cross more than 0.758 ATR(3m) on the wrong side of the session VWAP) against mac-v34, each alone, Rs 5 L,
# risk v44 and features v15 (the live set's), on the own capture the daily sync has just loaded. Image
# autotrade-trading-core:mac49-a484-frozen (branch research/a484-mac-v34-vwap in ../auto-trade-a484, main caa7af2 + the
# rule), config frozen in $A484/config. Days from 19 Nov 2026 (18 Nov, the day the idea came from, is not counted).
# Read ONCE at 20 sessions. Usage: 2026-11-18-a484-mac-v49-forward.sh YYYY-MM-DD
set -u
DAY=$1
A484="${A484_OUT:-$HOME/.autotrade-sync/a484}"; IMG=mac49-a484-frozen
CSV="$A484/forward.csv"; TRADES="$A484/trades.csv"; mkdir -p "$A484/logs" "$A484/local"
[[ "$DAY" < "${A484_FROM:-2026-11-19}" ]] && { echo "A-484 forward: $DAY is before the first forward day (19 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
if grep -q "^$DAY,mac-v49," "$CSV"; then
  echo "A-484 forward: $DAY already recorded ($(grep -c ',mac-v49,' "$CSV") sessions so far)"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
run() {   # variant -> sets ROW, appends trades to the day's tmp file
  local v=$1 LOGF="$A484/logs/$DAY-mac-$1.log"
  docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "A-484 forward: image $IMG missing"; exit 1; }
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A484/config:/app/config:ro" -v "$A484/local:/app/.local" \
    "autotrade-trading-core:$IMG" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v44.yaml \
    --autotrade.trading.features-file=config/features/features.v15.yaml \
    --autotrade.trading.strategy-files=config/strategy/ma-cross.$v.yaml > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-484 forward: $DAY mac-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,mac-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0")"
  $AT -F, -c "select '$DAY', 'mac-$v', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
run v49; R_v49=$ROW
run v34; R_v34=$ROW
echo "$R_v49" >> "$CSV"; echo "$R_v34" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep -c ',mac-v49,' "$CSV")
echo "A-484 forward $DAY: mac-v49 ${R_v49#*,mac-v49,*,} | mac-v34 ${R_v34#*,mac-v34,*,} (trades,won,net,costs); sessions $N of 20 (read once at 20)"
