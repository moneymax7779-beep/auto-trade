-- A workspace's default broker is the day's broker before its first switch (acct.trading_state), but it is one mutable
-- column: changing it (the first switch, adding the first account, removing the default account) rewrote what the state
-- was at every earlier moment with no event. On 17 Nov 2026 a first switch at 10:24 made the 09:21 entries' as-of target
-- the new broker, and they were bought an hour late at the 09:21 price.
--
-- The default now carries when it took effect: earlier on that same day, with no event, the state is Off (unknown, never
-- a broker chosen later). An earlier day (a replay or a back-test of followers) takes the default, as a subscription made
-- after the session's day takes the whole day (V27). Every change of the column stamps it (trigger), whoever writes it.
-- Existing defaults count from the beginning (-infinity), so nothing changes until a default is changed.

alter table acct.tenant add column default_since timestamptz not null default '-infinity';

create function acct.stamp_default_since() returns trigger language plpgsql as $$
begin
    if new.default_broker_account_id is distinct from old.default_broker_account_id then
        new.default_since := clock_timestamp();
    end if;
    return new;
end
$$;

create trigger tenant_default_since before update of default_broker_account_id on acct.tenant
    for each row execute function acct.stamp_default_since();

create or replace function acct.trading_state(p_tenant bigint, p_at timestamptz)
    returns table (broker_account_id bigint, mode text, since timestamptz)
    language sql stable as $$
    select case when e.id is not null then e.broker_account_id
                when p_at >= t.default_since
                     or (p_at at time zone 'Asia/Kolkata')::date < (t.default_since at time zone 'Asia/Kolkata')::date
                     then t.default_broker_account_id
                else null end,
           case when e.id is not null and (e.changed_at at time zone 'Asia/Kolkata')::date
                                          = (p_at at time zone 'Asia/Kolkata')::date then e.mode else 'PAPER' end,
           e.changed_at
    from acct.tenant t
    left join lateral (select x.id, x.broker_account_id, x.mode, x.changed_at from acct.trading_day_event x
                       where x.tenant_id = t.id and x.changed_at <= p_at
                       order by x.changed_at desc, x.id desc limit 1) e on true
    where t.id = p_tenant
$$;
