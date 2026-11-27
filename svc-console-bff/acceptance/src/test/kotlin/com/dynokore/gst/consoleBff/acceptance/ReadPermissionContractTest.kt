package com.dynokore.gst.consoleBff.acceptance

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** INV-AC-20321: the read-permission table covers every GET operation of the pinned contract (OWASP ASVS V4.1.1). */
class ReadPermissionContractTest {
    private data class Op(
        val operationId: String,
        val path: String,
    )

    private val openapi = File(File(System.getProperty("gst.contract.dir")), "openapi.yaml").readText()

    /** The GET operations of an OpenAPI text by operationId. */
    private fun getOperations(spec: String): List<Op> {
        val paths = (Yaml().load<Map<String, Any?>>(spec)["paths"] as? Map<*, *>).orEmpty()
        return paths.entries.flatMap { (path, item) ->
            (item as? Map<*, *>)
                .orEmpty()
                .entries
                .filter { (it.key as? String)?.lowercase() == "get" }
                .mapNotNull {
                    ((it.value as? Map<*, *>)?.get("operationId") as? String)?.let { id ->
                        Op(id, path as String)
                    }
                }
        }
    }

    private fun allKeys(): Set<String> = DeploymentForm.entries.flatMap { OperationRoles.keys(it) }.toSet()

    /** Every offence of the criterion, each naming the offender; empty when the table covers the contract. */
    private fun findings(
        contract: List<Op>,
        table: List<CatalogueReadPermissions.Entry>,
        domainTable: List<CatalogueReadPermissions.DomainEntry>,
        unmapped: Set<String>,
        keys: Set<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        val contractIds = contract.map { it.operationId }.toSet()
        val tableIds = (table.map { it.operationId } + domainTable.map { it.operationId }).toSet()
        (contractIds - tableIds - unmapped).sorted().forEach {
            out +=
                "GET operation not in the table or the unmapped list: $it"
        }
        (tableIds - contractIds).sorted().forEach { out += "table entry is not a GET operation of the contract: $it" }
        (unmapped - contractIds).sorted().forEach {
            out += "unmapped entry is not a GET operation of the contract: $it"
        }
        (unmapped.intersect(tableIds)).sorted().forEach { out += "operation both in the table and unmapped: $it" }
        table.forEach { e ->
            val op = contract.firstOrNull { it.operationId == e.operationId }
            if (op != null && (e.method != "GET" || e.pathTemplate != op.path)) {
                out += "table entry ${e.operationId} is ${e.method} ${e.pathTemplate}, contract has GET ${op.path}"
            }
            if (e.permissionKey !in
                keys
            ) {
                out += "table key of ${e.operationId} is not listed by the generated map: ${e.permissionKey}"
            }
        }
        out += domainFindings(contract, domainTable, keys)
        return out
    }

    /** The offences of the domain-keyed entries: route, and every key of every domain against the generated map. */
    private fun domainFindings(
        contract: List<Op>,
        domainTable: List<CatalogueReadPermissions.DomainEntry>,
        keys: Set<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        domainTable.forEach { e ->
            val op = contract.firstOrNull { it.operationId == e.operationId }
            if (op != null && (e.method != "GET" || e.pathTemplate != op.path)) {
                out += "domain entry ${e.operationId} is ${e.method} ${e.pathTemplate}, contract has GET ${op.path}"
            }
            e.keysByDomain.forEach { (domain, key) ->
                if (key !in keys) {
                    out += "domain key of ${e.operationId} for $domain is not listed by the generated map: $key"
                }
            }
        }
        return out
    }

    private fun real() =
        findings(getOperations(openapi), CatalogueReadPermissions.ENTRIES, DOMAINS, EXPECTED_UNMAPPED, allKeys())

    @Test
    fun `INV-AC-20321 the table covers every GET operation of the pinned contract with listed keys`() {
        val offences = real()
        check(offences.isEmpty()) { offences.joinToString("; ") }
        check(getOperations(openapi).isNotEmpty()) { "the pinned contract lists no GET operation" }
    }

