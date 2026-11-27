package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.BoxAggregator
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.VersionState
import java.math.BigDecimal
import java.util.Base64

/** One stored line; the members the store allows to be null stay nullable, so the use case can refuse them. */
data class StoredPairLine(
    val lineNumber: Int,
    val taxCode: String?,
    val netAmount: BigDecimal?,
    val taxAmount: BigDecimal?,
)

data class StoredPairVersion(
    val version: Int,
    val acknowledged: Boolean,
    val sellerUen: String?,
    val buyerUen: String?,
    val lines: List<StoredPairLine>,
)

/** One record (Doc UUID, entityRef, direction) with every stored version of it. */
data class StoredPairRecord(
    val entityRef: String,
    val direction: Direction,
    val versions: List<StoredPairVersion>,
)

data class StoredPairDocument(
    val docUuid: String,
    val records: List<StoredPairRecord>,
)

/** Read side of the pairing view. It reads one tenant only. */
fun interface PairingRecordsReader {
    /**
     * At most [count] documents, strictly ascending by the canonical lower-case [StoredPairDocument.docUuid], from
     * [fromDocUuid] inclusive (null: from the first). A document qualifies when [entityRef] holds a record of it whose
     * current period is [periodRef]. Each carries every record of that Doc UUID, of any entity of [tenant], whose
     * current period is [periodRef].
     */
    fun documents(
        tenant: String,
        entityRef: String,
        periodRef: String,
        fromDocUuid: String?,
        count: Int,
    ): List<StoredPairDocument>
}

