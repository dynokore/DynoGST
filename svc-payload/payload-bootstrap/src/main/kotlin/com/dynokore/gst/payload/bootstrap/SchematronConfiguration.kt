package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.packs.JooqRulePackReplica
import com.dynokore.gst.payload.adapters.packs.RulePackEventsHandler
import com.dynokore.gst.payload.adapters.packs.SchematronActivation
import com.dynokore.gst.payload.adapters.schematron.CompiledPackCache
import com.dynokore.gst.payload.adapters.schematron.SchXslt2Compiler
import com.dynokore.gst.payload.adapters.schematron.StagedSchematronSource
import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.gst.payload.domain.SchematronValidator
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Compiles the staged Schematron pack at activation, in this process, and reads the replica on a cache miss
 * (INV-REQ-776 behaviors 1 and 3, INV-AC-27230). Nothing reaches rules-svc.
 */
@Configuration(proxyBeanMethods = false)
class SchematronConfiguration {
    @Bean
    fun schematronRunner(): SchematronRunner = SchematronRunner()

    @Bean
    fun schematronCompiler(): SchematronCompiler = SchXslt2Compiler()

    @Bean
    fun stagedPackSource(rules: JooqRulePackReplica): StagedPackSource =
        StagedSchematronSource { version -> rules.contentsOfVersion(RulePackEventsHandler.SCHEMATRON_PACK_TYPE, version) }

    @Bean
    fun compiledPackCache(
        runner: SchematronRunner,
        source: StagedPackSource,
        compiler: SchematronCompiler,
    ): CompiledPackCache = CompiledPackCache(runner, source, compiler)

    @Bean
    fun schematronValidator(cache: CompiledPackCache): SchematronValidator = cache

    @Bean
    fun schematronActivation(cache: CompiledPackCache): SchematronActivation =
        SchematronActivation { version, contents -> cache.activate(version, StagedSchematronSource.parse(contents)) }
}
