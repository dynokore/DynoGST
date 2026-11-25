-- V31__retention_unit_registered_members.sql (INV-T-28695; INV-REQ-639, INV-AC-28694).
--
-- Retention-end deletion acts on the registered members of a unit, so a unit that holds only imported records (or only
-- some chain tables) is deletable, and it is serialized against concurrent writers by row locks on the unit row:
-- a writer's create_retention_partition takes FOR SHARE, the deletion's lock_retention_partition takes FOR UPDATE, so
-- no record is dropped without being archived (NIST SP 800-53 AU-9, AU-11, SI-12). The registry is complete by
-- construction, so the archive mark needs at least one registered member and every registered member DETACHED.
--
-- One lock order for the importer and the deletion (PostgreSQL manual, Explicit Locking, Deadlocks): the unit row is
-- locked before any statement on a partitioned table. The deletion starts with hold_retention_partition. The importer
-- locks its units with lock_import_unit in ascending unit-key order, creating a missing row, and only after the last
-- one creates a missing member (which locks a parent table ACCESS EXCLUSIVE until commit).

create or replace function evidence.create_retention_partition(
    p_parent regclass, p_tenant_id uuid, p_entity_ref text, p_period_from text, p_period_to text)
    returns text
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_partition text;
    v_chain text;
    v_id text;
    v_state text;
    v_member_state text;
begin
    if p_parent = 'evidence.decision_trace'::regclass then
        v_chain := 'decision_trace';
    elsif p_parent = 'evidence.evidence_payload'::regclass then
        v_chain := 'evidence_payload';
    elsif p_parent = 'evidence.acknowledgement'::regclass then
        v_chain := 'acknowledgement';
    elsif p_parent = 'evidence.audit_log'::regclass then
        v_chain := 'audit_log';
    elsif p_parent = 'evidence.prior_evidence'::regclass then
        v_chain := 'prior_evidence';
    else
        raise exception 'not a chain table' using errcode = '22023';
    end if;
    if p_tenant_id is null or p_entity_ref is null or p_period_from is null
       or p_period_to is distinct from p_period_from || chr(1)
       or position(chr(1) in p_entity_ref || p_period_from) > 0 then
        raise exception 'tenant, entity and period must be set; the range is one period' using errcode = '22023';
    end if;
    if (select evidence.bound_tenant()) is distinct from p_tenant_id and session_user::text <> 'evidence_migrate' then
        raise exception 'a partition is created only in its own tenant transaction' using errcode = '42501';
    end if;
    perform pg_advisory_xact_lock(hashtext(p_tenant_id::text || ':' || p_entity_ref), hashtext(v_chain || ':' || p_period_from));
    select r.partition_id, r.state into v_id, v_state from evidence.retention_partition r
        where r.tenant_id = p_tenant_id and r.entity_ref = p_entity_ref
          and r.period_from = p_period_from and r.period_to = p_period_to
        for share;
    if found then
        select m.state into v_member_state from evidence.retention_partition_member m
            where m.partition_id = v_id and m.chain_table = v_chain;
        if found then
            if v_member_state <> 'ATTACHED' then
                raise exception 'the partition of this period is no longer attached' using errcode = '55000';
            end if;
            return v_id;
        end if;
        if v_state <> 'ATTACHED' then
            raise exception 'the partition of this period is no longer attached' using errcode = '55000';
        end if;
    else
        v_id := 'rp_' || replace(gen_random_uuid()::text, '-', '');
        insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, state)
        values (v_id, p_tenant_id, p_entity_ref, p_period_from, p_period_to, 'ATTACHED')
        on conflict (tenant_id, entity_ref, period_from, period_to) do nothing;
        select r.partition_id, r.state into v_id, v_state from evidence.retention_partition r
            where r.tenant_id = p_tenant_id and r.entity_ref = p_entity_ref
              and r.period_from = p_period_from and r.period_to = p_period_to
            for share;
        if v_state is distinct from 'ATTACHED' then
            raise exception 'the partition of this period is no longer attached' using errcode = '55000';
        end if;
    end if;
    v_partition := format('%I.%I', 'evidence', v_chain || '_' || v_id);
    execute format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L, %L, %L) TO (%L, %L, %L)', v_partition, p_parent, p_tenant_id, p_entity_ref, p_period_from, p_tenant_id, p_entity_ref, p_period_to);
    insert into evidence.retention_partition_member (partition_id, tenant_id, chain_table, partition_table, state)
    values (v_id, p_tenant_id, v_chain, v_chain || '_' || v_id, 'ATTACHED');
    return v_id;
