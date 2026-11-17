-- Telegram /kill <strategy> and /flattenall (17 Nov 2026, Telegram controls plan C4/C5): a card names the strategy it
-- stops, and the two new verbs are allowed.
alter table exec.telegram_command add column strategy_id text;
alter table exec.telegram_command drop constraint telegram_command_verb_check;
alter table exec.telegram_command add constraint telegram_command_verb_check
    check (verb in ('KILL_GLOBAL', 'KILL_TENANT', 'KILL_ACCOUNT', 'KILL_STRATEGY', 'FLATTEN', 'FLATTEN_ALL', 'EXIT'));
