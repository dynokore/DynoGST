-- V32__retention_notice_tenants.sql (INV-T-26999; INV-REQ-639, INV-AC-26996)
-- The entity source of the retention notice job: the tenants that have a retention custody holder row. The runtime
-- login reads the holder table under its tenant policy only, so the list comes from a SECURITY DEFINER function owned
-- by the NOLOGIN evidence_sys (the shape of evidence.verification_entities, V7). The owner reads the tenant column and
-- no other column, so it cannot read the holder name, email or mobile; the runtime login gets EXECUTE only and the
-- search_path is fixed in the function (NIST SP 800-53 AC-6; OWASP ASVS V4). Static SQL only.

create policy offboarding_custody_holder_sys_select on evidence.offboarding_custody_holder
    for select to evidence_sys using (true);
grant select (tenant_id) on evidence.offboarding_custody_holder to evidence_sys;

create function evidence.retention_notice_tenants() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, evidence, pg_temp as $$
begin
    return coalesce((
        select jsonb_agg(jsonb_build_object('tenant_id', t.tenant_id) order by t.tenant_id)
        from (select distinct h.tenant_id from evidence.offboarding_custody_holder h) t
    ), '[]'::jsonb);
end
$$;
grant create on schema evidence to evidence_sys;
alter function evidence.retention_notice_tenants() owner to evidence_sys;
revoke all on function evidence.retention_notice_tenants() from public;
grant execute on function evidence.retention_notice_tenants() to evidence_runtime;
revoke create on schema evidence from evidence_sys;
