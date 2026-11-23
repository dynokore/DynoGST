-- INV-T-27570 (INV-REQ-1010 behaviour 4; CS-19, ARC-20, TPL-12). The operation ids (Idempotency-Key) of the
-- bulkResolveCounterparty calls that wrote, keyed by tenant and acting user id. A repeat of the key by the same user
-- finds the row and writes nothing. The row is inserted in the transaction of the write, so a failed write leaves no key.
-- Run only by the migration Job as masterdata_migrate.

CREATE TABLE masterdata.bulk_resolve_operation (
    tenant_id    uuid        NOT NULL,
    principal    text        NOT NULL CHECK (length(principal) BETWEEN 1 AND 128),
    operation_id uuid        NOT NULL,
    recorded_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, principal, operation_id)
);

ALTER TABLE masterdata.bulk_resolve_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.bulk_resolve_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.bulk_resolve_operation
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.bulk_resolve_operation TO masterdata_runtime;
