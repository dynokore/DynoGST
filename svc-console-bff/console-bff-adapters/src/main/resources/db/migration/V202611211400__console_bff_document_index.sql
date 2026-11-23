-- INV-T-26815: the document index read model (INV-REQ-1037, INV-AC-26808): identifiers and searchable attributes
-- per tenant, Doc UUID and version, fed by the console-bff event consumers. Forced row level security; the section
-- between the markers is the unchanged output of TenantRlsMigration.ddl, kept equal by DocumentIndexDbTest.
create table console_bff.document_index (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    version bigint not null check (version >= 1),
    entity_ref text not null check (entity_ref <> ''),
    document_number text,
    counterparty_name text,
    counterparty_uen text,
    amount numeric(19, 2),
    document_date date,
    transaction_date date not null,
    category_codes text[] not null default '{}',
    state text not null check (state in ('READY', 'NOT_SUBMITTABLE', 'HELD', 'SENT', 'ACKNOWLEDGED')),
    acknowledgement_id text,
    error_code text,
    primary key (tenant_id, doc_uuid, version)
);
create index document_index_entity_date on console_bff.document_index (tenant_id, entity_ref, document_date);
create index document_index_acknowledgement on console_bff.document_index (tenant_id, acknowledgement_id);
-- BEGIN TenantRlsMigration.ddl("console_bff", "document_index")
ALTER TABLE console_bff.document_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE console_bff.document_index FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON console_bff.document_index
    USING      (tenant_id = (select console_bff.bound_tenant()))
    WITH CHECK (tenant_id = (select console_bff.bound_tenant()));
-- END TenantRlsMigration.ddl
grant select, insert, update on console_bff.document_index to console_bff_runtime;
-- provisioning's default privileges also grant delete on every new table; this table is never deleted from
revoke delete on console_bff.document_index from console_bff_runtime;
