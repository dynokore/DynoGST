package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartition.Companion.RETENTION_PARTITION
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartitionMember.Companion.RETENTION_PARTITION_MEMBER
import com.dynokore.gst.evidence.application.retention.PartitionLifecycle
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The partition lifecycle of a retention-end deletion over the SECURITY DEFINER functions of V21 and V31 (least
 * privilege: the adapter only calls them), in the caller's tenant transaction, so a refusal of any call rolls the whole
 * sequence back. Each step runs once for each registered member of the unit: its chain tables in the order of
 * [RecordKind], then `prior_evidence` (V24). A unit holds only the members that were written to, so an imported-only
 * unit has `prior_evidence` alone. A call that fails or is refused stops the sequence and the failure is thrown, never
 * skipped (INV-AC-22962, INV-AC-22964, INV-AC-28694):
 * - [lock]: `hold_retention_partition` (V31) alone, the deletion's first call: it locks the unit row, in any state,
 *   until the transaction ends, before any statement on a partitioned table (one lock order with the importer);
 * - [detach]: first `lock_retention_partition` (V31) locks the unit row until the transaction ends, so a writer that
 *   came first has committed and is archived, and a later one waits and is refused; then the member list is read once
 *   (none, or a table that is not one of the five, is thrown before any object is written), their rows are archived and
 *   `detach_expired_partition(parent, partition, audit_ref, retention_end)` runs for each;
 * - [archive]: the rows of each member went to the object store before the first detach; `mark_partition_archived` is
 *   called once for the unit (the function checks that it has members and that all are detached);
 * - [drop]: `drop_detached_partition(partition)` for each registered member.
 * The archive objects are locked for [archiveLockPeriod] from the write (NIST SP 800-53 AU-9); it is configuration with
 * no default: while it is null the deletion is refused before any object is written. Zero is allowed, negative is not.
 * [observe] is told the name of each call just before it is made (`detach:<table>`, `archive`, `drop:<table>`);
 * neither lock is observed.
 */
