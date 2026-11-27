package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackActivation.Companion.BREACH_TERMS_PACK_ACTIVATION
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackType.Companion.BREACH_TERMS_PACK_TYPE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackVersion.Companion.BREACH_TERMS_PACK_VERSION
import com.dynokore.gst.evidence.application.cases.BreachTermsActivation
import com.dynokore.gst.evidence.application.cases.BreachTermsReplica
import com.dynokore.gst.evidence.application.cases.BreachTermsStage
import com.dynokore.gst.evidence.application.cases.BreachTermsStaging
import com.dynokore.gst.evidence.application.cases.BreachTermsVersion
import com.dynokore.gst.evidence.domain.cases.BreachTerms
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [BreachTermsReplica] over the `breach_terms_pack_*` tables (jOOQ only, STK §3). It opens no transaction: every write
 * commits or rolls back with the caller's, the inbox consumer's. The tables are of tenancy class REFERENCE: every write
 * is a definer function that answers the platform binding only, and every read names the platform tenant.
 */
class JooqBreachTermsReplica(
    private val dsl: DSLContext,
) : BreachTermsReplica {
    private val types = BREACH_TERMS_PACK_TYPE
    private val versions = BREACH_TERMS_PACK_VERSION
    private val activations = BREACH_TERMS_PACK_ACTIVATION
    private val platform = TenantId.PLATFORM.value

    override fun recordType(
        packIdentifier: String,
        packType: String,
    ): String {
        // insert-if-absent, then a new statement reads: a concurrent first staging waits on the unique key and reads
        // the winner's type
        call(
            "record_breach_terms_pack_type",
            SQLDataType.OTHER,
            DSL.`val`(packIdentifier, SQLDataType.CLOB),
            DSL.`val`(packType, SQLDataType.CLOB),
        )
        return checkNotNull(typeOf(packIdentifier)) { "the pack type row is not readable after its insert" }
    }

    override fun typeOf(packIdentifier: String): String? =
        dsl
            .select(types.PACK_TYPE)
            .from(types)
            .where(types.TENANT_ID.eq(platform).and(types.PACK_IDENTIFIER.eq(packIdentifier)))
            .fetchOne(types.PACK_TYPE)

    override fun stage(
        packIdentifier: String,
        version: String,
        terms: BreachTermsVersion,
        staging: BreachTermsStaging,
    ): BreachTermsStage {
        val inserted =
            call(
                "store_breach_terms_pack_version",
                SQLDataType.BOOLEAN,
                DSL.`val`(packIdentifier, SQLDataType.CLOB),
                DSL.`val`(version, SQLDataType.CLOB),
                DSL.`val`(terms.assessmentWithinHours, SQLDataType.BIGINT),
                DSL.`val`(terms.notificationWithinHours, SQLDataType.BIGINT),
                DSL.`val`(terms.sourceReference, SQLDataType.CLOB),
                DSL.`val`(staging.at.atOffset(ZoneOffset.UTC), SQLDataType.TIMESTAMPWITHTIMEZONE),
                DSL.`val`(staging.eventId, SQLDataType.CLOB),
            )
        if (inserted == true) return BreachTermsStage.STORED
        val same =
            dsl.fetchExists(
                versions,
                versionKey(packIdentifier, version)
                    .and(versions.ASSESSMENT_WITHIN_HOURS.eq(terms.assessmentWithinHours))
                    .and(versions.NOTIFICATION_WITHIN_HOURS.eq(terms.notificationWithinHours))
                    .and(versions.SOURCE_REFERENCE.eq(terms.sourceReference)),
            )
        return if (same) BreachTermsStage.UNCHANGED else BreachTermsStage.CONFLICT
    }

    override fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): BreachTermsActivation =
        when {
            !dsl.fetchExists(versions, versionKey(packIdentifier, version)) -> {
                BreachTermsActivation.NOT_STAGED
            }

            isStored(packIdentifier, version, effectiveFrom) -> {
                BreachTermsActivation.UNCHANGED
            }

            else -> {
                call(
                    "record_breach_terms_activation",
                    SQLDataType.OTHER,
                    DSL.`val`(packIdentifier, SQLDataType.CLOB),
                    DSL.`val`(version, SQLDataType.CLOB),
                    DSL.`val`(effectiveFrom, SQLDataType.LOCALDATE),
                )
                BreachTermsActivation.APPLIED
            }
        }

    private fun isStored(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): Boolean =
        dsl.fetchExists(
            activations,
            activations.TENANT_ID
                .eq(platform)
                .and(activations.PACK_IDENTIFIER.eq(packIdentifier))
                .and(activations.PACK_VERSION.eq(version))
                .and(activations.EFFECTIVE_FROM.eq(effectiveFrom)),
        )

    override fun activeTerms(on: LocalDate): BreachTerms? =
        dsl
            .select(versions.ASSESSMENT_WITHIN_HOURS, versions.NOTIFICATION_WITHIN_HOURS)
            .from(activations)
            .join(versions)
            .on(
                versions.TENANT_ID
                    .eq(activations.TENANT_ID)
                    .and(versions.PACK_IDENTIFIER.eq(activations.PACK_IDENTIFIER))
                    .and(versions.PACK_VERSION.eq(activations.PACK_VERSION)),
            ).where(activations.TENANT_ID.eq(platform).and(activations.EFFECTIVE_FROM.le(on)))
            .orderBy(activations.EFFECTIVE_FROM.desc(), versions.STAGED_AT.desc(), versions.STAGED_EVENT_ID.desc())
            .limit(1)
            .fetchOne()
            ?.let {
                BreachTerms(
                    assessmentWithin = Duration.ofHours(checkNotNull(it.value1())),
                    notificationWithin = Duration.ofHours(checkNotNull(it.value2())),
                )
            }

    /** One definer function of schema `evidence`, called through a select (tenancy-jooq refuses a generated routine). */
    private fun <T : Any> call(
        function: String,
        type: DataType<T>,
        vararg arguments: Field<*>,
    ): T? = dsl.select(DSL.function(DSL.name(SCHEMA, function), type, *arguments)).fetchSingle().value1()

    private fun versionKey(
        packIdentifier: String,
        version: String,
    ) = versions.TENANT_ID
        .eq(platform)
        .and(versions.PACK_IDENTIFIER.eq(packIdentifier))
        .and(versions.PACK_VERSION.eq(version))

    private companion object {
        const val SCHEMA = "evidence"
    }
}
