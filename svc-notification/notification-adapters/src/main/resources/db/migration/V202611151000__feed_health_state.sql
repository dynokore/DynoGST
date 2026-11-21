-- V202611151000__feed_health_state.sql (INV-T-26518, INV-REQ-910; slice 2 of 3 of the feed health alert).
-- Expand-only: one new table, nothing existing is altered or dropped. Coordinator ruling (delegated, owner 2026-10-14),
-- answered design question of INV-REQ-910: the consumer of FeedHealthChanged holds one row per tenant, feed, entity,
-- period and control and applies an event only when its stateVersion is greater than the one held (idempotent consumer,
-- producer-owned version guard). state_version 0 means the event carried none.

create table notification.feed_health_state (
    tenant_id uuid not null,
    feed text not null,
    entity_ref text not null,
    period text not null,
    control text not null,
    state text not null,
    state_version integer not null,
    alert_id uuid,
    recipients text[],
    updated_at timestamptz not null,
    primary key (tenant_id, feed, entity_ref, period, control),
    constraint feed_health_state_control check (control in ('SEQUENCE', 'COUNT', 'VALUE', 'WATERMARK', 'MANIFEST')),
    constraint feed_health_state_state check (state in ('FAILED', 'CLEARED')),
    constraint feed_health_state_version_min check (state_version >= 0)
);
comment on column notification.feed_health_state.tenant_id is 'class: operational';
comment on column notification.feed_health_state.feed is 'class: operational';
comment on column notification.feed_health_state.entity_ref is 'class: operational';
comment on column notification.feed_health_state.period is 'class: operational';
comment on column notification.feed_health_state.control is 'class: operational';
comment on column notification.feed_health_state.state is 'class: operational';
comment on column notification.feed_health_state.state_version is 'class: operational';
comment on column notification.feed_health_state.alert_id is 'class: operational';
comment on column notification.feed_health_state.recipients is 'class: operational (user IDs, never roles; null when CLEARED)';
comment on column notification.feed_health_state.updated_at is 'class: operational';

alter table notification.feed_health_state enable row level security;
alter table notification.feed_health_state force row level security;
create policy feed_health_state_tenant_isolation on notification.feed_health_state
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));

grant select, insert, update on notification.feed_health_state to notification_runtime;
