-- Telegram's /exit ID LOTS (17 Nov 2026): the confirm card stores how many of the position it sells (whole lots, in
-- units); null sells all of it, as before.
alter table exec.telegram_command add column quantity bigint check (quantity is null or quantity > 0);
