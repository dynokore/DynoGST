-- INV-T-24951 (INV-REQ-504, INV-AC-24949): the scoped, expiring acceptance of one INFERRED exchange-rate source for one
-- tenant. One row per tenant and source: a repeat replaces it (the repeatKey of the contract). accepted_by is the user
-- ID of the acting user, never a role (INV-Q-10647). Relational columns only: no json, jsonb or bytea.

create table rules.rate_source_acceptance (
    tenant_id   uuid        not null,
    source_id   text        not null check (source_id !~ '^\s*$' and char_length(source_id) <= 40),
    rationale   text        not null check (rationale !~ '^\s*$' and char_length(rationale) <= 500),
    expires_on  date        not null,
    accepted_by text        not null check (accepted_by !~ '^\s*$|[[:cntrl:]]'),
    accepted_at timestamptz not null,
    primary key (tenant_id, source_id)
);

alter table rules.rate_source_acceptance enable row level security;
alter table rules.rate_source_acceptance force row level security;

create policy rate_source_acceptance_tenant_read on rules.rate_source_acceptance for select
    using (tenant_id = (select rules.bound_tenant()));
create policy rate_source_acceptance_tenant_insert on rules.rate_source_acceptance for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy rate_source_acceptance_tenant_update on rules.rate_source_acceptance for update
    using (tenant_id = (select rules.bound_tenant()))
    with check (tenant_id = (select rules.bound_tenant()));
create policy rate_source_acceptance_tenant_delete on rules.rate_source_acceptance for delete
    using (tenant_id = (select rules.bound_tenant()));

-- Least privilege: nothing for PUBLIC; the runtime replaces a row and never deletes one.
revoke all on rules.rate_source_acceptance from public;
grant select, insert, update on rules.rate_source_acceptance to rules_runtime;
