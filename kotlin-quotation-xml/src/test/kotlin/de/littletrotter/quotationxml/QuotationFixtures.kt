package de.littletrotter.quotationxml

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.VatCategory
import java.math.BigDecimal
import java.time.LocalDate

/** Synthetic offer: 2 x 100.00 at 19 % plus 1 x 100.00 at 7 %. */
internal object QuotationFixtures {

    fun model(
        validUntil: LocalDate = LocalDate.of(2026, 11, 15),
        disclaimer: String = "Dieses Angebot ist freibleibend.",
        buyerEmail: String? = "einkauf@example.test",
        attachedDocuments: List<QuotationDocumentReference> = emptyList(),
    ): QuotationModel = QuotationModel(
        number = "AN-2026-0001",
        issueDate = LocalDate.of(2026, 10, 15),
        validUntil = validUntil,
        currency = Currency.EUR,
        seller = QuotationParty(
            name = "Musterwerk GmbH",
            street = "Beispielstrasse 1",
            postalCode = "10115",
            city = "Berlin",
            country = Country.DE,
            vatId = "DE123456789",
            email = "angebot@example.test",
        ),
        buyer = QuotationParty(
            name = "Testkunde AG",
            street = "Probeweg 2",
            postalCode = "80331",
            city = "Muenchen",
            country = Country.DE,
            email = buyerEmail,
        ),
        lines = listOf(
            QuotationLine(
                position = 1,
                name = "Beratung",
                quantity = BigDecimal("2"),
                unitCode = "HUR",
                unitPrice = Money(BigDecimal("100.00")),
                lineTotal = Money(BigDecimal("200.00")),
                vatCategory = VatCategory.STANDARD,
                vatRate = BigDecimal("19"),
                description = "Beratung vor Ort",
            ),
            QuotationLine(
                position = 2,
                name = "Handbuch",
                quantity = BigDecimal("1"),
                unitCode = "C62",
                unitPrice = Money(BigDecimal("100.00")),
                lineTotal = Money(BigDecimal("100.00")),
                vatCategory = VatCategory.STANDARD,
                vatRate = BigDecimal("7"),
            ),
        ),
        taxSubtotals = listOf(
            QuotationTaxSubtotal(VatCategory.STANDARD, BigDecimal("19"), Money(BigDecimal("200.00")), Money(BigDecimal("38.00"))),
            QuotationTaxSubtotal(VatCategory.STANDARD, BigDecimal("7"), Money(BigDecimal("100.00")), Money(BigDecimal("7.00"))),
        ),
        totals = QuotationTotals(net = Money(BigDecimal("300.00")), tax = Money(BigDecimal("45.00")), gross = Money(BigDecimal("345.00"))),
        disclaimer = disclaimer,
        notes = listOf("Lieferzeit nach Absprache"),
        attachedDocuments = attachedDocuments,
    )

    const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val HASH_B = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    fun references(): List<QuotationDocumentReference> = listOf(
        QuotationDocumentReference("ATT-1", "Lageplan.pdf", HASH_A, "application/pdf", "Lageplan der Baustelle"),
        QuotationDocumentReference("ATT-2", "foto.png", HASH_B, "image/png"),
    )

    fun xml(model: QuotationModel = model()): String = String(UblQuotationWriter().write(model), Charsets.UTF_8)
}
