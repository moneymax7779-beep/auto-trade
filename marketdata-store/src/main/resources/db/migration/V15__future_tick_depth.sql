-- Index futures' best five bid / ask levels (Upstox full mode sends them for futures as for options;
-- until now only total buy / sell quantity was kept). Nullable: rows captured before this have none.
alter table md.future_tick add column bid_px double precision[];
alter table md.future_tick add column bid_qty bigint[];
alter table md.future_tick add column bid_orders integer[];
alter table md.future_tick add column ask_px double precision[];
alter table md.future_tick add column ask_qty bigint[];
alter table md.future_tick add column ask_orders integer[];
