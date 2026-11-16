-- The command bot the stored updates came from (review 16 Nov 2026, D8): its id is the part of the token before ':'.
-- update_id is per bot, so a reader moved to another bot (bot.env changed) must not keep the old bot's offset: an
-- update with a lower id would be confirmed without being returned, a command lost with the poller looking healthy.
alter table exec.telegram_poller add column bot_id bigint;
