package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RecordRetentionNoticeTest {
    private val tenant = "00000000-0000-0000-0000-000000000001"
    private val at: Instant = Instant.parse("2026-03-01T10:00:00Z")

    /** The installation value and the acknowledgement are fixtures the case sets; nothing is a constant of the code. */
    private class Source(
        var days: Int,
        var ack: String? = null,
    ) : NoticeWindowSource {
        override fun window(
            tenant: String,
            on: LocalDate,
        ) = NoticeWindow(days)

        override fun acknowledgement(
            tenant: String,
            on: LocalDate,
        ) = ack?.let { NoticeAcknowledgement(it, null) }
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

    @Test
    fun `INV-AC-26255 without an acknowledgement the row holds the fixture window and no user ID`() {
        val source = Source(days = 37)
        val store = Store()
        RecordRetentionNotice(source, store).execute(tenant, "p1", at)
        store.rows
            .single()
            .window.days shouldBe 37
        store.rows.single().ack shouldBe null
        store.rows.single().notifiedAt shouldBe at
    }

    @Test
    fun `INV-AC-26255 one acknowledgement stores its user ID`() {
        val store = Store()
        RecordRetentionNotice(Source(days = 12, ack = "user-ack-1"), store).execute(tenant, "p1", at)
        store.rows
            .single()
            .ack
            ?.userId shouldBe "user-ack-1"
        store.rows
            .single()
            .window.days shouldBe 12
    }

    @Test
    fun `INV-AC-26255 a later change of the fixture value leaves the stored row unchanged`() {
        val source = Source(days = 41)
        val store = Store()
        RecordRetentionNotice(source, store).execute(tenant, "p1", at)
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
        val use = RecordRetentionNotice(source, store)
        use.execute(tenant, "p1", at)
        source.days = 55
        source.ack = "user-ack-3"
        use.execute(tenant, "p2", at)
        store.rows.map { Triple(it.partition, it.window.days, it.ack?.userId) } shouldBe
            listOf(Triple("p1", 21, null), Triple("p2", 55, "user-ack-3"))
    }
}
