-- V33__breach_terms_pack_replica.sql (INV-T-29320; INV-REQ-1385, INV-AC-29315)
-- The replica of the breach terms packs from RulePackStaged and RulePackActivated (event-carried state transfer, the
-- shape of the pack replica of payload-svc, INV-T-23579). The packs are operator-maintained: every row is a row of the
-- platform tenant (a check). The three tables are of tenancy class REFERENCE (INV-ADR-3968 item 4a): a signed binding
-- of any tenant reads the platform rows, and every write is one of the three definer functions below, which answer the
-- platform binding only. The runtime login holds select only (least privilege, NIST SP 800-53 AC-6). Static SQL only.

-- RulePackActivated carries no pack type: the type of every pack identifier is recorded once and never changed.
create table evidence.breach_terms_pack_type (
    tenant_id uuid not null check (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid),
    pack_identifier text not null check (pack_identifier <> ''),
    pack_type text not null check (pack_type <> ''),
    unique (tenant_id, pack_identifier)
);

-- staged_at and staged_event_id are the envelope time and id of the staged event that first stored the row: the
-- registration order of the versions at the producer, which decides between two activations of one effective date.
create table evidence.breach_terms_pack_version (
    tenant_id uuid not null check (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid),
    pack_identifier text not null check (pack_identifier <> ''),
    pack_version text not null check (pack_version <> ''),
    assessment_within_hours bigint not null check (assessment_within_hours >= 1),
    notification_within_hours bigint not null check (notification_within_hours >= 1),
    source_reference text not null check (source_reference <> ''),
    staged_at timestamptz not null,
    staged_event_id text collate "C" not null check (staged_event_id <> ''),
    unique (tenant_id, pack_identifier, pack_version)
);

-- applied_seq only identifies a row: the order in which activations were applied decides nothing. The unique key keeps
-- one row per activation also when two deliveries of it run at once (a database constraint, not check-then-insert).
create table evidence.breach_terms_pack_activation (
    applied_seq bigint generated always as identity primary key,
    tenant_id uuid not null check (tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid),
    pack_identifier text not null,
    pack_version text not null,
    effective_from date not null,
    unique (tenant_id, pack_identifier, pack_version, effective_from),
    foreign key (tenant_id, pack_identifier, pack_version)
        references evidence.breach_terms_pack_version (tenant_id, pack_identifier, pack_version)
);
create index breach_terms_pack_activation_in_force
    on evidence.breach_terms_pack_activation (tenant_id, effective_from desc);

ALTER TABLE evidence.breach_terms_pack_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_type
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_type FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_type FROM evidence_runtime;
grant select on evidence.breach_terms_pack_type to evidence_runtime;

ALTER TABLE evidence.breach_terms_pack_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_version
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_version FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_version FROM evidence_runtime;
grant select on evidence.breach_terms_pack_version to evidence_runtime;

ALTER TABLE evidence.breach_terms_pack_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.breach_terms_pack_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.breach_terms_pack_activation
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
CREATE POLICY platform_rows_read ON evidence.breach_terms_pack_activation FOR SELECT
    USING ((select evidence.bound_tenant()) IS NOT NULL AND tenant_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid);
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON evidence.breach_terms_pack_activation FROM evidence_runtime;
grant select on evidence.breach_terms_pack_activation to evidence_runtime;

create function evidence.record_breach_terms_pack_type(
    p_pack_identifier text, p_pack_type text)
    returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if (select evidence.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the breach terms replica is written under the platform binding only' using errcode = '42501';
    end if;
    if p_pack_identifier is null or p_pack_type is null then
        raise exception 'every argument is required' using errcode = '22004';
    end if;
    insert into evidence.breach_terms_pack_type (tenant_id, pack_identifier, pack_type)
    values ('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_identifier, p_pack_type)
    on conflict (tenant_id, pack_identifier) do nothing;
end
$$;

create function evidence.store_breach_terms_pack_version(
    p_pack_identifier text, p_pack_version text, p_assessment_within_hours bigint, p_notification_within_hours bigint,
    p_source_reference text, p_staged_at timestamptz, p_staged_event_id text)
    returns boolean
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_inserted bigint;
begin
    if (select evidence.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the breach terms replica is written under the platform binding only' using errcode = '42501';
    end if;
    if p_pack_identifier is null or p_pack_version is null or p_assessment_within_hours is null
       or p_notification_within_hours is null or p_source_reference is null or p_staged_at is null
       or p_staged_event_id is null then
        raise exception 'every argument is required' using errcode = '22004';
    end if;
    insert into evidence.breach_terms_pack_version (tenant_id, pack_identifier, pack_version, assessment_within_hours,
        notification_within_hours, source_reference, staged_at, staged_event_id)
    values ('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_identifier, p_pack_version, p_assessment_within_hours,
        p_notification_within_hours, p_source_reference, p_staged_at, p_staged_event_id)
    on conflict (tenant_id, pack_identifier, pack_version) do nothing;
    get diagnostics v_inserted = row_count;
    return v_inserted = 1;
end
$$;

create function evidence.record_breach_terms_activation(
    p_pack_identifier text, p_pack_version text, p_effective_from date)
    returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    if (select evidence.bound_tenant()) is distinct from 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid then
        raise exception 'the breach terms replica is written under the platform binding only' using errcode = '42501';
    end if;
    if p_pack_identifier is null or p_pack_version is null or p_effective_from is null then
        raise exception 'every argument is required' using errcode = '22004';
    end if;
    insert into evidence.breach_terms_pack_activation (tenant_id, pack_identifier, pack_version, effective_from)
    values ('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, p_pack_identifier, p_pack_version, p_effective_from)
    on conflict do nothing; -- no conflict target: naming the key would need select on the table
end
$$;

-- The functions are owned by evidence_sys (INV-ADR-3968 item 6a); its rights are those their statements need (ON
-- CONFLICT reads the key), under the tenant policy of each table.
grant select, insert on evidence.breach_terms_pack_type, evidence.breach_terms_pack_version to evidence_sys;
grant insert on evidence.breach_terms_pack_activation to evidence_sys;
grant create on schema evidence to evidence_sys;
alter function evidence.record_breach_terms_pack_type(text, text) owner to evidence_sys;
alter function evidence.store_breach_terms_pack_version(text, text, bigint, bigint, text, timestamptz, text) owner to evidence_sys;
alter function evidence.record_breach_terms_activation(text, text, date) owner to evidence_sys;
revoke create on schema evidence from evidence_sys;
revoke all on function evidence.record_breach_terms_pack_type(text, text) from public;
grant execute on function evidence.record_breach_terms_pack_type(text, text) to evidence_runtime;
revoke all on function evidence.store_breach_terms_pack_version(text, text, bigint, bigint, text, timestamptz, text) from public;
grant execute on function evidence.store_breach_terms_pack_version(text, text, bigint, bigint, text, timestamptz, text) to evidence_runtime;
revoke all on function evidence.record_breach_terms_activation(text, text, date) from public;
grant execute on function evidence.record_breach_terms_activation(text, text, date) to evidence_runtime;
