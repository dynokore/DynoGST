SET lock_timeout = '5s';

-- INV-T-26838, INV-REQ-1006, INV-AC-26836: the identity triple corrections received from masterdata-svc (event-carried
-- state transfer from IdentityTripleCorrected). One row per (tenant, entity, constitution, change reference); the first
-- stored row stands.
create table submission.identity_triple_correction (
    tenant_id uuid not null,
    entity_ref text not null check (length(entity_ref) between 1 and 40),
    constitution text not null check (length(constitution) between 1 and 40),
    change_ref text not null check (length(change_ref) between 1 and 40),
    effective_from date not null,
    access_point_escalation boolean not null check (access_point_escalation),
    received_at timestamptz not null default now(),
    primary key (tenant_id, entity_ref, constitution, change_ref)
);

alter table submission.identity_triple_correction enable row level security;
alter table submission.identity_triple_correction force row level security;
create policy identity_triple_correction_tenant_isolation on submission.identity_triple_correction
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.identity_triple_correction to submission_runtime;

comment on column submission.identity_triple_correction.tenant_id is 'class: operational';
comment on column submission.identity_triple_correction.entity_ref is 'class: operational';
comment on column submission.identity_triple_correction.constitution is 'class: operational';
comment on column submission.identity_triple_correction.change_ref is 'class: operational';
comment on column submission.identity_triple_correction.effective_from is 'class: operational';
comment on column submission.identity_triple_correction.access_point_escalation is 'class: operational';
comment on column submission.identity_triple_correction.received_at is 'class: operational';
