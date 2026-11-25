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

# one run at a time; weekends and exchange holidays have no session
if [ -e "$HOME_DIR/.lock" ] && kill -0 "$(cat "$HOME_DIR/.lock")" 2>/dev/null; then
  log "another sync is running (pid $(cat "$HOME_DIR/.lock")); exiting"; exit 0
fi
echo $$ > "$HOME_DIR/.lock"
[ "$(date -j -f %F "$DAY" +%u)" -ge 6 ] && finish "weekend, nothing to sync" 0 quiet
# exchange holidays have no session either (user 25 Nov 2026, after 24 Nov, a holiday, ran and was reported FAILED): the
# list is the engine's config/exchange/nse-bse-sessions.v3.yaml, copied here by bin/sync/install.sh (launchd cannot read
# ~/Desktop). A year the file does not list yet is said in the log, and that year's holidays are not skipped.
CAL="$HOME_DIR/exchange-calendar.yaml"
if [ -f "$CAL" ]; then
  HOL=$(grep -E "^[[:space:]]*-[[:space:]]*\"$DAY\"" "$CAL" | sed -E 's/^[^#]*#[[:space:]]*//' | head -1)
  [ -n "$HOL" ] && finish "exchange holiday ($HOL), nothing to sync" 0 quiet
  grep -qE "^[[:space:]]*-[[:space:]]*\"${DAY:0:4}-" "$CAL" \
    || log "warning: $CAL lists no holidays for ${DAY:0:4}: holidays are not skipped (copy the new year's list)"
else
  log "warning: no exchange calendar at $CAL: holidays are not skipped (run bin/sync/install.sh)"
fi
H=$(TZ=Asia/Kolkata date +%H%M); TODAY=$(TZ=Asia/Kolkata date +%F)
if [ "$DAY" = "$TODAY" ] && [ "$H" -lt 1550 ]; then finish "too early: the session is not over (run after 15:50 IST)" 0 quiet; fi
docker exec autotrade-postgres pg_isready -q || finish "FAILED: local Docker/Postgres is not running" 1
log "start $DAY parts $PARTS"

