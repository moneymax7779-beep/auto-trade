-- Telegram's update_id is unique per bot (V39's own words), but exec.telegram_update was keyed by update_id alone: an
-- update of a new bot (or one after Telegram restarted its ids) that carried an id an earlier bot had used was dropped
-- as a repeat, unhandled and unlogged (review 16 Nov 2026, second pass). Keyed by (bot_id, update_id) now; the updates
-- kept before carry bot 0 (not known), as do the commands they started.
alter table exec.telegram_update add column bot_id bigint not null default 0;
alter table exec.telegram_command add column bot_id bigint not null default 0;
alter table exec.telegram_command drop constraint telegram_command_update_id_fkey;
alter table exec.telegram_update drop constraint telegram_update_pkey;
alter table exec.telegram_update add primary key (bot_id, update_id);
alter table exec.telegram_command add constraint telegram_command_update_fkey foreign key (bot_id, update_id)
    references exec.telegram_update (bot_id, update_id);
