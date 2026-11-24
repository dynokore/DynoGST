SET lock_timeout = '5s';

-- INV-T-28448, INV-REQ-1110: the SUB-017 hold of one document version, insert-only. A hold is open exactly when it has
-- no row in prior_solution_hold_resolution, so closing is an insert and no history is rewritten. The runtime role may
-- select and insert; it can never update or delete.
create table submission.prior_solution_hold (
    tenant_id uuid not null,
    doc_uuid text not null,
    version integer not null check (version >= 1),
    entity_ref text not null,
    reason text not null check (reason in (
        'RESUBMISSION_OF_EXTERNALLY_SUBMITTED', 'CORRECTION_OF_PRE_CUTOVER_DOCUMENT', 'NO_PREDECESSOR_ESTABLISHED')),
    code text not null check (code = 'SUB-017'),
    raised_at timestamptz not null,
    primary key (tenant_id, doc_uuid, version)
);
comment on column submission.prior_solution_hold.tenant_id is 'class: operational';
comment on column submission.prior_solution_hold.doc_uuid is 'class: operational';
comment on column submission.prior_solution_hold.version is 'class: operational';
comment on column submission.prior_solution_hold.entity_ref is 'class: operational';
comment on column submission.prior_solution_hold.reason is 'class: operational';
comment on column submission.prior_solution_hold.code is 'class: operational';
comment on column submission.prior_solution_hold.raised_at is 'class: operational';

alter table submission.prior_solution_hold enable row level security;
alter table submission.prior_solution_hold force row level security;
create policy prior_solution_hold_tenant_isolation on submission.prior_solution_hold
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.prior_solution_hold to submission_runtime;

-- The resolution rows, written by the resolve operation of INV-T-28441; created here, empty, so that the read of the
-- open hold is one left join. Insert-only as well; one resolution per hold.
create table submission.prior_solution_hold_resolution (
    tenant_id uuid not null,
    doc_uuid text not null,
    version integer not null,
    reason text not null,
    evidence_ref text not null,
    resolved_by text not null,
    resolved_at timestamptz not null,
    primary key (tenant_id, doc_uuid, version),
    foreign key (tenant_id, doc_uuid, version) references submission.prior_solution_hold (tenant_id, doc_uuid, version)
);
comment on column submission.prior_solution_hold_resolution.tenant_id is 'class: operational';
comment on column submission.prior_solution_hold_resolution.doc_uuid is 'class: operational';
comment on column submission.prior_solution_hold_resolution.version is 'class: operational';
comment on column submission.prior_solution_hold_resolution.reason is 'class: operational';
comment on column submission.prior_solution_hold_resolution.evidence_ref is 'class: operational';
comment on column submission.prior_solution_hold_resolution.resolved_by is 'class: operational';
comment on column submission.prior_solution_hold_resolution.resolved_at is 'class: operational';

alter table submission.prior_solution_hold_resolution enable row level security;
alter table submission.prior_solution_hold_resolution force row level security;
create policy prior_solution_hold_resolution_tenant_isolation on submission.prior_solution_hold_resolution
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.prior_solution_hold_resolution to submission_runtime;
