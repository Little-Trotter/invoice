package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * AP-IMP-5 — `InvoiceDetailDtoUiExtensions.kt` had no dedicated test at all: every ordinal
 * decoder's fallback branch, and [InvoiceDetailDto.isReadOnly]'s three-way OR, were only ever
 * exercised incidentally by other tests, never pinned directly.
 */
class InvoiceDetailDtoUiExtensionsTest {

    private fun invoice(status: Int = InvoiceStatus.DRAFT.ordinal, typeCode: Int? = null, currency: Int? = null) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        typeCode = typeCode, currency = currency,
    )

    private fun item(category: String? = null, unit: String? = null, taxCategory: String? = null) = ItemDto(
        id = 1L, positionId = "1", description = "Test", quantity = java.math.BigDecimal.ONE,
        unit = unit, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = category, taxCategory = taxCategory,
    )

    // ── statusEnum / isReadOnly ─────────────────────────────────────────────

    @Test
    fun `statusEnum falls back to DRAFT for an out-of-range ordinal`() {
        assertEquals(InvoiceStatus.DRAFT, invoice(status = 999).statusEnum())
    }

    @Test
    fun `a DRAFT commercial invoice is not read-only`() {
        assertFalse(invoice(status = InvoiceStatus.DRAFT.ordinal).isReadOnly())
    }

    @Test
    fun `an ISSUED invoice is read-only`() {
        assertTrue(invoice(status = InvoiceStatus.ISSUED.ordinal).isReadOnly())
    }

    @Test
    fun `a CANCELLED invoice is read-only`() {
        assertTrue(invoice(status = InvoiceStatus.CANCELLED.ordinal).isReadOnly())
    }

    @Test
    fun `a VOIDED invoice is read-only`() {
        assertTrue(invoice(status = InvoiceStatus.VOIDED.ordinal).isReadOnly())
    }

    @Test
    fun `a DRAFT cancellation document is read-only regardless of its own status`() {
        assertTrue(invoice(status = InvoiceStatus.DRAFT.ordinal, typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal).isReadOnly())
    }

    @Test
    fun `PO decision 1 (2026-09-02) - a DRAFT CREDIT_NOTE is no longer read-only by type code alone`() {
        assertFalse(invoice(status = InvoiceStatus.DRAFT.ordinal, typeCode = InvoiceTypeCode.CREDIT_NOTE.ordinal).isReadOnly())
    }

    // ── typeCodeEnum ─────────────────────────────────────────────────────────

    @Test
    fun `typeCodeEnum falls back to COMMERCIAL_INVOICE when null`() {
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, invoice(typeCode = null).typeCodeEnum())
    }

    @Test
    fun `typeCodeEnum falls back to COMMERCIAL_INVOICE for an out-of-range ordinal`() {
        assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, invoice(typeCode = 99).typeCodeEnum())
    }

    @Test
    fun `typeCodeEnum decodes CREDIT_NOTE`() {
        assertEquals(InvoiceTypeCode.CREDIT_NOTE, invoice(typeCode = InvoiceTypeCode.CREDIT_NOTE.ordinal).typeCodeEnum())
    }

    @Test
    fun `typeCodeEnum decodes CORRECTED_INVOICE`() {
        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, invoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE.ordinal).typeCodeEnum())
    }

    // ── currencyEnum ─────────────────────────────────────────────────────────

    @Test
    fun `currencyEnum falls back to EUR when null`() {
        assertEquals(Currency.EUR, invoice(currency = null).currencyEnum())
    }

    @Test
    fun `currencyEnum decodes a valid ordinal`() {
        assertEquals(Currency.entries[0], invoice(currency = 0).currencyEnum())
    }

    @Test
    fun `currencyEnum falls back to EUR for an out-of-range ordinal`() {
        assertEquals(Currency.EUR, invoice(currency = 999).currencyEnum())
    }

    // ── ItemDto.categoryEnum / unitEnum / taxCategoryEnum ──────────────────────

    @Test
    fun `categoryEnum falls back to SERVICE when null`() {
        assertEquals(InvoiceItemCategory.SERVICE, item(category = null).categoryEnum())
    }

    @Test
    fun `categoryEnum falls back to SERVICE for an unrecognized name`() {
        assertEquals(InvoiceItemCategory.SERVICE, item(category = "NONSENSE").categoryEnum())
    }

    @Test
    fun `categoryEnum decodes a matching name`() {
        assertEquals(InvoiceItemCategory.PRODUCT, item(category = "PRODUCT").categoryEnum())
    }

    @Test
    fun `unitEnum falls back to PIECE when null`() {
        assertEquals(InvoiceItemUnit.PIECE, item(unit = null).unitEnum())
    }

    @Test
    fun `unitEnum falls back to PIECE for an unrecognized name`() {
        assertEquals(InvoiceItemUnit.PIECE, item(unit = "NONSENSE").unitEnum())
    }

    @Test
    fun `unitEnum decodes a matching name`() {
        assertEquals(InvoiceItemUnit.HOUR, item(unit = "HOUR").unitEnum())
    }

    @Test
    fun `taxCategoryEnum is null when the item carries no tax category`() {
        assertNull(item(taxCategory = null).taxCategoryEnum())
    }

    @Test
    fun `taxCategoryEnum decodes a matching code`() {
        assertEquals(VatCategory.STANDARD, item(taxCategory = "S").taxCategoryEnum())
    }
}
