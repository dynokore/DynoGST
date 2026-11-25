package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.SchematronLayerOneCheck
import com.dynokore.gst.payload.adapters.packs.JooqRulePackReplica
import com.dynokore.gst.payload.adapters.schematron.CompiledPackCache
import com.dynokore.gst.payload.application.build.LayerOneCheck
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The production layer-one check of the build (INV-AC-27232): the build bean no longer falls back to NOT_WIRED. */
@Configuration(proxyBeanMethods = false)
class LayerOneConfiguration {
    @Bean
    fun layerOneCheck(
        cache: CompiledPackCache,
        rules: JooqRulePackReplica,
    ): LayerOneCheck = SchematronLayerOneCheck(cache) { rules.active(it) }
}
