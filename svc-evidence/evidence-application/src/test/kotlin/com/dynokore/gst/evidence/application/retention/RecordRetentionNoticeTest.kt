package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RecordRetentionNoticeTest {
    private val tenant = "00000000-0000-0000-0000-000000000001"
    private val at: Instant = Instant.parse("2026-03-01T10:00:00Z")
    private val period: LocalDate = LocalDate.of(2026, 1, 1)

    private class Registry : PartitionRegistry {
        val all = mutableMapOf<String, PartitionRecord>()

        override fun find(partitionId: String) = all[partitionId]
    }

    /** The installation value and the acknowledgement are fixtures the case sets; nothing is a constant of the code. */
    private class Source(
        var days: Int,
        var ack: String? = null,
        var sole: Boolean = false,
    ) : NoticeWindowSource {
        val askedEntities = mutableListOf<String>()

        override fun window(
            tenant: String,
            on: LocalDate,
        ) = NoticeWindow(days)

        override fun acknowledgement(
            tenant: String,
            entityRef: String,
            notifiedAt: Instant,
        ): NoticeAcknowledgement? {
            askedEntities += entityRef
            return ack?.let { NoticeAcknowledgement(it, sole) }
        }
    }

    private class Row(
        val partition: String,
        val notifiedAt: Instant,
        val window: NoticeWindow,
        val ack: NoticeAcknowledgement?,
    )

    private class Store : NoticeWriter {
        val rows = mutableListOf<Row>()

        override fun record(
            tenant: String,
            partitionId: String,
            notifiedAt: Instant,
            window: NoticeWindow,
            acknowledgement: NoticeAcknowledgement?,
        ) {
            rows += Row(partitionId, notifiedAt, window, acknowledgement)
        }
    }

    private val registry =
        Registry().apply {
            all["p1"] = PartitionRecord("p1", "E1", period, period.plusDays(30), PartitionState.ACTIVE, listOf("h"))
            all["p2"] = PartitionRecord("p2", "E2", period, period.plusDays(30), PartitionState.ACTIVE, listOf("h"))
        }

    @Test
    fun `INV-AC-26255 without an acknowledgement the row holds the fixture window and no user ID`() {
        val store = Store()
        RecordRetentionNotice(registry, Source(days = 37), store).execute(tenant, "p1", at) shouldBe
            RecordRetentionNoticeResult.RECORDED
        store.rows
            .single()
            .window.days shouldBe 37
        store.rows.single().ack shouldBe null
        store.rows.single().notifiedAt shouldBe at
    }

    @Test
    fun `INV-AC-26255 one acknowledgement stores its user ID and the sole-operator value true or false`() {
        for (sole in listOf(true, false)) {
            val store = Store()
            RecordRetentionNotice(registry, Source(days = 12, ack = "user-ack-1", sole = sole), store)
                .execute(tenant, "p1", at)
            store.rows.single().ack shouldBe NoticeAcknowledgement("user-ack-1", sole)
            store.rows
                .single()
                .window.days shouldBe 12
        }
    }

    @Test
    fun `INV-AC-26255 the source is asked for the entity of the registry record and two entities differ`() {
        val source = Source(days = 9, ack = "user-ack-1")
        val use = RecordRetentionNotice(registry, source, Store())
        use.execute(tenant, "p1", at)
        use.execute(tenant, "p2", at)
        source.askedEntities shouldBe listOf("E1", "E2")
    }

    @Test
    fun `INV-AC-26255 an unknown partition gives NOT_FOUND and no row`() {
        val store = Store()
        RecordRetentionNotice(registry, Source(days = 9, ack = "u"), store).execute(tenant, "nope", at) shouldBe
            RecordRetentionNoticeResult.NOT_FOUND
        store.rows shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26255 a later change of the fixture value leaves the stored row unchanged`() {
        val source = Source(days = 41)
        val store = Store()
        RecordRetentionNotice(registry, source, store).execute(tenant, "p1", at)
        source.days = 73
        source.ack = "user-ack-2"
        store.rows
            .single()
            .window.days shouldBe 41
        store.rows.single().ack shouldBe null
    }

    @Test
    fun `INV-AC-26255 two partitions under two fixture values keep their own values`() {
        val source = Source(days = 21)
        val store = Store()
        val use = RecordRetentionNotice(registry, source, store)
        use.execute(tenant, "p1", at)
        source.days = 55
        source.ack = "user-ack-3"
        use.execute(tenant, "p2", at)
        store.rows.map { Triple(it.partition, it.window.days, it.ack?.userId) } shouldBe
            listOf(Triple("p1", 21, null), Triple("p2", 55, "user-ack-3"))
    }
}
