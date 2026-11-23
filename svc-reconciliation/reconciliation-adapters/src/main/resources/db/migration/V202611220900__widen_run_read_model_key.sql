-- V202611220900__widen_run_read_model_key.sql (INV-T-26647, INV-REQ-740, INV-AC-26646).
--
-- One Doc UUID holds both sides of a pair: the supply (SALE) of one entity and the purchase (PURCHASE) of another. The
-- natural key of a received document row becomes the Doc UUID with the entity and the direction (event-carried state;
-- no heuristic matching). document_acknowledgement takes the same key (tenant_id, doc_uuid, entity_ref, direction,
-- version): the SubmissionAcknowledged payload now names the entity and the direction (INV-T-27281, INV-T-27282).
--
-- document_line and document_acknowledgement are append-only (trigger plus revokes, NIST SP 800-53 AU-9), so their new
-- columns are filled by a rebuild of each table, never by an UPDATE: every stored row is copied once, taking entity_ref
-- and direction from the one document_version row of its (tenant_id, doc_uuid, version), which is unique before this
-- migration; then the old table is dropped. The migration fails closed when a stored acknowledgement has no such
-- version row: no value is invented, no default is written and no audit row is deleted (INV-T-26647, open point B).
-- The trigger, policy, force flag, revokes and grants of each rebuilt table equal V202611190900's.
do $$
begin
    if exists (
        select 1 from reconciliation.document_acknowledgement a
        where not exists (
            select 1 from reconciliation.document_version v
            where v.tenant_id = a.tenant_id and v.doc_uuid = a.doc_uuid and v.version = a.version)
    ) then
        raise exception 'a stored acknowledgement has no document_version row: entity_ref and direction cannot be set'
            using errcode = '23503';
    end if;
end
$$;
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

alter table reconciliation.document_acknowledgement no force row level security;
alter table reconciliation.document_acknowledgement rename to document_acknowledgement_old;
alter table reconciliation.document_acknowledgement_old
    rename constraint document_acknowledgement_pkey to document_acknowledgement_old_pkey;
-- no foreign key to document_version: the acknowledgement topic and the version topic are not ordered against each other
create table reconciliation.document_acknowledgement (
    tenant_id uuid not null,
    doc_uuid uuid not null,
    entity_ref text not null check (btrim(entity_ref) <> ''),
    direction text not null check (direction in ('SALE', 'PURCHASE')),
    version int not null,
    acknowledgement_id text not null check (btrim(acknowledgement_id) <> ''),
    acknowledged_at timestamptz not null,
    primary key (tenant_id, doc_uuid, entity_ref, direction, version)
);
insert into reconciliation.document_acknowledgement
    (tenant_id, doc_uuid, entity_ref, direction, version, acknowledgement_id, acknowledged_at)
select a.tenant_id, a.doc_uuid, v.entity_ref, v.direction, a.version, a.acknowledgement_id, a.acknowledged_at
from reconciliation.document_acknowledgement_old a
join reconciliation.document_version v
    on v.tenant_id = a.tenant_id and v.doc_uuid = a.doc_uuid and v.version = a.version;
drop table reconciliation.document_acknowledgement_old;

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

comment on column reconciliation.document_acknowledgement.entity_ref is 'class: operational';
comment on column reconciliation.document_acknowledgement.direction is 'class: operational';
comment on column reconciliation.document_acknowledgement.acknowledgement_id is 'class: operational';
ALTER TABLE reconciliation.document_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation.document_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY document_acknowledgement_tenant_isolation ON reconciliation.document_acknowledgement
    USING      (tenant_id = (select reconciliation.bound_tenant()))
    WITH CHECK (tenant_id = (select reconciliation.bound_tenant()));
create trigger document_acknowledgement_append_only before update or delete on reconciliation.document_acknowledgement
    for each row execute function reconciliation.run_read_model_append_only();
revoke update, delete, truncate on reconciliation.document_acknowledgement from reconciliation_runtime;
grant select, insert on reconciliation.document_acknowledgement to reconciliation_runtime;
