package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqRenditionSampleStore
import com.dynokore.gst.payload.application.rendition.StoredSampleCheck
import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The sample check store against the real database as `payload_runtime` with a signed binding (INV-T-27774). */
class JooqRenditionSampleStoreTest {
    private companion object {
        const val SAMPLE_SHA = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab4"
        const val USER = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b"
        const val ARTEFACT =
            """{"checkId":"chk-0001","checkedAt":"2026-03-20T03:20:00Z","checkedBy":"$USER","entityRef":"ENT-0001",""" +
                """"missingWording":["Buyer Created Tax Invoice"],"result":"FAILED","sampleSha256":"$SAMPLE_SHA",""" +
                """"scenario":"SELF_BILLING","submissionType":"TYPE_2","textFound":true}"""
    }

    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val entity = "ENT-" + UUID.randomUUID()

    @Suppress("LongParameterList")
    private fun vector(
        checkId: String = "chk-0001",
        entityRef: String = entity,
        scenario: String = "SELF_BILLING",
        type: SubmissionType = SubmissionType.TYPE_2,
        result: SampleResult = SampleResult.FAILED,
        sha: String = SAMPLE_SHA,
        by: String = USER,
        checkedAt: Instant = Instant.parse("2026-03-20T03:20:00Z"),
    ) = StoredSampleCheck(
        checkId,
        entityRef,
        scenario,
        type,
        Base64.getDecoder().decode("JVBERi0xLjQK"),
        sha,
        result,
        if (result == SampleResult.FAILED) listOf("Buyer Created Tax Invoice") else emptyList(),
        true,
        checkedAt,
        by,
        ARTEFACT.toByteArray(Charsets.UTF_8),
    )

    private fun <T> bound(
        tenant: UUID,
        block: (JooqRenditionSampleStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(JooqRenditionSampleStore(DSL.using(c, SQLDialect.POSTGRES)) { tenant })
            } finally {
                c.commit()
            }
        }

    private fun save(
        tenant: UUID,
        check: StoredSampleCheck,
    ) = bound(tenant) { it.save(check.entityRef, check, emptyMap()) }

    @Test
    fun `INV-AC-27769 the shared vector round trips field by field and byte-equal`() {
        val saved = vector()
        save(t1, saved)
        val read = bound(t1) { it.find(entity, "chk-0001") }!!
        read.checkId shouldBe saved.checkId
        read.entityRef shouldBe saved.entityRef
        read.scenario shouldBe saved.scenario
        read.submissionType shouldBe saved.submissionType
        read.sampleSha256 shouldBe saved.sampleSha256
        read.result shouldBe saved.result
        read.missingWording shouldBe listOf("Buyer Created Tax Invoice")
        read.textFound shouldBe true
        read.checkedAt shouldBe saved.checkedAt
        read.checkedBy shouldBe USER
        read.sampleContent.contentEquals(saved.sampleContent) shouldBe true
        read.artefactBytes.contentEquals(saved.artefactBytes) shouldBe true
        read.sampleContent.size shouldBe 9
    }

