package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OutputIntentSubjectTest {

    private fun invoice(seller: PartyDto?) =
        InvoiceDto(invoiceNumber = InvoiceNumberDto("INV-2026-00001"), senderProfile = seller)

    @Test
    fun `spellings of the same VAT-ID normalise to identical bytes`() {
        assertEquals("DE123456789", normaliseOutputIntentSubject("de 123-456/789"))
        assertEquals(normaliseOutputIntentSubject("DE123456789"), normaliseOutputIntentSubject(" d e.123_456–789 "))
    }

    @Test
    fun `decomposed and composed characters normalise identically`() {
        assertEquals(normaliseOutputIntentSubject("Müller"), normaliseOutputIntentSubject("Müller"))
    }

    @Test
    fun `the VAT-ID wins over tax number and name`() {
        val seller = PartyDto(name = "Acme", vatId = "de 123", taxNumber = "12/345")
        assertEquals("DE123", resolveOutputIntentSubject(invoice(seller)))
    }

    @Test
    fun `the tax number wins over the name when no VAT-ID exists`() {
        val seller = PartyDto(name = "Acme", vatId = " ", taxNumber = "12/345/678")
        assertEquals("12345678", resolveOutputIntentSubject(invoice(seller)))
    }

    @Test
    fun `the name is the last resort`() {
        assertEquals("ACMEGMBH", resolveOutputIntentSubject(invoice(PartyDto(name = "Acme GmbH"))))
    }

    @Test
    fun `a value that is only separators counts as blank`() {
        val seller = PartyDto(name = "Acme", vatId = "- / .")
        assertEquals("ACME", resolveOutputIntentSubject(invoice(seller)))
    }

    @Test
    fun `no identity at all yields null`() {
        assertNull(resolveOutputIntentSubject(invoice(PartyDto(name = " ", vatId = "", taxNumber = null))))
        assertNull(resolveOutputIntentSubject(invoice(null)))
    }
}
