-- follower-risk v10 (user 10 Nov 2026: "LIVE slots first" -> "Yes, prepare v10"; ledger A-372): v9 with three open
-- positions instead of two (positions.max_open_positions and live.max_open_positions); every other limit, the LIVE
-- strategy list and its defaults are v9's. Moves every account on v9 to v10, as V32 moved v7 to v9. An account's own
-- LIVE strategy choice (live_strategies) is untouched. Rollback: the same update with the two file names swapped.
update acct.broker_account set risk_file = replace(risk_file, 'follower-risk.v9.yaml', 'follower-risk.v10.yaml'),
                               updated_at = now()
where risk_file like '%follower-risk.v9.yaml';
