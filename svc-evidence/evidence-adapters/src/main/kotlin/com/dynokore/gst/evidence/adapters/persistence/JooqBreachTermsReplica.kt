package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackActivation.Companion.BREACH_TERMS_PACK_ACTIVATION
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackType.Companion.BREACH_TERMS_PACK_TYPE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.BreachTermsPackVersion.Companion.BREACH_TERMS_PACK_VERSION
import com.dynokore.gst.evidence.application.cases.BreachTermsActivation
import com.dynokore.gst.evidence.application.cases.BreachTermsReplica
import com.dynokore.gst.evidence.application.cases.BreachTermsStage
import com.dynokore.gst.evidence.application.cases.BreachTermsVersion
import com.dynokore.gst.evidence.domain.cases.BreachTerms
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Duration
import java.time.LocalDate

/**
 * [BreachTermsReplica] over the `breach_terms_pack_*` tables (jOOQ only, STK §3). It opens no transaction: every write
 * commits or rolls back with the caller's, the inbox consumer's. Every statement names the platform tenant; row
 * security lets only the platform binding write and any signed binding read these rows.
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
        // insert-if-absent, then read: a concurrent first staging waits on the unique key and reads the winner's type
        dsl
            .insertInto(types)
            .set(types.TENANT_ID, platform)
            .set(types.PACK_IDENTIFIER, packIdentifier)
            .set(types.PACK_TYPE, packType)
            .onConflict(types.TENANT_ID, types.PACK_IDENTIFIER)
            .doNothing()
            .execute()
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
    ): BreachTermsStage {
        val inserted =
            dsl
                .insertInto(versions)
                .set(versions.TENANT_ID, platform)
                .set(versions.PACK_IDENTIFIER, packIdentifier)
                .set(versions.PACK_VERSION, version)
                .set(versions.ASSESSMENT_WITHIN_HOURS, terms.assessmentWithinHours)
                .set(versions.NOTIFICATION_WITHIN_HOURS, terms.notificationWithinHours)
                .set(versions.SOURCE_REFERENCE, terms.sourceReference)
                .onConflict(versions.TENANT_ID, versions.PACK_IDENTIFIER, versions.PACK_VERSION)
                .doNothing()
                .execute() == 1
        if (inserted) return BreachTermsStage.STORED
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

            inForceFor(effectiveFrom) == packIdentifier to version -> {
                BreachTermsActivation.UNCHANGED
            }

            else -> {
                dsl
                    .insertInto(activations)
                    .set(activations.TENANT_ID, platform)
                    .set(activations.PACK_IDENTIFIER, packIdentifier)
                    .set(activations.PACK_VERSION, version)
                    .set(activations.EFFECTIVE_FROM, effectiveFrom)
                    .execute()
                BreachTermsActivation.APPLIED
            }
        }

    /** The pack identifier and version last applied for exactly [effectiveFrom], or null. */
    private fun inForceFor(effectiveFrom: LocalDate): Pair<String?, String?>? =
        dsl
            .select(activations.PACK_IDENTIFIER, activations.PACK_VERSION)
            .from(activations)
            .where(activations.TENANT_ID.eq(platform).and(activations.EFFECTIVE_FROM.eq(effectiveFrom)))
            .orderBy(activations.APPLIED_SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let { it.value1() to it.value2() }

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
            .orderBy(activations.EFFECTIVE_FROM.desc(), activations.APPLIED_SEQ.desc())
            .limit(1)
            .fetchOne()
            ?.let {
                BreachTerms(
                    assessmentWithin = Duration.ofHours(checkNotNull(it.value1())),
                    notificationWithin = Duration.ofHours(checkNotNull(it.value2())),
                )
            }

    private fun versionKey(
        packIdentifier: String,
        version: String,
    ) = versions.TENANT_ID
        .eq(platform)
        .and(versions.PACK_IDENTIFIER.eq(packIdentifier))
        .and(versions.PACK_VERSION.eq(version))
}
