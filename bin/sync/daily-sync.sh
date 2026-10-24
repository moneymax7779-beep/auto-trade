#!/bin/bash
# Daily GCP -> Mac market-data sync (user 15 Oct 2026: "build the automatic daily sync").
# For one session DAY (default: today, IST): waits until GCP has no RUNNING session (the live day and its shadow replays),
# starts bin/sync/vm-export-day.sh on zt-autotrade, then on the Mac:
#   own   - auto-trade's own capture (md.*), manifests, the instrument snapshot, VIX candles -> autotrade DB;
#   zt    - zt-tiger-v2's capture of the day -> the zt_archive scratch DB (replays read it as the zt source).
# GCP's trading records are not imported (user 15 Oct ~08:20: stop the import of live sessions).
# Each table: GCP's row count (.done marker) vs the Mac's for the day; equal = skipped; else the day's rows are replaced
# and the count checked. A copy of every dump stays on /Volumes/T7/autotrade-sync/DAY when the drive is mounted.
# Reads GCP only (and zt-tiger-v2 through its read-only role, after market hours); never changes GCP.
# Usage: daily-sync.sh [YYYY-MM-DD] [own,zt]. Installed by bin/sync/install.sh into ~/.autotrade-sync and run there by
# launchd (macOS does not let background jobs read ~/Desktop): logs, the lock and the dumps live under that folder.
set -u
HOME_DIR="${AUTOTRADE_SYNC_HOME:-$HOME/.autotrade-sync}"; mkdir -p "$HOME_DIR"; cd "$HOME_DIR" || exit 1
DAY=${1:-$(TZ=Asia/Kolkata date +%F)}; PARTS=${2:-own,zt}
OUT="$HOME_DIR/logs/$DAY"; mkdir -p "$OUT"; LOG="$OUT/sync.txt"
BK=/Volumes/T7/autotrade-sync/$DAY; { [ -d /Volumes/T7 ] && mkdir -p "$BK" 2>/dev/null && touch "$BK/.w" 2>/dev/null; } || BK="$OUT/dumps"; mkdir -p "$BK"
B=gs://zt-tiger-v2-prod-xfer/daily/$DAY; ACC=satyajava704@gmail.com
G="--tunnel-through-iap --zone=asia-south1-a --project=zt-tiger-v2-prod --account=$ACC"
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
STORE="env CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.12 gcloud storage"   # gcloud storage needs python 3.12 on this Mac
AT="docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -q -At -v ON_ERROR_STOP=1"
ZA="docker exec -i autotrade-postgres psql -U autotrade -d zt_archive -X -q -At -v ON_ERROR_STOP=1"
PROBLEMS=0
log() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
notify() {   # Telegram (the app's bot settings); never prints the token
  [ -f "$HOME_DIR/bot.env" ] || return 0
  ( set -a; . "$HOME_DIR/bot.env"; set +a
    [ "${TELEGRAM_ENABLED:-true}" = "false" ] && exit 0
    curl -s -m 15 -o /dev/null "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
      --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" --data-urlencode "text=$1" ) || true
}
finish() { log "$1"; [ "${3:-}" = quiet ] || notify "auto-trade daily sync $DAY: $1"; rm -f "$HOME_DIR/.lock"; exit "${2:-0}"; }

# one run at a time; weekends have no session
if [ -e "$HOME_DIR/.lock" ] && kill -0 "$(cat "$HOME_DIR/.lock")" 2>/dev/null; then
  log "another sync is running (pid $(cat "$HOME_DIR/.lock")); exiting"; exit 0
fi
echo $$ > "$HOME_DIR/.lock"
[ "$(date -j -f %F "$DAY" +%u)" -ge 6 ] && finish "weekend, nothing to sync" 0 quiet
H=$(TZ=Asia/Kolkata date +%H%M); TODAY=$(TZ=Asia/Kolkata date +%F)
if [ "$DAY" = "$TODAY" ] && [ "$H" -lt 1550 ]; then finish "too early: the session is not over (run after 15:50 IST)" 0 quiet; fi
docker exec autotrade-postgres pg_isready -q || finish "FAILED: local Docker/Postgres is not running" 1
log "start $DAY parts $PARTS"

# 1. wait for GCP to finish the live session and its shadow replays (up to 90 minutes)
for i in $(seq 1 18); do
  R=$(gcloud compute ssh zt-autotrade $G --command="sudo docker exec autotrade-postgres psql -U autotrade -d autotrade -X -At -c \"select count(*) from trade.session where status = 'RUNNING' and session_date = '$DAY'\"" 2>/dev/null | tr -d '[:space:]')
  [ "$R" = "0" ] && break
  log "GCP sessions still running for $DAY: '$R' (check $i)"; sleep 300
done
[ "$R" = "0" ] || finish "FAILED: GCP still has a running session for $DAY after 90 minutes" 1

# 2. start the export on the VM (detached), unless this day's export is already complete in the bucket
complete=1   # each requested part's last marker
case ",$PARTS," in *,own,*) $STORE ls "$B/ref.index_candle.done" --account=$ACC >/dev/null 2>&1 || complete=0;; esac
case ",$PARTS," in *,zt,*) $STORE ls "$B/zt.market_tick_records.done" --account=$ACC >/dev/null 2>&1 || complete=0;; esac
if [ "$complete" = 1 ]; then
  log "export already in the bucket"