    @Test
    fun `INV-AC-27769 latest of an entity with no row is empty`() {
        bound(t1) { it.latest("ENT-NONE-" + UUID.randomUUID()) } shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-27769 latest holds the most recently recorded result of each pair`() {
        // recorded_seq order differs from checked_at order on purpose: the later insert carries the earlier time
        fun at(hour: Int) = Instant.parse("2026-03-20T0$hour:00:00Z")
        save(t1, vector("c1", result = SampleResult.PASSED, sha = "a".repeat(64), checkedAt = at(3)))
        save(t1, vector("c2", result = SampleResult.FAILED, sha = "b".repeat(64), checkedAt = at(6)))
        save(t1, vector("c3", result = SampleResult.PASSED, sha = "c".repeat(64), checkedAt = at(4)))
        save(t1, vector("c4", scenario = "STANDARD", result = SampleResult.FAILED, sha = "d".repeat(64)))
        bound(t1) { it.latest(entity) } shouldBe
            mapOf(
                ("SELF_BILLING" to SubmissionType.TYPE_2) to SampleResult.PASSED,
                ("STANDARD" to SubmissionType.TYPE_2) to SampleResult.FAILED,
            )
    }

    @Test
    fun `INV-AC-27769 a repeat lookup by the unique key returns the stored result`() {
        save(t1, vector())
        val expected = SampleCheckResult(SampleResult.FAILED, listOf("Buyer Created Tax Invoice"), true)
        repeat(2) { _ ->
            bound(t1) { it.findRecorded(entity, SAMPLE_SHA, "SELF_BILLING", SubmissionType.TYPE_2) } shouldBe expected
        }
        bound(t1) { it.findRecorded(entity, SAMPLE_SHA, "STANDARD", SubmissionType.TYPE_2) } shouldBe null
    }

    private fun runtimeStatement(
        tenant: UUID,
        sql: String,
    ): Int =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                c.createStatement().use { st -> st.executeUpdate(sql) }
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `INV-AC-27769 the runtime role gets a permission error on UPDATE and DELETE and the row is unchanged`() {
        save(t1, vector())
        val table = "payload.rendition_sample_check"
        val update = shouldThrow<SQLException> { runtimeStatement(t1, "update $table set result = 'PASSED'") }
        update.sqlState shouldBe "42501"
        val delete = shouldThrow<SQLException> { runtimeStatement(t1, "delete from $table") }
        delete.sqlState shouldBe "42501"
        bound(t1) { it.find(entity, "chk-0001") }!!.result shouldBe SampleResult.FAILED
    }

    @Test
    fun `INV-AC-27769 a T2 binding reads no row of T1 and cannot insert with the tenant id of T1`() {
        save(t1, vector())
        bound(t2) { it.find(entity, "chk-0001") } shouldBe null
        bound(t2) { it.latest(entity) } shouldBe emptyMap()
        bound(t2) { it.findRecorded(entity, SAMPLE_SHA, "SELF_BILLING", SubmissionType.TYPE_2) } shouldBe null
        val c = vector("c-t1")
        shouldThrow<DataAccessException> {
            PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { conn ->
                conn.autoCommit = false
                SignedBindings.bind(conn, t2)
                try {
                    JooqRenditionSampleStore(DSL.using(conn, SQLDialect.POSTGRES)) { t1 }.save(entity, c, emptyMap())
                } finally {
                    conn.rollback()
                }
            }
        }
    }

    private fun refused(
        result: String = "FAILED",
        sha: String = SAMPLE_SHA,
        by: String = USER,
        type: String = "TYPE_2",
    ) {
        PayloadDb.superuser().use { c ->
            c.createStatement().use { s ->
                shouldThrow<SQLException> {
                    s.execute(
                        "insert into payload.rendition_sample_check (tenant_id, entity_ref, check_id, scenario, " +
                            "submission_type, sample_content, sample_sha256, result, missing_wording, text_found, " +
                            "checked_at, checked_by, artefact_bytes) values ('$t1', 'E', '${UUID.randomUUID()}', " +
                            "'S', " +
                            "'$type', '\\x00', '$sha', '$result', '[]', true, now(), '$by', " +
                            "'\\x00')",
                    )
                }
            }
        }
    }

    @Test
    fun `INV-AC-27769 a bad result, a short or uppercase hash, a blank checked_by and a bad type are refused`() {
        refused(result = "passed")
        refused(sha = "a".repeat(63))
        refused(sha = "A".repeat(64))
        refused(by = "   ")
        refused(type = "TYPE_9")
    }

    @Test
    fun `INV-AC-27769 findMetadata of the shared vector returns every metadata member equal to the saved one`() {
        val saved = vector()
        save(t1, saved)
        val m = bound(t1) { it.findMetadata(entity, "chk-0001") }!!
        m.checkId shouldBe saved.checkId
        m.entityRef shouldBe saved.entityRef
        m.scenario shouldBe saved.scenario
        m.submissionType shouldBe saved.submissionType
        m.sampleSha256 shouldBe saved.sampleSha256
        m.result shouldBe saved.result
        m.missingWording shouldBe saved.missingWording
        m.textFound shouldBe saved.textFound
        m.checkedAt shouldBe saved.checkedAt
        m.checkedBy shouldBe saved.checkedBy
    }

    @Test
    fun `INV-AC-27769 findMetadata of an unknown checkId and of the checkId of another entity is null`() {
        save(t1, vector())
        bound(t1) { it.findMetadata(entity, "chk-unknown") } shouldBe null
        bound(t1) { it.findMetadata("ENT-OTHER", "chk-0001") } shouldBe null
    }

    @Test
    fun `INV-AC-27769 a T2 binding reads no metadata of T1`() {
        save(t1, vector())
        bound(t2) { it.findMetadata(entity, "chk-0001") } shouldBe null
    }

    @Test
    fun `INV-AC-27769 the SQL findMetadata executes names neither byte column and is not select star`() {
        save(t1, vector())
        // findMetadata fetches exactly the query metadataQuery returns, so its rendered text is the executed text
        val text = bound(t1) { it.metadataQuery(entity, "chk-0001").sql }.lowercase()
        Regex("(?<![a-z0-9_])sample_content(?![a-z0-9_])").containsMatchIn(text) shouldBe false
        Regex("(?<![a-z0-9_])artefact_bytes(?![a-z0-9_])").containsMatchIn(text) shouldBe false
        text shouldNotContain "*"
        // the probe: the full read names both columns, so the same pattern can fail
        val full = bound(t1) { it.find(entity, "chk-0001") }!!
        full.sampleContent.size shouldBe 9
    }
}
