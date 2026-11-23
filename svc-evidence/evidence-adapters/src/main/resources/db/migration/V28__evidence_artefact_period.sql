-- V28__evidence_artefact_period.sql (INV-T-28219; INV-REQ-988, INV-AC-28217).
--
-- The period of a reconciliation work-paper, taken from header.periodRef of the sealed object when the row is
-- recorded. Other artefact types keep it null. V13 is not edited: its grants (select, insert only), forced row-level
-- security and the append-only trigger evidence_artefact_no_mutation cover the new column unchanged.

alter table evidence.evidence_artefact add column period_ref text;
alter table evidence.evidence_artefact
    add constraint evidence_artefact_work_paper_period
    check (artefact_type <> 'RECONCILIATION_WORK_PAPER' or period_ref is not null);
create index evidence_artefact_period_idx
    on evidence.evidence_artefact (tenant_id, artefact_type, entity_ref, period_ref);
