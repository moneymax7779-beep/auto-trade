#!/bin/bash
# The shadow list on the Mac (user 11 Nov 2026: "shadow list should run locally not on GCP"; GCP's application.yml
# shadow-strategy-files is empty from the next deploy). After the daily sync has loaded a day's own capture into the Mac
# DB, each strategy file of $SH/files.txt is replayed on it like GCP's after-close shadow run did: trading-core
# --mode=shadow, account SHADOW-1, risk/features/costs of main's application.yml, own capture, no orders anywhere.
# Image autotrade-trading-core:$IMG (main, built on the Mac), config frozen in $SH/config (git archive of main).
# Flyway is off: the Mac DB's history (an old V30) differs from main's, and migrating it would break the older
# tracker images; shadow replays read and write only trade.* and md.*. A file that already has a finished SHADOW-1
# session for the day is not run again. Days from 12 Nov 2026 (11 Nov's
# shadows ran on GCP). Results: $SH/forward.csv (one row per file and day) and $SH/trades.csv.
# SHADOWS_ARGS (17 Nov, A-475): extra trading-core arguments, e.g. a features-file override for the sbr shadows.
# Usage: 2026-11-11-shadow-list-local.sh YYYY-MM-DD
set -u
DAY=$1
SH="${SHADOWS_OUT:-$HOME/.autotrade-sync/shadows}"; IMG="${SHADOWS_IMAGE:-$(cat "$SH/image" 2>/dev/null)}"
CSV="$SH/forward.csv"; TRADES="$SH/trades.csv"; mkdir -p "$SH/logs" "$SH/local"
[[ "$DAY" < "${SHADOWS_FROM:-2026-11-12}" ]] && { echo "shadows: $DAY is before the first local day (12 Nov); not run"; exit 0; }
[ -n "$IMG" ] && docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 \
  || { echo "shadows: image autotrade-trading-core:${IMG:-?} missing"; exit 1; }
[ -f "$CSV" ] || echo "day,file,session,positions,wins,net,costs" > "$CSV"
[ -f "$TRADES" ] || echo "day,file,underlying,side,opened,closed,exit_reason,quantity,net" > "$TRADES"
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
PENDING=(); ALL=()
record() {   # $1 file name, $2 SHADOW-1 session id: append its results unless the day already has them
  grep -q "^$DAY,$1," "$CSV" && return 0
  echo "$DAY,$1,$2,$($AT -F, -c "select count(*), count(*) filter (where net > 0), coalesce(round(sum(net)), 0), \
    coalesce(round(sum(costs)), 0) from trade.position where session_id = $2 and quantity > 0" < /dev/null)" >> "$CSV"
  $AT -F, -c "select '$DAY', '$1', underlying, option_side, to_char(opened_at at time zone 'Asia/Kolkata','HH24:MI'), \
    to_char(closed_at at time zone 'Asia/Kolkata','HH24:MI'), exit_reason, quantity, round(net) from trade.position \
    where session_id = $2 and quantity > 0 order by opened_at" < /dev/null >> "$TRADES"
}
while read -r f; do
  case "$f" in ""|\#*) continue;; esac
  ALL+=("$f")
  name=${f##*/}
  ran=$($AT -c "select exists (select 1 from trade.session where account = 'SHADOW-1' and session_date = '$DAY' \
    and status = 'DONE' and jsonb_exists(configs, '$name'))" < /dev/null)   # fix 12 Nov: docker -i ate the file list
  [ "$ran" = t ] || PENDING+=("$f")
done < "$SH/files.txt"
for f in "${ALL[@]}"; do   # fix 12 Nov: a run stopped mid-batch leaves finished files DONE but not in $CSV
  name=${f##*/}
  SID=$($AT -c "select max(id) from trade.session where account = 'SHADOW-1' and session_date = '$DAY' \
    and status = 'DONE' and jsonb_exists(configs, '$name')" < /dev/null)
  [ -n "$SID" ] && record "$name" "$SID"
done
[ ${#PENDING[@]} -eq 0 ] && { echo "shadows: $DAY every file already ran"; exit 0; }
LOGF="$SH/logs/$DAY.log"
LIST=$(IFS=,; echo "${PENDING[*]}")
docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
  -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
  -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
  -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -v "$SH/config:/app/config:ro" -v "$SH/local:/app/.local" \
  "autotrade-trading-core:$IMG" --mode=shadow --session="$DAY" \
  --spring.flyway.enabled=false --autotrade.trading.shadow-strategy-files="$LIST" ${SHADOWS_ARGS:-} > "$LOGF" 2>&1 < /dev/null
RC=$?
done_n=0; failed=()
for f in "${PENDING[@]}"; do
  name=${f##*/}
  SID=$($AT -c "select max(id) from trade.session where account = 'SHADOW-1' and session_date = '$DAY' \
    and status = 'DONE' and jsonb_exists(configs, '$name')")
  if [ -z "$SID" ]; then failed+=("$name"); continue; fi
  record "$name" "$SID"
  done_n=$((done_n + 1))
done
NET=$(awk -F, -v d="$DAY" '$1 == d {n += $6} END {print n + 0}' "$CSV")
echo "shadows $DAY: $done_n of ${#PENDING[@]} files ran (exit $RC), net of all files Rs $NET${failed:+; FAILED: ${failed[*]} (see $LOGF)}"
[ ${#failed[@]} -eq 0 ]
