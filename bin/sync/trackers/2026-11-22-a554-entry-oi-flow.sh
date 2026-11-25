#!/bin/bash
# A-554 (ledger, user 22 Nov 2026: "log the 15-min OI flow at every entry from Monday"): forward record only.
# Each night, after the own capture of DAY is loaded on the Mac, reads from GCP (read-only) every PAPER-1 PAPER_LIVE
# engine position of DAY, then computes the 15/30-min ATM +-3 OI flow at each entry from the Mac's own capture and
# appends it to $A554_OUT/entries.csv (a day already recorded is replaced). Prints one summary line.
# Usage: 2026-11-22-a554-entry-oi-flow.sh YYYY-MM-DD
set -u
DAY=$1
OUTD="${A554_OUT:-$HOME/.autotrade-sync/a554}"; mkdir -p "$OUTD"
HERE=$(cd "$(dirname "$0")" && pwd)
G="--tunnel-through-iap --zone=asia-south1-a --project=zt-tiger-v2-prod --account=${ACC:-satyajava704@gmail.com}"
Q="select '$DAY', p.strategy_id, p.underlying, p.option_side, p.symbol,
 to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI:SS'), coalesce(to_char(p.closed_at at time zone 'Asia/Kolkata','HH24:MI:SS'), ''),
 coalesce(p.exit_reason, ''), p.quantity, round(coalesce(p.net, 0)::numeric)
 from trade.position p join trade.session s on s.id = p.session_id
 where s.account = 'PAPER-1' and s.mode = 'PAPER_LIVE' and s.session_date = '$DAY' and p.quantity > 0
 order by p.opened_at"
printf '%s\n' "copy ($Q) to stdout csv" > /tmp/a554-q.$$.sql
P=$(gcloud compute scp /tmp/a554-q.$$.sql zt-autotrade:/tmp/a554-q.sql $G >/dev/null 2>&1 \
    && gcloud compute ssh zt-autotrade $G --command="sudo docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -At -v ON_ERROR_STOP=1 < /tmp/a554-q.sql; rc=\$?; rm -f /tmp/a554-q.sql; exit \$rc" 2>/dev/null)
RC=$?; rm -f /tmp/a554-q.$$.sql
[ $RC -eq 0 ] || { echo "A-554 $DAY: reading GCP failed; the day is not recorded"; exit 1; }
printf '%s\n' "$P" > "$OUTD/positions-$DAY.csv"
/usr/bin/python3 -I "$HERE/2026-11-22-a554-entry-oi-flow.py" "$DAY" "$OUTD/positions-$DAY.csv" "$OUTD/entries.csv"
