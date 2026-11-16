-- The owner's choice of LIVE strategies per account (user 9 Nov 2026: "As the owner I should be able to select or
-- deselect from all the available live strategies"). Null = the risk file's live.default_strategies (or, in a file
-- without them, its live.strategies). The risk engine trades LIVE only the chosen strategies that are also in the risk
-- file's live.strategies (the list that may be chosen from), so a choice can never widen what the reviewed file allows.
alter table acct.broker_account add column live_strategies text[];

-- follower-risk v9 is v7 with every live-set strategy choosable and v7's three as the default: moving the v7 accounts
-- to it changes nothing they trade until the owner chooses (the file itself says so).
update acct.broker_account set risk_file = replace(risk_file, 'follower-risk.v7.yaml', 'follower-risk.v9.yaml'),
                               updated_at = now()
where risk_file like '%follower-risk.v7.yaml';
