#!/bin/bash
# A-548 (user 22 Nov 2026: "yes set up and do replay"): the forward record of mco-v1 (ma-cross-open, A-537: mac-v34's rules on
# EMAs carried from the previous session, 09:30-10:21) and mco-v2 (A-546: + skip a cross in the opening gap's direction before
# 09:45 when |gap| >= 0.25 %), each alone, Rs 5 L, risk v44, features v15, NIFTY + SENSEX, on the own capture the daily sync
# has just loaded. Image autotrade-trading-core:mco-a548-frozen (= a547-mco: worktree ../auto-trade-a537 branch
# research/a537-mac-open, main 29d923e + mco code, uncommitted), config frozen in $A548/config. Days from 23 Nov 2026.
# Read ONCE at 20 sessions with an mco trade in either arm. Usage: 2026-11-22-a548-mco-forward.sh YYYY-MM-DD
set -u
DAY=$1
A548="${A548_OUT:-$HOME/.autotrade-sync/a548}"; IMG=mco-a548-frozen
CONF="${A548_CONFIG:-$HOME/.autotrade-sync/a548/config}"   # frozen config (independent of the output folder)
GATE=/Users/satyasiripuram/.autotrade-sync/bin/replay-slot   # 23 Nov: launchd may not run scripts under ~/Desktop
CSV="$A548/forward.csv"; TRADES="$A548/trades.csv"; mkdir -p "$A548/logs"
[[ "$DAY" < "${A548_FROM:-2026-11-23}" ]] && { echo "A-548 forward: $DAY is before the first forward day (23 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,symbol,opened,closed,exit_reason,quantity,entry,exit,net" > "$TRADES"
if grep -q "^$DAY,mco-v1," "$CSV"; then
  echo "A-548 forward: $DAY already recorded ($(grep -c ',mco-v1,' "$CSV") sessions so far)"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
run() {   # variant -> sets ROW, appends trades to the day's tmp file
  local v=$1 LOGF="$A548/logs/$DAY-mco-$1.log"
  docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "A-548 forward: image $IMG missing"; exit 1; }
  REPLAY_SLOT_LABEL=a548fwd-$v-$DAY $GATE docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -e AUTOTRADE_CODE_VERSION=a548fwd-$v -v "$CONF:/app/config:ro" \
    -e AUTOTRADE_REPLAY_CACHE_DIR=/cache -v /Volumes/T7/autotrade-replay-cache:/cache \
    "autotrade-trading-core:$IMG" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.underlyings=NIFTY,SENSEX \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v44.yaml \
    --autotrade.trading.features-file=config/features/features.v15.yaml \
    --autotrade.trading.strategy-files=config/strategy/ma-cross-open.$v.yaml --spring.flyway.enabled=false > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-548 forward: $DAY mco-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,mco-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0")"
  $AT -F, -c "select '$DAY', 'mco-$v', underlying, option_side, symbol, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(average_cost::numeric,2), round(average_exit::numeric,2), round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
run v1; R_v1=$ROW
run v2; R_v2=$ROW
echo "$R_v1" >> "$CSV"; echo "$R_v2" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(awk -F, 'NR>1 && $4>0 {print $1}' "$CSV" | sort -u | wc -l | tr -d ' ')
echo "A-548 forward $DAY: mco-v1 ${R_v1#*,mco-v1,*,} | mco-v2 ${R_v2#*,mco-v2,*,} (trades,won,net,costs); sessions with an mco trade $N of 20 (read once at 20)"
