-- V33__breach_terms_pack_replica.sql (INV-T-29320; INV-REQ-1385, INV-AC-29315)
-- The replica of the breach terms packs from RulePackStaged and RulePackActivated (event-carried state transfer, the
-- shape of the pack replica of payload-svc, INV-T-23579). The packs are operator-maintained: the store writes and reads
-- rows of the platform tenant only. A signed binding of any tenant reads the platform rows (the shape of
-- rules.pack_artefact: a tenant's staged event is compared with the recorded type, and a case of a tenant reads the
-- terms); only the platform binding writes a platform row. All three tables are append-only: the runtime login holds
-- select and insert only. The store records an activation only for a stored version. Static SQL only.

-- RulePackActivated carries no pack type: the type of every pack identifier is recorded once and never changed.
create table evidence.breach_terms_pack_type (
    tenant_id uuid not null,
    pack_identifier text not null check (pack_identifier <> ''),
    pack_type text not null check (pack_type <> ''),
    unique (tenant_id, pack_identifier)
);

create table evidence.breach_terms_pack_version (
    tenant_id uuid not null,
    pack_identifier text not null check (pack_identifier <> ''),
    pack_version text not null check (pack_version <> ''),
    assessment_within_hours bigint not null check (assessment_within_hours >= 1),
    notification_within_hours bigint not null check (notification_within_hours >= 1),
    source_reference text not null check (source_reference <> ''),
    unique (tenant_id, pack_identifier, pack_version)
);

-- applied_seq is the order in which activations were applied: it decides between two of the same effective date.
create table evidence.breach_terms_pack_activation (
    applied_seq bigint generated always as identity primary key,
    tenant_id uuid not null,
    pack_identifier text not null,
    pack_version text not null,
    effective_from date not null
);
create index breach_terms_pack_activation_in_force
    on evidence.breach_terms_pack_activation (tenant_id, effective_from desc, applied_seq desc);

ALTER TABLE evidence.breach_terms_pack_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_type
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_type FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_type FROM evidence_runtime;
grant select, insert on evidence.breach_terms_pack_type to evidence_runtime;

ALTER TABLE evidence.breach_terms_pack_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_version
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_version FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_version FROM evidence_runtime;
grant select, insert on evidence.breach_terms_pack_version to evidence_runtime;

ALTER TABLE evidence.breach_terms_pack_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_activation
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_activation FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_activation FROM evidence_runtime;
grant select, insert on evidence.breach_terms_pack_activation to evidence_runtime;
