package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.shared.VatRegistrationDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * RCG012-2026-08-00001 — BT-31 must carry the seller's *active* VAT registration, not
 * unconditionally the German home `vatId`, when the invoice is billed under one of the seller's
 * foreign registrations ([InvoiceMetaDto.sellerVatRegistrationCountry]). See
 * `SellerVatIdResolution.kt`'s class KDoc for the full reasoning.
 */
class MustangExportModelMapperTest {

    private fun sender(
        sellerVatRegistrationCountry: String? = null,
        additionalVatRegistrations: List<VatRegistrationDto> = emptyList(),
    ) = InvoiceDto(
        senderProfile = PartyDto(
            name = "Seller",
            vatId = "DE123456789",
            additionalVatRegistrations = additionalVatRegistrations,
        ),
        recipient = PartyDto(name = "Buyer", country = "LU"),
        meta = InvoiceMetaDto(sellerVatRegistrationCountry = sellerVatRegistrationCountry),
    )

    @Test
    fun `an active LU registration with a matching entry exports the LU number, not the DE home vatId`() {
        val model = MustangExportModelMapper.toModel(
            sender(
                sellerVatRegistrationCountry = "LU",
                additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
            ),
        )
        assertEquals("LU12345678", model.sender?.vatId)
    }

    @Test
    fun `BT-32 stays the German tax number and the foreign tax number never reaches the model`() {
        val invoice = InvoiceDto(
            senderProfile = PartyDto(
                name = "Seller",
                vatId = "DE123456789",
                taxNumber = "12/345/67890",
                additionalVatRegistrations = listOf(
                    VatRegistrationDto("LU", "LU12345678", "1990010112345", "foreignTaxNumber.label.matricule"),
                ),
            ),
            recipient = PartyDto(name = "Buyer", country = "LU"),
            meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
        )

        val model = MustangExportModelMapper.toModel(invoice)

        assertEquals("12/345/67890", model.sender?.taxNumber)
        assertEquals("LU12345678", model.sender?.vatId)
        assertEquals(false, model.sender.toString().contains("1990010112345"))
    }

    @Test
    fun `a registration with a recipient outside its country keeps BT-31 German`() {
        val registrations = listOf(VatRegistrationDto("LU", "LU12345678", "1990010112345", "foreignTaxNumber.label.matricule"))
        val domestic = MustangExportModelMapper.toModel(
            sender(sellerVatRegistrationCountry = "LU", additionalVatRegistrations = registrations)
                .copy(recipient = PartyDto(name = "Buyer")),
        )
        val french = MustangExportModelMapper.toModel(
            sender(sellerVatRegistrationCountry = "LU", additionalVatRegistrations = registrations)
                .copy(recipient = PartyDto(name = "Buyer", country = "FR")),
        )
        assertEquals("DE123456789", domestic.sender?.vatId)
        assertEquals("DE123456789", french.sender?.vatId)
    }

    @Test
    fun `no registration country set exports the German home vatId unchanged`() {
        val model = MustangExportModelMapper.toModel(sender())
        assertEquals("DE123456789", model.sender?.vatId)
    }

    @Test
    fun `a registration country set without a matching additionalVatRegistrations entry falls back to the home vatId`() {
        val model = MustangExportModelMapper.toModel(
            sender(sellerVatRegistrationCountry = "LU", additionalVatRegistrations = emptyList()),
        )
        assertEquals("DE123456789", model.sender?.vatId)
    }

    @Test
    fun `the country match is case-insensitive`() {
        val model = MustangExportModelMapper.toModel(
            sender(
                sellerVatRegistrationCountry = "lu",
                additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
            ),
        )
        assertEquals("LU12345678", model.sender?.vatId)
    }

    @Test
    fun `the recipient's own vatId is never touched by the seller's registration override`() {
        val model = MustangExportModelMapper.toModel(
            InvoiceDto(
                senderProfile = PartyDto(
                    name = "Seller",
                    vatId = "DE123456789",
                    additionalVatRegistrations = listOf(VatRegistrationDto("LU", "LU12345678")),
                ),
                recipient = PartyDto(name = "Buyer", vatId = "LU999999999", country = "LU"),
                meta = InvoiceMetaDto(sellerVatRegistrationCountry = "LU"),
            ),
        )
        assertEquals("LU12345678", model.sender?.vatId)
        assertEquals("LU999999999", model.recipient?.vatId)
    }

    // ── collectReceipts() — blank fileName is skipped entirely ──────────────────────────────

    private fun itemWith(receipts: List<ReceiptDto> = emptyList()): InvoiceItemDto = InvoiceItemDto(
        positionId = "1", description = "Item", quantity = BigDecimal.ONE,
        unitPriceNet = Money(BigDecimal("10")), taxRatePercent = BigDecimal("19"), receipts = receipts,
    )

