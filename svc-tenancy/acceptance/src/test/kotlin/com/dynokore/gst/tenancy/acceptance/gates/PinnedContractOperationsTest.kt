package com.dynokore.gst.tenancy.acceptance.gates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

class PinnedContractOperationsTest {
    private val root = File(System.getProperty("gst.repo.root"))
    private val contractDir = File(System.getProperty("gst.contract.dir"))

    private fun tableRows(): List<List<String>> =
        File(root, OperationsGate.NAME)
            .readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.split('\t') }

    @Test
    fun `INV-AC-13969 every operation and channel of the pinned contract is listed once with its assigned task`() {
        val operations = OperationsGate.contractOperations(File(contractDir, "openapi.yaml").readText())
        val channels = OperationsGate.contractChannels(File(contractDir, "asyncapi.yaml").readText())
        val rows = tableRows()
        for ((kind, names) in listOf("operation" to operations, "channel" to channels)) {
            for (name in names) {
                val matching = rows.filter { it[0] == name && it[1] == kind }
                matching.size shouldBe 1
                matching.single()[2] shouldBe Assignments.of(kind)[name]
            }
        }
        listOf("loadCredential", "recordSampleApproval", "requestSampleCopy").forEach { name ->
            rows.single { it[0] == name && it[1] == "operation" }[2] shouldBe "INV-T-4932"
        }
    }

    @Test
    @DisplayName(
        "INV-AC-27600 the pinned contract holds listRenditionAttestations " +
            "and the table lists it for the controller slice task",
    )
    fun `INV-AC-27600 pinned contract holds listRenditionAttestations listed for the controller slice`() {
        val name = "listRenditionAttestations"
        val operations = OperationsGate.contractOperations(File(contractDir, "openapi.yaml").readText())
        (name in operations) shouldBe true
        val matching = tableRows().filter { it[0] == name }
        matching.size shouldBe 1
        matching.single()[1] shouldBe "operation"
        matching.single()[2] shouldBe "INV-T-27611"
    }

    @Test
    @DisplayName(
        "INV-AC-28055 the pinned contract holds getSubmissionScope " +
            "and the table lists it for the serving task",
    )
    fun `INV-AC-28055 the pinned contract holds getSubmissionScope and the table lists it for the serving task`() {
        val name = "getSubmissionScope"
        val operations = OperationsGate.contractOperations(File(contractDir, "openapi.yaml").readText())
        (name in operations) shouldBe true
        val matching = tableRows().filter { it[0] == name }
        matching.size shouldBe 1
        matching.single()[1] shouldBe "operation"
        matching.single()[2] shouldBe "INV-T-27013"
    }

    @Test
    @DisplayName(
        "INV-AC-29131 the pinned contract holds listSandboxCertifications " +
            "and the table lists it once for the serving task",
    )
    fun `INV-AC-29131 the pinned contract holds listSandboxCertifications listed once for the serving task`() {
        val name = "listSandboxCertifications"
        val operations = OperationsGate.contractOperations(File(contractDir, "openapi.yaml").readText())
        (name in operations) shouldBe true
        val matching = tableRows().filter { it[0] == name }
        matching.size shouldBe 1
        matching.single()[1] shouldBe "operation"
        matching.single()[2] shouldBe "INV-T-29134"
    }
}
