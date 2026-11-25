-- V9__agreed_rate_optional.sql (INV-T-24959; INV-REQ-1317, INV-AC-24957).
-- A connection may have no agreed rate until the set-up flow of INV-REQ-1309 records it. setThroughputCeiling then
-- refuses with POL-010 (bound unavailable), fail closed. V1 is not edited (plain forward migration, pre-go-live).
alter table transport.ap_connection alter column agreed_rate drop not null;
-- tenant_ceiling <= agreed_rate is unknown for a null rate and would let a ceiling be stored without a rate, so the
-- fail-closed rule is also held at the data layer (OWASP ASVS V5; NIST SP 800-53 SI-10).
alter table transport.ap_connection
    add constraint ap_connection_ceiling_needs_rate_check check (tenant_ceiling is null or agreed_rate is not null);
