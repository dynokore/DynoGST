-- INV-T-26283: the portfolio read model (INV-AC-26279), per tenant, entity and period. Forced row level security;
-- each section between the markers is the unchanged output of TenantRlsMigration.ddl, kept equal by
-- PortfolioStoreDbTest.
create table console_bff.portfolio_period (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    state text not null check (state in ('OPEN', 'DUE', 'FILED', 'LATE')),
    filing_due_date date not null,
    filing_date date,
    extension_granted boolean not null,
    primary key (tenant_id, entity_ref, period_ref)
);
-- BEGIN TenantRlsMigration.ddl("console_bff", "portfolio_period")
ALTER TABLE console_bff.portfolio_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.portfolio_period FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.portfolio_period
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.portfolio_period to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; this table is never deleted from
revoke delete on console_bff.portfolio_period from console_bff_runtime;

create table console_bff.portfolio_readiness (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    verdict text not null check (verdict in ('READY', 'BLOCKED', 'UNABLE_TO_DETERMINE')),
    blocked_count integer check (blocked_count >= 0),
    observed_at timestamptz not null,
    primary key (tenant_id, entity_ref, period_ref),
    check ((verdict = 'BLOCKED') = (blocked_count is not null))
);
-- BEGIN TenantRlsMigration.ddl("console_bff", "portfolio_readiness")
ALTER TABLE console_bff.portfolio_readiness ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.portfolio_readiness FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.portfolio_readiness
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.portfolio_readiness to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; this table is never deleted from
revoke delete on console_bff.portfolio_readiness from console_bff_runtime;
