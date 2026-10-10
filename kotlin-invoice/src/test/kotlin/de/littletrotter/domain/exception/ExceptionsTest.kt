package de.littletrotter.domain.exception

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExceptionsTest {

    /**
     * [InvoiceWithoutSenderProfileException] exists so no fabricated seller id ends up in the
     * message. It must name the invoice -- and when even that has no id (an unpersisted draft),
     * say so honestly instead of interpolating a raw `null` into the text.
     */
    @Test
    fun `InvoiceWithoutSenderProfileException benennt die Rechnung, und ohne id sagt sie das`() {
        val mitId = InvoiceWithoutSenderProfileException(42L)
        assertTrue(mitId.message!!.contains("42"), "die Meldung muss die Rechnung nennen: ${mitId.message}")
        assertTrue(!mitId.message!!.contains("null"), "keine rohe null im Text: ${mitId.message}")

        val ohneId = InvoiceWithoutSenderProfileException(null)
        assertTrue(ohneId.message!!.contains("unpersisted"), "ohne id wird das benannt: ${ohneId.message}")
        assertTrue(!ohneId.message!!.contains("null"), "auch hier keine rohe null: ${ohneId.message}")
    }

    @Test
    fun `not found exceptions message and type`() {
        val ex1 = InvoiceNotFoundException(42)
        assertTrue(ex1.message!!.contains("Invoice not found: 42"))

        val ex2 = InvoiceItemNotFoundException(7)
        assertTrue(ex2.message!!.contains("Invoice item not found: 7"))
    }

    @Test
    fun `transition and validation exceptions`() {
        val t = InvoiceStatusTransitionException("A", "B")
        assertTrue(t.message!!.contains("Cannot transition invoice from A to B"))

        val v = InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING, message = "bad")
        assertEquals("bad", v.message)
    }
}
