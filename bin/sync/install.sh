#!/bin/bash
# Installs (or updates) the daily GCP -> Mac market-data sync: copies the scripts, the nightly trackers, the exchange
# calendar and the Telegram settings into ~/.autotrade-sync (outside ~/Desktop, which macOS does not let background jobs
# read), writes the launchd agent and (re)loads it. Run again after changing bin/sync/. Uninstall:
#   launchctl bootout gui/$(id -u)/com.autotrade.daily-sync; rm ~/Library/LaunchAgents/com.autotrade.daily-sync.plist
#
# 25 Nov 2026: the installed copy had been edited in place for weeks (tracker steps A-243 ... A-557) and no branch held it;
# it is now bin/sync/daily-sync.sh, vm-day-status.sh and bin/sync/trackers/. Change the files here and run this script,
# never the installed copy. What stays outside git: each tracker's frozen config (~/.autotrade-sync/aNNN/config, shadows*/),
# its results, the frozen Docker images the trackers name, and bot.env.
set -eu
REPO="$(cd "$(dirname "$0")/../.." && pwd)"; DEST="$HOME/.autotrade-sync"; mkdir -p "$DEST/logs" "$DEST/trackers"
# a sync in progress would be killed by the agent's reload, and a script copied over the one it runs corrupts it
if [ -e "$DEST/.lock" ] && kill -0 "$(cat "$DEST/.lock")" 2>/dev/null; then
  echo "a sync is running (pid $(cat "$DEST/.lock"), log $DEST/logs/); run this again after it ends" >&2; exit 1
fi
# each file: a temporary copy, then a rename (never written over in place)
put() { cp "$1" "$2.tmp.$$" && chmod "$3" "$2.tmp.$$" && mv -f "$2.tmp.$$" "$2"; }
for f in daily-sync.sh vm-export-day.sh vm-day-status.sh; do put "$REPO/bin/sync/$f" "$DEST/$f" 755; done
# the exchange holidays the sync skips (25 Nov 2026): the engine's own calendar
put "$REPO/config/exchange/nse-bse-sessions.v3.yaml" "$DEST/exchange-calendar.yaml" 644
# the nightly forward trackers daily-sync.sh runs (A-197 and A-209 live with their studies in docs/studies)
for f in "$REPO"/bin/sync/trackers/*; do put "$f" "$DEST/trackers/$(basename "$f")" 755; done
for f in 2026-10-24-weekly-strangle-ticks.py 2026-10-24-mac-vix-forward.py; do put "$REPO/docs/studies/$f" "$DEST/trackers/$f" 644; done
mkdir -p "$DEST/a197" "$DEST/a209"
if [ -f "$REPO/.local/telegram/bot.env" ]; then put "$REPO/.local/telegram/bot.env" "$DEST/bot.env" 600; fi
PLIST="$HOME/Library/LaunchAgents/com.autotrade.daily-sync.plist"
sed -e "s|/Users/satyasiripuram/Desktop/Dev/Code/auto-trade/bin/sync/daily-sync.sh|$DEST/daily-sync.sh|" \
    -e "s|/Users/satyasiripuram/Desktop/Dev/Code/auto-trade/.local/daily-sync/launchd.log|$DEST/logs/launchd.log|g" \
    "$REPO/bin/sync/com.autotrade.daily-sync.plist" > "$PLIST"
plutil -lint "$PLIST" >/dev/null
launchctl bootout "gui/$(id -u)/com.autotrade.daily-sync" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: $DEST, weekdays 16:00 (logs: $DEST/logs)"
