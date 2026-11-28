SET lock_timeout = '5s';

-- INV-T-29143, INV-REQ-1205 behavior 4, INV-AC-29140: the tenants holding a stored readiness signal, for the replay of
-- ReadinessChanged across tenants. Modelled on measured_tenants: the function returns tenant ids and nothing else.
-- submission_sys gains the one column it needs of readiness_signal (tenant_id) and a select policy; no existing
-- grant or policy changes.
grant select (tenant_id) on submission.readiness_signal to submission_sys;
create policy readiness_signal_sys_select on submission.readiness_signal
    for select to submission_sys using (true);
create function submission.readiness_replay_tenants()
returns table (replay_tenant uuid)
language sql stable security definer set search_path = pg_catalog, submission, pg_temp as $$
    select distinct s.tenant_id from submission.readiness_signal s
$$;
grant create on schema submission to submission_sys;
alter function submission.readiness_replay_tenants() owner to submission_sys;
revoke create on schema submission from submission_sys;
revoke all on function submission.readiness_replay_tenants() from public;
grant execute on function submission.readiness_replay_tenants() to submission_runtime;
