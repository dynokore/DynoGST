SET lock_timeout = '5s';

-- INV-T-27733, INV-REQ-413, INV-AC-27723: a window part may be MISSED, and missed_at keeps the instant it was first
-- reported missed (null in every other state). Grants and row-level security of the table are unchanged.
-- Rollback: forward only (a later migration); no existing row is rewritten.
alter table submission.window_part_state
    drop constraint window_part_state_state_check,
    add constraint window_part_state_state_check check (state in ('OPEN', 'CLOSED', 'LATE', 'MISSED')),
    add column missed_at timestamptz;
