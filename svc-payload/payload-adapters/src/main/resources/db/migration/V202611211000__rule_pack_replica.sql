-- The replica of the schematron and severity-override pack versions payload-svc holds from RulePackStaged and RulePackActivated (INV-T-27184; INV-AC-27183). Event-carried state transfer as payload.mde_pack_replica: a version is staged once with its contents, which are never rewritten; exactly one version per tenant and pack type is active. Expand-only.
create table payload.rule_pack_replica (
    tenant_id uuid not null,
    pack_type text not null check (pack_type in ('schematron', 'severity-override')),
    pack_identifier text not null check (pack_identifier <> ''),
    pack_version text not null check (pack_version <> ''),
    contents jsonb not null,
    staged_at timestamptz not null default now(),
    active boolean not null default false,
    primary key (tenant_id, pack_type, pack_identifier, pack_version)
);
create unique index rule_pack_replica_one_active on payload.rule_pack_replica (tenant_id, pack_type) where active;
ALTER TABLE payload.rule_pack_replica ENABLE ROW LEVEL SECURITY;
ALTER TABLE payload.rule_pack_replica FORCE ROW LEVEL SECURITY;
create policy rule_pack_replica_tenant_isolation on payload.rule_pack_replica
    using      (tenant_id = (select payload.bound_tenant()))
    with check (tenant_id = (select payload.bound_tenant()));
-- the activation flag is the only column that changes after the insert: the contents of a staged version are never rewritten
grant select, insert on payload.rule_pack_replica to payload_runtime;
grant update (active) on payload.rule_pack_replica to payload_runtime;
