package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * RCG012-2026-08-00001 — direct branch-level tests for [findSellerVatRegistrationByCountry] and
 * [resolveActiveSellerVatId], complementing the higher-level proofs already exercised indirectly
 * through `FacturXMetadataMapperTest`/`MustangExportModelMapperTest`.
 */
class SellerVatIdResolutionTest {

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
        assertNull(resolveActiveSellerVatId(null, InvoiceMetaDto()))
    }

    @Test
    fun `a blank home vatId with no matching registration resolves to null`() {
        val sender = PartyDto(name = "Seller", vatId = "   ")
        assertNull(resolveActiveSellerVatId(sender, InvoiceMetaDto()))
    }

    @Test
    fun `a real home vatId with no registration country set is returned unchanged`() {
        val sender = PartyDto(name = "Seller", vatId = "DE123456789")
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, InvoiceMetaDto()))
    }

    @Test
    fun `a matching registration whose own vatId is blank falls back to the home vatId`() {
        val sender = PartyDto(
            name = "Seller", vatId = "DE123456789",
            additionalVatRegistrations = listOf(VatRegistrationDto("LU", "   ")),
        )
        val meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU")
        assertEquals("DE123456789", resolveActiveSellerVatId(sender, meta))
    }
}
