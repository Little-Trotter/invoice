package de.littletrotter.xpdf.impl

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemDayExportDto
import de.littletrotter.shared.TripExpenseExportDto
import de.littletrotter.shared.TripExportDto
import java.math.BigDecimal

/** Invoices for the print-layout tests of the one bundled template, and where its item table and totals sit on the page. */
internal object InvoiceLayoutFixtures {

    /** Whether [pdf] actually rendered the "detail" item-table variant — decided by inspecting
     *  the render itself (its own "Einheit" column head, printed only there), never by which
     *  items built it: since PO decision 2026-09-27 a unit label alone no longer selects the
     *  detail variant, so a fixture that merely carries one (most of [plainItem]'s callers) can
     *  no longer be assumed to have rendered it. A full-width scan, `contains` rather than an
     *  exact match: [PdfTableGeometry.textLines] joins every glyph sharing a baseline into one
     *  line regardless of column, so the whole header row glues into one string in reading order
     *  — "Einheit" still appears as an intact substring, but its own `x` range shifts left with
     *  the USt-%-column switch ([HANDWERKER_COLUMNS] only documents the no-VAT-column positions),
     *  which a range-scoped exact match would miss. */
    fun isDetailVariant(pdf: ByteArray): Boolean = PdfTableGeometry.textLines(pdf, 0f, 600f).any { it.text.contains("Einheit") }

    /** The Bezeichnung column of whichever item-table variant [pdf] actually rendered ([isDetailVariant]). */
    fun descriptionColumn(pdf: ByteArray): Pair<Float, Float> =
        if (isDetailVariant(pdf)) HANDWERKER_COLUMNS.getValue("Bezeichnung") else PLAIN_COLUMNS.getValue("Bezeichnung")

    /** `x` range (page margin 57 + the widths before) of each "detail" item-table-variant column,
     *  glyph starts fall inside it — chosen whenever a fixture item carries a service period or a
     *  seller/buyer article number ([InvoiceReportModelBuilder.hasItemDetailColumns]). */
    val HANDWERKER_COLUMNS: Map<String, Pair<Float, Float>> = linkedMapOf(
        "Pos" to (57f to 90f),
        "Bezeichnung" to (87f to 178f),
        "Datum" to (178f to 228f),
        "Menge" to (228f to 266f),
        "Einheit" to (266f to 321f),
        "Einzelpreis" to (321f to 386f),
        "Netto" to (386f to 451f),
        "Art" to (451f to 491f),
        "KdArt" to (491f to 538f),
    )

    /** `x` range of each "plain" item-table-variant column (page margin 57 + the widths before,
     *  no USt-% column) — chosen whenever no fixture item carries a service period or a
     *  seller/buyer article number
     *  ([noDetailItem]; a unit label alone, e.g. [plainItem], no longer selects the detail
     *  variant either, PO decision 2026-09-27). */
    val PLAIN_COLUMNS: Map<String, Pair<Float, Float>> = linkedMapOf(
        "Pos" to (57f to 97f),
        "Bezeichnung" to (97f to 337f),
        "Menge" to (337f to 404f),
        "Einzelpreis" to (404f to 471f),
        "Netto" to (471f to 538f),
    )

    /**
     * The text lines of one "detail" table-variant column that belong to the item rows: below the
     * repeated table header of their page, above the last row separator (the totals block also has
     * glyphs in the Einheit/Preis/Netto ranges).
     */
    fun handwerkerBodyLines(pdf: ByteArray, column: String): List<PdfTableGeometry.TextLine> {
        val (xMin, xMax) = HANDWERKER_COLUMNS.getValue(column)
        val (descMin, descMax) = HANDWERKER_COLUMNS.getValue("Bezeichnung")
        val header = PdfTableGeometry.textLines(pdf, descMin, descMax).filter { it.text.startsWith("Bezeichnung") }.associateBy { it.page }
        val rules = PdfTableGeometry.separators(pdf, descMin, descMax).groupBy { it.page }
        return PdfTableGeometry.textLines(pdf, xMin, xMax).filter { line ->
            val head = header[line.page] ?: return@filter false
            val last = rules[line.page]?.maxOf { it.y } ?: return@filter false
            line.baseline > head.baseline + 1f && line.baseline < last
        }
    }

