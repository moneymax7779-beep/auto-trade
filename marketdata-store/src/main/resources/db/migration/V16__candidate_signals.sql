-- Every entry or add a strategy asked for, approved or refused, with the market context at that minute
-- (docs/EXPERIMENT-LEDGER.md A-033): the sample for asking which signals deserve size. A refused candidate's
-- outcome comes from an unconstrained replay of the same day (research risk file), not from this table.
create table trade.candidate (
    id          bigserial primary key,
    session_id  bigint not null references trade.session (id),
    strategy_id text not null,
    underlying  text not null,
    at          timestamptz not null,
    action      text not null,               -- ENTER, ENTER_STRADDLE or ADD
    side        text,                        -- CE / PE; null for a straddle
    reason      text,
    approved    boolean not null,
    refusal     text,
    spot        double precision,
    context     jsonb not null
);
create index candidate_session on trade.candidate (session_id, strategy_id, underlying, at);
