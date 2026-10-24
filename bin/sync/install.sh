#!/bin/bash
# Installs (or updates) the daily GCP -> Mac market-data sync: copies the scripts and the Telegram settings into
# ~/.autotrade-sync (outside ~/Desktop, which macOS does not let background jobs read), writes the launchd agent and
# (re)loads it. Run again after changing bin/sync/*.sh. Uninstall:
#   launchctl bootout gui/$(id -u)/com.autotrade.daily-sync; rm ~/Library/LaunchAgents/com.autotrade.daily-sync.plist
set -eu
REPO="$(cd "$(dirname "$0")/../.." && pwd)"; DEST="$HOME/.autotrade-sync"; mkdir -p "$DEST/logs"
cp "$REPO/bin/sync/daily-sync.sh" "$REPO/bin/sync/vm-export-day.sh" "$DEST/" && chmod 755 "$DEST"/*.sh
mkdir -p "$DEST/trackers" "$DEST/a197" "$DEST/a209"   # nightly forward trackers (A-197, A-209), run by daily-sync.sh step 3b/3c
cp "$REPO/docs/studies/2026-10-24-weekly-strangle-ticks.py" "$REPO/docs/studies/2026-10-24-mac-vix-forward.py" "$DEST/trackers/"
if [ -f "$REPO/.local/telegram/bot.env" ]; then cp "$REPO/.local/telegram/bot.env" "$DEST/bot.env" && chmod 600 "$DEST/bot.env"; fi
PLIST="$HOME/Library/LaunchAgents/com.autotrade.daily-sync.plist"
sed -e "s|/Users/satyasiripuram/Desktop/Dev/Code/auto-trade/bin/sync/daily-sync.sh|$DEST/daily-sync.sh|" \
    -e "s|/Users/satyasiripuram/Desktop/Dev/Code/auto-trade/.local/daily-sync/launchd.log|$DEST/logs/launchd.log|g" \
    "$REPO/bin/sync/com.autotrade.daily-sync.plist" > "$PLIST"
plutil -lint "$PLIST" >/dev/null
launchctl bootout "gui/$(id -u)/com.autotrade.daily-sync" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: $DEST, weekdays 16:45 (logs: $DEST/logs)"
