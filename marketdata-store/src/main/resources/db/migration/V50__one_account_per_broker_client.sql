-- One account per broker client (account-management review, 19 Nov 2026): the same Zerodha / Upstox / Kotak / Groww
-- client on two enabled real accounts (e.g. one in the admin's workspace and one in a member's) would place every LIVE
-- order twice, and each account's reconciliation would see the other's positions. Enabled real accounts only (paper
-- twins and PAPER accounts carry no broker client; a disabled copy may stay as history). The app refuses it first with
-- a readable message (AccountAdminController.sameClient); this index holds against a race.
create unique index broker_account_one_enabled_client on acct.broker_account
    (broker_type, upper(external_broker_user_id))
    where enabled and paper_of_account_id is null and broker_type <> 'PAPER' and external_broker_user_id is not null;
