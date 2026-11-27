package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.ValidationOverride
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.records.ValidationOverrideRecord
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.application.override.OverrideStore
import com.dynokore.gst.payload.application.override.ReleasedOverrides
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.domain.override.OverrideStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

/**
 * The override store over `payload.validation_override`. It opens no transaction: the rows commit with the use case of
 * the caller. The tenant is the one bound to the request ([boundTenant]); forced row security filters every statement.
 *
 * Both release paths first take the release lock of the document, held until the transaction of the caller ends, and
 * number the release in a later statement, so the release sequences of a version follow the lock order. The caller's
 * transaction must be READ COMMITTED (the default): the later statement then sees the releases committed before it.
 */
class JooqOverrideStore(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : OverrideStore,
    ReleasedOverrides {
    private val table = ValidationOverride.VALIDATION_OVERRIDE

    override fun insert(override: NewOverride): StoredOverride? {
        val releaseSequence =
            if (override.status == OverrideStatus.RELEASED) {
                lockRelease(override.docUuid)
                nextReleaseSequence(override.docUuid, override.version)
            } else {
                null
            }
        return dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, override.docUuid)
            .set(table.VERSION, override.version)
            .set(table.CODE, override.code)
            .set(table.REASON, override.reason)
            .set(table.STATUS, override.status.name)
            .set(table.REQUESTED_BY, override.requestedBy)
            .set(table.RELEASED_BY, override.releasedBy)
            .set(table.RELEASED_AT, if (override.releasedBy != null) DSL.currentOffsetDateTime() else null)
            .set(table.RELEASE_SEQUENCE, releaseSequence)
            // only the (doc, version, code) key means "already taken": a repeated release sequence is an error
            .onConflict(table.TENANT_ID, table.DOC_UUID, table.VERSION, table.CODE)
            .doNothing()
            .returning()
            .fetchOne()
            ?.let(::stored)
    }

    override fun find(
        docUuid: UUID,
        version: Int,
        code: String,
    ): StoredOverride? =
        dsl
            .selectFrom(table)
            .where(
                table.DOC_UUID
                    .eq(docUuid)
                    .and(table.VERSION.eq(version))
                    .and(table.CODE.eq(code)),
            ).fetchOne()
            ?.let(::stored)

    /** One conditional update: of two simultaneous releases one changes the row and the other changes none. */
    override fun release(
        docUuid: UUID,
        version: Int,
        code: String,
        releasedBy: String,
    ): StoredOverride? {
        lockRelease(docUuid)
        return dsl
            .update(table)
            .set(table.STATUS, OverrideStatus.RELEASED.name)
            .set(table.RELEASED_BY, releasedBy)
            .set(table.RELEASED_AT, DSL.currentOffsetDateTime())
            .set(table.RELEASE_SEQUENCE, nextReleaseSequence(docUuid, version))
            .where(
                table.DOC_UUID
                    .eq(docUuid)
                    .and(table.VERSION.eq(version))
                    .and(table.CODE.eq(code))
                    .and(table.STATUS.eq(OverrideStatus.PENDING.name)),
            ).returning()
            .fetchOne()
            ?.let(::stored)
    }

    override fun ofDocument(docUuid: UUID): List<StoredOverride> =
        dsl
            .selectFrom(table)
            .where(
                table.DOC_UUID
                    .eq(docUuid)
                    .and(table.STATUS.eq(OverrideStatus.RELEASED.name))
                    .and(table.RELEASE_SEQUENCE.isNotNull),
            ).orderBy(table.VERSION, table.RELEASE_SEQUENCE)
            .fetch { stored(it) }

    /** The release lock of (tenant, document): every version of a document shares it. */
    private fun lockRelease(docUuid: UUID) {
        val key = DSL.inline("override-release|${boundTenant()}|$docUuid")
        dsl
            .select(
                DSL.function(
                    DSL.name("pg_advisory_xact_lock"),
                    SQLDataType.OTHER,
                    DSL.function(DSL.name("hashtextextended"), SQLDataType.BIGINT, key, DSL.inline(0L)),
                ),
            ).fetch()
    }

    /**
     * One more than the highest release sequence of the version, or 1; row security limits it to the tenant. It is no
     * database sequence: a lost insert or a rolled-back release consumes no number. Call it after [lockRelease], so
     * that this later statement sees every release committed under the lock. No table alias: the tenant guard of the
     * service refuses one.
     */
    private fun nextReleaseSequence(
        docUuid: UUID,
        version: Int,
    ): Int {
        val highest =
            dsl
                .select(DSL.max(table.RELEASE_SEQUENCE))
                .from(table)
                .where(table.DOC_UUID.eq(docUuid).and(table.VERSION.eq(version)))
                .fetchOne()
                ?.value1()
        return (highest ?: 0) + 1
    }

    override fun entries(
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ): List<StoredOverride> {
        var condition = table.DOC_UUID.eq(docUuid).and(table.VERSION.eq(version))
        if (after != null) {
            val at = after.first.atOffset(java.time.ZoneOffset.UTC)
            condition = condition.and(DSL.row(table.REQUESTED_AT, table.CODE).gt(DSL.row(at, after.second)))
        }
        return dsl
            .selectFrom(table)
            .where(condition)
            .orderBy(table.REQUESTED_AT, table.CODE)
            .limit(limit)
            .fetch { stored(it) }
    }

    private fun stored(row: ValidationOverrideRecord): StoredOverride =
        StoredOverride(
            checkNotNull(row.docUuid),
            checkNotNull(row.version),
            checkNotNull(row.code),
            checkNotNull(row.reason),
            OverrideStatus.valueOf(checkNotNull(row.status)),
            checkNotNull(row.requestedBy),
            checkNotNull(row.requestedAt).toInstant(),
            row.releasedBy,
            row.releasedAt?.toInstant(),
            row.releaseSequence,
        )
}
