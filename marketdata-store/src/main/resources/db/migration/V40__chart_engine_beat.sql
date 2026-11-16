-- The chart-order engine's heartbeat (review 16 Nov 2026, D6). The engine runs inside the trading session; arming and
-- moving chart orders is the execution app's, and the two meet only in this database. Chart orders could be armed (and
-- an open position's stop moved) with no engine watching them (engine off, session failed or restarting): nothing would
-- ever fire. The engine stamps beat_at at every sync (about once a second); arm and move are refused when the day's
-- stamp is older than a few seconds, and the order list shows the engine's state.
create table exec.chart_engine_beat (
    session_date  date        primary key,
    beat_at       timestamptz not null
);
