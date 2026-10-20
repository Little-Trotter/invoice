package de.littletrotter.xpdf

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.b2b.B2bTerm
import java.math.BigDecimal

/**
 * The NekarProfile document for the export proof: exactly the eight fields (BT-84/BT-19/BT-58
 * mandatory, BT-13/BT-12/BT-18 optional, BT-155/BT-156 conditional on BT-13) — the same
 * mandatory/optional pattern, under a neutral name and with its own values, instead of the
 * generic [b2bInvoiceDto], which carries different fields (BT-56) and not all eight.
 *
 * @param mandatoryOnly `false` (default) &rarr; all eight fields set (the complete case: purchase
 *   order number captured, so also the two line identifiers that depend on it). `true` &rarr;
 *   only the three mandatory fields (BT-84/BT-19/BT-58); BT-13 (`purchaseOrderReference`, BOUND —
 *   no `b2bValues` entry), BT-12, BT-18 are missing at the header, BT-155/BT-156 are missing on
 *   the line — the proof that the optional/conditional fields can really be omitted.
 */
internal fun nekarProfileInvoiceDto(number: String = "NEKAR-2026-0001", mandatoryOnly: Boolean = false): InvoiceDto {
    val header = if (mandatoryOnly) {
        listOf(
            B2bValueDto(B2bTerm.BT_19, "KST-NEKAR-001"),
            B2bValueDto(B2bTerm.BT_58, "rechnung@nekar-supermarkt.de"),
        )
    } else {
        listOf(
            B2bValueDto(B2bTerm.BT_19, "KST-NEKAR-001"),
            B2bValueDto(B2bTerm.BT_58, "rechnung@nekar-supermarkt.de"),
            B2bValueDto(B2bTerm.BT_12, "VERTRAG-2026-01"),
            B2bValueDto(B2bTerm.BT_18, "IA-2026-01"),
        )
    }
    // BT-13 is BOUND (B2bTerm.BT_13.boundProperty == "purchaseOrderReference"), unlike the FREE
    // terms above — its value never sits in b2b_field_value, but directly on the
    // InvoiceReferencesDto field (MustangExportModelMapper reads it there, not via b2bValue()).
    val purchaseOrderReference = if (mandatoryOnly) null else "2000325012"
    val line = if (mandatoryOnly) {
        emptyList()
    } else {
        // Conditional on BT-13 (only set together with BT-13): once BT-13 is populated,
        // BT-155/BT-156 become mandatory on every line item.
        listOf(
            B2bValueDto(B2bTerm.BT_155, "Nekar-Artikelbezeichnung 4711"),
            B2bValueDto(B2bTerm.BT_156, "NEKAR-ART-4711"),
        )
    }

    return InvoiceDto(
        invoiceNumber = InvoiceNumberDto(number),
        dates = InvoiceDatesDto(
            invoiceDate = "2026-02-10",
            dueDate = "2026-03-12",
            servicePeriodFrom = "2026-01-01",
            servicePeriodTo = "2026-01-31",
        ),
        references = InvoiceReferencesDto(
            buyerReference = "PO-NEKAR-1",
            purchaseOrderReference = purchaseOrderReference,
        ),
        senderProfile = PartyDto(
            name = "Nekar Sender GmbH",
            street = "Hafenstraße 12", postalCode = "28195", city = "Bremen", country = "DE",
            email = "buchhaltung@sender.example",
            // BT-42 — without a phone number the document violates BR-DE-6 (as with b2bInvoiceDto).
            phone = "+49 421 1234567",
            vatId = "DE123456789", taxNumber = "12/345/67890",
            // BT-84 — no b2b_field_value entry (the BOUND IBAN), but directly on senderProfile, as
            // NekarProfileConfigIT's KDoc on the SELLER placement records (the same pattern as the
            // rest of the field list).
            iban = "DE02120300000000202051",
        ),
        recipient = PartyDto(
            name = "Nekar Supermarkt",
            street = "Kundenallee 2", postalCode = "20095", city = "Hamburg", country = "DE",
            email = "kreditoren@nekar-supermarkt.example", vatId = "DE987654321",
            customerNumber = "K-NEKAR-1",
        ),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Beratung",
                itemDescription = "Fachliche Begleitung, Januar 2026",
                quantity = BigDecimal("8"),
                unitPriceNet = Money(BigDecimal("100.00")),
                unitCode = "HUR",
                taxRatePercent = BigDecimal("19"),
                taxCategory = "S",
                servicePeriodFrom = "2026-01-05",
                servicePeriodTo = "2026-01-20",
                netAmount = Money(BigDecimal("800.00")),
                vatAmount = Money(BigDecimal("152.00")),
                b2bValues = line,
            ),
        ),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("800.00")),
            vatAmount = Money(BigDecimal("152.00")),
            grossAmount = Money(BigDecimal("952.00")),
        ),
        b2bValues = header,
    )
}
