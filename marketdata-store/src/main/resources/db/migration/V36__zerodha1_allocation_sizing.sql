-- follower-risk v11 for ZERODHA-1 (user 11 Nov 2026: "I want to make my zerodha account to be allowed to trade the
-- capital and stop loss specified"; answers: size "Full (size step 100 %)", lot ceiling "Allocation only", loss limit
-- "Count open loss, close all"). The account leaves the one-lot pilot: every LIVE entry (and add) is sized from its own
-- allocation like a paper follower (size step 100), and its daily loss limit counts open positions at the bid and sells
-- everything when reached (v11). Its allocation, equity mode, daily loss limit and LIVE strategy choice are untouched
-- (the Accounts page settings). Only the platform owner's Zerodha account ZERODHA-1 (tenant 'owner', account id 1 on
-- GCP) on follower-risk v10; every other account stays, a member's account named ZERODHA-1 in its own workspace included.
-- Rollback: the same update back to follower-risk.v10.yaml and size_step_pct 0 (one lot an entry).
with moved as (
    update acct.broker_account
    set risk_file = replace(risk_file, 'follower-risk.v10.yaml', 'follower-risk.v11.yaml'),
        size_step_pct = 100,
        updated_at = now()
    where display_name = 'ZERODHA-1' and broker_type = 'ZERODHA' and risk_file like '%follower-risk.v10.yaml'
      and tenant_id = (select id from acct.tenant where name = 'owner')
    returning id, tenant_id
)
insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail)
select tenant_id, id, 'migration V36', 'RISK_FILE', jsonb_build_object('riskFile', 'follower-risk.v11.yaml',
       'sizeStepPct', 100, 'reason', 'allocation sizing and daily loss with open positions (user 11 Nov 2026)')
from moved;
