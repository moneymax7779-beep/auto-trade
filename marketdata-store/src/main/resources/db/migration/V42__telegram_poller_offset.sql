-- The command bot reader's own next offset (review 16 Nov 2026, second pass on D8). V41 kept the bot; a restart still
-- took max(update_id) + 1 over every update ever stored, so after Telegram restarted its ids lower (a quiet week, or a
-- bot whose ids are lower) every new update below the old maximum was confirmed unread: /kill lost for up to 6 days.
-- The reader writes the offset it would read from next at every successful poll; a restart reads it back when the
-- token's bot is the one it belongs to (bot_id), else starts from the bot's first unconfirmed update.
alter table exec.telegram_poller add column next_offset bigint;
