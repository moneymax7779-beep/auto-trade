#!/bin/bash
# A-345 (ledger 307fb94 + amendment; user 7 Nov 2026 ~23:50: "add v40 live and v34 as shadow and deploy in local", then chose
# "Nightly Mac replay"): the forward record of mac-v40 (entries from 12:00 + MFE trail + one below-breakeven look at 60 min,
# no early-fail) against mac-v34 (GCP's live ma-cross), each alone, Rs 5 L, risk v17, on the own capture the daily sync has
# just loaded. Image autotrade-trading-core:mac40-a345 (= mac40-local, branch deploy/mac-v40-local 69ce713), config frozen in
# $A345/config. Days from 9 Nov 2026. Read ONCE at 20 sessions. Usage: 2026-11-07-a345-mac-v40-forward.sh YYYY-MM-DD
set -u
DAY=$1
A345="${A345_OUT:-$HOME/.autotrade-sync/a345}"; IMG=mac40-a345
CSV="$A345/forward.csv"; TRADES="$A345/trades.csv"; mkdir -p "$A345/logs" "$A345/local"
[[ "$DAY" < "${A345_FROM:-2026-11-09}" ]] && { echo "A-345 forward: $DAY is before the first forward day (9 Nov); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
if grep -q "^$DAY,mac-v40," "$CSV"; then
  echo "A-345 forward: $DAY already recorded ($(grep -c ',mac-v40,' "$CSV") sessions so far)"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
run() {   # variant -> sets ROW, appends trades to the day's tmp file
  local v=$1 LOGF="$A345/logs/$DAY-mac-$1.log"
  docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "A-345 forward: image $IMG missing"; exit 1; }
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A345/config:/app/config:ro" -v "$A345/local:/app/.local" \
    "autotrade-trading-core:$IMG" --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files=config/strategy/ma-cross.$v.yaml > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-345 forward: $DAY mac-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,mac-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0")"
  $AT -F, -c "select '$DAY', 'mac-$v', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
run v40; R_v40=$ROW
run v34; R_v34=$ROW
echo "$R_v40" >> "$CSV"; echo "$R_v34" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep -c ',mac-v40,' "$CSV")
echo "A-345 forward $DAY: mac-v40 ${R_v40#*,mac-v40,*,} | mac-v34 ${R_v34#*,mac-v34,*,} (trades,won,net,costs); sessions $N of 20 (read once at 20)"
