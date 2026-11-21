-- V202611220900__widen_run_read_model_key.sql (INV-T-26647, INV-REQ-740, INV-AC-26646).
--
-- One Doc UUID holds both sides of a pair: the supply (SALE) of one entity and the purchase (PURCHASE) of another. The
-- natural key of a received document row becomes the Doc UUID with the entity and the direction (event-carried state;
-- no heuristic matching). document_acknowledgement keeps its key (tenant_id, doc_uuid, version): the
-- SubmissionAcknowledged payload names no entity.
--
-- document_line is append-only (trigger plus revokes, NIST SP 800-53 AU-9), so its new columns are filled by a rebuild
-- of the table, never by an UPDATE: the rows are copied once from their version row, then the old table is dropped.
-- The trigger, policy, force flag, revokes and grants of the rebuilt table equal V202611190900's.
alter table reconciliation.document_line no force row level security;
alter table reconciliation.document_line rename to document_line_old;
alter table reconciliation.document_line_old rename constraint document_line_pkey to document_line_old_pkey;

-- the cascade drops only the foreign key of document_line_old
alter table reconciliation.document_version drop constraint document_version_pkey cascade;
alter table reconciliation.document_version
    add primary key (tenant_id, doc_uuid, entity_ref, direction, version);

create table reconciliation.document_line (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    entity_ref text not null,
    direction text not null,
    version int not null,
    line_number int not null,
    tax_code text,
    line_amount numeric,
    tax_amount numeric,
    primary key (tenant_id, doc_uuid, entity_ref, direction, version, line_number),
    foreign key (tenant_id, doc_uuid, entity_ref, direction, version)
        references reconciliation.document_version (tenant_id, doc_uuid, entity_ref, direction, version)
);
insert into reconciliation.document_line
    (tenant_id, doc_uuid, entity_ref, direction, version, line_number, tax_code, line_amount, tax_amount)
select l.tenant_id, l.doc_uuid, v.entity_ref, v.direction, l.version, l.line_number, l.tax_code, l.line_amount,
       l.tax_amount
from reconciliation.document_line_old l
join reconciliation.document_version v
    on v.tenant_id = l.tenant_id and v.doc_uuid = l.doc_uuid and v.version = l.version;
drop table reconciliation.document_line_old;

alter table reconciliation.document_period drop constraint document_period_pkey;
alter table reconciliation.document_period add primary key (tenant_id, doc_uuid, entity_ref);

comment on column reconciliation.document_line.entity_ref is 'class: operational';
comment on column reconciliation.document_line.direction is 'class: operational';
comment on column reconciliation.document_line.tax_code is 'class: operational';
comment on column reconciliation.document_line.line_amount is 'class: operational';
comment on column reconciliation.document_line.tax_amount is 'class: operational';

ALTER TABLE reconciliation.document_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_line FORCE ROW LEVEL SECURITY;
CREATE POLICY document_line_tenant_isolation ON reconciliation.document_line
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create trigger document_line_append_only before update or delete on reconciliation.document_line
    for each row execute function reconciliation.run_read_model_append_only();
revoke update, delete, truncate on reconciliation.document_line from reconciliation_runtime;
grant select, insert on reconciliation.document_line to reconciliation_runtime;
