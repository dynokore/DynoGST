package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun version(
    entity: String,
    constitution: String,
    from: String,
) = TripleVersion(
    EntityRef(entity),
    Constitution(constitution),
    IdentityTriple("0195:SG-$entity-$constitution", "SUP-$from", "201912345A"),
    LocalDate.parse(from),
)

/** The repository and its transaction: a read outside the transaction fails, and so does every save. */
private class ReadOnlyTriples(
    private val stored: List<TripleVersion>,
) : TripleRepository,
    TransactionRunner {
    private var open = false
    var transactions = 0
        private set
    var reads = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        check(!open) { "a transaction is already open" }
        open = true
        transactions++
        try {
            return block()
        } finally {
            open = false
        }
    }

    override fun versions(
        entity: EntityRef,
        constitution: Constitution,
    ): List<TripleVersion> = error("the tenant-wide read does not read one pair")

    override fun allVersions(): List<TripleVersion> {
        check(open) { "read outside the transaction" }
        reads++
        return stored
    }

    override fun save(
        version: TripleVersion,
        proposedBy: PersonRef,
        confirmedBy: PersonRef,
    ): Unit = error("a read saves nothing")
}

/** 2026-03-31 in UTC and 2026-04-01 in Asia/Singapore. */
private val clock = Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneOffset.UTC)

private fun read(triples: ReadOnlyTriples) = ReadActiveIdentityTriples(triples, triples, clock).handle()

class ReadActiveIdentityTriplesTest {
    @Test
    fun `INV-AC-29192 returns only the version of each entity and constitution in force on the Singapore date`() {
        val triples =
            ReadOnlyTriples(
                listOf(
                    version("E-002", "PTE_LTD", "2026-12-01"),
                    version("E-001", "PTE_LTD", "2026-12-01"),
                    version("E-001", "PTE_LTD", "2026-04-01"),
                    version("E-001", "PTE_LTD", "2026-01-01"),
                    version("E-001", "PARTNERSHIP", "2026-02-01"),
                ),
            )
        assertEquals(
            listOf(
                version("E-001", "PARTNERSHIP", "2026-02-01"),
                version("E-001", "PTE_LTD", "2026-04-01"),
            ),
            read(triples),
        )
        assertEquals(1, triples.transactions)
        assertEquals(1, triples.reads)
    }

    @Test
    fun `INV-AC-29192 orders by entity reference before constitution code`() {
        val triples =
            ReadOnlyTriples(
                listOf(
                    version("E-002", "PARTNERSHIP", "2026-01-01"),
                    version("E-001", "PTE_LTD", "2026-01-01"),
                    version("E-002", "LLP", "2026-01-01"),
                ),
            )
        assertEquals(
            listOf(
                version("E-001", "PTE_LTD", "2026-01-01"),
                version("E-002", "LLP", "2026-01-01"),
                version("E-002", "PARTNERSHIP", "2026-01-01"),
            ),
            read(triples),
        )
    }

    @Test
    fun `INV-AC-29192 an empty repository gives an empty list`() {
        assertEquals(emptyList<TripleVersion>(), read(ReadOnlyTriples(emptyList())))
    }

    @Test
    fun `INV-AC-29192 the repository fake refuses a save and a read outside its transaction`() {
        val triples = ReadOnlyTriples(emptyList())
        val any = version("E-001", "PTE_LTD", "2026-01-01")
        assertThrows(IllegalStateException::class.java) {
            triples.inTransaction { triples.save(any, PersonRef("proposer"), PersonRef("confirmer")) }
        }
        assertThrows(IllegalStateException::class.java) { triples.allVersions() }
        assertEquals(emptyList<TripleVersion>(), read(triples))
    }
}
