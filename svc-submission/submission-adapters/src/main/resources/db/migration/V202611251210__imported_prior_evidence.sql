SET lock_timeout = '5s';

-- INV-T-28454, INV-REQ-1109, INV-AC-28453: the prior submission evidence imported per tenant (event-carried state
-- transfer from PriorSubmissionEvidenceImported). Insert-only; the key is the exact match key of INV-REQ-1109 step 3.
create table submission.imported_prior_evidence (
    tenant_id uuid not null,
    entity_ref text not null check (length(entity_ref) between 1 and 200),
    document_number text not null check (length(document_number) between 1 and 100),
    transaction_date date not null,
    acknowledgement_ref text not null check (length(acknowledgement_ref) between 1 and 100),
    prior_solution text not null check (length(prior_solution) between 1 and 128),
    imported_at timestamptz not null default now(),
    primary key (tenant_id, entity_ref, document_number, transaction_date)
);

alter table submission.imported_prior_evidence enable row level security;
alter table submission.imported_prior_evidence force row level security;
create policy imported_prior_evidence_tenant_isolation on submission.imported_prior_evidence
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.imported_prior_evidence to submission_runtime;
comment on column submission.imported_prior_evidence.tenant_id is 'class: operational';
comment on column submission.imported_prior_evidence.entity_ref is 'class: operational';
comment on column submission.imported_prior_evidence.document_number is 'class: operational';
comment on column submission.imported_prior_evidence.transaction_date is 'class: operational';
comment on column submission.imported_prior_evidence.acknowledgement_ref is 'class: operational';
comment on column submission.imported_prior_evidence.prior_solution is 'class: operational';
comment on column submission.imported_prior_evidence.imported_at is 'class: operational';
