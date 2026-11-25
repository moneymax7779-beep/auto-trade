#!/bin/bash
# A-378 (ledger): the PAPER forward test of full size on high-RVOL entries. Each night, reads from GCP (read-only) the
# day's positions of PAPER-40 (follower-risk v11, 40 %) and PAPER-40-RVOL (v12: RVOL >= 2.5 -> full size), with the
# signal's rvol_tod at entry and the entry intents both arms refused, and appends them to $A378_OUT/trades.csv and
# refusals.csv (a day already recorded is replaced). Prints one summary line: the day, then the running totals and the
# number of flagged entries (rvol_tod >= 2.5) so far; the ledger reads the test at 30 flagged trades or 40 sessions.
# Usage: 2026-11-12-a378-paper-forward.sh YYYY-MM-DD
set -u
DAY=$1
OUTD="${A378_OUT:-$HOME/.autotrade-sync/a378}"; mkdir -p "$OUTD"
TR="$OUTD/trades.csv"; RF="$OUTD/refusals.csv"
G="--tunnel-through-iap --zone=asia-south1-a --project=zt-tiger-v2-prod --account=${ACC:-satyajava704@gmail.com}"
Q="select '$DAY', a.display_name, p.strategy_id, p.symbol, to_char(p.opened_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
 to_char(p.closed_at at time zone 'Asia/Kolkata','HH24:MI:SS'), coalesce(p.exit_reason, ''), p.status,
 (select max(o.quantity_filled) from exec.order_execution o where o.trade_intent_id = p.entry_trade_intent_id and o.role = 'ENTRY'),
 round(p.average_entry::numeric, 2), round(coalesce(p.realised_gross - p.charges, 0)::numeric, 2),
 coalesce((d.context->>'rvol_tod'), '')
 from exec.position p join acct.broker_account a on a.id = p.broker_account_id
 left join exec.trade_intent i on i.id = p.entry_trade_intent_id left join exec.trade_batch b on b.id = i.trade_batch_id
 left join exec.signal_decision d on d.id = b.signal_decision_id
 where a.display_name in ('PAPER-40', 'PAPER-40-RVOL') and (p.opened_at at time zone 'Asia/Kolkata')::date = '$DAY'
 order by p.opened_at, a.display_name"
R="select '$DAY', a.display_name, i.strategy_id, i.symbol, to_char(i.created_at at time zone 'Asia/Kolkata','HH24:MI:SS'),
 i.state, coalesce(i.reject_code, ''), coalesce((d.context->>'rvol_tod'), '')
 from exec.trade_intent i join acct.broker_account a on a.id = i.broker_account_id
 join exec.trade_batch b on b.id = i.trade_batch_id join exec.signal_decision d on d.id = b.signal_decision_id
 where a.display_name in ('PAPER-40', 'PAPER-40-RVOL') and i.action = 'ENTER' and i.state <> 'EXECUTED'
   and d.session_date = '$DAY' order by i.created_at, a.display_name"
run() {
  printf '%s\n' "$1" > /tmp/a378-q.$$.sql
  gcloud compute scp /tmp/a378-q.$$.sql zt-autotrade:/tmp/a378-q.sql $G >/dev/null 2>&1 \
    && gcloud compute ssh zt-autotrade $G --command="sudo docker exec -i autotrade-postgres psql -U autotrade -d autotrade -X -At -F, -v ON_ERROR_STOP=1 < /tmp/a378-q.sql; rc=\$?; rm -f /tmp/a378-q.sql; exit \$rc" 2>/dev/null
  local rc=$?; rm -f /tmp/a378-q.$$.sql; return $rc
}
T=$(run "copy ($Q) to stdout csv") || { echo "A-378 $DAY: reading GCP failed; the day is not recorded"; exit 1; }
F=$(run "copy ($R) to stdout csv") || { echo "A-378 $DAY: reading GCP failed; the day is not recorded"; exit 1; }
[ -f "$TR" ] || echo "day,account,strategy,symbol,opened,closed,exit_reason,status,quantity,entry,net,rvol_tod" > "$TR"
[ -f "$RF" ] || echo "day,account,strategy,symbol,at,state,reject_code,rvol_tod" > "$RF"
for f in "$TR" "$RF"; do grep -v "^$DAY," "$f" > "$f.tmp"; mv "$f.tmp" "$f"; done
[ -n "$T" ] && printf '%s\n' "$T" >> "$TR"
[ -n "$F" ] && printf '%s\n' "$F" >> "$RF"
awk -F, -v d="$DAY" 'NR > 1 {
    n[$2] += $11; if ($1 == d) { t[$2] += $11; c[$2]++ }
    if ($2 == "PAPER-40-RVOL" && $12 != "" && $12 + 0 >= 2.5) flagged++
    days[$1] = 1 }
  END { nd = 0; for (x in days) nd++
    printf "A-378 %s: PAPER-40 %+d (%d trades), PAPER-40-RVOL %+d (%d); since 13 Nov: %+d vs %+d over %d session(s) with trades; flagged entries %d of 30\n",
      d, t["PAPER-40"], c["PAPER-40"], t["PAPER-40-RVOL"], c["PAPER-40-RVOL"], n["PAPER-40"], n["PAPER-40-RVOL"], nd, flagged + 0 }' "$TR"
