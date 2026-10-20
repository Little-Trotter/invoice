package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityCheckException
import de.littletrotter.application.invoice.port.outbound.ProbedPage
import de.littletrotter.application.invoice.port.outbound.ReceiptPageProbe
import de.littletrotter.shared.receipt.readability.PageReadability
import de.littletrotter.shared.receipt.readability.ReadabilityPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptReadabilityServiceTest {

    private val preview = byteArrayOf(1, 2, 3)

    private fun serviceWith(vararg pages: ProbedPage) = ReceiptReadabilityService(ReceiptPageProbe { _, _ -> pages.toList() })

    @Test
    fun `only suspect pages are reported with rounded dpi`() {
        val service = serviceWith(
            ProbedPage(PageReadability(1, 150.0, 900.0), null),
            ProbedPage(PageReadability(2, 72.4, 900.0), preview),
        )
        val found = service.findHardToReadPages(ByteArray(0))
        assertEquals(1, found.size)
        assertEquals(2, found.single().pageNumber)
        assertEquals(72, found.single().effectiveDpi)
        assertEquals(preview.toList(), found.single().previewPng.toList())
    }

    @Test
    fun `blur only leaves the dpi open`() {
        val found = serviceWith(ProbedPage(PageReadability(1, 200.0, 5.0), preview)).findHardToReadPages(ByteArray(0))
        assertEquals(200, found.single().effectiveDpi)
    }

    @Test
    fun `a suspect page without a preview is dropped`() {
        assertTrue(serviceWith(ProbedPage(PageReadability(1, 50.0, 900.0), null)).findHardToReadPages(ByteArray(0)).isEmpty())
    }

    @Test
    fun `the probe is asked for the policy page limit`() {
        var asked = 0
        ReceiptReadabilityService(ReceiptPageProbe { _, max -> asked = max; emptyList() }).findHardToReadPages(ByteArray(0))
        assertEquals(ReadabilityPolicy.MAX_PAGES_CHECKED, asked)
    }

    @Test
    fun `a decoding failure of the probe is reported as a failed check`() {
        val failing = ReceiptReadabilityService(ReceiptPageProbe { _, _ -> throw IllegalArgumentException("bad image") })
        val thrown = assertThrows(ReceiptReadabilityCheckException::class.java) { failing.findHardToReadPages(ByteArray(0)) }
        assertTrue(thrown.cause is IllegalArgumentException)
        val stateFailing = ReceiptReadabilityService(ReceiptPageProbe { _, _ -> throw IllegalStateException("closed") })
        assertThrows(ReceiptReadabilityCheckException::class.java) { stateFailing.findHardToReadPages(ByteArray(0)) }
    }
}
