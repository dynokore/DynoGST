SET lock_timeout = '5s';

-- V202611261000__document_version_source_event.sql (INV-T-28871; INV-REQ-1191, INV-AC-28870).
-- Every document_version row stores the key of the source_event row of the event that produced it, written in the same
-- transaction (provenance recorded at write time, NIST SP 800-53 AU-3 and AU-10).
-- No default and no backfill: before the first release-train cut there is no data to keep, so this migration fails on
-- a table that holds rows and there is no legacy branch.
-- The foreign key is deferred because the record path inserts the source_event row after the versions it produces.
alter table canonical.document_version
    add column source_system text not null,
    add column source_key text not null,
    add column source_revision bigint not null;
alter table canonical.document_version
    add constraint document_version_source_event_fk
    foreign key (tenant_id, source_system, source_key, source_revision)
    references canonical.source_event (tenant_id, source_system, source_key, source_revision)
    deferrable initially deferred;
comment on column canonical.document_version.source_system is 'class: operational';
comment on column canonical.document_version.source_key is 'class: operational';
comment on column canonical.document_version.source_revision is 'class: operational';