    @Test
    fun `a receipt with a blank fileName is skipped, even with a valid objectKey`() {
        val model = MustangExportModelMapper.toModel(
            InvoiceDto(items = listOf(itemWith(receipts = listOf(ReceiptDto(fileName = "   ", objectKey = "r/a.pdf"))))),
        )
        assertTrue(model.receipts.isEmpty())
    }

    @Test
    fun `a receipt with a real fileName is kept`() {
        val model = MustangExportModelMapper.toModel(
            InvoiceDto(items = listOf(itemWith(receipts = listOf(ReceiptDto(fileName = "a.pdf", objectKey = "r/a.pdf"))))),
        )
        assertEquals(1, model.receipts.size)
    }

    // ── currency resolution ──────────────────────────────────────────────────────────────────
    // invoice.totals.currency is a typed, always-valid Currency now (was a raw Int?
    // ordinal); the null/out-of-range fallback this mapper used to re-check moved to
    // CurrencyOrdinalDeserializer and is pinned there (kotlin-shared's CurrencyTest).

    @Test
    fun `currency resolves via the invoice's typed Currency, unchanged from before`() {
        val invoice = InvoiceDto(totals = de.littletrotter.shared.InvoiceTotalsDto(currency = de.littletrotter.shared.Currency.CHF))
        assertEquals("CHF", MustangExportModelMapper.toModel(invoice).currencyCode)
    }

    // ── per-line unitCode / productClassificationScheme / productClassificationCode ─────────

