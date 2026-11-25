-- V29__offboarding_custody_holder.sql (INV-T-26717; INV-REQ-639, INV-AC-26713)
-- The retention custody holder and the notice window carried by OffboardingExportRequested (event-carried state
-- transfer, coordinator ruling 2026-11-21): one row per tenant and offboarding, written once by the inbox consumer.
-- The three custody holder columns are personal data (class: personal, as V10 marks a personal column). The runtime
-- login holds select and insert only: a row is never changed or deleted by the service. Static SQL only.

create table evidence.offboarding_custody_holder (
    tenant_id uuid not null,
    offboarding_ref text not null check (offboarding_ref <> ''),
    custody_holder_name text not null check (custody_holder_name <> ''),
    custody_holder_email text not null check (custody_holder_email <> ''),
    custody_holder_mobile text,
    notice_window_days integer check (notice_window_days is null or notice_window_days >= 1),
    received_at timestamptz not null,
    unique (tenant_id, offboarding_ref)
);
comment on column evidence.offboarding_custody_holder.custody_holder_name is 'class: personal';
comment on column evidence.offboarding_custody_holder.custody_holder_email is 'class: personal';
comment on column evidence.offboarding_custody_holder.custody_holder_mobile is 'class: personal';

ALTER TABLE evidence.offboarding_custody_holder ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.offboarding_custody_holder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.offboarding_custody_holder
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.offboarding_custody_holder FROM evidence_runtime;
grant select, insert on evidence.offboarding_custody_holder to evidence_runtime;
