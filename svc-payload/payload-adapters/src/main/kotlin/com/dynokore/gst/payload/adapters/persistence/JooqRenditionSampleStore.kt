package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.RenditionSampleCheck
import com.dynokore.gst.payload.application.rendition.RenditionSampleReader
import com.dynokore.gst.payload.application.rendition.RenditionSampleStore
import com.dynokore.gst.payload.application.rendition.StoredSampleCheck
import com.dynokore.gst.payload.application.rendition.StoredSampleMetadata
import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The sample check store over `payload.rendition_sample_check` (INV-REQ-1033, INV-REQ-1036). Rows are only inserted
 * (the runtime role holds no UPDATE or DELETE). It opens no transaction. The tenant is the one bound to the request;
 * forced row security filters every statement. [findMetadata] names its columns and never reads the byte columns.
 */
class JooqRenditionSampleStore(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : RenditionSampleStore,
    RenditionSampleReader {
    private val table = RenditionSampleCheck.RENDITION_SAMPLE_CHECK
    private val mapper = ObjectMapper()

    override fun findRecorded(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
    ): SampleCheckResult? =
        dsl
            .select(table.RESULT, table.MISSING_WORDING, table.TEXT_FOUND)
            .from(table)
            .where(
                table.ENTITY_REF
                    .eq(entityRef)
                    .and(table.SAMPLE_SHA256.eq(sampleSha256))
                    .and(table.SCENARIO.eq(scenario))
                    .and(table.SUBMISSION_TYPE.eq(submissionType.name)),
            ).fetchOne()
            ?.let {
                SampleCheckResult(
                    SampleResult.valueOf(checkNotNull(it.get(table.RESULT))),
                    words(checkNotNull(it.get(table.MISSING_WORDING))),
                    checkNotNull(it.get(table.TEXT_FOUND)),
                )
            }

    /** The rows in `recorded_seq` order: the last row of a pair wins, so `latest` is derived here, never stored. */
    override fun latest(entityRef: String): Map<Pair<String, SubmissionType>, SampleResult> =
        dsl
            .select(table.SCENARIO, table.SUBMISSION_TYPE, table.RESULT)
            .from(table)
            .where(table.ENTITY_REF.eq(entityRef))
            .orderBy(table.RECORDED_SEQ.asc())
            .fetch()
            .associate {
                Pair(
                    checkNotNull(it.get(table.SCENARIO)),
                    SubmissionType.valueOf(checkNotNull(it.get(table.SUBMISSION_TYPE))),
                ) to SampleResult.valueOf(checkNotNull(it.get(table.RESULT)))
            }

    /** The `latest` argument is ignored: the standing is derived from the stored rows. */
    override fun save(
        entityRef: String,
        check: StoredSampleCheck,
        latest: Map<Pair<String, SubmissionType>, SampleResult>,
    ) {
        require(entityRef == check.entityRef) { "the entity of the check differs from the entity saved" }
        dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.ENTITY_REF, entityRef)
            .set(table.CHECK_ID, check.checkId)
            .set(table.SCENARIO, check.scenario)
            .set(table.SUBMISSION_TYPE, check.submissionType.name)
            .set(table.SAMPLE_CONTENT, check.sampleContent)
            .set(table.SAMPLE_SHA256, check.sampleSha256)
            .set(table.RESULT, check.result.name)
            .set(table.MISSING_WORDING, DSL.cast(mapper.writeValueAsString(check.missingWording), SQLDataType.JSONB))
            .set(table.TEXT_FOUND, check.textFound)
            .set(table.CHECKED_AT, check.checkedAt.atOffset(java.time.ZoneOffset.UTC))
            .set(table.CHECKED_BY, check.checkedBy)
            .set(table.ARTEFACT_BYTES, check.artefactBytes)
            .execute()
    }

    override fun find(
        entityRef: String,
        checkId: String,
    ): StoredSampleCheck? =
        dsl
            .select(
                table.CHECK_ID,
                table.ENTITY_REF,
                table.SCENARIO,
                table.SUBMISSION_TYPE,
                table.SAMPLE_CONTENT,
                table.SAMPLE_SHA256,
                table.RESULT,
                table.MISSING_WORDING,
                table.TEXT_FOUND,
                table.CHECKED_AT,
                table.CHECKED_BY,
                table.ARTEFACT_BYTES,
            ).from(table)
            .where(table.ENTITY_REF.eq(entityRef).and(table.CHECK_ID.eq(checkId)))
            .fetchOne()
            ?.let { r ->
                StoredSampleCheck(
                    checkId = checkNotNull(r.get(table.CHECK_ID)),
                    entityRef = checkNotNull(r.get(table.ENTITY_REF)),
                    scenario = checkNotNull(r.get(table.SCENARIO)),
                    submissionType = SubmissionType.valueOf(checkNotNull(r.get(table.SUBMISSION_TYPE))),
                    sampleContent = checkNotNull(r.get(table.SAMPLE_CONTENT)),
                    sampleSha256 = checkNotNull(r.get(table.SAMPLE_SHA256)),
                    result = SampleResult.valueOf(checkNotNull(r.get(table.RESULT))),
                    missingWording = words(checkNotNull(r.get(table.MISSING_WORDING))),
                    textFound = checkNotNull(r.get(table.TEXT_FOUND)),
                    checkedAt = checkNotNull(r.get(table.CHECKED_AT)).toInstant(),
                    checkedBy = checkNotNull(r.get(table.CHECKED_BY)),
                    artefactBytes = checkNotNull(r.get(table.ARTEFACT_BYTES)),
                )
            }

    /** An explicit column list with neither `sample_content` nor `artefact_bytes`: the bytea columns are not read. */
    internal fun metadataQuery(
        entityRef: String,
        checkId: String,
    ) = dsl
        .select(
            table.CHECK_ID,
            table.ENTITY_REF,
            table.SCENARIO,
            table.SUBMISSION_TYPE,
            table.SAMPLE_SHA256,
            table.RESULT,
            table.MISSING_WORDING,
            table.TEXT_FOUND,
            table.CHECKED_AT,
            table.CHECKED_BY,
        ).from(table)
        .where(table.ENTITY_REF.eq(entityRef).and(table.CHECK_ID.eq(checkId)))

    override fun findMetadata(
        entityRef: String,
        checkId: String,
    ): StoredSampleMetadata? =
        metadataQuery(entityRef, checkId)
            .fetchOne()
            ?.let { r: Record ->
                StoredSampleMetadata(
                    checkId = checkNotNull(r.get(table.CHECK_ID)),
                    entityRef = checkNotNull(r.get(table.ENTITY_REF)),
                    scenario = checkNotNull(r.get(table.SCENARIO)),
                    submissionType = SubmissionType.valueOf(checkNotNull(r.get(table.SUBMISSION_TYPE))),
                    sampleSha256 = checkNotNull(r.get(table.SAMPLE_SHA256)),
                    result = SampleResult.valueOf(checkNotNull(r.get(table.RESULT))),
                    missingWording = words(checkNotNull(r.get(table.MISSING_WORDING))),
                    textFound = checkNotNull(r.get(table.TEXT_FOUND)),
                    checkedAt = checkNotNull(r.get(table.CHECKED_AT)).toInstant(),
                    checkedBy = checkNotNull(r.get(table.CHECKED_BY)),
                )
            }

    private fun words(json: JSONB): List<String> = mapper.readValue(json.data(), Array<String>::class.java).toList()
}
