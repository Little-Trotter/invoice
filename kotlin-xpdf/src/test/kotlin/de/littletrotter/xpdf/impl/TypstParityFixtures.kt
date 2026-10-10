package de.littletrotter.xpdf.impl

import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.TripExpenseExportDto
import de.littletrotter.shared.TripExportDto
import de.littletrotter.shared.b2b.B2bTerm
import java.math.BigDecimal

/**
 * The synthetic invoices both engines render in the parity tests — one per layout situation the
 * shipped templates distinguish (table variant, VAT column, storno, reverse charge, notes, free
 * text placement, pagination, trip sub-rows, document language). Every fixture uses a sender with
 * all footer facts filled, so the footer columns are exercised on every page.
 */
internal object TypstParityFixtures {

    data class Case(val name: String, val invoice: InvoiceDto)

    fun sender(): PartyDto = PartyDto(
        name = "Meisterbetrieb Sanitär Schmidt GmbH & Co. KG",
        street = "Werkstattweg 3",
        postalCode = "28195",
        city = "Bremen",
        country = "Deutschland",
        email = "rechnung@schmidt-sanitaer.example",
        phone = "+49 421 987654-0",
        website = "https://www.schmidt-sanitaer.example/",
        vatId = "DE123456789",
        taxNumber = "123/456/78901",
        iban = "DE89370400440532013000",
        bic = "COBADEFFXXX",
        bankName = "Commerzbank Bremen",
        managingDirector = "Klaus Schmidt",
        courtOfJurisdiction = "Bremen",
        commercialRegisterNumber = "HRA 12345",
        registerCourt = "Amtsgericht Bremen",
        registeredSeat = "Bremen",
    )

    fun recipient(): PartyDto = PartyDto(
        name = "Nekar Supermarkt eG",
        street = "Marktplatz 1",
        postalCode = "10115",
        city = "Berlin",
        customerNumber = "K-0042",
    )

    fun plainItem(index: Int, rate: String = "19.00"): InvoiceItemDto = InvoiceItemDto(
        positionId = index.toString(),
        description = "Wartungsleistung Position $index",
        quantity = BigDecimal("1.5"),
        unitCode = "HUR",
        unitLabel = "Stunden",
        unitPriceNet = Money(BigDecimal("120.00")),
        netAmount = Money(BigDecimal("180.00")),
        vatAmount = vatFor(BigDecimal("180.00"), rate),
        taxRatePercent = BigDecimal(rate),
    )

    fun detailItem(index: Int, rate: String = "19.00"): InvoiceItemDto = InvoiceItemDto(
        positionId = index.toString(),
        description = "Montage Heizungsanlage $index",
        itemDescription = "Austausch Therme inkl. Verrohrung, 2. OG",
        quantity = BigDecimal("1"),
        unitCode = "H87",
        unitLabel = "Stück",
        unitPriceNet = Money(BigDecimal("1200.00")),
        netAmount = Money(BigDecimal("1200.00")),
        vatAmount = vatFor(BigDecimal("1200.00"), rate),
        taxRatePercent = BigDecimal(rate),
        servicePeriodFrom = "2026-08-01",
        servicePeriodTo = "2026-08-03",
        b2bValues = listOf(B2bValueDto(B2bTerm.BT_155, "ART-4711"), B2bValueDto(B2bTerm.BT_156, "KD-991")),
    )

    /** A trip position whose only expense is measured in "lfm" (laufender Meter) — the case the
     *  unit-footnote lookup must catch on a trip *sub*-row, not just on a main item row. */
    fun tripItemWithLfmExpense(index: Int, rate: String = "19.00"): InvoiceItemDto {
        val net = BigDecimal("140.00")
        return InvoiceItemDto(
            positionId = index.toString(),
            description = "Dienstreise Kabeltrasse",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(net),
            netAmount = Money(net),
            vatAmount = vatFor(net, rate),
            taxRatePercent = BigDecimal(rate),
            unitLabel = "Pauschale",
            itemDescription = "Reisefahrt",
            servicePeriodFrom = "2026-08-04",
            servicePeriodTo = "2026-08-04",
            category = InvoiceItemCategory.TRIP,
            trip = TripExportDto(
                detailedOnInvoice = true,
                fixedNetPrice = Money(net),
                calculatedNet = Money(net),
                expenses = listOf(
                    TripExpenseExportDto(
                        kindLabelKey = "trip.kind.other",
                        description = "Kabeltrasse verlegt",
                        quantity = BigDecimal("20"),
                        unitLabel = "lfm",
                        unitPriceNet = Money(BigDecimal("7.00")),
                        netAmount = Money(net),
                    ),
                ),
            ),
        )
    }

    private fun vatFor(net: BigDecimal, rate: String): Money =
        Money(net.multiply(BigDecimal(rate)).divide(BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP))