    /** The heading ("Rechnung Nr. ...") and the table header row of page 1, measured in the left half of the page. */
    data class TitleGeometry(val heading: PdfTableGeometry.TextLine, val tableHeader: PdfTableGeometry.TextLine) {
        val gap: Float get() = tableHeader.top - heading.bottom
    }

    fun titleGeometry(pdf: ByteArray): TitleGeometry {
        val lines = PdfTableGeometry.textLines(pdf, 57f, 262f).filter { it.page == 1 }
        return TitleGeometry(
            heading = lines.first { it.text.startsWith("Rechnung Nr.") },
            tableHeader = lines.first { it.text.contains("Bezeichnung") },
        )
    }

    /** The label column of the totals block (`x` 215 in the band, page margin 57, up to the value column). */
    const val TOTALS_LABEL_X_MIN = 268f
    const val TOTALS_LABEL_X_MAX = 340f

    /** The 12 pt bold "Rechnungsbetrag" is wider than the label column - its last glyphs start right of it and are not part of the line. */
    const val GROSS_LABEL_PREFIX = "Rechnungs"

    /** A unit label, but no service period or seller/buyer article number — keeps
     *  [InvoiceReportModelBuilder.hasItemDetailColumns] false, i.e. renders the "plain"
     *  item-table variant (PO decision 2026-09-27: a unit alone is not "detail data"). */
    fun plainItem(index: Int, rate: String = "19.0") = InvoiceItemDto(
        positionId = index.toString(),
        description = "ITEMROW-${index.toString().padStart(3, '0')}",
        quantity = BigDecimal("1.5"),
        unitPriceNet = Money(BigDecimal("120.00")),
        netAmount = Money(BigDecimal("180.00")),
        vatAmount = Money(BigDecimal("34.20")),
        taxRatePercent = BigDecimal(rate),
        unitLabel = "Stunde",
    )

    /** No unit, service period, seller or buyer article number either — renders the same "plain"
     *  item-table variant as [plainItem] (a bare quantity instead of "quantity + unit" in Menge is
     *  the only difference between the two fixtures now that a unit alone no longer selects the
     *  detail variant). */
    fun noDetailItem(index: Int, rate: String = "19.0") = InvoiceItemDto(
        positionId = index.toString(),
        description = "ITEMROW-${index.toString().padStart(3, '0')}",
        quantity = BigDecimal("1.5"),
        unitPriceNet = Money(BigDecimal("120.00")),
        netAmount = Money(BigDecimal("180.00")),
        vatAmount = Money(BigDecimal("34.20")),
        taxRatePercent = BigDecimal(rate),
    )

    private fun tripExpense(kindKey: String, description: String, quantity: String, unit: String, net: String) =
        TripExpenseExportDto(
            kindLabelKey = kindKey,
            description = description,
            quantity = BigDecimal(quantity),
            unitLabel = unit,
            unitPriceNet = Money(BigDecimal(net)),
            netAmount = Money(BigDecimal(net)),
        )

