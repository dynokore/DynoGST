SET lock_timeout = '5s';

-- INV-T-29425, INV-REQ-413, INV-AC-29421: two windows (business unit, category) may have the same part dates, so both
-- are part of the key of a window part row. A row of an event without them holds the empty string in both. Grants and
-- row-level security of the table are unchanged.
-- An instance of the earlier release names the old key in its upsert, which fails after this migration; the event is
-- delivered again later.
-- Rollback: forward only (a later migration); every existing row keeps its values.
alter table submission.window_part_state
    add column business_unit_ref text not null default '',
    add column aggregate_type text not null default '',
    drop constraint window_part_state_pkey,
    add constraint window_part_state_pkey primary key
        (tenant_id, entity_ref, period_ref, part_from, part_to, business_unit_ref, aggregate_type);
