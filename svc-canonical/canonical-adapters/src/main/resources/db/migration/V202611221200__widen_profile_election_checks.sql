-- V202611221200__widen_profile_election_checks.sql (INV-T-26609; INV-REQ-1284, INV-REQ-1285, INV-REQ-987).
-- The profile election table also keeps the apportionmentMode and exchangeRateSource entries. The two unnamed column
-- checks of V202611171200 are looked up by their columns in the catalog; the DO block raises unless each lookup finds
-- exactly one check and its generated name is the one dropped below (fail closed). The drops are static statements
-- because the migration lint (dynamic-sql, INV-Q-4464 A) admits no EXECUTE in a DO block.
SET lock_timeout = '5s';

DO $$
DECLARE
    attribute_check name;
    value_check name;
BEGIN
    SELECT c.conname INTO STRICT attribute_check
      FROM pg_constraint c
     WHERE c.contype = 'c'
       AND c.conrelid = 'canonical.profile_election'::regclass
       AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                              WHERE a.attrelid = c.conrelid AND a.attname = 'attribute')];
    SELECT c.conname INTO STRICT value_check
      FROM pg_constraint c
     WHERE c.contype = 'c'
       AND c.conrelid = 'canonical.profile_election'::regclass
       AND (SELECT a.attnum FROM pg_attribute a
             WHERE a.attrelid = c.conrelid AND a.attname = 'value') = ANY (c.conkey);
    IF attribute_check <> 'profile_election_attribute_check' OR value_check <> 'profile_election_check' THEN
        RAISE EXCEPTION 'unexpected check names on canonical.profile_election: %, %', attribute_check, value_check;
    END IF;
END
$$;

ALTER TABLE canonical.profile_election DROP CONSTRAINT profile_election_attribute_check;
ALTER TABLE canonical.profile_election DROP CONSTRAINT profile_election_check;

ALTER TABLE canonical.profile_election ADD CONSTRAINT profile_election_attribute_chk
    CHECK (attribute = 'purchaseDateBasis' OR attribute = 'apportionmentMode' OR attribute = 'exchangeRateSource'
           OR attribute ~ '^gstScheme\.[A-Z0-9_-]{1,40}$');
ALTER TABLE canonical.profile_election ADD CONSTRAINT profile_election_value_chk
    CHECK (length(value) BETWEEN 1 AND 40
           AND (attribute <> 'purchaseDateBasis' OR value IN ('INVOICE_DATE', 'POSTING_DATE'))
           AND (attribute <> 'apportionmentMode' OR value IN ('CLAIMABLE_PORTION', 'ENTIRE_INVOICE')));