class JooqPartitionLifecycle(
    private val dsl: DSLContext,
    private val store: ObjectStore,
    private val clock: Clock,
    private val archiveLockPeriod: Duration? = null,
    private val observe: (String) -> Unit = {},
) : PartitionLifecycle {
    init {
        require(archiveLockPeriod == null || !archiveLockPeriod.isNegative) {
            "the archive lock period must not be negative, was $archiveLockPeriod"
        }
    }

    override fun lock(partitionId: String) = callOnUnit("hold_retention_partition", partitionId)

    override fun detach(
        partitionId: String,
        auditRef: String,
        retentionEnd: Instant,
    ) {
        checkNotNull(archiveLockPeriod) { "no archive lock period is configured" }
        lockAttached(partitionId)
        val members = members(partitionId)
        // The runtime login holds no right on a detached partition (it is owned by the migration login), so the rows
        // are read through the chain tables, still attached, and written to the object store before the first detach.
        val prefix = archive(partitionId, readUnit(partitionId), members)
        pendingPrefix[partitionId] = prefix
        members.forEach { table ->
            observe("detach:$table")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "detach_expired_partition"),
                        SQLDataType.VARCHAR,
                        DSL.inline("$SCHEMA.$table"),
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, table)}"),
                        DSL.`val`(auditRef),
                        DSL.`val`(retentionEnd.atOffset(ZoneOffset.UTC)),
                    ),
                ).fetch()
        }
    }

    /** Marks the unit archived (`mark_partition_archived`, once; the function checks every member detached). */
    override fun archive(partitionId: String): String {
        val prefix = checkNotNull(pendingPrefix.remove(partitionId)) { "the rows of $partitionId were not archived" }
        observe("archive")
        dsl
            .select(
                DSL.function(
                    DSL.name(SCHEMA, "mark_partition_archived"),
                    SQLDataType.OTHER,
                    DSL.`val`(partitionId),
                    DSL.`val`(prefix),
                ),
            ).fetch()
        return prefix
    }

    private fun lockAttached(partitionId: String) = callOnUnit("lock_retention_partition", partitionId)

    private fun callOnUnit(
        function: String,
        partitionId: String,
    ) {
        dsl
            .select(DSL.function(DSL.name(SCHEMA, function), SQLDataType.OTHER, DSL.`val`(partitionId)))
            .fetch()
    }

    private class UnitKey(
        val tenant: UUID,
        val entity: String,
        val period: String,
    )

    private fun readUnit(partitionId: String): UnitKey {
        val p = RETENTION_PARTITION
        val row = dsl.selectFrom(p).where(p.PARTITION_ID.eq(partitionId)).fetchSingle()
        return UnitKey(row.tenantId.req(), row.entityRef.req(), row.periodFrom.req())
    }

    /** One object per member table: the unit's rows as JSON, under `retention-archive/<tenant>/<partition>/`. */
    private fun archive(
        partitionId: String,
        unit: UnitKey,
        members: List<String>,
    ): String {
        val prefix = "retention-archive/${unit.tenant}/$partitionId"
        members.forEach { table ->
            // The chain tables are ordered by seq; the prior table has none: transaction date, document number, id.
            val order =
                if (table == PRIOR_EVIDENCE) {
                    listOf("transaction_date", "document_number", "id").map { DSL.field(DSL.name(it)) }
                } else {
                    listOf(DSL.field(DSL.name("seq")))
                }
            val rows =
                dsl
                    .selectFrom(DSL.table(DSL.name(SCHEMA, table)))
                    .where(
                        DSL.field(DSL.name("tenant_id"), UUID::class.java).eq(unit.tenant),
                        DSL.field(DSL.name("entity_ref"), String::class.java).eq(unit.entity),
                        DSL.field(DSL.name("period_ref"), String::class.java).eq(unit.period),
                    ).orderBy(order)
                    .fetch()
            store.put(ObjectKey("$prefix/$table.json"), rows.formatJSON().toByteArray(Charsets.UTF_8), retain())
        }
        return prefix
    }

    private fun retain(): Instant = clock.instant().plus(checkNotNull(archiveLockPeriod))

    override fun drop(partitionId: String) {
        members(partitionId).forEach { table ->
            observe("drop:$table")
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "drop_detached_partition"),
                        SQLDataType.OTHER,
                        DSL.inline("$SCHEMA.${partitionTable(partitionId, table)}"),
                    ),
                ).fetch()
        }
    }

    /** The registered members of the unit in call order; none, or a table that is not one of the five, is thrown. */
    private fun members(partitionId: String): List<String> {
        val m = RETENTION_PARTITION_MEMBER
        val registered =
            dsl
                .select(m.CHAIN_TABLE)
                .from(m)
                .where(m.PARTITION_ID.eq(partitionId))
                .fetch(m.CHAIN_TABLE)
                .map { it.req() }
        check(registered.isNotEmpty() && MEMBER_TABLES.containsAll(registered)) {
            "the registered members of $partitionId are not usable: $registered"
        }
        return MEMBER_TABLES.filter { it in registered }
    }

    /** The table of the unit's member [table], read from the registry; its shape is checked before it is inlined. */
    private fun partitionTable(
        partitionId: String,
        table: String,
    ): String {
        val m = RETENTION_PARTITION_MEMBER
        val name =
            dsl
                .select(m.PARTITION_TABLE)
                .from(m)
                .where(m.PARTITION_ID.eq(partitionId), m.CHAIN_TABLE.eq(table))
                .fetchOne(m.PARTITION_TABLE)
        require(name != null && TABLE_NAME.matches(name)) { "no chain partition of $partitionId in $table" }
        return name
    }

    /** The archive prefix of a unit between its detach and its archive mark (one deletion of a unit at a time). */
    private val pendingPrefix = ConcurrentHashMap<String, String>()

    private companion object {
        const val SCHEMA = "evidence"
        const val PRIOR_EVIDENCE = "prior_evidence"

        /** The tables a unit can have a member in, in call order. */
        val MEMBER_TABLES = RecordKind.entries.map { it.table } + PRIOR_EVIDENCE
        val TABLE_NAME = Regex("^[a-z_]+_rp_[0-9a-f]{32}$")
    }
}
