SET lock_timeout = '5s';

-- INV-T-26745, INV-REQ-980, INV-AC-26739: the append-only history of mapping pack activations per entity, one row per
-- MappingPackActivated event, (tenant, event_id); the runtime role may only select and insert (NIST SP 800-53 AU-9).
-- Additive: this migration creates one table and alters nothing.
create table submission.pack_activation (
    tenant_id uuid not null,
    event_id text not null,
    entity_ref text not null check (btrim(entity_ref) <> ''),
    pack_id text not null,
    pack_label text not null,
    effective_from date not null,
    effective_to date,
    activated_by text not null,
    activated_at timestamptz not null,
    unique (tenant_id, event_id)
);

comment on column submission.pack_activation.tenant_id is 'class: operational';
comment on column submission.pack_activation.event_id is 'class: operational';
comment on column submission.pack_activation.entity_ref is 'class: operational';
comment on column submission.pack_activation.pack_id is 'class: operational';
comment on column submission.pack_activation.pack_label is 'class: operational';
comment on column submission.pack_activation.effective_from is 'class: operational';
comment on column submission.pack_activation.effective_to is 'class: operational';
comment on column submission.pack_activation.activated_by is 'class: personal';
comment on column submission.pack_activation.activated_at is 'class: operational';

alter table submission.pack_activation enable row level security;
alter table submission.pack_activation force row level security;
create policy pack_activation_tenant_isolation on submission.pack_activation
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.pack_activation to submission_runtime;
