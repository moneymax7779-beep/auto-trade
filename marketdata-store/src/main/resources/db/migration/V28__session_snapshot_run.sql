-- Code review 5 Nov 2026: a session's trade context (entry / exit feature snapshots) found its research.run by date and
-- start time, so two replays of one date started back to back could show each other's snapshots. A session now records
-- the run it stores its snapshots under; older sessions keep the time match.
alter table trade.session add column snapshot_run_id bigint references research.run (id);