else
  gcloud compute scp "$HOME_DIR/vm-export-day.sh" zt-autotrade:/tmp/vm-export-day.sh $G >> "$LOG" 2>&1 || finish "FAILED: scp" 1
  gcloud compute ssh zt-autotrade $G --command="sudo mkdir -p /srv/replays/daily; sudo setsid nohup bash /tmp/vm-export-day.sh $DAY $PARTS > /dev/null 2>&1 < /dev/null & echo started" >> "$LOG" 2>&1 \
    || finish "FAILED: could not start the VM export" 1
  log "VM export started"
fi
waitfor() {   # marker, up to 3 hours
  for i in $(seq 1 720); do
    $STORE ls "$1" --account=$ACC >/dev/null 2>&1 && return 0
    $STORE ls "$B/REFUSED" --account=$ACC >/dev/null 2>&1 && return 2
    sleep 15
  done
  return 1
}
load() {   # psql command, dump name, table, day filter[, SQL run before the delete (rows that reference this table)]
  local db=$1 name=$2 table=$3 where=$4 pre=${5:-} n got t0=$(date +%s)
  waitfor "$B/$name.done"; case $? in 0) ;; 2) log "$name: VM refused (market hours)"; PROBLEMS=$((PROBLEMS+1)); return;; *) log "$name: no export after 3 h"; PROBLEMS=$((PROBLEMS+1)); return;; esac
  n=$($STORE cat "$B/$name.done" --account=$ACC | tr -d '[:space:]')
  got=$($db -c "select count(*) from $table where $where")
  if [ "$n" = "$got" ]; then log "$name: $got rows already here, skipped"; return; fi
  $STORE cat "$B/$name.zst" --account=$ACC > "$BK/$name.zst" 2>>"$OUT/errors.txt" || { log "$name: download failed"; PROBLEMS=$((PROBLEMS+1)); return; }
  [ -n "$pre" ] && $db -c "$pre" >/dev/null 2>>"$OUT/errors.txt"
  $db -c "delete from $table where $where" >/dev/null 2>>"$OUT/errors.txt"
  zstd -dc "$BK/$name.zst" | $db -c "\\copy $table from stdin" 2>>"$OUT/errors.txt"
  got=$($db -c "select count(*) from $table where $where")
  if [ "$n" = "$got" ]; then log "$name: $got rows loaded ($(( $(date +%s)-t0 )) s)"; else log "$name: MISMATCH expected $n, have $got"; PROBLEMS=$((PROBLEMS+1)); fi
}
NEXT=$(date -j -v+1d -f %F "$DAY" +%F); PREV=$(date -j -v-1d -f %F "$DAY" +%F)

