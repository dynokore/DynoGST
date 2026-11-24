-- The stored rendition sample checks of payload-svc (INV-T-27774; INV-AC-27769; INV-REQ-1033, INV-REQ-1036). Each recorded check keeps the uploaded sample bytes, its result and the exact artefact bytes hashed into the evidence. Append-only by grant (NIST SP 800-53 AU-9): the runtime role may select and insert, never update or delete. Expand-only.
create table payload.rendition_sample_check (
    recorded_seq bigint generated always as identity,
    tenant_id uuid not null,
    entity_ref text not null,
    check_id text not null,
    scenario text not null,
    submission_type text not null check (submission_type in ('TYPE_1A', 'TYPE_1B', 'TYPE_2', 'TYPE_3')),
    sample_content bytea not null,
    sample_sha256 text not null check (sample_sha256 ~ '^[0-9a-f]{64}$'),
    result text not null check (result in ('PASSED', 'FAILED')),
    missing_wording jsonb not null,
    text_found boolean not null,
    checked_at timestamptz not null,
    checked_by text not null check (btrim(checked_by) <> ''),
    artefact_bytes bytea not null,
    primary key (tenant_id, entity_ref, check_id),
    unique (tenant_id, entity_ref, sample_sha256, scenario, submission_type)
);
ALTER TABLE payload.rendition_sample_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.rendition_sample_check FORCE ROW LEVEL SECURITY;
create policy rendition_sample_check_tenant_isolation on payload.rendition_sample_check
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
-- no update and no delete: a recorded check is retained as written
grant select, insert on payload.rendition_sample_check to payload_runtime;