data class PairLine(
    val lineNumber: Int,
    val taxCode: String,
    val netAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

/** The latest effective version of one record and the lines of exactly that version, ascending by line number. */
data class PairSide(
    val entityRef: String,
    val documentVersion: Int,
    val lines: List<PairLine>,
)

data class IntraTenantPair(
    val docUuid: String,
    val supply: PairSide,
    val purchase: PairSide,
)

enum class UnpairedSide {
    SUPPLY,
    PURCHASE,
}

data class IntraTenantUnpaired(
    val docUuid: String,
    val side: UnpairedSide,
    val entry: PairSide,
)

data class IntraTenantPairsView(
    val items: List<IntraTenantPair>,
    val unpaired: List<IntraTenantUnpaired>,
    val nextCursor: String?,
)

/**
 * The informational intra-tenant pairing view of one entity and period (INV-REQ-740, INV-AC-28554). A SALE record and
 * a PURCHASE record of two different entities are a pair only when the Doc UUID, the seller UEN and the buyer UEN are
 * equal and both UENs are present, each taken from the latest effective version. Figures are never compared. Every
 * other record of the requested entity is listed as unpaired. One keyset cursor covers both lists (API-18); a limit
 * outside 1..500 is refused, never clamped. A stored line without tax code or amount fails the whole read.
 */
class ListIntraTenantPairs(
    private val reader: PairingRecordsReader,
) {
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
        limit: Int?,
        cursor: String?,
    ): IntraTenantPairsView {
        val size = limit ?: DEFAULT_LIMIT
        require(size in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val after = cursor?.let(::decode)
        // Every document yields at least one entry. The cursor's own document may have none left, and one entry
        // beyond the page shows that a next page exists: two documents more than the page size always suffice.
        val documents = reader.documents(tenant, entityRef, periodRef, after?.docUuid, size + 2)
        check(documents.zipWithNext().all { (a, b) -> a.docUuid < b.docUuid }) {
            "the pairing documents are not strictly ascending by Doc UUID"
        }
        val rows =
            documents
                .flatMap { entriesOf(it, entityRef) }
                .filter { after == null || it.position > after }
                .sortedBy { it.position }
        val page = rows.take(size)
        return IntraTenantPairsView(
            items = page.mapNotNull { (it as? Entry.Paired)?.pair },
            unpaired = page.mapNotNull { (it as? Entry.Single)?.unpaired },
            nextCursor = if (rows.size > size) encode(page.last().position) else null,
        )
    }

    private fun entriesOf(
        document: StoredPairDocument,
        entityRef: String,
    ): List<Entry> {
        val docUuid = document.docUuid
        check(document.records.distinctBy { it.entityRef to it.direction }.size == document.records.size) {
            "document $docUuid holds one record twice"
        }
        val records = document.records.map { effective(docUuid, it) }
        val own = records.filter { it.side.entityRef == entityRef }
        check(own.isNotEmpty()) { "document $docUuid has no record of the requested entity" }
        val sales = records.filter { it.direction == Direction.SALE }
        val purchases = records.filter { it.direction == Direction.PURCHASE }
        val pairs =
            sales.flatMap { sale ->
                purchases
                    .filter { purchase -> entityRef in setOf(sale.side.entityRef, purchase.side.entityRef) }
                    .filter { purchase -> matches(sale, purchase) }
                    .map { purchase -> sale to purchase }
            }
        val paired = pairs.flatMap { listOf(it.first, it.second) }.toSet()
        return pairs.map { (sale, purchase) ->
            Entry.Paired(
                Position(docUuid, sale.side.entityRef, purchase.side.entityRef),
                IntraTenantPair(docUuid, sale.side, purchase.side),
            )
        } + own.filterNot { it in paired }.map { single(docUuid, it) }
    }

    private fun matches(
        sale: Effective,
        purchase: Effective,
    ): Boolean =
        sale.side.entityRef != purchase.side.entityRef &&
            sale.sellerUen != null &&
            sale.buyerUen != null &&
            sale.sellerUen == purchase.sellerUen &&
            sale.buyerUen == purchase.buyerUen

    private fun single(
        docUuid: String,
        record: Effective,
    ): Entry {
        val entity = record.side.entityRef
        val (side, position) =
            when (record.direction) {
                Direction.SALE -> UnpairedSide.SUPPLY to Position(docUuid, entity, "")
                Direction.PURCHASE -> UnpairedSide.PURCHASE to Position(docUuid, "", entity)
            }
        return Entry.Single(position, IntraTenantUnpaired(docUuid, side, record.side))
    }

    /** The version the reconciliation run counts: the highest acknowledged one, else the highest stored one. */
    private fun effective(
        docUuid: String,
        record: StoredPairRecord,
    ): Effective {
        val states = record.versions.map { VersionState(docUuid, it.version, it.acknowledged) }
        val chosen =
            BoxAggregator.submittedVersions(states)[docUuid]
                ?: BoxAggregator.unsubmittedCurrentVersions(states)[docUuid]
        // Every stored line is checked, not only those of the chosen version.
        val lines = record.versions.associate { it.version to it.lines.map { line -> complete(docUuid, line) } }
        check(lines.size == record.versions.size) { "document $docUuid holds one version twice" }
        val version = checkNotNull(record.versions.find { it.version == chosen }) { "document $docUuid has no version" }
        return Effective(
            direction = record.direction,
            sellerUen = version.sellerUen,
            buyerUen = version.buyerUen,
            side =
                PairSide(
                    record.entityRef,
                    version.version,
                    lines.getValue(version.version).sortedBy { it.lineNumber },
                ),
        )
    }

    private fun complete(
        docUuid: String,
        line: StoredPairLine,
    ): PairLine {
        val where = "line ${line.lineNumber} of document $docUuid"
        return PairLine(
            line.lineNumber,
            checkNotNull(line.taxCode) { "$where has no tax code" },
            checkNotNull(line.netAmount) { "$where has no net amount" },
            checkNotNull(line.taxAmount) { "$where has no tax amount" },
        )
    }

    private fun encode(position: Position): String =
        listOf(position.docUuid, position.supplyEntity, position.purchaseEntity).joinToString(SEPARATOR) {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        }

    private fun decode(cursor: String): Position {
        val parts =
            try {
                cursor.split(SEPARATOR).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("unreadable cursor", e)
            }
        require(parts.size == CURSOR_PARTS && DOC_UUID.matches(parts[0])) { "unreadable cursor" }
        require(parts[1].isNotEmpty() || parts[2].isNotEmpty()) { "unreadable cursor" }
        return Position(parts[0], parts[1], parts[2])
    }

    /** The keyset position of one entry: unique over both lists; an unpaired entry leaves the other entity empty. */
    private data class Position(
        val docUuid: String,
        val supplyEntity: String,
        val purchaseEntity: String,
    ) : Comparable<Position> {
        override fun compareTo(other: Position): Int =
            compareValuesBy(this, other, Position::docUuid, Position::supplyEntity, Position::purchaseEntity)
    }

    private data class Effective(
        val direction: Direction,
        val sellerUen: String?,
        val buyerUen: String?,
        val side: PairSide,
    )

    private sealed interface Entry {
        val position: Position

        data class Paired(
            override val position: Position,
            val pair: IntraTenantPair,
        ) : Entry

        data class Single(
            override val position: Position,
            val unpaired: IntraTenantUnpaired,
        ) : Entry
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
        const val SEPARATOR = "."
        const val CURSOR_PARTS = 3
        val DOC_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
