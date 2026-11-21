package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The in-flight store returns the submission type and does not list a READY Type 1A only Doc UUID (INV-AC-26137). */
class InFlightStoreTypeDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqInFlightStore(dsl)
    private val sub = Submission.SUBMISSION_
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val tenant = UUID.randomUUID()
    private val s = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun close() = connection.close()

    private fun doc(name: String) = "$name-$s"

    private fun seed(
        doc: String,
        version: Int,
        state: LifecycleState,
        type: SubmissionKind,
    ) {
        SignedBindings.bind(connection, tenant)
        if (version == 1) {
            dsl
                .insertInto(entry)
                .set(entry.TENANT_ID, tenant)
                .set(entry.DOC_UUID, doc)
                .set(entry.ENTITY_REF, "e-$s")
                .set(entry.PERIOD_REF, "p-$s")
                .set(entry.DUE_DATE, LocalDate.of(2026, 11, 30))
                .set(entry.IN_SCOPE, true)
                .set(entry.KIND, "INVOICE")
                .execute()
        }
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, version)
            .set(sub.STATE, state.name)
            .set(sub.ENTITY_REF, "e-$s")
            .set(sub.SUBMISSION_TYPE, type.name)
            .execute()
    }

    private fun waiting(): List<String> {
        SignedBindings.bind(connection, tenant)
        return store.waitingDocUuids(TenantId(tenant), 100)
    }

    @Test
    fun `INV-AC-26137 lockVersions returns the submission type of each row`() {
        val d = doc("both")
        seed(d, 1, LifecycleState.SENT, SubmissionKind.TYPE_1B)
        seed(d, 2, LifecycleState.READY, SubmissionKind.TYPE_1A)
        SignedBindings.bind(connection, tenant)
        val rows = store.lockVersions(TenantId(tenant), d)
        rows.map { it.version to it.submissionType } shouldBe
            listOf(1 to SubmissionKind.TYPE_1B, 2 to SubmissionKind.TYPE_1A)
    }

    @Test
    fun `INV-AC-26137 waitingDocUuids lists a READY 1B Doc UUID`() {
        val d = doc("one-b")
        seed(d, 1, LifecycleState.READY, SubmissionKind.TYPE_1B)
        waiting() shouldContain d
    }

    @Test
    fun `INV-AC-26137 waitingDocUuids omits a Doc UUID whose only READY version is 1A`() {
        val d = doc("one-a")
        seed(d, 1, LifecycleState.READY, SubmissionKind.TYPE_1A)
        waiting() shouldNotContain d
    }

    @Test
    fun `INV-AC-26137 waitingDocUuids lists a Doc UUID with a READY 1A and a READY 1B version`() {
        val d = doc("mixed")
        seed(d, 1, LifecycleState.READY, SubmissionKind.TYPE_1A)
        seed(d, 2, LifecycleState.READY, SubmissionKind.TYPE_1B)
        waiting() shouldContain d
    }
}