    /** Job 701: one plain item, then a trip position with a two-line purpose/period sub-line and five trip sub-rows. */
    private fun tripItems(longDescription: Boolean): List<InvoiceItemDto> {
        val plain = InvoiceItemDto(
            positionId = "1",
            description = if (longDescription) "Elektroinstallation Erdgeschoss inklusive sämtlicher Nebenarbeiten und Kleinmaterial" else "Elektroinstallation",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("68.00")),
            netAmount = Money(BigDecimal("68.00")),
            vatAmount = Money(BigDecimal("12.92")),
            taxRatePercent = BigDecimal("19.0"),
            unitLabel = "Stunde",
        )
        val perDiemDay = PerDiemDayExportDto(
            isArrivalDeparture = false,
            breakfastDeduction = false,
            lunchDeduction = false,
            dinnerDeduction = false,
            claimableAmount = Money(BigDecimal("15.40")),
        )
        val trip = InvoiceItemDto(
            positionId = "2",
            description = "Stefan",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("1045.00")),
            netAmount = Money(BigDecimal("1045.00")),
            vatAmount = Money(BigDecimal("198.55")),
            taxRatePercent = BigDecimal("19.0"),
            unitLabel = "Pauschale",
            itemDescription = "Reisefahrt",
            servicePeriodFrom = "2026-09-05",
            servicePeriodTo = "2026-09-12",
            category = InvoiceItemCategory.TRIP,
            trip = TripExportDto(
                detailedOnInvoice = true,
                fixedNetPrice = Money(BigDecimal("1045.00")),
                calculatedNet = Money(BigDecimal("1045.00")),
                expenses = listOf(
                    tripExpense("trip.kind.hotel", "Hotel Berlin", "3", "Tage", "660.00"),
                    tripExpense("trip.kind.transport", "Bahnfahrt hin und zurück", "1", "Pauschale", "220.00"),
                    tripExpense("trip.kind.mileage", "Zu Koblenz HBF hin und zurück", "66", "Kilometer", "19.80"),
                    tripExpense("trip.kind.other", "Tax Köln", "1", "Pauschale", "22.00"),
                ),
                perDiemDays = List(8) { perDiemDay },
            ),
        )
        return listOf(plain, trip)
    }

    fun job701Items(longDescription: Boolean = false): List<InvoiceItemDto> = tripItems(longDescription)

    /** Where the three totals lines (net, "zzgl. ... Umsatzsteuer", "Rechnungsbetrag") and the payment title printed. */
    data class TotalsGeometry(
        val net: PdfTableGeometry.TextLine?,
        val vat: PdfTableGeometry.TextLine?,
        val gross: PdfTableGeometry.TextLine?,
        val payment: PdfTableGeometry.TextLine?,
        val lastItemRule: Float?,
        val lastItemPage: Int?,
    ) {
        val lines: List<PdfTableGeometry.TextLine?> get() = listOf(net, vat, gross)

        /** All three lines exist and share one page. */
        fun together(): Boolean = lines.all { it != null } && lines.map { it!!.page }.distinct().size == 1

        fun describe(): String =
            "net=${net?.let { "p${it.page}@${it.baseline}" }} vat=${vat?.let { "p${it.page}@${it.baseline}" }} " +
                "gross=${gross?.let { "p${it.page}@${it.baseline}" }} pay=${payment?.let { "p${it.page}@${it.baseline}" }} " +
                "lastRule=p$lastItemPage@$lastItemRule"
    }

    fun totalsGeometry(pdf: ByteArray): TotalsGeometry {
        val labels = PdfTableGeometry.textLines(pdf, TOTALS_LABEL_X_MIN, TOTALS_LABEL_X_MAX)
        val (colMin, colMax) = descriptionColumn(pdf)
        val rules = PdfTableGeometry.separators(pdf, colMin, colMax)
        val paymentLine = PdfTableGeometry.textLines(pdf, 68f, 200f).firstOrNull { it.text.startsWith("ZAHLUNGSBEDINGUNGEN") }
        val net = labels.firstOrNull { it.text.startsWith("Nettobetrag") }
        val lastRule = rules.maxWithOrNull(compareBy({ it.page }, { it.y }))
        return TotalsGeometry(
            net = net,
            vat = labels.firstOrNull { it.text.startsWith("zzgl.") },
            gross = labels.firstOrNull { it.text.startsWith(GROSS_LABEL_PREFIX) },
            payment = paymentLine,
            lastItemRule = lastRule?.y,
            lastItemPage = lastRule?.page,
        )
    }
}