# 1. wait for GCP to finish the live session and every shadow replay (up to 3 hours). No RUNNING session is not enough on
# its own: the shadows run one after another, and on 30 Oct the check fell between two of them at 16:23, 1 h 45 min
# before the last one ended (21 shadows took 15:40-18:09). vm-day-status.sh also counts the configured shadow files with
# no finished session yet; when that list cannot be read ('?'), two checks in a row with nothing running will do.
gcloud compute scp "$HOME_DIR/vm-day-status.sh" zt-autotrade:/tmp/vm-day-status.sh $G >> "$LOG" 2>&1 || finish "FAILED: scp" 1
R=; P=; idle=0
for i in $(seq 1 36); do
  S=$(gcloud compute ssh zt-autotrade $G --command="sudo bash /tmp/vm-day-status.sh $DAY" 2>/dev/null | tail -1)
  R=${S%% *}; P=${S##* }
  [ "$R" = 0 ] && [ "$P" = 0 ] && break
  if [ "$R" = 0 ] && [ "$P" = "?" ]; then idle=$((idle + 1)); [ "$idle" -ge 2 ] && break; else idle=0; fi
  log "GCP for $DAY: running '$R', shadow files still to run '$P' (check $i)"; sleep 300
done
[ "$R" = 0 ] || finish "FAILED: GCP still has a running session for $DAY after 3 hours" 1
[ "$P" = 0 ] || log "warning: shadow files without a finished session for $DAY: '$P' (a failed file, or the list was unreadable); syncing anyway"

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
    gcloud compute ssh zt-autotrade $G --command="sudo docker exec autotrade-postgres psql -U autotrade -d autotrade -X -At -F, -c \"select s.session_date, s.id, p.underlying, p.option_side, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI'), p.net, p.average_cost * p.quantity from trade.position p join trade.session s on s.id = p.session_id where s.session_date = '$DAY' and s.mode = 'PAPER_LIVE' and s.account = 'PAPER-1' and p.strategy_id = 'ma-cross' and p.quantity > 0\"" 2>>"$OUT/errors.txt" > "$OUT/a209-mac.csv"
    RC=$?
    if [ $RC -ne 0 ]; then          # a failed read is not a day without mac trades: skip it, do not record it
      log "A-209 mac/VIX tracker skipped: reading GCP failed (exit $RC); the day is not recorded"
    else
      $PY "$TR/2026-10-24-mac-vix-forward.py" "$DAY" < "$OUT/a209-mac.csv" > "$OUT/a209-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a209-forward.txt")" || log "A-209 mac/VIX tracker failed (see $OUT/a209-forward.txt)"
    fi
  fi
  # 3d. A-243 (user 2026-10-26): A-242's S1 retest-bounce (calls + put mirror) on the own capture just loaded, frozen rules,
  # with the previous session's closing-squeeze flag and the own-expiry flag; read once at 40 trades.
  if [ -f "$TR/2026-10-26-closing-squeeze-forward.py" ]; then
    env A243_OUT="$HOME_DIR/a243" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /usr/bin/python3 \
      "$TR/2026-10-26-closing-squeeze-forward.py" "$DAY" > "$OUT/a243-forward.txt" 2>&1 \
      && log "$(tail -1 "$OUT/a243-forward.txt")" || log "A-243 S1 forward tracker failed (see $OUT/a243-forward.txt)"
  fi
  # 3e. A-308 (user 2026-11-03): mac-v9 (fresh-extreme momentum cross, frozen image a307-local) vs mac-v1, each replayed
  # alone on the own capture just loaded (Rs 5 L, risk v17); read once at 20 sessions. Skipped when the own capture did not
  # load cleanly (the day is not recorded).
  if [ -f "$TR/2026-11-03-a308-mac-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A308_OUT="$HOME_DIR/a308" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-03-a308-mac-forward.sh" "$DAY" > "$OUT/a308-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a308-forward.txt")" || log "A-308 forward tracker failed (see $OUT/a308-forward.txt)"
    else
      log "A-308 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3f. A-317 (user 2026-11-04): mac-v14 (skip crosses > 0.758 ATR on the wrong side of VWAP, image a317-local) vs mac-v1
  # (A-308's row for the day), days from 5 Nov; read once at 20 sessions. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-04-a317-mac-vwap-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A317_OUT="$HOME_DIR/a317" A308_OUT="$HOME_DIR/a308" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-04-a317-mac-vwap-forward.sh" "$DAY" > "$OUT/a317-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a317-forward.txt")" || log "A-317 forward tracker failed (see $OUT/a317-forward.txt)"
    else
      log "A-317 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3g. A-327 (user 2026-11-06): wbr-v3 vs wbr-v2 (control), each replayed alone on the own capture just loaded (image
  # wbr3b-local, config frozen in a327/config), days from 9 Nov; read once at 20 expiry days. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-06-a327-wbr-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A327_OUT="$HOME_DIR/a327" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-06-a327-wbr-forward.sh" "$DAY" > "$OUT/a327-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a327-forward.txt")" || log "A-327 forward tracker failed (see $OUT/a327-forward.txt)"
    else
      log "A-327 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3h. A-345 (user 2026-11-07: "add v40 live and v34 as shadow and deploy in local" -> nightly Mac replay): mac-v40 vs
  # mac-v34, each alone on the own capture just loaded (image mac40-a345, config frozen in a345/config), days from 9 Nov;
  # read once at 20 sessions. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-07-a345-mac-v40-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A345_OUT="$HOME_DIR/a345" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-07-a345-mac-v40-forward.sh" "$DAY" > "$OUT/a345-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a345-forward.txt")" || log "A-345 forward tracker failed (see $OUT/a345-forward.txt)"
    else
      log "A-345 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3n. A-484 (user 2026-11-18: "yes add the v34+VWAP arm"): mac-v49 (= mac-v34 + A-317's VWAP-side rule) vs mac-v34,
  # each alone on the own capture just loaded (image mac49-a484-frozen, config frozen in a484/config, risk v44, features
  # v15), days from 19 Nov; read once at 20 sessions. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-18-a484-mac-v49-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A484_OUT="$HOME_DIR/a484" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-18-a484-mac-v49-forward.sh" "$DAY" > "$OUT/a484-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a484-forward.txt")" || log "A-484 forward tracker failed (see $OUT/a484-forward.txt)"
    else
      log "A-484 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3h. A-344 (user 2026-11-07: "i want this to run in shadow"): order-flow absorption reversal, frozen study rules, on the
  # NIFTY / SENSEX futures ticks just loaded; days from 9 Nov; read once at 20 confirmed trades. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-07-absorption-reversal-forward.py" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A344_OUT="$HOME_DIR/a344" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /usr/bin/python3 -I \
        "$TR/2026-11-07-absorption-reversal-forward.py" "$DAY" > "$OUT/a344-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a344-forward.txt")" || log "A-344 forward tracker failed (see $OUT/a344-forward.txt)"
    else
      log "A-344 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3i. A-346 (user 2026-11-08: "hook the A-346 tracker into daily-sync"): the live set of five (ecr-v11, etr-v1, mac-v34,
  # obc-v13, wbr-v13 last) vs the four without wbr-v13, one shared-capital session each on the own capture just loaded
  # (image wbr13-a346, config frozen in a346/config), days from 9 Nov; read once at 20 expiry days. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-08-a346-liveset-wbr13-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A346_OUT="$HOME_DIR/a346" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-08-a346-liveset-wbr13-forward.sh" "$DAY" > "$OUT/a346-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a346-forward.txt")" || log "A-346 forward tracker failed (see $OUT/a346-forward.txt)"
    else
      log "A-346 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3j. The shadow list (user 2026-11-11: "shadow list should run locally not on GCP"): every strategy file of
  # $HOME_DIR/shadows/files.txt replayed in shadow on the own capture just loaded (image in shadows/image, config frozen
  # in shadows/config), days from 12 Nov. Same clean-load condition as 3e.
  # OFF from 12 Nov 2026 (user: "Turn it off"); AUTOTRADE_SHADOWS=on runs it again for one sync.
  if [ "${AUTOTRADE_SHADOWS:-off}" != on ]; then
    log "shadow list off (user 12 Nov 2026); AUTOTRADE_SHADOWS=on runs it"
  elif [ -f "$TR/2026-11-11-shadow-list-local.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env SHADOWS_OUT="$HOME_DIR/shadows" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-11-shadow-list-local.sh" "$DAY" > "$OUT/shadows.txt" 2>&1 \
        && log "$(tail -1 "$OUT/shadows.txt")" || log "shadow list failed or partly failed: $(tail -1 "$OUT/shadows.txt")"
    else
      log "shadow list skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); run it by hand after a reload"
    fi
  fi
  # 3k. A-378 (user 2026-11-12: "Set up paper test"): PAPER-40 vs PAPER-40-RVOL on GCP, read-only, into a378/.
  if [ -f "$TR/2026-11-12-a378-paper-forward.sh" ] && [[ ! "$DAY" < "2026-11-13" ]]; then
    env A378_OUT="$HOME_DIR/a378" ACC="$ACC" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
      "$TR/2026-11-12-a378-paper-forward.sh" "$DAY" > "$OUT/a378-forward.txt" 2>&1 \
      && log "$(tail -1 "$OUT/a378-forward.txt")" || log "A-378 tracker failed: $(tail -1 "$OUT/a378-forward.txt")"
  fi
  # 3o. A-548 (user 2026-11-22: "yes set up and do replay"): mco-v1 (ma-cross-open, A-537) and mco-v2 (A-546 gap skip), each
  # alone on the own capture just loaded (image mco-a548-frozen, config frozen in a548/config, risk v44, features v15,
  # NIFTY + SENSEX, shared replay queue + cache), days from 23 Nov; read once at 20 sessions with an mco trade. Same
  # clean-load condition as 3e.
  if [ -f "$TR/2026-11-22-a548-mco-forward.sh" ]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A548_OUT="$HOME_DIR/a548" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-22-a548-mco-forward.sh" "$DAY" > "$OUT/a548-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a548-forward.txt")" || log "A-548 forward tracker failed (see $OUT/a548-forward.txt)"
    else
      log "A-548 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3p. A-554 (user 2026-11-22: "log the 15-min OI flow at every entry from Monday"): every PAPER-1 live entry of the day,
  # read from GCP read-only, with the 15/30-min ATM +-3 OI flow at entry computed from the own capture just loaded, into
  # a554/entries.csv; days from 23 Nov; read once at 30 entries with known flow. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-22-a554-entry-oi-flow.sh" ] && [[ ! "$DAY" < "2026-11-23" ]]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A554_OUT="$HOME_DIR/a554" ACC="$ACC" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-22-a554-entry-oi-flow.sh" "$DAY" > "$OUT/a554-entries.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a554-entries.txt")" || log "A-554 entry OI tracker failed: $(tail -1 "$OUT/a554-entries.txt")"
    else
      log "A-554 skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3q. A-557 (user 2026-11-22: "set up the ₹60K limit as a forward PAPER shadow"): the A-532 P set replayed twice on the
  # own capture just loaded: C = risk v44mc (account limit), L = v44mcol12 (etr own Rs 60K limit, exempt from the account
  # limit); image a555-frozen, config frozen in a555/config; days from 23 Nov; read once at 8 changed days or 60 days.
  # Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-22-a555-etr-limit-forward.sh" ] && [[ ! "$DAY" < "2026-11-23" ]]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env A555_OUT="$HOME_DIR/a555" PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-22-a555-etr-limit-forward.sh" "$DAY" > "$OUT/a557-forward.txt" 2>&1 \
        && log "$(tail -1 "$OUT/a557-forward.txt")" || log "A-557 forward tracker failed (see $OUT/a557-forward.txt)"
    else
      log "A-557 forward skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); the day is not recorded"
    fi
  fi
  # 3l. A-470 forward (user 2026-11-16: "add obc-v28 and wbr-v29 as shadows on the Mac"): obc-v28 / wbr-v29 and their
  # live versions obc-v13 / wbr-v13 in shadow on the day just loaded (image in shadows-a470/image: sbr28-a471 from 17 Nov 02:20, config frozen in shadows-a470/config; + obc-v31),
  # days from 17 Nov. Runs even while the main shadow list (3j) is off. Same clean-load condition as 3e.
  if [ -f "$TR/2026-11-11-shadow-list-local.sh" ] && [[ ! "$DAY" < "2026-11-17" ]]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env SHADOWS_OUT="$HOME_DIR/shadows-a470" SHADOWS_FROM=2026-11-17 PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-11-shadow-list-local.sh" "$DAY" > "$OUT/a470-shadows.txt" 2>&1 \
        && log "A-470 $(tail -1 "$OUT/a470-shadows.txt")" || log "A-470 shadows failed or partly failed: $(tail -1 "$OUT/a470-shadows.txt")"
    else
      log "A-470 shadows skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); run it by hand after a reload"
    fi
  fi
  # 3m. A-475 forward (user 2026-11-17: "add v22, v13, v5 as shadows on the Mac"): session-break-retest v5 / v13 / v22 alone
  # in shadow on the day just loaded (image and config in shadows-sbr, features v15 via SHADOWS_ARGS), days from 17 Nov.
  if [ -f "$TR/2026-11-11-shadow-list-local.sh" ] && [[ ! "$DAY" < "2026-11-17" ]]; then
    if [ "$PROBLEMS" -eq 0 ]; then
      env SHADOWS_OUT="$HOME_DIR/shadows-sbr" SHADOWS_FROM=2026-11-17 \
        SHADOWS_ARGS="--autotrade.trading.features-file=config/features/features.v15.yaml" \
        PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin /bin/bash \
        "$TR/2026-11-11-shadow-list-local.sh" "$DAY" > "$OUT/a475-sbr-shadows.txt" 2>&1 \
        && log "A-475 $(tail -1 "$OUT/a475-sbr-shadows.txt")" || log "A-475 sbr shadows failed or partly failed: $(tail -1 "$OUT/a475-sbr-shadows.txt")"
    else
      log "A-475 sbr shadows skipped: the own capture of $DAY did not load cleanly ($PROBLEMS problem(s)); run it by hand after a reload"
    fi
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
