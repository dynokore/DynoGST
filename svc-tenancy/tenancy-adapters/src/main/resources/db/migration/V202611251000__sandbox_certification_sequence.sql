-- V202611251000: the sequence of a sandbox certification run (INV-REQ-1008; INV-T-29135, INV-AC-29132). With
-- recorded_at it gives the history of an entity a total order. The database assigns it, to stored rows and new ones,
-- from 1 upward; no role supplies it. Grants and the row-level security policy are unchanged. Run by tenancy_migrate.
SET lock_timeout = '5s';

ALTER TABLE tenancy.sandbox_certification
    ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;

ALTER TABLE tenancy.sandbox_certification
    ADD CONSTRAINT sandbox_certification_seq_unique UNIQUE (tenant_id, entity_id, seq);
