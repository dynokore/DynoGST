-- The recipients of an alert (INV-REQ-895): one row per user ID the alert was addressed to, stored inside the tenant.
-- Expand-only: a new table, no change to an existing one. The runtime role can select and insert, never update or delete.
create table notification.alert_recipient (
    tenant_id uuid not null,
    alert_id uuid not null,
    user_id text not null,
    created_at timestamptz not null default now(),
    primary key (tenant_id, alert_id, user_id),
    foreign key (tenant_id, alert_id) references notification.alert (tenant_id, alert_id)
);
comment on column notification.alert_recipient.tenant_id is 'class: operational';
comment on column notification.alert_recipient.alert_id is 'class: operational';
comment on column notification.alert_recipient.user_id is 'class: operational (the recipient user ID, never a role)';
comment on column notification.alert_recipient.created_at is 'class: operational';

alter table notification.alert_recipient enable row level security;
alter table notification.alert_recipient force row level security;
create policy alert_recipient_tenant_isolation on notification.alert_recipient
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));

grant select, insert on notification.alert_recipient to notification_runtime;
