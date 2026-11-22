-- V27__submission_index_entity.sql (INV-T-27669; INV-REQ-710 behavior 2 and 3, slice S2 of INV-AC-27668).
--
-- Index for the entity-scoped reverse lookup: every read is tenant-bound, so tenant_id leads, then the entity
-- reference the scoped read filters with IN (...), then the document number. No table, policy or grant changes.

create index submission_index_entity_document_number
    on evidence.submission_index (tenant_id, entity_ref, document_number);
