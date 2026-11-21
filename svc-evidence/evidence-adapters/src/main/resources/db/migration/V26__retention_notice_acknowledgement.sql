-- INV-T-26258 (INV-REQ-2462): the notice keeps the acknowledgement behind a shortened window, fixed at notice time.
-- Existing rows keep both columns null. The user ID is stored, never a role (INV-Q-10647).
alter table evidence.retention_notice
    add column acknowledged_by text null check (acknowledged_by <> ''),
    add column acknowledged_sole_operator boolean null;
