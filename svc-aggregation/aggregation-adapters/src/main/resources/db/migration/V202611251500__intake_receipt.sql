SET lock_timeout = '5s';
-- V202611251500 (INV-T-28770; INV-AC-28764): the submitting principal of every accepted aggregate intake, an
-- append-only record of the acting subject (NIST SP 800-53 AU-3, AU-9): one row per intake operation and triple
-- (entity_ref, source_key, source_revision) it newly accepts, written in the transaction that stores the intake. The
-- two operations check replays in separate tables, so each can accept the same triple: the operation is part of the
-- key. The runtime role only reads and inserts. Intake accepted before this migration has no receipt (no backfill).
create table aggregation.intake_receipt (
    tenant_id uuid not null,
    intake_operation text not null check (intake_operation in ('BATCH', 'TRANSACTIONS')),
    entity_ref text not null,
    source_key text not null,
    source_revision bigint not null,
    aggregate_type text not null check (aggregate_type in ('POS', 'STI', 'PCP')),
    submitted_by text not null check (submitted_by !~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$|[\u0001-\u001F\u007F-\u009F]'),
    recorded_at timestamptz not null default now(),
    primary key (tenant_id, intake_operation, entity_ref, source_key, source_revision)
);
comment on column aggregation.intake_receipt.submitted_by is 'class: personal';
alter table aggregation.intake_receipt enable row level security;
alter table aggregation.intake_receipt force row level security;
create policy intake_receipt_tenant_isolation on aggregation.intake_receipt
    using      (tenant_id = (select aggregation.bound_tenant()))
    with check (tenant_id = (select aggregation.bound_tenant()));
grant select, insert on aggregation.intake_receipt to aggregation_runtime;
