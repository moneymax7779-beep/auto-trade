#!/bin/bash
# Replays many (label, strategy file, day) jobs, each strategy ALONE, Rs 5 L, risk v17, in parallel; resumable.
#
# usage: replay_batch.sh OUT IMAGE CONFIG_DIR SOURCE JOBS [PARALLEL]
#   OUT         run folder (logs/, progress.txt are written here)
#   IMAGE       autotrade-trading-core tag, e.g. obc-loop-base (must already exist)
#   CONFIG_DIR  folder mounted as /app/config (the worktree's config/ holding every strategy file the jobs name)
#   SOURCE      own (own tick capture) | bars (Upstox 1-min bars in hist.candle)
#   JOBS        text file, one job per line: "<label> <strategy-file-relative-to-config> <YYYY-MM-DD>"
#               e.g. "obc-v13 strategy/oi-burst-cross.v13.yaml 2026-10-01"
#   PARALLEL    concurrent replays (default 3; more than 3 slows the Mac and other sessions)
#
# progress.txt lines: "HH:MM:SS <label> <day> <session id> {events=..., positions=..., wins=..., net=..., ...}"
# A finished job leaves logs/<label>-<day>.done, so a rerun only does what is missing. A line with NO SUMMARY means
# the replay failed (see its log); it is retried on the next run.
set -u
OUT=$1 IMG=$2 CFG=$3 SRC=$4 JOBS=$5 P=${6:-3}
mkdir -p "$OUT/logs"
OUT=$(cd "$OUT" && pwd) CFG=$(cd "$CFG" && pwd) JOBS=$(cd "$(dirname "$JOBS")" && pwd)/$(basename "$JOBS")   # docker -v needs absolute paths
docker image inspect "autotrade-trading-core:$IMG" >/dev/null 2>&1 || { echo "image autotrade-trading-core:$IMG missing"; exit 1; }
one() {
  local label=$1 file=$2 d=$3 key="$1-$3"
  [ -f "$OUT/logs/$key.done" ] && return
  docker run --rm --network autotrade_default -e TZ=Asia/Kolkata \
    -e AUTOTRADE_DB_URL=jdbc:postgresql://postgres:5432/autotrade -e AUTOTRADE_DB_PASSWORD=autotrade \
    -e ZT_SOURCE_URL=jdbc:postgresql://postgres:5432/zt_archive -e ZT_SOURCE_USER=autotrade -e ZT_SOURCE_PASSWORD=autotrade \
    -e ZT_SOURCE_PASSWORD_FILE=/nonexistent -e JAVA_TOOL_OPTIONS="-Xmx2g -XX:+ExitOnOutOfMemoryError" \
    -v "$CFG:/app/config:ro" -v "$OUT/local:/app/.local" \
    "autotrade-trading-core:$IMG" --mode=replay --session="$d" --autotrade.trading.replay-source="$SRC" \
    --autotrade.trading.replay-equity=500000 --autotrade.trading.risk-file=config/risk/paper-risk.v17.yaml \
    --autotrade.trading.strategy-files="config/$file" > "$OUT/logs/$key.log" 2>&1 < /dev/null
  local line sid
  line=$(grep -h "session summary" "$OUT/logs/$key.log" | tail -1 | sed 's/.*session summary: //')
  sid=$(grep -ho "trading session [0-9]* (PAPER_REPLAY" "$OUT/logs/$key.log" | awk '{print $3}')
  echo "$(date +%T) $label $d ${sid:--} ${line:-NO SUMMARY}" >> "$OUT/progress.txt"
  [ -n "$line" ] && touch "$OUT/logs/$key.done"
}
export -f one; export OUT IMG CFG SRC
mkdir -p "$OUT/local"
echo "$(date +%T) start: $(grep -c . "$JOBS") jobs, image $IMG, source $SRC" >> "$OUT/progress.txt"
xargs -P "$P" -L 1 bash -c 'one "$@"' _ < "$JOBS"
echo "$(date +%T) DONE $(basename "$JOBS")" >> "$OUT/progress.txt"
