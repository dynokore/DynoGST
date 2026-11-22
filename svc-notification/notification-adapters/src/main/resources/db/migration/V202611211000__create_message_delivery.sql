-- V202611211000__create_message_delivery.sql (INV-T-26365, INV-REQ-817, INV-REQ-2762).
-- Expand-only: one new table and one index, nothing existing is altered or dropped. One row per (event, recipient
-- position); the row holds a recipient reference, never an address, a number or a sealed passcode.

create table notification.message_delivery (
    message_id uuid primary key,
    tenant_id uuid not null,
    event_id text not null,
    position int not null,
    entity_ref text not null,
    channel text not null check (channel in ('EMAIL', 'SMS')),
    template text not null,
    template_version text not null,
    recipient_ref text not null,
    state text not null check (state in ('QUEUED', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED')),
    delivery_id text,
    failure_reason text,
    sent_at timestamptz,
    unique (tenant_id, event_id, position)
);
create index message_delivery_tenant_state on notification.message_delivery (tenant_id, state);

comment on column notification.message_delivery.message_id is 'class: operational';
comment on column notification.message_delivery.tenant_id is 'class: operational';
comment on column notification.message_delivery.event_id is 'class: operational';
comment on column notification.message_delivery.position is 'class: operational';
comment on column notification.message_delivery.entity_ref is 'class: operational';
comment on column notification.message_delivery.channel is 'class: operational';
comment on column notification.message_delivery.template is 'class: operational';
comment on column notification.message_delivery.template_version is 'class: operational';
comment on column notification.message_delivery.recipient_ref is 'class: operational (a reference, never an address)';
comment on column notification.message_delivery.state is 'class: operational';
comment on column notification.message_delivery.delivery_id is 'class: operational';
comment on column notification.message_delivery.failure_reason is 'class: operational';
comment on column notification.message_delivery.sent_at is 'class: operational';

alter table notification.message_delivery enable row level security;
alter table notification.message_delivery force row level security;
create policy message_delivery_tenant_isolation on notification.message_delivery
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));

grant select, insert, update on notification.message_delivery to notification_runtime;
