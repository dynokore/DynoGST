package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.JooqRenditionSampleStore
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleCheck
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleMetadata
import com.dynokore.gst.payload.application.rendition.RecordRenditionSample
import com.dynokore.gst.payload.application.rendition.RenditionSampleReader
import com.dynokore.gst.payload.application.rendition.RenditionSampleStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The sample store beans, booted with the existing beans (INV-T-27774; INV-AC-27769). */
class RenditionSampleConfigurationTest {
    @Test
    fun `INV-AC-27769 the context starts with one store bean and the two read use cases and no RecordRenditionSample`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(JooqRenditionSampleStore::class.java).size shouldBe 1
            context.getBeansOfType(ReadRenditionSampleCheck::class.java).size shouldBe 1
            context.getBeansOfType(ReadRenditionSampleMetadata::class.java).size shouldBe 1
            context.getBeansOfType(RecordRenditionSample::class.java).size shouldBe 0
            val store = context.getBean(JooqRenditionSampleStore::class.java)
            context.getBean(RenditionSampleStore::class.java) shouldBe store
            context.getBean(RenditionSampleReader::class.java) shouldBe store
        }
    }

    @Test
    fun `INV-AC-27769 ReadRenditionSampleMetadata is built over the same store bean`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offline(dir).run { context ->
            val store = context.getBean(JooqRenditionSampleStore::class.java)
            val use = context.getBean(ReadRenditionSampleMetadata::class.java)
            val field = ReadRenditionSampleMetadata::class.java.getDeclaredField("reader")
            field.isAccessible = true
            (field.get(use) === store) shouldBe true
            val check = ReadRenditionSampleCheck::class.java.getDeclaredField("reader")
            check.isAccessible = true
            (check.get(context.getBean(ReadRenditionSampleCheck::class.java)) === store) shouldBe true
        }
    }
}
