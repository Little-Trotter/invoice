package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.reference.AllocatedNumber
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.LocalDate

class InvoiceNumberAssignmentTest {

    private val sender = aSenderProfile { id = 3L; invoicePrefix = "RE-" }

    @Test
    fun `the number is drawn for the invoice date, so a New Year's Eve invoice keeps the old year's number`() {
        val silvester = LocalDate.of(2026, 12, 31)
        val numbers = mock<InvoiceNumberService> {
            on { generateNextNumber(any(), eq(silvester), anyOrNull()) } doReturn
                AllocatedNumber("RE", 42, "RE-2026-12-31-00042", "2026")
        }
        val invoice = anInvoice(id = 11L, senderProfile = sender, invoiceDate = silvester)

        InvoiceNumberAssignment(numbers).assignIfNeeded(invoice)

        verify(numbers).generateNextNumber(sender, silvester, 11L)
        assertEquals("RE-2026-12-31-00042", invoice.invoiceNumber.value)
        assertEquals("RE", invoice.invoiceNumberPrefix)
        assertEquals(42L, invoice.invoiceNumberSequence)
    }

    @Test
    fun `an already numbered invoice is never renumbered`() {
        val numbers = mock<InvoiceNumberService>()
        val invoice = anInvoice(
            senderProfile = sender,
            invoiceDate = LocalDate.of(2027, 1, 1),
            invoiceNumber = InvoiceNumber("RE-2026-12-31-00042"),
        )

        InvoiceNumberAssignment(numbers).assignIfNeeded(invoice)

        verify(numbers, never()).generateNextNumber(any(), any(), anyOrNull())
        assertEquals("RE-2026-12-31-00042", invoice.invoiceNumber.value)
    }
}
