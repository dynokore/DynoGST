-- V30__prior_evidence_prior_doc_uuid.sql (INV-T-28465; INV-REQ-1109, INV-AC-28460).
-- The Doc UUID the prior solution used for an imported record; null when the record has none. Additive (expand pattern).
alter table evidence.prior_evidence add column prior_doc_uuid uuid;
