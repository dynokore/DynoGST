-- V202611260900__run_read_model_party_uen.sql (INV-T-28553, INV-REQ-740, INV-AC-28552).
--
-- The run read model stores the seller UEN and the buyer UEN of each document version, so the pairing view can match a
-- supply with its purchase (event-carried state). Both columns are nullable: they are filled only from an SG_UEN party
-- identifier of the event. No stored row is updated and no value is invented: rows stored before this migration keep
-- null and show as unpaired until a rebuild by replay. Grants, row security and the append-only trigger of
-- document_version are unchanged.
alter table reconciliation.document_version add column seller_uen text;
alter table reconciliation.document_version add column buyer_uen text;

comment on column reconciliation.document_version.seller_uen is 'class: operational';
comment on column reconciliation.document_version.buyer_uen is 'class: operational';
