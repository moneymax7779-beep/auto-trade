-- The Live page's futures-surge panel reads the last quote of a few contracts in a given minute
-- (ATM +- 2 strikes, calls and puts). With only the time index each lookup read every option tick of
-- that minute (~14,000 for NIFTY); this index makes it one index probe per contract.
create index if not exists option_tick_contract_time on md.option_tick (underlying, expiry, strike, option_type, recv_ts desc);