    @Test
    fun `a null unitCode defaults to C62`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(unitCode = null)))
        assertEquals("C62", MustangExportModelMapper.toModel(invoice).items.single().unitCode)
    }

    @Test
    fun `a blank unitCode defaults to C62`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(unitCode = "")))
        assertEquals("C62", MustangExportModelMapper.toModel(invoice).items.single().unitCode)
    }

    @Test
    fun `a real unitCode is kept unchanged`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(unitCode = "HUR")))
        assertEquals("HUR", MustangExportModelMapper.toModel(invoice).items.single().unitCode)
    }

    @Test
    fun `a null productClassificationScheme defaults to ETIM`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationScheme = null)))
        assertEquals("ETIM", MustangExportModelMapper.toModel(invoice).items.single().productClassificationScheme)
    }

    @Test
    fun `a blank productClassificationScheme defaults to ETIM`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationScheme = "  ")))
        assertEquals("ETIM", MustangExportModelMapper.toModel(invoice).items.single().productClassificationScheme)
    }

    @Test
    fun `a real productClassificationScheme is kept unchanged`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationScheme = "EC")))
        assertEquals("EC", MustangExportModelMapper.toModel(invoice).items.single().productClassificationScheme)
    }

    @Test
    fun `a null productClassificationCode stays null`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationCode = null)))
        assertNull(MustangExportModelMapper.toModel(invoice).items.single().productClassificationCode)
    }

    @Test
    fun `a blank productClassificationCode becomes null`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationCode = "  ")))
        assertNull(MustangExportModelMapper.toModel(invoice).items.single().productClassificationCode)
    }

    @Test
    fun `a real productClassificationCode is kept unchanged`() {
        val invoice = InvoiceDto(items = listOf(itemWith().copy(productClassificationCode = "EC002714")))
        assertEquals("EC002714", MustangExportModelMapper.toModel(invoice).items.single().productClassificationCode)
    }

    // ── BT-19 accounting reference: FREE catalog value wins over the legacy field ────────────

    @Test
    fun `accountingReference falls back to references costCenter with no BT-19 b2b value`() {
        val invoice = InvoiceDto(
            references = de.littletrotter.shared.InvoiceReferencesDto(costCenter = "KST-42"),
            items = listOf(itemWith()),
        )
        assertEquals("KST-42", MustangExportModelMapper.toModel(invoice).items.single().accountingReference)
    }

    @Test
    fun `accountingReference is null when neither BT-19 nor costCenter is present`() {
        val invoice = InvoiceDto(items = listOf(itemWith()))
        assertNull(MustangExportModelMapper.toModel(invoice).items.single().accountingReference)
    }

    @Test
    fun `accountingReference prefers the BT-19 b2b value over the legacy costCenter field`() {
        val invoice = InvoiceDto(
            references = de.littletrotter.shared.InvoiceReferencesDto(costCenter = "KST-LEGACY"),
            b2bValues = listOf(B2bValueDto(B2bTerm.BT_19, "KST-FREE")),
            items = listOf(itemWith()),
        )
        assertEquals("KST-FREE", MustangExportModelMapper.toModel(invoice).items.single().accountingReference)
    }

    // ── documentLanguage: same "en"-or-de normalisation InvoiceReportContextBuilder
    //    .resolveRenderLanguage applies, one rule not two (resolveDocumentRenderLanguage). ────────

    @Test
    fun `documentLanguage is en only for the exact value en`() {
        assertEquals("en", MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "en"))).documentLanguage)
    }

    @Test
    fun `documentLanguage defaults to de for null, blank or an unknown code`() {
        assertEquals("de", MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = null))).documentLanguage)
        assertEquals("de", MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = ""))).documentLanguage)
        assertEquals("de", MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(documentLanguage = "fr"))).documentLanguage)
    }

    // ── PO decisions of 06.09.2026 — paymentTermName passes through unchanged; it is
    //    already resolved (snapshot-aware, language-resolved) upstream in InvoiceDtoMapper. ──────

    @Test
    fun `paymentTermName passes through verbatim when present`() {
        val invoice = InvoiceDto(dates = de.littletrotter.shared.InvoiceDatesDto(paymentTermName = "30 Tage netto"))
        assertEquals("30 Tage netto", MustangExportModelMapper.toModel(invoice).paymentTermName)
    }

    @Test
    fun `paymentTermName is null when the invoice has no payment term at all`() {
        val invoice = InvoiceDto(dates = de.littletrotter.shared.InvoiceDatesDto(paymentTermName = null))
        assertNull(MustangExportModelMapper.toModel(invoice).paymentTermName)
    }

    @Test
    fun `paymentTermName blank is normalised to null, same as every other nonBlank field`() {
        val invoice = InvoiceDto(dates = de.littletrotter.shared.InvoiceDatesDto(paymentTermName = "   "))
        assertNull(MustangExportModelMapper.toModel(invoice).paymentTermName)
    }

    // ── BR-DE-18 — cashDiscount days/percent pass through unchanged; the domain already
    //    decided applicability (null on cancellation, non-positive gross, or a term without one). ────

    @Test
    fun `cashDiscount days and percent pass through unchanged when the domain computed a discount`() {
        val invoice = InvoiceDto(
            cashDiscount = de.littletrotter.shared.InvoiceCashDiscountDto(
                percent = BigDecimal("2.5"),
                days = 7,
                deadline = "2026-10-13",
                amount = Money.of("25.00"),
                payableAmount = Money.of("975.00"),
            ),
        )
        val model = MustangExportModelMapper.toModel(invoice)
        assertEquals(7, model.cashDiscountDays)
        assertEquals(BigDecimal("2.5"), model.cashDiscountPercent)
    }

    @Test
    fun `cashDiscount fields are null when the invoice carries no cash discount`() {
        val invoice = InvoiceDto(cashDiscount = null)
        val model = MustangExportModelMapper.toModel(invoice)
        assertNull(model.cashDiscountDays)
        assertNull(model.cashDiscountPercent)
    }

    // ── T2 — BT-22 header note is the plain rendering of the free text ───────────────────────

    @Test
    fun `headerNote is the plain rendering of the free text without markup delimiters`() {
        val freeText = "**Wichtig** ++unterstrichen++ ~~alt~~\n1. Eins\n    - Unterpunkt"
        val invoice = InvoiceDto(meta = InvoiceMetaDto(freeText = freeText))
        assertEquals(
            "Wichtig unterstrichen alt\n1. Eins\n    - Unterpunkt",
            MustangExportModelMapper.toModel(invoice).headerNote,
        )
    }

    @Test
    fun `headerNote stays absent for a null or blank free text`() {
        assertNull(MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(freeText = null))).headerNote)
        assertNull(MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(freeText = "  \n "))).headerNote)
    }

    @Test
    fun `consumerNote is the trimmed consumer hint and stays absent for null or blank`() {
        assertEquals(
            "Hinweis fuer Privatkunden",
            MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(consumerNote = " Hinweis fuer Privatkunden\n"))).consumerNote,
        )
        assertNull(MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(consumerNote = null))).consumerNote)
        assertNull(MustangExportModelMapper.toModel(InvoiceDto(meta = InvoiceMetaDto(consumerNote = " \n "))).consumerNote)
    }

    // ── exportProfile — the caller-resolved profile, or the profileId-only default ───────────

    @Test
    fun `the caller-supplied exportProfile wins over the invoice's own meta profileId`() {
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = "EN16931"))
        val model = MustangExportModelMapper.toModel(invoice, exportProfile = "EXTENDED")
        assertEquals("EXTENDED", model.exportProfile)
    }

    @Test
    fun `with no exportProfile argument the default reads meta profileId`() {
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = "EXTENDED"))
        assertEquals("EXTENDED", MustangExportModelMapper.toModel(invoice).exportProfile)
    }

    @Test
    fun `with no exportProfile argument and no meta profileId the default is EN16931`() {
        val invoice = InvoiceDto(meta = InvoiceMetaDto(profileId = null))
        assertEquals("EN16931", MustangExportModelMapper.toModel(invoice).exportProfile)
    }
}