    fun invoice(
        number: String,
        items: List<InvoiceItemDto>,
        language: String = "de",
        storno: Boolean = false,
        reverseCharge: Boolean = false,
        footerNote: String? = null,
        freeText: String? = null,
        consumerNote: String? = null,
        withBank: Boolean = true,
    ): InvoiceDto {
        val net = items.sumOf { it.netAmount.amount }
        val vat = items.sumOf { it.vatAmount.amount }
        val sender = sender().copy(footerNote = footerNote).let {
            if (withBank) it else it.copy(iban = null, bic = null, bankName = null)
        }
        val base = InvoiceDto(
            invoiceNumber = InvoiceNumberDto(number),
            dates = InvoiceDatesDto(invoiceDate = "2026-08-13", dueDate = "2026-08-27"),
            references = InvoiceReferencesDto(purchaseOrderReference = "NEKAR-BST-2026-1", projectNumber = "PRJ-2026-7"),
            senderProfile = sender,
            recipient = recipient(),
            items = items,
            totals = InvoiceTotalsDto(netAmount = Money(net), vatAmount = Money(vat), grossAmount = Money(net.add(vat)), reverseCharge = reverseCharge),
            meta = InvoiceMetaDto(documentLanguage = language, freeText = freeText, consumerNote = consumerNote),
        )
        return if (storno) base.copy(typeCode = "384", storno = true) else base
    }

    private const val MARKUP_FREE_TEXT =
        "**Wichtiger Hinweis:** Die Arbeiten wurden am *13.08.2026* abgenommen.\n" +
            "- Garantie ++24 Monate++ auf alle Teile\n" +
            "    - Verschleißteile ~~12~~ 6 Monate\n" +
            "1. Zahlung per Überweisung\n" +
            "2. Rückfragen an die Buchhaltung"

    private fun longFreeText(): String = (1..40).joinToString("\n") { i ->
        "Absatz $i: Diese Vereinbarung beschreibt Leistungsumfang, Abnahme und Gewährleistung in aller Ausführlichkeit, " +
            "damit der Freitext die Kopfzone der ersten Seite verlässt und hinter der Positionstabelle gedruckt wird."
    }

    /** Every parity case, in a stable order (the sample files and reports are named after them). */
    fun cases(): List<Case> = listOf(
        Case("plain-single-rate", invoice("PAR-2026-0001", (1..3).map { plainItem(it) })),
        Case("plain-mixed-rates", invoice("PAR-2026-0002", listOf(plainItem(1), plainItem(2, "7.00"), plainItem(3)))),
        Case("detail-single-rate", invoice("PAR-2026-0003", listOf(detailItem(1), plainItem(2)))),
        Case("detail-mixed-rates", invoice("PAR-2026-0004", listOf(detailItem(1), detailItem(2, "7.00")))),
        Case("storno-with-note", invoice("PAR-2026-0005", (1..2).map { plainItem(it) }, storno = true, footerNote = "Gutschrift zur Rechnung PAR-2026-0001 vom 01.08.2026.")),
        Case(
            "reverse-charge-note-no-bank",
            invoice(
                "PAR-2026-0006", (1..2).map { plainItem(it, "0.00") }, reverseCharge = true, withBank = false,
                footerNote = "Bitte überweisen Sie erst nach Erhalt der Zahlungsaufforderung.",
            ),
        ),
        Case("free-text-inline", invoice("PAR-2026-0007", (1..2).map { plainItem(it) }, freeText = MARKUP_FREE_TEXT)),
        Case("free-text-after-items", invoice("PAR-2026-0008", (1..2).map { plainItem(it) }, freeText = longFreeText())),
        Case("multi-page", invoice("PAR-2026-0009", (1..60).map { plainItem(it) })),
        Case("trip-detailed", invoice("PAR-2026-0010", InvoiceLayoutFixtures.job701Items())),
        Case("english", invoice("PAR-2026-0011", (1..3).map { plainItem(it) }, language = "en", freeText = "**Note:** payment within 14 days.")),
        // The detail variant (article-number columns/sublines, BT-154/155/156) under English
        // document language — "detail-single-rate" is its German twin; both must carry the same
        // article data, just labelled in English.
        Case("english-detail", invoice("PAR-2026-0012", listOf(detailItem(1), plainItem(2)), language = "en")),
        Case("unit-footnote-lfm-de", invoice("PAR-2026-0013", listOf(plainItem(1).copy(unitLabel = "lfm"), plainItem(2)))),
        Case("unit-footnote-lfm-en", invoice("PAR-2026-0014", listOf(plainItem(1).copy(unitLabel = "lfm")), language = "en")),
        Case("unit-footnote-lfm-trip-subrow", invoice("PAR-2026-0015", listOf(plainItem(1), tripItemWithLfmExpense(2)))),
    )
}