# 3. own capture (manifests first: md rows reference them)
case ",$PARTS," in *,own,*)
  load "$AT" md.load_manifest md.load_manifest "session_date = '$DAY'"
  for t in session_phase_event index_tick future_tick auction_tick constituent_tick oi_profile candle option_tick; do
    load "$AT" md.$t md.$t "session_date = '$DAY'"
  done
  load "$AT" ref.instrument_snapshot ref.instrument_snapshot "snapshot_date = '$DAY'" \
    "delete from ref.instrument where snapshot_id in (select id from ref.instrument_snapshot where snapshot_date = '$DAY')"
  load "$AT" ref.instrument ref.instrument "snapshot_id in (select id from ref.instrument_snapshot where snapshot_date = '$DAY')"
  load "$AT" ref.index_candle ref.index_candle "session_date = '$DAY'"
  $AT -c "select setval(pg_get_serial_sequence('md.load_manifest','id'), greatest((select max(id) from md.load_manifest), 1))" >/dev/null 2>&1
  $AT -c "select setval(pg_get_serial_sequence('ref.instrument_snapshot','id'), greatest((select max(id) from ref.instrument_snapshot), 1))" >/dev/null 2>&1
esac

# 3b. A-197 (S0 of the buy/sell plan, user 2026-10-24): the weekly NIFTY short-strangle forward record on the own capture
# just loaded (frozen rules; equity on a Rs 20 L paper account). 3c. A-209 (user 2026-10-24): live mac trades of the day,
# read from GCP (read-only), split by the previous session's India VIX close. Trackers are copied into $HOME_DIR/trackers
# by install.sh (launchd jobs cannot read ~/Desktop). Neither ever counts as a sync problem.
case ",$PARTS," in *,own,*)
  TR="$HOME_DIR/trackers"; PY="env PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin A197_OUT=$HOME_DIR/a197 A209_OUT=$HOME_DIR/a209 /usr/bin/python3"
  if [ -f "$TR/2026-10-24-weekly-strangle-ticks.py" ]; then
    $PY "$TR/2026-10-24-weekly-strangle-ticks.py" forward > "$OUT/a197-forward.txt" 2>&1 \
      && log "A-197 forward: $(tail -1 "$OUT/a197-forward.txt")" || log "A-197 forward tracker failed (see $OUT/a197-forward.txt)"
  fi
  if [ -f "$TR/2026-10-24-mac-vix-forward.py" ]; then
    gcloud compute ssh zt-autotrade $G --command="sudo docker exec autotrade-postgres psql -U autotrade -d autotrade -X -At -F, -c \"select s.session_date, s.id, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), p.net, p.average_cost * p.quantity from trade.position p join trade.session s on s.id = p.session_id where s.session_date = '$DAY' and s.mode = 'PAPER_LIVE' and s.account = 'PAPER-1' and p.strategy_id = 'ma-cross' and p.quantity > 0\"" 2>/dev/null > "$OUT/a209-mac.csv"
    $PY "$TR/2026-10-24-mac-vix-forward.py" "$DAY" < "$OUT/a209-mac.csv" > "$OUT/a209-forward.txt" 2>&1 \
      && log "$(tail -1 "$OUT/a209-forward.txt")" || log "A-209 mac/VIX tracker failed (see $OUT/a209-forward.txt)"
  fi
esac

# 4. zt-tiger-v2's capture -> zt_archive
case ",$PARTS," in *,zt,*)
  load "$ZA" zt.market_candles market_candles "bar_start >= '$DAY' and bar_start < '$NEXT'"
  load "$ZA" zt.option_tick_snapshots option_tick_snapshots "captured_at >= '$DAY' and captured_at < '$NEXT'"
  load "$ZA" zt.market_session_price_events market_session_price_events "event_time_utc >= '$PREV 18:30+00' and event_time_utc < '$DAY 18:30+00'"
  load "$ZA" zt.market_tick_records market_tick_records "session_date = '$DAY'"
esac

[ "$PROBLEMS" -eq 0 ] && finish "OK ($PARTS)" 0 || finish "FAILED: $PROBLEMS problem(s), see ~/.autotrade-sync/logs/$DAY/sync.txt" 1
