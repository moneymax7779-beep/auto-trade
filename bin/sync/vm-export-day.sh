#!/bin/bash
# Runs ON zt-autotrade (sudo), started by bin/sync/daily-sync.sh on the Mac. Exports one session's data to
# gs://zt-tiger-v2-prod-xfer/daily/<DAY>/ as compressed COPY dumps, each followed by a .done marker holding its row count:
#   own   - auto-trade's own capture (md.*), its load manifests, the day's instrument snapshot, VIX candles;
#   zt    - zt-tiger-v2's capture of the day (read only, role autotrade_reader).
# Usage: vm-export-day.sh YYYY-MM-DD [own,zt]. Refuses during market hours (08:55-15:50 IST, weekdays).
# Nothing on either VM is changed apart from the log under /srv/replays/daily.
set -u
DAY=$1; PARTS=${2:-own,zt}
B=gs://zt-tiger-v2-prod-xfer/daily/$DAY; OUT=/srv/replays/daily/$DAY; mkdir -p $OUT
log() { echo "$(date +%T) $*" >> $OUT/progress.txt; }
H=$(TZ=Asia/Kolkata date +%H%M); D=$(TZ=Asia/Kolkata date +%u)
if [ "$D" -le 5 ] && [ "$H" -ge 0855 ] && [ "$H" -lt 1550 ]; then log "refused: market hours"; echo "REFUSED market hours" | gcloud storage cp - $B/REFUSED -q; exit 1; fi
PW=$(head -1 /srv/auto-trade/.local/zt-source.env | tr -d "\r\n" | cut -d= -f2-)
ZT="docker exec -i -e PGPASSWORD=$PW autotrade-postgres psql -h 10.20.0.10 -p 5490 -U autotrade_reader -d trading -X -q -At"
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At"
put() {  # name, psql command, select
  local t0=$(date +%s) n
  n=$($2 -c "copy (select count(*) from ($3) x) to stdout")
  $2 -c "copy ($3) to stdout" 2>>$OUT/errors.txt | zstd -q -T0 -3 | gcloud storage cp - $B/$1.zst -q 2>>$OUT/errors.txt
  echo "$n" | gcloud storage cp - $B/$1.done -q 2>>$OUT/errors.txt
  log "$1: $n rows, $(( $(date +%s)-t0 )) s"
}
NEXT=$(date -d "$DAY + 1 day" +%F); PREV=$(date -d "$DAY - 1 day" +%F)
log "start $DAY parts $PARTS"
case ",$PARTS," in *,own,*)
  put md.load_manifest "$AT" "select * from md.load_manifest where session_date = '$DAY'"
  for t in session_phase_event index_tick future_tick auction_tick constituent_tick oi_profile candle option_tick; do
    put md.$t "$AT" "select * from md.$t where session_date = '$DAY'"
  done
  put ref.instrument_snapshot "$AT" "select * from ref.instrument_snapshot where snapshot_date = '$DAY'"
  put ref.instrument "$AT" "select * from ref.instrument where snapshot_id in (select id from ref.instrument_snapshot where snapshot_date = '$DAY')"
  put ref.index_candle "$AT" "select * from ref.index_candle where session_date = '$DAY'"
esac
case ",$PARTS," in *,zt,*)
  put zt.market_candles "$ZT" "select * from market_candles where bar_start >= '$DAY' and bar_start < '$NEXT'"
  put zt.option_tick_snapshots "$ZT" "select * from option_tick_snapshots where captured_at >= '$DAY' and captured_at < '$NEXT'"
  put zt.market_session_price_events "$ZT" "select * from market_session_price_events where event_time_utc >= '$PREV 18:30+00' and event_time_utc < '$DAY 18:30+00'"
  put zt.market_tick_records "$ZT" "select * from market_tick_records where session_date = '$DAY'"
esac
log "ALL DONE"
echo done | gcloud storage cp - $B/ALL.done -q
