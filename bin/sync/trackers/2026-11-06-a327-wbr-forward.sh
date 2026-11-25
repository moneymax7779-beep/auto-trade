#!/bin/bash
# A-327 (registered 6 Nov 2026, ledger 26f1986; user: "yes merge and deploy it in local for now"): the forward record of
# wbr-v3 against wbr-v2 (control), each alone, Rs 5 L, risk v17, on the own capture the daily sync has just loaded.
# Image autotrade-trading-core:wbr3b-local (feature/wbr-v3 822d41a), config frozen in $A327/config. Both trade only on the
# expiry day of an index, so a day counts toward the read only when NIFTY or SENSEX expires that day. Days from 9 Nov 2026.
# Read ONCE at 20 expiry days (see the ledger). Usage: 2026-11-06-a327-wbr-forward.sh YYYY-MM-DD
# A-330 (6 Nov, user: "yes register v6 and add it to the forward"): wbr-v6 (v3 + wall >= 0.5 ATR away) is a third arm on its
# own image wbr47-local (feature/wbr-v3 9db80b2); v3 and v2 keep wbr3b-local.
# A-331 (6 Nov, user: "yes do step 1 and prepare step 2"): wbr-v9 (v6 + no entry at ATM IV >= 0.35, A-329) is a fourth arm on
# image wbr9-a331 (= a329-local, Id sha256:933d303ffba3, frozen).
# A-341 (7 Nov, user: "go with others" on the v9 review): wbr-v19 (v9 + no entry when ATM straddle > 7.0 x ATR3m) is a fifth
# arm on image wbr19-a341 (= a339-local, Id sha256:e4c7c23404d1, frozen). Forward only; read once at 20 expiry days with A-331.
set -u
DAY=$1
A327="${A327_OUT:-$HOME/.autotrade-sync/a327}"
FROM="${A327_FROM:-2026-11-09}"
CSV="$A327/forward.csv"; TRADES="$A327/trades.csv"; mkdir -p "$A327/logs" "$A327/local"
[[ "$DAY" < "$FROM" ]] && { echo "A-327 forward: $DAY is before the first forward day ($FROM); not recorded"; exit 0; }
[ -f "$CSV" ] || echo "day,expiry,variant,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,variant,underlying,side,opened,closed,exit_reason,quantity,entry,exit,net" > "$TRADES"
if grep -q "^$DAY,.*,wbr-v3," "$CSV"; then
  echo "A-327 forward: $DAY already recorded"; exit 0
fi
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
EXP=$($AT -c "select string_agg(distinct underlying, '+' order by underlying) from md.option_tick where recv_ts >= '$DAY 09:15+05:30' and recv_ts < '$DAY 09:20+05:30' and expiry = '$DAY' and underlying in ('NIFTY','SENSEX')")
EXP=${EXP:-none}
run() {   # variant [image] -> sets ROW, appends trades to the day's tmp file
  local v=$1 img=${2:-wbr3b-local} LOGF="$A327/logs/$DAY-wbr-$1.log"
  docker image inspect autotrade-trading-core:$img >/dev/null 2>&1 || { echo "A-327 forward: image $img missing"; exit 1; }
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$A327/config:/app/config:ro" -v "$A327/local:/app/.local" \
    autotrade-trading-core:$img --mode=replay --session="$DAY" --autotrade.trading.replay-source=own \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files=config/strategy/wall-break-rider.$v.yaml > "$LOGF" 2>&1 < /dev/null
  local SUMMARY SID
  SUMMARY=$(grep -h "session summary" "$LOGF" | tail -1 | sed 's/.*session summary: //')
  SID=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$LOGF" | awk '{print $3}')
  case "$SUMMARY" in
    ""|*"events=0,"*) echo "A-327 forward: $DAY wbr-$v replay incomplete (${SUMMARY:-no summary}); day NOT recorded, see $LOGF"; exit 1;;
  esac
  ROW="$DAY,$EXP,wbr-$v,$SID,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), coalesce(round(sum(costs)), 0) from trade.position where session_id = $SID and quantity > 0")"
  $AT -F, -c "select '$DAY', 'wbr-$v', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(average_cost::numeric, 2), round(average_exit::numeric, 2), round(net) from trade.position where session_id = $SID and quantity > 0 order by opened_at" >> "$TRADES.$DAY.tmp"
}
rm -f "$TRADES.$DAY.tmp"
run v3; R_v3=$ROW
run v2; R_v2=$ROW
run v6 wbr47-local; R_v6=$ROW
run v9 wbr9-a331; R_v9=$ROW
run v19 wbr19-a341; R_v19=$ROW
echo "$R_v3" >> "$CSV"; echo "$R_v2" >> "$CSV"; echo "$R_v6" >> "$CSV"; echo "$R_v9" >> "$CSV"; echo "$R_v19" >> "$CSV"
cat "$TRADES.$DAY.tmp" >> "$TRADES" 2>/dev/null; rm -f "$TRADES.$DAY.tmp"
N=$(grep ',wbr-v3,' "$CSV" | grep -vc '^[^,]*,none,')
echo "A-327/A-330/A-331/A-341 forward $DAY (expiry: $EXP): wbr-v3 ${R_v3#*,wbr-v3,*,} | wbr-v2 ${R_v2#*,wbr-v2,*,} | wbr-v6 ${R_v6#*,wbr-v6,*,} | wbr-v9 ${R_v9#*,wbr-v9,*,} | wbr-v19 ${R_v19#*,wbr-v19,*,} (trades,won,net,costs); expiry days $N of 20 (read once at 20)"
