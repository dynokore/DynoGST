-- V202611231000__create_identity_triple_request_marker.sql (INV-T-26780; INV-REQ-1006, INV-Q-26498 option A).
-- One row per (tenant, CloudEvents id) whose IdentityTripleReprocessingRequested was answered: a redelivery adds
-- no row and no version.
CREATE TABLE canonical.identity_triple_request_marker (
    tenant_id uuid NOT NULL,
    event_id text NOT NULL CHECK (length(event_id) BETWEEN 1 AND 128),
    answered_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
ALTER TABLE canonical.identity_triple_request_marker ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.identity_triple_request_marker FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canonical.identity_triple_request_marker
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
GRANT SELECT, INSERT ON canonical.identity_triple_request_marker TO canonical_runtime;
