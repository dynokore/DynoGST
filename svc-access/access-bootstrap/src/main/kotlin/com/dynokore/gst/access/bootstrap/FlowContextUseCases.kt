package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.IssueFlowContextOperation
import com.dynokore.gst.access.adapters.token.NimbusFlowContextSigner
import com.dynokore.gst.access.application.flowcontext.FlowContextSigner
import com.dynokore.gst.access.application.flowcontext.IssueFlowContextUseCase
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.contract.api.AuthoriseApi
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The allowed callers of `issueFlowContext`, read from the `x-sync-edge` of the contract as the plugin
 * `gst.contract-server` generates it into `META-INF/gst/sync-edge-callers.tsv` (the file the sync-edge filter of
 * service-runtime enforces first), so no caller name is written in any main source. The line format is
 * `<METHOD><TAB><path template><TAB><callers>`. Fail closed (OWASP ASVS V4.1, deny by default): the start-up fails when
 * no line is for the operation, when lines for it differ, or when its callers are `any`, `any-except:<svc>`, empty or
 * malformed. The parser is this module's own because the `EdgeCallers` type of service-runtime is not part of its
 * published API.
 */
object FlowEntryCallers {
    const val RESOURCE = "META-INF/gst/sync-edge-callers.tsv"
    private const val METHOD = "POST"
    private const val FIELDS = 3
    private const val ANY = "any"
    private const val ANY_EXCEPT = "any-except:"
    private val NAME = Regex("[a-z][a-z-]*")

    /** The callers of the operation in every resource [RESOURCE] of [loader]. */
    fun from(loader: ClassLoader): Set<String> =
        of(
            loader.getResources(RESOURCE).toList().flatMap { url ->
                url
                    .openStream()
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
                    .lines()
                    .filter { it.isNotEmpty() }
            },
        )

    /** The callers of the operation in the raw [lines] of the resource. */
    fun of(
        lines: List<String>,
        path: String = AuthoriseApi.PATH_ISSUE_FLOW_CONTEXT,
    ): Set<String> {
        val fields = lines.map { it.split('\t') }
        check(fields.all { it.size == FIELDS }) { "$RESOURCE: a line needs exactly 3 TAB-separated fields" }
        val mine = fields.filter { it[0] == METHOD && it[1] == path }.map { it[2] }.toSet()
        check(mine.isNotEmpty()) { "$RESOURCE has no line for $METHOD $path" }
        check(mine.size == 1) { "$RESOURCE has differing lines for $METHOD $path" }
        val field = mine.single()
        check(field != ANY && !field.startsWith(ANY_EXCEPT)) { "$RESOURCE: the callers of $METHOD $path are open" }
        val names = field.split(',')
        check(names.all { NAME.matches(it) }) { "$RESOURCE: the callers of $METHOD $path are empty or malformed" }
        check(names.toSet().size == names.size) { "$RESOURCE: a repeated caller for $METHOD $path" }
        return names.toSet()
    }
}

/** TPL-18: the wiring of `issueFlowContext`; the signer shares the signing key and the registry of the tokens. */
@Configuration(proxyBeanMethods = false)
class FlowContextUseCases {
    @Bean
    fun flowContextSigner(
        secrets: SecretStore,
        registry: VerificationKeyRegistry,
        settings: TokenSettings,
        clock: Clock,
    ): FlowContextSigner = NimbusFlowContextSigner(secrets, registry, settings, clock)

    @Bean
    fun issueFlowContextUseCase(
        signer: FlowContextSigner,
        clock: Clock,
        context: ApplicationContext,
    ) = IssueFlowContextUseCase(signer, clock, FlowEntryCallers.from(checkNotNull(context.classLoader)))

    @Bean
    fun issueFlowContextOperation(
        useCase: IssueFlowContextUseCase,
        serviceCaller: ServiceCaller,
    ) = IssueFlowContextOperation(useCase, serviceCaller)
}
