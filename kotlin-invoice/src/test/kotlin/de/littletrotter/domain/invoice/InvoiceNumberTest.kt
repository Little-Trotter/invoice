package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceNumberTest {

    @Test
    fun `accepts new format`() {
        InvoiceNumber("ABC123-2026-03-00001")
    }

    @Test
    fun `accepts legacy format`() {
        InvoiceNumber("DEV-2026-0001")
    }

    @Test
    fun `rejects invalid format`() {
        assertThrows<InvoiceValidationException> {
            InvoiceNumber("BADFORMAT")
        }
    }

    // Was misreported as INVOICE_NUMBER_MISSING via the deprecated String-only
    // InvoiceValidationException constructor — pin down the *correct* code here.
    @Test
    fun `an invalid format is reported as INVOICE_NUMBER_INVALID_FORMAT`() {
        val ex = assertThrows<InvoiceValidationException> {
            InvoiceNumber("BADFORMAT")
        }
        assertEquals(InvoiceValidationError.INVOICE_NUMBER_INVALID_FORMAT, ex.error)
    }

    // ── InvoiceNumberConverter — the JPA AttributeConverter round-trip ─────────

    @Test
    fun `the converter reads a stored number back into an InvoiceNumber`() {
        val converter = InvoiceNumberConverter()
        val restored = converter.convertToEntityAttribute("ABC123-2026-03-00001")
        assertEquals(InvoiceNumber("ABC123-2026-03-00001"), restored)
    }

    @Test
    fun `the converter reads a null column as no InvoiceNumber at all`() {
        val converter = InvoiceNumberConverter()
        assertEquals(null, converter.convertToEntityAttribute(null))
    }

    @Test
    fun `the converter writes an assigned number out as its plain string value`() {
        val converter = InvoiceNumberConverter()
        assertEquals("ABC123-2026-03-00001", converter.convertToDatabaseColumn(InvoiceNumber("ABC123-2026-03-00001")))
    }

    @Test
    fun `the converter writes a null attribute as a null column, not UNASSIGNED's empty string`() {
        val converter = InvoiceNumberConverter()
        assertEquals(null, converter.convertToDatabaseColumn(null))
    }

    // ── equals() / hashCode() ───────────────────────────────────────────────────

    @Test
    fun `two invoice numbers with the same value are equal`() {
        val num1 = InvoiceNumber("ABC123-2026-03-00001")
        val num2 = InvoiceNumber("ABC123-2026-03-00001")
        assertEquals(num1, num2)
    }

    @Test
    fun `two invoice numbers with different values are not equal`() {
        val num1 = InvoiceNumber("ABC123-2026-03-00001")
        val num2 = InvoiceNumber("ABC123-2026-03-00002")
        assertNotEquals(num1, num2)
    }

    @Test
    fun `an invoice number is not equal to null`() {
        val num = InvoiceNumber("ABC123-2026-03-00001")
        assertNotEquals(num, null)
    }

    @Test
    fun `an invoice number is not equal to a different type`() {
        val num = InvoiceNumber("ABC123-2026-03-00001")
        assertNotEquals(num, "ABC123-2026-03-00001")
    }

    @Test
    fun `unassigned invoice numbers are equal to each other`() {
        val num1 = InvoiceNumber.UNASSIGNED
        val num2 = InvoiceNumber("")
        assertEquals(num1, num2)
    }

    @Test
    fun `equal invoice numbers have the same hash code`() {
        val num1 = InvoiceNumber("ABC123-2026-03-00001")
        val num2 = InvoiceNumber("ABC123-2026-03-00001")
        assertEquals(num1.hashCode(), num2.hashCode())
    }

    @Test
    fun `different invoice numbers produce different hash codes`() {
        val num1 = InvoiceNumber("ABC123-2026-03-00001")
        val num2 = InvoiceNumber("ABC123-2026-03-00002")
        assertNotEquals(num1.hashCode(), num2.hashCode())
    }

    @Test
    fun `invoice number hash code is stable`() {
        val num = InvoiceNumber("ABC123-2026-03-00001")
        val hash1 = num.hashCode()
        val hash2 = num.hashCode()
        assertEquals(hash1, hash2)
    }

    // ── toString() ───────────────────────────────────────────────────────────

    @Test
    fun `happy path - toString returns the plain value`() {
        assertEquals("ABC123-2026-03-00001", InvoiceNumber("ABC123-2026-03-00001").toString())
    }

    @Test
    fun `boundary - toString on an unassigned invoice number returns an empty string`() {
        assertEquals("", InvoiceNumber.UNASSIGNED.toString())
    }
}
