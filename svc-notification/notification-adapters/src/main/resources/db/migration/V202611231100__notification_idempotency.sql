-- V202611231100__notification_idempotency.sql (INV-T-28107; INV-AC-28106)
-- Generated: IdempotencySchema.ddl("notification", "notification_runtime", "notification_sys") of service-runtime of
-- the BOM train pinned in gradle/libs.versions.toml, the Idempotency-Key store of the notification servlets.
-- IdempotencyMigrationTest fails when this file differs from what the pinned module generates.
create table notification.idempotency_key (
    tenant_id uuid not null,
    principal text not null,
    idempotency_key uuid not null,
    fingerprint text not null,
    status integer not null check (status between 100 and 599),
    body text not null,
    created_at timestamptz not null,
    primary key (tenant_id, principal, idempotency_key)
);
grant usage on schema notification to notification_runtime;
grant select, insert on notification.idempotency_key to notification_runtime;
grant usage on schema notification to notification_sys;
ALTER TABLE notification.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON notification.idempotency_key
    USING      (tenant_id = (select notification.bound_tenant()))
    WITH CHECK (tenant_id = (select notification.bound_tenant()));
create policy idempotency_key_sys_select on notification.idempotency_key for select to notification_sys using (true);
create policy idempotency_key_sys_purge on notification.idempotency_key for delete to notification_sys using (true);
grant select (tenant_id, principal, idempotency_key, created_at) on notification.idempotency_key to notification_sys;
grant delete on notification.idempotency_key to notification_sys;
create function notification.idempotency_key_purge_expired(cutoff timestamptz, batch_size integer) returns bigint
language plpgsql security definer set search_path = pg_catalog, notification, pg_temp as $$
declare
    deleted bigint;
begin
    if cutoff is null or cutoff > now() - interval '24 hours' then
        raise exception 'cut-off % is later than the retention floor', cutoff using errcode = '22023';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'batch size % is outside 1..10000', batch_size using errcode = '22023';
    end if;
    delete from notification.idempotency_key where (tenant_id, principal, idempotency_key) in
        (select tenant_id, principal, idempotency_key from notification.idempotency_key
         where created_at < cutoff limit batch_size);
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema notification to notification_sys;
alter function notification.idempotency_key_purge_expired(timestamptz, integer) owner to notification_sys;
revoke all on function notification.idempotency_key_purge_expired(timestamptz, integer) from public;
grant execute on function notification.idempotency_key_purge_expired(timestamptz, integer) to notification_runtime;
revoke create on schema notification from notification_sys;