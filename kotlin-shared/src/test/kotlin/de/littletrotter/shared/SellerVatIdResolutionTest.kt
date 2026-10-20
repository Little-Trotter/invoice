package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * RCG012-2026-08-00001 — direct branch-level tests for [findSellerVatRegistrationByCountry] and
 * [resolveActiveSellerVatId], complementing the higher-level proofs already exercised indirectly
 * through `MustangExportModelMapperTest`.
 */
class SellerVatIdResolutionTest {

    private val luRecipient = PartyDto(name = "Kunde", country = "LU")

    // ── findSellerVatRegistrationByCountry ──────────────────────────────────

    @Test
    fun `a null registration country yields no match`() {
        val sender = PartyDto(name = "Seller", additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")))
        assertNull(findSellerVatRegistrationByCountry(sender, null))
    }

    @Test
    fun `a blank registration country yields no match`() {
        val sender = PartyDto(name = "Seller", additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")))
        assertNull(findSellerVatRegistrationByCountry(sender, "   "))
    }

    @Test
    fun `a null senderProfile yields no match even with a real country`() {
        assertNull(findSellerVatRegistrationByCountry(null, "LU"))
    }

    @Test
    fun `a country with no matching registration yields no match`() {
        val sender = PartyDto(name = "Seller", additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")))
        assertNull(findSellerVatRegistrationByCountry(sender, "AT"))
    }

    @Test
    fun `a matching registration is found case-insensitively and trimmed`() {
        val sender = PartyDto(name = "Seller", additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")))
        val match = findSellerVatRegistrationByCountry(sender, "  lu  ")
        assertEquals("LU12345678", match?.vatId)
    }

    // ── resolveActiveSellerVatId ─────────────────────────────────────────────

    @Test
    fun `no senderProfile and no registration means no seller vat id at all`() {
        assertNull(resolveActiveSellerVatId(null, InvoiceMetaDto(), null))
    }

    @Test
    fun `a blank home vatId with no matching registration resolves to null`() {
        val sender = PartyDto(name = "Seller", vatId = "   ")
        assertNull(resolveActiveSellerVatId(sender, InvoiceMetaDto(), null))
    }

    @Test
    fun `a real home vatId with no registration country set is returned unchanged`() {
        val sender = PartyDto(name = "Seller", vatId = "DE123456789")
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, InvoiceMetaDto(), null))
    }

    @Test
    fun `a matching registration whose own vatId is blank falls back to the home vatId`() {
        val sender = PartyDto(
            name = "Seller", vatId = "DE123456789",
            additionalVatRegistrations = listOf(VatRegistrationDto("LU", "   ")),
        )
        val meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU")
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, meta, luRecipient))
    }

    @Test
    fun `a matching registration replaces the home vatId when the recipient sits in that country`() {
        val sender = PartyDto(
            name = "Seller", vatId = "DE123456789",
            additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
        )
        val meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU")
        assertEquals("LU12345678", resolveActiveSellerVatId(sender, meta, luRecipient))
    }

    @Test
    fun `a registration with a recipient in another country or without a country keeps the home vatId`() {
        val sender = PartyDto(
            name = "Seller", vatId = "DE123456789",
            additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
        )
        val meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU")
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, meta, PartyDto(name = "Kunde", country = "FR")))
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, meta, PartyDto(name = "Kunde")))
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, meta, null))
    }

    // ── effectiveSellerRegistrationCountry ───────────────────────────────────

    @Test
    fun `effectiveness requires equal countries, ignoring case and whitespace`() {
        assertEquals("LU", effectiveSellerRegistrationCountry(" LU ", "lu"))
    }

    @Test
    fun `a mismatch, a missing registration or a missing recipient country is not effective`() {
        assertNull(effectiveSellerRegistrationCountry("LU", "FR"))
        assertNull(effectiveSellerRegistrationCountry(null, "LU"))
        assertNull(effectiveSellerRegistrationCountry("  ", "LU"))
        assertNull(effectiveSellerRegistrationCountry("LU", null))
        assertNull(effectiveSellerRegistrationCountry("LU", "  "))
    }
}
