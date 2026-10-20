package de.littletrotter.shared.receipt

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.ReceiptDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderedReceiptsTest {

    private fun item(positionId: String, vararg receipts: ReceiptDto) =
        InvoiceItemDto(positionId = positionId, taxRatePercent = BigDecimal("19"), receipts = receipts.toList())

    private fun invoice(receipts: List<ReceiptDto>, vararg items: InvoiceItemDto) =
        InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-1"), receipts = receipts, items = items.toList())

    @Test
    fun `invoice level comes first, then positions, duplicates appear once with all positions`() {
        val shared = ReceiptDto("shared.pdf", "k/shared")
        val result = invoice(
            listOf(ReceiptDto("inv.pdf", "k/inv"), shared),
            item("1", ReceiptDto("p1.pdf", "k/p1"), shared),
            item("", shared, ReceiptDto("p2.pdf", "k/p2", description = "Taxi")),
            item("3", ReceiptDto("p1.pdf", "k/p1")),
        ).orderedDistinctReceipts()

        assertEquals(listOf("inv.pdf", "shared.pdf", "p1.pdf", "p2.pdf"), result.map { it.fileName })
        assertEquals(listOf("1", "2"), result[1].positions)
        assertTrue(result[1].invoiceLevel)
        assertEquals(listOf("1", "3"), result[2].positions)
        assertEquals(false, result[2].invoiceLevel)
        assertEquals("Taxi", result[3].description)
    }

    @Test
    fun `repeating a receipt on the same level adds nothing, and blank names are dropped`() {
        val r = ReceiptDto("a.pdf", "k/a")
        val result = invoice(listOf(r, r, ReceiptDto("  ", "k/x")), item("1", r, r)).orderedDistinctReceipts()

        assertEquals(1, result.size)
        assertEquals(listOf("1"), result.single().positions)
    }

    @Test
    fun `a receipt without a key is identified by its file name`() {
        val result = invoice(listOf(ReceiptDto("a.pdf", null), ReceiptDto("a.pdf", " "))).orderedDistinctReceipts()
        assertEquals(1, result.size)
        assertEquals(null, result.single().objectKey)
    }

    @Test
    fun `the unavailable exception message names the receipt and the cause`() {
        val ex = ReceiptUnavailableException("a.pdf", detail = "weg")
        assertEquals("Beleg \"a.pdf\" konnte nicht in das Dokument übernommen werden: weg. Der Export wurde abgebrochen.", ex.userMessage)
        assertTrue(ReceiptUnavailableException("b.pdf").userMessage.startsWith("Beleg \"b.pdf\""))
    }

    private fun storeThrowing(failure: Throwable) = object : de.littletrotter.shared.DocumentStore {
        override fun put(objectKey: String, data: ByteArray, contentType: String) = Unit
        override fun get(objectKey: String): ByteArray = throw failure
        override fun exists(objectKey: String) = false
        override fun enableImmutability(objectKey: String) = Unit
        override fun delete(objectKey: String) = Unit
    }

    @Test
    fun `readReceipt returns the stored bytes`() {
        val store = object : de.littletrotter.shared.DocumentStore by storeThrowing(IllegalStateException()) {
            override fun get(objectKey: String): ByteArray = byteArrayOf(1, 2)
        }
        assertEquals(listOf<Byte>(1, 2), store.readReceipt("a.pdf", "k/a").toList())
    }

    @Test
    fun `readReceipt turns every storage failure into the receipt exception`() {
        val failures = listOf(
            software.amazon.awssdk.core.exception.SdkClientException.create("net"),
            java.io.IOException("io"),
            java.io.UncheckedIOException(java.io.IOException("io")),
            NoSuchElementException("none"),
            IllegalStateException("state"),
        )
        failures.forEach { failure ->
            val ex = org.junit.jupiter.api.Assertions.assertThrows(ReceiptUnavailableException::class.java) {
                storeThrowing(failure).readReceipt("a.pdf", "k/a")
            }
            assertTrue(ex.userMessage.contains("Dokumentenspeicher"), ex.userMessage)
            assertEquals(failure, ex.cause)
        }
    }

    @Test
    fun `readReceipt rejects a missing or blank key`() {
        listOf(null, " ").forEach { key ->
            val ex = org.junit.jupiter.api.Assertions.assertThrows(ReceiptUnavailableException::class.java) {
                storeThrowing(IllegalStateException()).readReceipt("a.pdf", key)
            }
            assertTrue(ex.userMessage.contains("kein Speicherort"), ex.userMessage)
        }
    }

    @Test
    fun `the raster kind recorded at upload is carried to the ordered receipt, unknown names are ignored`() {
        val result = invoice(
            listOf(
                ReceiptDto("a.pdf", "k/a", rasterKind = "IMAGE_PDF"),
                ReceiptDto("b.pdf", "k/b", rasterKind = "SOMETHING_NEW"),
                ReceiptDto("c.pdf", "k/c"),
            ),
        ).orderedDistinctReceipts()

        assertEquals(listOf(ReceiptRasterKind.IMAGE_PDF, null, null), result.map { it.rasterKind })
    }
}