end
$$;

-- The deletion's lock of one attached unit of the bound tenant, held until its transaction ends.
create function evidence.lock_retention_partition(p_partition_id text) returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    perform 1 from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant and r.state = 'ATTACHED' for update;
    if not found then
        raise exception 'the partition is not attached' using errcode = '55000';
    end if;
end
$$;

-- The deletion's first call: the unit row of the bound tenant in any state, locked before anything is read. A row
-- that does not exist or is another tenant's is neither locked nor refused: the deletion's own read answers for it.
create function evidence.hold_retention_partition(p_partition_id text) returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    perform 1 from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant for update;
end
$$;

-- The importer's lock of one unit, created when missing, held until its transaction ends. It takes no lock on a
-- partitioned table. Owned by the migration login, as create_retention_partition is, which inserts the same row.
create function evidence.lock_import_unit(
    p_tenant_id uuid, p_entity_ref text, p_period_from text, p_period_to text)
    returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_state text;
begin
    if p_tenant_id is null or p_entity_ref is null or p_period_from is null
       or p_period_to is distinct from p_period_from || chr(1)
       or position(chr(1) in p_entity_ref || p_period_from) > 0 then
        raise exception 'tenant, entity and period must be set; the range is one period' using errcode = '22023';
    end if;
    if (select evidence.bound_tenant()) is distinct from p_tenant_id then
        raise exception 'a unit is locked only in its own tenant transaction' using errcode = '42501';
    end if;
    insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, state)
    values ('rp_' || replace(gen_random_uuid()::text, '-', ''), p_tenant_id, p_entity_ref, p_period_from, p_period_to,
            'ATTACHED')
    on conflict (tenant_id, entity_ref, period_from, period_to) do nothing;
    select r.state into v_state from evidence.retention_partition r
        where r.tenant_id = p_tenant_id and r.entity_ref = p_entity_ref
          and r.period_from = p_period_from and r.period_to = p_period_to
        for update;
    if v_state is distinct from 'ATTACHED' then
        raise exception 'the partition of this period is no longer attached' using errcode = '55000';
    end if;
end
$$;

create or replace function evidence.mark_partition_archived(p_partition_id text, p_archive_ref text) returns void
language plpgsql security definer set search_path = pg_catalog, evidence, pg_temp as $$
declare
    v_tenant uuid := (select evidence.bound_tenant());
begin
    if v_tenant is null then
        raise exception 'no tenant is bound' using errcode = '42501';
    end if;
    if p_archive_ref is null or btrim(p_archive_ref) = '' then
        raise exception 'the archive reference is required' using errcode = '23514';
    end if;
    perform 1 from evidence.retention_partition r
        where r.partition_id = p_partition_id and r.tenant_id = v_tenant and r.state = 'DETACHED';
    if not found or exists (select 1 from evidence.retention_partition_member m
                            where m.partition_id = p_partition_id and m.state <> 'DETACHED')
       or not exists (select 1 from evidence.retention_partition_member m where m.partition_id = p_partition_id) then
        raise exception 'the partition is not detached' using errcode = '55000';
    end if;
    update evidence.retention_partition_member m set state = 'ARCHIVED'
     where m.partition_id = p_partition_id and m.tenant_id = v_tenant;
    update evidence.retention_partition r
       set state = 'ARCHIVED', archive_ref = p_archive_ref, archived_at = clock_timestamp()
     where r.partition_id = p_partition_id and r.tenant_id = v_tenant;
end
$$;
-- The replaced functions keep their grants; the owner statements are repeated for the migration lint (V21 style).
grant create on schema evidence to evidence_sys;
alter function evidence.mark_partition_archived(text, text) owner to evidence_sys;
alter function evidence.lock_retention_partition(text) owner to evidence_sys;
alter function evidence.hold_retention_partition(text) owner to evidence_sys;
revoke create on schema evidence from evidence_sys;
revoke all on function evidence.lock_retention_partition(text) from public;
grant execute on function evidence.lock_retention_partition(text) to evidence_runtime;
revoke all on function evidence.hold_retention_partition(text) from public;
grant execute on function evidence.hold_retention_partition(text) to evidence_runtime;
revoke all on function evidence.lock_import_unit(uuid, text, text, text) from public;
grant execute on function evidence.lock_import_unit(uuid, text, text, text) to evidence_runtime;
