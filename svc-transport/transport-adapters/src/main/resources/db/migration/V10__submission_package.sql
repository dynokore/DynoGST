-- V10__submission_package.sql (INV-T-29531; INV-REQ-424, INV-REQ-538, INV-AC-29528).
-- The package log: one row per packageRef of a tenant, claimed before the package is sent once. The member attempts
-- keep their own rows and carry the packageRef, so a rejection of a whole package is not a settled outcome of a member.
create table transport.submission_package (
    tenant_id uuid not null,
    package_ref text not null check (char_length(package_ref) between 1 and 40),
    entity_ref text not null,
    fingerprint text not null,
    adapter_id text not null,
    started_at timestamptz not null,
    finished_at timestamptz,
    outcome text check (outcome in ('ACCEPTED', 'REJECTED', 'TRANSIENT_FAILURE')),
    acknowledgement_id text,
    reasons text,
    primary key (tenant_id, package_ref),
    check ((outcome is null) = (finished_at is null))
);
comment on column transport.submission_package.reasons is 'class: operational; JSON list of {code, field, message}';
alter table transport.submission_attempt add column package_ref text;
alter table transport.submission_package enable row level security;
alter table transport.submission_package force row level security;
create policy submission_package_tenant_isolation on transport.submission_package
    using      (tenant_id = (select transport.bound_tenant()))
    with check (tenant_id = (select transport.bound_tenant()));
grant select, insert, update on transport.submission_package to transport_runtime;
