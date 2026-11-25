#!/bin/bash
# A-557 (user 22 Nov 2026: "set up the ₹60K limit as a forward PAPER shadow"): the forward record of A-555. Each new
# own-capture tick day the daily sync loads, the A-532 P set (etr-v1, mac-v34, obc-v31t1431, wbr-v29, sbr-v27, Rs 5 L,
# features v15, NIFTY + SENSEX) is replayed twice: C = risk v44mc (account daily-loss limit 22 % for everyone) and
# L = risk v44mcol12 (etr exempt from the account limit, its own limit 12 % of equity = Rs 60K). Image
# autotrade-trading-core:a555-frozen (= a555-ownloss60: worktree ../auto-trade-a552, branch research/a552-etr-own-loss,
# uncommitted), config frozen in $A555/config. Days from 23 Nov 2026. Read ONCE at 8 days where C and L differ, or at
# 60 forward days. Usage: 2026-11-22-a555-etr-limit-forward.sh YYYY-MM-DD
set -u
DAY=$1
A555="${A555_OUT:-$HOME/.autotrade-sync/a555}"; IMG=a555-frozen
CONF="${A555_CONFIG:-$HOME/.autotrade-sync/a555/config}"
GATE=/Users/satyasiripuram/.autotrade-sync/bin/replay-slot   # 23 Nov: launchd may not run scripts under ~/Desktop
CSV="$A555/forward.csv"; TRADES="$A555/trades.csv"; mkdir -p "$A555/logs"
FILES=config/strategy/expiry-trend-rider.v1.yaml,config/strategy/ma-cross.v34.yaml,config/strategy/oi-burst-cross.v31t1431.yaml,config/strategy/wall-break-rider.v29.yaml,config/strategy/session-break-retest.v27.yaml
[[ "$DAY" < "${A555_FROM:-2026-11-23}" ]] && { echo "A-557 forward: $DAY is before the first forward day (23 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,arm,session,positions,wins,net,costs,kill_switches,etr_net" > "$CSV"
[ -f "$TRADES" ] || echo "day,arm,strategy,underlying,side,symbol,opened,closed,exit_reason,lots,quantity,entry,exit,net" > "$TRADES"
if grep -q "^$DAY,C," "$CSV"; then
  echo "A-557 forward: $DAY already recorded"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
run() {   # arm risk-file -> sets ROW, appends trades to the day's tmp file
  local arm=$1 risk=$2 LOGF="$A555/logs/$DAY-$1.log"
  docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "A-557 forward: image $IMG missing"; exit 1; }
  REPLAY_SLOT_LABEL=a557fwd-$arm-$DAY $GATE docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -e AUTOTRADE_CODE_VERSION=a557fwd-$arm -v "$CONF:/app/config:ro" \
    -e AUTOTRADE_REPLAY_CACHE_DIR=/cache -v /Volumes/T7/autotrade-replay-cache:/cache \
    "autotrade-trading-core:$IMG" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.underlyings=NIFTY,SENSEX \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/$risk \
    --autotrade.trading.features-file=config/features/features.v15.yaml \
    --autotrade.trading.strategy-files=$FILES --spring.flyway.enabled=false > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID KS
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-557 forward: $DAY $arm replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  KS=$(echo "$SUMMARY" | sed -n 's/.*killSwitches=\[\([^]]*\)\].*/\1/p' | tr -d ' ' | tr ',' ';')
  ROW="$DAY,$arm,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0"),$KS,$($AT -c "select coalesce(round(sum(net)), 0) from trade.position where session_id = $SID and strategy_id = 'expiry-trend-rider'")"
  $AT -F, -c "select '$DAY', '$arm', strategy_id, underlying, option_side, symbol, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity / lot_size, quantity, round(average_cost::numeric,2), round(average_exit::numeric,2), round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at, id" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
run C paper-risk.v44mc.yaml; R_C=$ROW
run L paper-risk.v44mcol12.yaml; R_L=$ROW
echo "$R_C" >> "$CSV"; echo "$R_L" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
NC=$(echo "$R_C" | cut -d, -f6); NL=$(echo "$R_L" | cut -d, -f6)
# a day "differs" when the two arms' trade lists differ (not only their nets)
DIFF=$(awk -F, -v d="$DAY" 'NR>1 && $1==d {k=$3 FS $4 FS $5 FS $6 FS $7 FS $8 FS $9 FS $10 FS $11 FS $12 FS $13 FS $14; if ($2=="C") c[k]++; else l[k]++}
  END {for (k in c) if (c[k]!=l[k]) {print "yes"; exit} for (k in l) if (c[k]!=l[k]) {print "yes"; exit} print "no"}' "$TRADES")
echo "$DAY,$DIFF" >> "$A555/differs.csv"
N=$(grep -c ",yes$" "$A555/differs.csv" | tr -d ' '); D=$(cut -d, -f1 "$A555/differs.csv" | sort -u | wc -l | tr -d ' ')
echo "A-557 forward $DAY: C net $NC | L net $NL ($((NL - NC)) vs C); differs: $DIFF; changed days $N of 8, forward days $D of 60 (read once at either)"
