package com.dynokore.gst.payload.adapters.packs

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.RulePackReplica
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.util.UUID

/**
 * The replica of the schematron and severity-override pack versions over `payload.rule_pack_replica`
 * (INV-AC-27183), jOOQ only. It opens no transaction: every write commits or rolls back with the inbox consumer's
 * transaction. The tenant is the one bound to the request ([boundTenant]), in every statement as a literal; forced row
 * security is the second line. Every statement names the pack type and the pack identifier with the version.
 */
class JooqRulePackReplica(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) {
    private val table = RulePackReplica.RULE_PACK_REPLICA

    /**
     * Inserts the version once. A second delivery with equal contents (jsonb equality) is [StageResult.UNCHANGED]; the
     * same version with other contents is [StageResult.CONFLICT] and nothing is written.
     */
    fun stage(
        packType: String,
        packIdentifier: String,
        version: String,
        contentsJson: String,
    ): StageResult {
        val contents = DSL.cast(contentsJson, SQLDataType.JSONB)
        val inserted =
            dsl
                .insertInto(table)
                .set(table.TENANT_ID, boundTenant())
                .set(table.PACK_TYPE, packType)
                .set(table.PACK_IDENTIFIER, packIdentifier)
                .set(table.PACK_VERSION, version)
                .set(table.CONTENTS, contents)
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) return StageResult.STORED
        val same = dsl.fetchExists(table, key(packType, packIdentifier, version).and(table.CONTENTS.eq(contents)))
        return if (same) StageResult.UNCHANGED else StageResult.CONFLICT
    }

    /**
     * Marks exactly the pair active and every other row of the tenant and of [packType] inactive. A pair that is not
     * staged is never invented: [IllegalStateException], so the inbox retries it. Returns true when a row changed.
     */
    fun activate(
        packType: String,
        packIdentifier: String,
        version: String,
    ): Boolean {
        check(dsl.fetchExists(table, key(packType, packIdentifier, version))) { "the pack version is not staged yet" }
        val target = table.PACK_IDENTIFIER.eq(packIdentifier).and(table.PACK_VERSION.eq(version))
        return dsl
            .update(table)
            .set(table.ACTIVE, target)
            .where(table.TENANT_ID.eq(boundTenant()).and(table.PACK_TYPE.eq(packType)))
            .and(table.ACTIVE.ne(target))
            .execute() > 0
    }

    /** The active row of [packType] with its contents, or null when none is active for the tenant. */
    fun active(packType: String): ReplicaPack? =
        dsl
            .selectFrom(table)
            .where(
                table.TENANT_ID
                    .eq(boundTenant())
                    .and(table.PACK_TYPE.eq(packType))
                    .and(table.ACTIVE.isTrue),
            ).fetchOne()
            ?.let {
                ReplicaPack(
                    checkNotNull(it.packIdentifier),
                    checkNotNull(it.packVersion),
                    checkNotNull(it.contents).data(),
                )
            }

    /** The contents of a staged version of the tenant, or null. */
    fun contentsOf(
        packType: String,
        packIdentifier: String,
        version: String,
    ): String? =
        dsl
            .selectFrom(table)
            .where(key(packType, packIdentifier, version))
            .fetchOne()
            ?.contents
            ?.data()

    private fun key(
        packType: String,
        packIdentifier: String,
        version: String,
    ) = table.TENANT_ID
        .eq(boundTenant())
        .and(table.PACK_TYPE.eq(packType))
        .and(table.PACK_IDENTIFIER.eq(packIdentifier))
        .and(table.PACK_VERSION.eq(version))
}
