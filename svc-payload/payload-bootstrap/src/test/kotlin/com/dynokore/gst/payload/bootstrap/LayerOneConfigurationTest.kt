package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.SchematronLayerOneCheck
import com.dynokore.gst.payload.adapters.packs.JooqRulePackReplica
import com.dynokore.gst.payload.adapters.schematron.CompiledPackCache
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.LayerOneCheck
import com.dynokore.gst.payload.application.build.NOT_WIRED
import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

class LayerOneConfigurationTest {
    @Test
    fun `INV-AC-29337 the Schematron pack type of the build equals that of the layer-one adapter`() {
        BuildPayload.SCHEMATRON_PACK_TYPE shouldBe SchematronLayerOneCheck.SCHEMATRON
    }

    @Test
    fun `INV-AC-27232 the layer-one bean is the SchematronLayerOneCheck and not NOT_WIRED`() {
        ApplicationContextRunner()
            .withUserConfiguration(LayerOneConfiguration::class.java)
            .withBean(
                CompiledPackCache::class.java,
                {
                    CompiledPackCache(
                        SchematronRunner(),
                        object : StagedPackSource {
                            override fun fetch(packVersion: String) = null
                        },
                        SchematronCompiler { ByteArray(0) },
                    )
                },
            ).withBean(
                JooqRulePackReplica::class.java,
                { JooqRulePackReplica(DSL.using(SQLDialect.POSTGRES)) { UUID.randomUUID() } },
            ).run { ctx ->
                val bean = ctx.getBean(LayerOneCheck::class.java)
                bean.shouldBeInstanceOf<SchematronLayerOneCheck>()
                (bean === NOT_WIRED) shouldBe false
            }
    }
}
