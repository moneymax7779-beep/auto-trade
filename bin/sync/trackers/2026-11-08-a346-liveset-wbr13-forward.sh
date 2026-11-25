#!/bin/bash
# A-346 (ledger a814155; user 8 Nov 2026 ~00:30: "deploy in local live set along with this live wbr-v13"): the forward record of
# the live set WITH wbr-v13 (ecr-v11, etr-v1, mac-v34, obc-v13, wbr-v13 last; one session, shared Rs 5 L, risk v17) against
# the same four WITHOUT it, on the own capture the daily sync has just loaded. Image autotrade-trading-core:wbr13-a346
# (= local13, branch deploy/local-wbr13), config frozen in $A346/config. Days from 9 Nov 2026. Read ONCE at 20 expiry days.
# Usage: 2026-11-08-a346-liveset-wbr13-forward.sh YYYY-MM-DD
set -u
DAY=$1
A346="${A346_OUT:-$HOME/.autotrade-sync/a346}"; IMG=wbr13-a346
CSV="$A346/forward.csv"; TRADES="$A346/trades.csv"; mkdir -p "$A346/logs" "$A346/local"
[[ "$DAY" < "${A346_FROM:-2026-11-09}" ]] && { echo "A-346 forward: $DAY is before the first forward day (9 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,strategy,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
if grep -q "^$DAY,live-set5," "$CSV"; then
  echo "A-346 forward: $DAY already recorded ($(grep -c ',live-set5,' "$CSV") sessions so far)"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
set5() { FILES=config/strategy/early-confirm-runner.v11.yaml,config/strategy/expiry-trend-rider.v1.yaml,config/strategy/ma-cross.v34.yaml,config/strategy/oi-burst-cross.v13.yaml$1; }
run() {   # variant -> sets ROW, appends trades to the day's tmp file
  local v=$1 LOGF="$A346/logs/$DAY-$1.log"
  docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "A-346 forward: image $IMG missing"; exit 1; }
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A346/config:/app/config:ro" -v "$A346/local:/app/.local" \
    "autotrade-trading-core:$IMG" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files=$FILES > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-346 forward: $DAY live-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,live-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0")"
  $AT -F, -c "select '$DAY', 'live-$v', strategy_id, underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
set5 ",config/strategy/wall-break-rider.v13.yaml"; run set5; R_5=$ROW
set5 ""; run set4; R_4=$ROW
echo "$R_5" >> "$CSV"; echo "$R_4" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep -c ',live-set5,' "$CSV")
echo "A-346 forward $DAY: set with wbr-v13 ${R_5#*,live-set5,*,} | set without ${R_4#*,live-set4,*,} (trades,won,net,costs); sessions $N (read once at 20 expiry days)"