    @Test
    fun `INV-AC-20321 a contract GET operation absent from the table and the unmapped list fails`() {
        val extra =
            openapi.replaceFirst(
                "paths:",
                "paths:\n  /v1/probe:\n    get:\n      operationId: probeOperation\n" +
                    "      responses:\n        '200':\n          description: ok",
            )
        val offences =
            findings(getOperations(extra), CatalogueReadPermissions.ENTRIES, DOMAINS, EXPECTED_UNMAPPED, allKeys())
        check(offences == listOf("GET operation not in the table or the unmapped list: probeOperation")) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a table entry that is not a GET operation of the contract fails`() {
        val post = CatalogueReadPermissions.Entry("GET", "/v1/exceptions", "overrideException", "exception.view")
        val offences =
            findings(
                getOperations(openapi),
                CatalogueReadPermissions.ENTRIES + post,
                DOMAINS,
                EXPECTED_UNMAPPED,
                allKeys(),
            )
        check(offences.any { it.contains("not a GET operation of the contract: overrideException") }) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a path template different from the contract fails`() {
        val moved = CatalogueReadPermissions.ENTRIES.map { it.copy(pathTemplate = it.pathTemplate + "/") }
        val offences = findings(getOperations(openapi), moved, DOMAINS, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("contract has GET") }) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a key one character off or absent from the generated map fails`() {
        val off = CatalogueReadPermissions.ENTRIES.map { it.copy(permissionKey = it.permissionKey + "s") }
        val offences = findings(getOperations(openapi), off, DOMAINS, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("exception.views") }) { "$offences" }
    }

    @Test
    fun `INV-AC-26281 GET v1 portfolio maps to period-readiness view and is no longer unmapped`() {
        val portfolio =
            CatalogueReadPermissions.ENTRIES.filter {
                it.operationId == "getPortfolio" || it.pathTemplate == "/v1/portfolio"
            }
        check(portfolio.size == 1) { "expected exactly one portfolio entry: $portfolio" }
        val e = portfolio.single()
        check(e.method == "GET") { "method: ${e.method}" }
        check(e.pathTemplate == "/v1/portfolio") { "path template: ${e.pathTemplate}" }
        check(e.operationId == "getPortfolio") { "operation id: ${e.operationId}" }
        check(e.permissionKey == "period-readiness.view") { "key: ${e.permissionKey}" }
        check("period-readiness.view" in allKeys()) { "key not in the generated map of the pinned catalogue" }
        check("getPortfolio" !in EXPECTED_UNMAPPED) { "getPortfolio is still listed as unmapped" }
    }

    @Test
    fun `INV-AC-27035 the real table decides previewConfigurationPromotion by domain and the contract passes`() {
        check("previewConfigurationPromotion" !in EXPECTED_UNMAPPED) { "still listed as unmapped" }
        val e = DOMAINS.single { it.operationId == "previewConfigurationPromotion" }
        check(e.method == "GET") { "method: ${e.method}" }
        check(e.pathTemplate == "/bff/configuration-bundles/{bundleId}/promotion-diff") { "path: ${e.pathTemplate}" }
        check(e.domainParameter == "domain") { "parameter: ${e.domainParameter}" }
        check(
            e.keysByDomain ==
                mapOf("mapping-pack" to "mapping-pack.author", "identity-triple" to "entity-config.manage"),
        ) { "keys: ${e.keysByDomain}" }
        check(
            CatalogueReadPermissions.ENTRIES.none {
                it.operationId == e.operationId ||
                    it.pathTemplate == e.pathTemplate
            },
        ) {
            "the route is also in ENTRIES"
        }
        val offences = real()
        check(offences.isEmpty()) { offences.joinToString("; ") }
    }

    @Test
    fun `INV-AC-27035 a domain key one character off the generated map fails by naming that key`() {
        val off =
            DOMAINS.map { e ->
                e.copy(keysByDomain = e.keysByDomain + ("identity-triple" to "entity-config.manages"))
            }
        val offences =
            findings(getOperations(openapi), CatalogueReadPermissions.ENTRIES, off, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("entity-config.manages") }) { "$offences" }
        check(offences.size == 1) { "$offences" }
    }

    @Test
    fun `INV-AC-27605 the pinned contract and catalogue hold the route and its key`() {
        check("getEntityRenditionEvidence" !in EXPECTED_UNMAPPED) { "still listed as unmapped" }
        val e = CatalogueReadPermissions.ENTRIES.single { it.operationId == "getEntityRenditionEvidence" }
        check(e.method == "GET") { "method: ${e.method}" }
        check(e.pathTemplate == "/bff/entities/{entityRef}/rendition-evidence") { "path: ${e.pathTemplate}" }
        check(e.permissionKey == "rendition-evidence.view") { "key: ${e.permissionKey}" }
        check(e.entityPathVariable == "entityRef") { "entity variable: ${e.entityPathVariable}" }
        check("rendition-evidence.view" in allKeys()) { "key not in the generated map of the pinned catalogue" }
        check(real().isEmpty()) { "${real()}" }
        val others = CatalogueReadPermissions.ENTRIES.filter { it.operationId != "getEntityRenditionEvidence" }
        check(findings(getOperations(openapi), others, DOMAINS, EXPECTED_UNMAPPED, allKeys()).isNotEmpty()) {
            "entry removed went unnoticed"
        }
        val variants =
            listOf(
                e.copy(permissionKey = "rendition-evidence.view "),
                e.copy(permissionKey = "rendition-evidences.view"),
                e.copy(pathTemplate = "/bff/entities/{entityRef}/rendition-evidences"),
            )
        for (v in variants) {
            val table = others + v
            check(findings(getOperations(openapi), table, DOMAINS, EXPECTED_UNMAPPED, allKeys()).isNotEmpty()) {
                "variant passed: $v"
            }
        }
    }

    @Test
    fun `INV-AC-27783 the route of getRenditionSampleContent is mapped to its key at entity scope`() {
        val template = "/bff/entities/{entityRef}/rendition-sample-checks/{checkId}/content"
        check("getRenditionSampleContent" !in EXPECTED_UNMAPPED) { "listed as unmapped" }
        val e = CatalogueReadPermissions.ENTRIES.single { it.operationId == "getRenditionSampleContent" }
        check(
            e ==
                CatalogueReadPermissions.Entry(
                    "GET",
                    template,
                    "getRenditionSampleContent",
                    "rendition-evidence.view",
                    entityPathVariable = "entityRef",
                ),
        ) { "entry: $e" }
        check(real().isEmpty()) { "${real()}" }
        check(ask("keyFor", "GET", template) == "rendition-evidence.view") { "key of the template" }
        check(ask("entityPathVariableFor", "GET", template) == "entityRef") { "entity variable" }
        check(ask("entityParameterFor", "GET", template) == null) { "a query parameter names the entity" }
        check(ask("admitsAnyEntityFor", "GET", template) == false) { "admits any entity" }
        val path = "/bff/entities/ENT-0001/rendition-sample-checks/chk-0001/content"
        check(ask("keyFor", "GET", path) == "rendition-evidence.view") { "key of a path" }
        check(ask("entityRefFor", "GET", path) == "ENT-0001") { "entity of a path" }
        for (other in listOf(template.removeSuffix("/content"), "$template/")) {
            check(ask("keyFor", "GET", other) == null) { "mapped: $other" }
            check(ask("entityRefFor", "GET", other) == null) { "entity taken: $other" }
        }
        check(ask("keyFor", "POST", template) == null) { "mapped for POST" }
    }

    /**
     * One lookup in the real table. It is called by reflection: the table implements a port of the application module,
     * which is not on the compile classpath of this module.
     */
    private fun ask(
        lookup: String,
        method: String,
        path: String,
    ): Any? =
        CatalogueReadPermissions::class.java
            .getMethod(lookup, String::class.java, String::class.java)
            .invoke(CatalogueReadPermissions::class.java.getConstructor().newInstance(), method, path)

    @Test
    fun `INV-AC-27783 the contract check fails on the sample content entry removed or changed`() {
        val e = CatalogueReadPermissions.ENTRIES.single { it.operationId == "getRenditionSampleContent" }
        val others = CatalogueReadPermissions.ENTRIES.filter { it != e }
        val removed = findings(getOperations(openapi), others, DOMAINS, EXPECTED_UNMAPPED, allKeys())
        check(removed == listOf("GET operation not in the table or the unmapped list: getRenditionSampleContent")) {
            "$removed"
        }
        val variants =
            listOf(
                e.copy(permissionKey = "rendition-evidence.view "),
                e.copy(permissionKey = "rendition-evidence.views"),
                e.copy(pathTemplate = e.pathTemplate.removeSuffix("/content")),
                e.copy(pathTemplate = e.pathTemplate + "/"),
            )
        for (v in variants) {
            val offences = findings(getOperations(openapi), others + v, DOMAINS, EXPECTED_UNMAPPED, allKeys())
            check(offences.size == 1 && "getRenditionSampleContent" in offences.single()) { "$v: $offences" }
        }
    }

    private companion object {
        val DOMAINS: List<CatalogueReadPermissions.DomainEntry> = CatalogueReadPermissions.DOMAIN_ENTRIES

        /** GET operations of the contract that have no permission decision yet (the filter refuses their routes). */
        val EXPECTED_UNMAPPED: Set<String> =
            setOf(
                "getExceptionDecisionTrace",
                "getOverridePreview",
                "searchDocuments",
                "compareDocumentVersions",
            )
    }
}
