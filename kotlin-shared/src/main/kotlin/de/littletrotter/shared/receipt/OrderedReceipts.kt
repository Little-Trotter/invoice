package de.littletrotter.shared.receipt

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.ReceiptDto
import software.amazon.awssdk.core.exception.SdkException
import java.io.IOException
import java.io.UncheckedIOException

/**
 * One distinct receipt of an invoice together with where it is attached. [positions] holds the
 * position labels of the items it hangs on, [invoiceLevel] says whether it is attached to the
 * invoice as a whole; the same receipt can be both.
 */
data class OrderedReceipt(
    val fileName: String,
    val objectKey: String?,
    val description: String?,
    val positions: List<String>,
    val invoiceLevel: Boolean,
    /** Recorded at upload; `null` when the sender predates it, then every page is rendered. */
    val rasterKind: ReceiptRasterKind? = null,
    /** Lowercase hex SHA-256 the loaded bytes must match; `null` when no checksum is known. */
    val sha256: String? = null,
)

/**
 * The single order and identity rule for a document's receipts, used by the visible receipt pages
 * and by the e-invoice XML attachments so both always list the same receipts in the same order:
 * invoice-level receipts first, then item receipts in position order, each in its list order.
 * A receipt is identified by its object key (file name when it has none); its first occurrence
 * fixes its place, later occurrences only add their position. Entries without a file name carry
 * nothing a reader could identify and are dropped.
 */
fun InvoiceDto.orderedDistinctReceipts(): List<OrderedReceipt> {
    val byIdentity = LinkedHashMap<String, OrderedReceipt>()

    fun add(receipt: ReceiptDto, position: String?) {
        val (fileName, objectKey, description) = receipt
        val name = fileName?.takeIf { it.isNotBlank() } ?: return
        val key = objectKey?.takeIf { it.isNotBlank() }
        val existing = byIdentity[key ?: name]
        byIdentity[key ?: name] = existing?.copy(
            positions = if (position == null || position in existing.positions) existing.positions else existing.positions + position,
            invoiceLevel = existing.invoiceLevel || position == null,
        ) ?: OrderedReceipt(
            fileName = name,
            objectKey = key,
            description = description?.takeIf { it.isNotBlank() },
            positions = listOfNotNull(position),
            invoiceLevel = position == null,
            rasterKind = receipt.rasterKind?.let { runCatching { ReceiptRasterKind.valueOf(it) }.getOrNull() },
        )
    }

    receipts.forEach { add(it, null) }
    items.forEachIndexed { index, item ->
        val label = item.positionId.takeIf { it.isNotBlank() } ?: (index + 1).toString()
        item.receipts.forEach { add(it, label) }
    }
    return byIdentity.values.toList()
}

/**
 * A receipt assigned to a document could not be turned into content. The export stops instead of
 * delivering a document that silently lacks it; [userMessage] is meant to be shown as is.
 */
class ReceiptUnavailableException(
    val fileName: String,
    cause: Throwable? = null,
    detail: String? = null,
) : RuntimeException(
    "Beleg \"$fileName\" konnte nicht in das Dokument übernommen werden" +
        (detail?.let { ": $it" } ?: "") + ". Der Export wurde abgebrochen.",
    cause,
) {
    val userMessage: String get() = message.orEmpty()
}

/**
 * Reads a receipt's bytes for a document and turns every storage read failure into the one
 * [ReceiptUnavailableException], so the visible pages and the XML attachment fail the same way.
 * A missing [objectKey] is a failure too: a receipt without a location cannot be part of the
 * document.
 */
fun DocumentStore.readReceipt(fileName: String, objectKey: String?): ByteArray {
    val key = objectKey?.takeIf { it.isNotBlank() }
        ?: throw ReceiptUnavailableException(fileName, detail = "kein Speicherort bekannt")
    return runCatching { get(key) }.getOrElse { failure ->
        if (failure.isStorageReadFailure()) throw ReceiptUnavailableException(fileName, failure, STORE_DETAIL)
        throw failure
    }
}

private fun Throwable.isStorageReadFailure(): Boolean =
    this is SdkException || this is IOException || this is UncheckedIOException ||
        this is NoSuchElementException || this is IllegalStateException

private const val STORE_DETAIL = "nicht im Dokumentenspeicher lesbar"
