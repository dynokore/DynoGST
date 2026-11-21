SET lock_timeout = '5s';

-- INV-T-26618, INV-REQ-570: the last signalled readiness snapshot of each entity and period of a tenant. RunReadiness
-- compares a new snapshot with it in one compare-and-set statement and stages ReadinessChanged only when it differs
-- (transactional outbox). The runtime role may insert, update and select; it can never delete.
create table submission.readiness_signal (
    tenant_id uuid not null,
    entity_ref text not null,
    period_ref text not null,
    verdict text not null check (verdict in ('READY', 'NOT_READY')),
    blocked_count integer not null check (blocked_count >= 0),
    counts_by_state jsonb not null,
    signalled_at timestamptz not null,
    primary key (tenant_id, entity_ref, period_ref)
);
comment on column submission.readiness_signal.tenant_id is 'class: operational';
comment on column submission.readiness_signal.entity_ref is 'class: operational';
comment on column submission.readiness_signal.period_ref is 'class: operational';
comment on column submission.readiness_signal.verdict is 'class: operational';
comment on column submission.readiness_signal.blocked_count is 'class: operational';
comment on column submission.readiness_signal.counts_by_state is 'class: operational';
comment on column submission.readiness_signal.signalled_at is 'class: operational';

alter table submission.readiness_signal enable row level security;
alter table submission.readiness_signal force row level security;
create policy readiness_signal_tenant_isolation on submission.readiness_signal
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.readiness_signal to submission_runtime;
