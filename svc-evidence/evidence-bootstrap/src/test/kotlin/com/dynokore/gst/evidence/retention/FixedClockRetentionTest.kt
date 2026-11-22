package com.dynokore.gst.evidence.retention

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture
import com.dynokore.gst.evidence.application.retention.RetentionEndReader
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED

/** The helper's date comes from an injected fixed [Clock] in UTC, so no test depends on the time of day. */
class FixedClockRetentionTest {
    @Test
    fun `INV-AC-27350 the helper date is the Singapore business date of the fixed clock`() {
        RetentionService.today() shouldBe LocalDate.parse("2026-03-11")
        RetentionService.today() shouldNotBe LocalDate.parse("2026-03-10")
    }

    @Test
    fun `INV-AC-27350 an injected clock changes the date at the Singapore midnight`() {
        RetentionService.today(Clock.fixed(Instant.parse("2026-03-10T15:59:59Z"), ZoneOffset.UTC)) shouldBe
            LocalDate.parse("2026-03-10")
        RetentionService.today(Clock.fixed(Instant.parse("2026-03-10T16:00:00Z"), ZoneOffset.UTC)) shouldBe
            LocalDate.parse("2026-03-11")
    }

    @Test
    fun `INV-AC-27350 the zone of the injected clock never matters`() {
        val instant = Instant.parse("2026-03-10T20:00:00Z")
        RetentionService.today(Clock.fixed(instant, ZoneId.of("Asia/Singapore"))) shouldBe
            LocalDate.parse("2026-03-11")
        RetentionService.today(Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))) shouldBe
            LocalDate.parse("2026-03-11")
    }

    private fun seedAck9(s: RetentionService): TenantId {
        val tenant = s.newTenant()
        s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 5), "baseline").accepted()
        s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 9, SOURCE to 5), "obligation of the parent").accepted()
        return tenant
    }

    @Test
    fun `INV-AC-27350 a value dated by the helper is effective from the helper date and not before`(
        @TempDir dir: Path,
    ) {
        RetentionService.start(dir).use { s ->
            val tenant = seedAck9(s)
            s.years(tenant, "ENT-A", ACK, LocalDate.parse("2026-03-10")) shouldNotBe 9
            s.years(tenant, "ENT-A", ACK, LocalDate.parse("2026-03-11")) shouldBe 9
            s.years(tenant, "ENT-A", ACK, LocalDate.parse("2026-03-12")) shouldBe 9
        }
    }

    @Test
    fun `INV-AC-27350 the retention end of a partition reads the years as of the Singapore date`(
        @TempDir dir: Path,
    ) {
        RetentionService.start(dir).use { s ->
            val tenant = seedAck9(s)
            EvidenceDb.superuser().use {
                RetentionPartitionFixture.ensureRetentionPartitions(
                    it,
                    tenant.value,
                    "ENT-A",
                    "2010-Q1",
                )
            }
            val id =
                EvidenceDb.superuser().use { c ->
                    c
                        .prepareStatement(
                            "select partition_id from evidence.retention_partition where tenant_id = ?",
                        ).use { st ->
                            st.setObject(1, tenant.value)
                            st.executeQuery().use { rs ->
                                rs.next().also { check(it) }
                                rs.getString(1)
                            }
                        }
                }
            val base = LocalDate.parse("2010-03-31").plusDays(1).atStartOfDay(ZoneOffset.UTC)
            val end =
                s.bean(TenantScope::class.java).bind(tenant, "svc:evidence-svc") {
                    s.bean(TransactionRunner::class.java).inTransaction {
                        s.bean(RetentionEndReader::class.java).retentionEnd(tenant.value.toString(), id)
                    }
                }
            end shouldBe base.plusYears(9).toInstant()
            end shouldNotBe base.plusYears(5).toInstant()
        }
    }

    @Test
    fun `INV-AC-27350 the Clock bean of every started context starts at the fixed clock`(
        @TempDir dir: Path,
    ) {
        val lists = listOf(null, listOf(WorkloadTokensOnly::class.java, EndUserTokensOnly::class.java))
        lists.forEachIndexed { n, beans ->
            val sub =
                dir.resolve("c$n").also {
                    java.nio.file.Files
                        .createDirectories(it)
                }
            val service = if (beans == null) RetentionService.start(sub) else RetentionService.start(sub, beans)
            service.use { s ->
                s.bean(Clock::class.java).instant() shouldBe Instant.parse("2026-03-10T20:00:00Z")
                s.bean(Clock::class.java).zone shouldBe ZoneOffset.UTC
            }
        }
    }

    @Test
    fun `INV-AC-27350 stale advances only the clock of its own context past the lag bound`(
        @TempDir dir: Path,
    ) {
        val dirA =
            java.nio.file.Files
                .createDirectories(dir.resolve("a"))
        val dirB =
            java.nio.file.Files
                .createDirectories(dir.resolve("b"))
        // Both contexts are open at once (random ports), so a static clock or a shared constant would show in b.
        val start = Instant.parse("2026-03-10T20:00:00Z")
        RetentionService.start(dirA).use { a ->
            RetentionService.start(dirB).use { b ->
                a.grants.stale()
                val lagBound = a.bean(AuthorityReplica::class.java).lagBound
                a.bean(Clock::class.java).instant() shouldBe
                    RetentionService.FIXED_CLOCK
                        .instant()
                        .plus(lagBound)
                        .plusMillis(ReplicaGrants.MARGIN_MS)
                b.bean(Clock::class.java).instant() shouldBe RetentionService.FIXED_CLOCK.instant()
            }
        }
        RetentionService.FIXED_CLOCK.instant() shouldBe start
    }

    @Test
    fun `INV-AC-27350 a replica grant is valid from a day before the fixed clock`(
        @TempDir dir: Path,
    ) {
        RetentionService.start(dir).use { s ->
            val tenant = s.newTenant()
            s.grants.role("tenant.admin", GrantScope.TENANT, tenant.toString()).validFrom shouldBe
                Instant.parse("2026-03-09T20:00:00Z")
        }
    }
}
