-- A-378 (user 12 Nov 2026: "Set up paper test", after A-377): the PAPER forward test of full size on high-RVOL entries.
-- Two PAPER followers of the owner's LIVE-SET copy group, identical but for one setting, both mirroring ZERODHA-1 as it
-- trades from 13 Nov (Rs 1 L, size step 40 %, daily loss Rs 20 K; its four LIVE strategies etr / mac / obc / wbr):
--   PAPER-40       follower-risk v11: every entry at 40 % of full size (= ZERODHA-1's sizing, simulated fills);
--   PAPER-40-RVOL  follower-risk v12: the same, except an entry whose signal carries futures RVOL >= 2.5 is full size.
-- FIXED capital (not compounding) so both compare rupee for rupee from 13 Nov. Simulated fills only: a PAPER broker
-- account can never trade LIVE (V21 check). Not paper twins (a twin follows its real account's settings).
-- Only where the owner's tenant has its LIVE-SET group and ZERODHA-1 (GCP); elsewhere nothing. Re-runnable.
-- Rollback: disable both accounts (enabled = false) on the Accounts page.
with owner as (
    select z.tenant_id, z.app_user_id, g.id as copy_group_id
    from acct.broker_account z
    join acct.copy_group g on g.tenant_id = z.tenant_id and g.name = 'LIVE-SET'
    where z.display_name = 'ZERODHA-1' and z.broker_type = 'ZERODHA'
      and z.tenant_id = (select id from acct.tenant where name = 'owner')
),
arms (display_name, risk_file) as (
    values ('PAPER-40', 'config/risk/follower-risk.v11.yaml'),
           ('PAPER-40-RVOL', 'config/risk/follower-risk.v12.yaml')
),
created as (
    insert into acct.broker_account (tenant_id, app_user_id, broker_type, display_name, account_role, connection_status,
                                     trade_mode, allocated_capital, equity_mode, equity_from, risk_file, size_step_pct,
                                     daily_loss_limit, enabled)
    select o.tenant_id, o.app_user_id, 'PAPER', a.display_name, 'FOLLOWER_TRADE', 'ACTIVE', 'PAPER', 100000, 'FIXED',
           date '2026-11-13', a.risk_file, 40, 20000, true
    from owner o cross join arms a
    on conflict (tenant_id, display_name) do nothing
    returning id, tenant_id, display_name, risk_file
),
subscribed as (
    insert into acct.strategy_subscription (copy_group_id, broker_account_id, strategy_id, enabled, size_multiplier)
    select o.copy_group_id, c.id, s.strategy_id, true, 1
    from created c join owner o on o.tenant_id = c.tenant_id
    cross join (values ('expiry-trend-rider'), ('ma-cross'), ('oi-burst-cross'), ('wall-break-rider')) s (strategy_id)
    on conflict (copy_group_id, broker_account_id, strategy_id) do nothing
    returning broker_account_id
)
insert into exec.audit_log (tenant_id, broker_account_id, actor, action, detail)
select c.tenant_id, c.id, 'migration V37', 'ACCOUNT_CREATED',
       jsonb_build_object('name', c.display_name, 'broker', 'PAPER', 'riskFile', c.risk_file, 'allocation', 100000,
                          'equityMode', 'FIXED', 'sizeStepPct', 40, 'dailyLossLimit', 20000,
                          'strategies', jsonb_build_array('expiry-trend-rider', 'ma-cross', 'oi-burst-cross',
                                                          'wall-break-rider'),
                          'subscriptions', (select count(*) from subscribed s where s.broker_account_id = c.id),
                          'reason', 'A-378 PAPER forward test of full size at RVOL >= 2.5 (user 12 Nov 2026)')
from created c;
