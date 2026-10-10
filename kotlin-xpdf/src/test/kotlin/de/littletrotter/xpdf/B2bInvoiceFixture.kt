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
 * T7 — **the** B2B document for the export proof, described once and used by all three
 * verification levels: the XML comparison in
 * [de.littletrotter.xpdf.impl.mustang.En16931TermsExportTest], the KoSIT check in
 * [KositValidationIT], and the PDF/A check in
 * [de.littletrotter.xpdf.impl.PdfA3MinimalCheckTest]/[de.littletrotter.xpdf.impl.VeraPdfConformanceIT].
 *
 * Three fixtures for the same case would create exactly the contradiction this design avoids —
 * "otherwise the proof splits into two halves that can contradict each other" — so this sits
 * alongside [validCiiXml] in the same role.
 *
 * The document is deliberately **complete**, not minimal: it carries everything XRechnung 3.0
 * requires for a standard-rate German invoice, including BT-42 (seller's phone number, BR-DE-6)
 * — without this one field the generated file is KoSIT-`INVALID`, and the whole export proof
 * would rest on an invoice nobody could actually send.
 *
 * @param b2bValues `true` &rarr; the document carries **all seven** FREE terms of the catalog
 *   (`V903__seed_b2b.sql`): BT-12/BT-18/BT-19/BT-56/BT-58 at the header, BT-155/BT-156 on the
 *   line; `false` &rarr; the same document as the community case, i.e. with empty B2B lists. The
 *   difference between the two is the actual subject under test.
 *
 *   The KoSIT proof is only as good as the values that actually appear in the checked document —
 *   a term the fixture does not carry has never been seen by KoSIT. BT-19 itself still gets no
 *   header element (Mustang 2.25.0 has no header setter for it), but reaches the document as
 *   BT-133 on the line — see the KDoc of [de.littletrotter.xpdf.impl.mustang.MustangExportModel].
 */
internal fun b2bInvoiceDto(number: String = "B2B-2026-0001", b2bValues: Boolean = true): InvoiceDto {
    val header = if (b2bValues) {
        listOf(
            B2bValueDto(B2bTerm.BT_12, "V-2026-0815"),
            B2bValueDto(B2bTerm.BT_18, "OBJ-4711"),
            B2bValueDto(B2bTerm.BT_19, "KST-4711"),
            B2bValueDto(B2bTerm.BT_56, "Frau Meier, Kreditorenbuchhaltung"),
            B2bValueDto(B2bTerm.BT_58, "meier@acme.example"),
        )
    } else {
        emptyList()
    }
    val line = if (b2bValues) {
        listOf(B2bValueDto(B2bTerm.BT_155, "ART-4711"), B2bValueDto(B2bTerm.BT_156, "KUNDE-0815"))
    } else {
        emptyList()
    }

    return InvoiceDto(
        invoiceNumber = InvoiceNumberDto(number),
        dates = InvoiceDatesDto(
            invoiceDate = "2026-02-10",
            dueDate = "2026-03-12",
            // BG-14 (BT-73/BT-74) — the billing period.
            servicePeriodFrom = "2026-01-01",
            servicePeriodTo = "2026-01-31",
        ),
        references = InvoiceReferencesDto(
            buyerReference = "PO-B2B-1",
        ),
        senderProfile = PartyDto(
            name = "B2B Anbieter GmbH",
            street = "Hafenstraße 12", postalCode = "28195", city = "Bremen", country = "DE",
            email = "buchhaltung@b2b.example",
            // BT-42 — without a phone number the document violates BR-DE-6.
            phone = "+49 421 1234567",
            vatId = "DE123456789", taxNumber = "12/345/67890",
            iban = "DE89370400440532013000",
        ),
        recipient = PartyDto(
            name = "Acme AG",
            street = "Musterstraße 42", postalCode = "10115", city = "Berlin", country = "DE",
            email = "kreditoren@acme.example", vatId = "DE987654321",
            customerNumber = "K-0042",
        ),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Beratung",
                itemDescription = "Fachliche Begleitung der Migration, Januar 2026",
                quantity = BigDecimal("8"),
                unitPriceNet = Money(BigDecimal("100.00")),
                unitCode = "HUR",
                taxRatePercent = BigDecimal("19"),
                taxCategory = "S",
                // BG-26 (BT-134/BT-135) — the line item's service period.
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
