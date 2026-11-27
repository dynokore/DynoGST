-- The release sequence of a released validation override within its document version (INV-T-29373, INV-AC-29372).
-- Nullable: a row released before this migration keeps null; no existing row is updated.
alter table payload.validation_override add column release_sequence integer;
alter table payload.validation_override
    add constraint validation_override_release_sequence_check
    check (release_sequence is null or (release_sequence >= 1 and status = 'RELEASED'));
create unique index validation_override_release_sequence_key
    on payload.validation_override (tenant_id, doc_uuid, version, release_sequence)
    where release_sequence is not null;
