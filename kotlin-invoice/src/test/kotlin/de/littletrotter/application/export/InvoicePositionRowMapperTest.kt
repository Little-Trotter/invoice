package de.littletrotter.application.export

import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure unit tests for [InvoicePositionRowMapper.fromRow]/[InvoicePositionRowMapper.itemId] — no
 * database, hand-built `Array<Any?>` rows in the exact column layout the mapper's own KDoc
 * documents (26 columns, 0-indexed), mirroring [InvoiceHeaderRowMapperTest]'s style. Its own
 * [InvoicePositionRowMapper.tripDetailRows] tests live further down, after the `fromRow` ones.
 */
class InvoicePositionRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(26)

    private fun Array<Any?>.withOverrides(vararg overrides: Pair<Int, Any?>): Array<Any?> =
        copyOf().also { copy -> overrides.forEach { (index, value) -> copy[index] = value } }

    @Test
    fun `maps link, order and description columns field by field`() {
        val row = blankRow().withOverrides(
            0 to 5L, // item id (join key)
            1 to "1",
            2 to 3,
            3 to InvoiceItemCategory.PRODUCT,
            4 to "Beratung",
            5 to "Detaillierte Beschreibung",
        )

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(42L, "IP-2026-01-00001"), null to null, emptyList())

        assertThat(result.invoiceId).isEqualTo(42L)
        assertThat(result.invoiceNumber).isEqualTo("IP-2026-01-00001")
        assertThat(result.positionId).isEqualTo("1")
        assertThat(result.sortOrder).isEqualTo(3)
        assertThat(result.category).isEqualTo(InvoiceItemCategory.PRODUCT)
        assertThat(result.description).isEqualTo("Beratung")
        assertThat(result.itemDescription).isEqualTo("Detaillierte Beschreibung")
    }

    @Test
    fun `every default falls back to the InvoiceItem-matching value when its column is null`() {
        val result = InvoicePositionRowMapper.fromRow(blankRow(), InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.positionId).isEmpty()
        assertThat(result.sortOrder).isZero()
        assertThat(result.category).isEqualTo(InvoiceItemCategory.SERVICE)
        assertThat(result.description).isEmpty()
        assertThat(result.itemDescription).isNull()
        assertThat(result.quantity).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(result.unit).isEqualTo(InvoiceItemUnit.HOUR)
        assertThat(result.unitPriceNet).isEqualTo(Money.ZERO)
        assertThat(result.taxRatePercent).isNull()
        assertThat(result.netAmount).isEqualTo(Money.ZERO)
        assertThat(result.vatAmount).isEqualTo(Money.ZERO)
        assertThat(result.grossAmount).isEqualTo(Money.ZERO)
        assertThat(result.receiptsCount).isZero()
        assertThat(result.receiptFileNames).isNull()
    }

    @Test
    fun `decodes an enum supplied as its raw ordinal number`() {
        val row = blankRow().withOverrides(3 to InvoiceItemCategory.TRIP.ordinal, 7 to InvoiceItemUnit.KILOGRAM.ordinal)

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.category).isEqualTo(InvoiceItemCategory.TRIP)
        assertThat(result.unit).isEqualTo(InvoiceItemUnit.KILOGRAM)
    }

    @Test
    fun `R7 - reads the position's own invoice currency, not always Euro`() {
        val row = blankRow().withOverrides(24 to Currency.CHF)

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.currency).isEqualTo(Currency.CHF)
    }

    @Test
    fun `R7 - a missing currency column defaults to Euro rather than throwing`() {
        val row = blankRow()

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.currency).isEqualTo(Currency.EUR)
    }

    @Test
    fun `R7 - decodes a currency supplied as its raw ordinal number`() {
        val row = blankRow().withOverrides(24 to Currency.GBP.ordinal)

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.currency).isEqualTo(Currency.GBP)
    }

    @Test
    fun `a negative unit price - a discount line - is carried through unchanged`() {
        val row = blankRow().withOverrides(6 to BigDecimal("2"), 8 to Money(BigDecimal("-10.00")))

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.quantity).isEqualByComparingTo(BigDecimal("2"))
        assertThat(result.unitPriceNet).isEqualTo(Money(BigDecimal("-10.00")))
        assertThat(result.unitPriceNet.isNegative()).isTrue()
    }

    @Test
    fun `the frozen tax-rate snapshot wins over the live taxRateRef rate when both are present`() {
        val row = blankRow().withOverrides(10 to BigDecimal("7.70"), 11 to BigDecimal("19.00"))

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.taxRatePercent).isEqualByComparingTo(BigDecimal("7.70"))
    }

    @Test
    fun `the live taxRateRef rate is the fallback when no snapshot has been taken yet`() {
        val row = blankRow().withOverrides(11 to BigDecimal("19.00"))

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.taxRatePercent).isEqualByComparingTo(BigDecimal("19.00"))
    }

    @Test
    fun `an item with neither a tax-rate snapshot nor a live rate maps a null percent, never a thrown exception`() {
        val row = blankRow() // both index 10 and 11 stay null

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.taxRatePercent).isNull()
    }

    @Test
    fun `a zero tax rate (zero-rated or reverse-charge) is kept distinct from a missing one`() {
        val row = blankRow().withOverrides(10 to BigDecimal.ZERO)

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(result.taxRatePercent).isNotNull()
        assertThat(result.taxRatePercent).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `maps tax detail, amount, context and article columns`() {
        val row = blankRow().withOverrides(
            9 to "AE",
            12 to "TR-1",
            13 to "EXEMPT-CODE",
            14 to "Innergemeinschaftliche Lieferung",
            15 to "Art. 138 MwStSystRL",
            16 to Money(BigDecimal("100.00")),
            17 to Money(BigDecimal("0.00")),
            18 to Money(BigDecimal("100.00")),
            19 to LocalDate.of(2026, 1, 1),
            20 to LocalDate.of(2026, 1, 31),
            21 to "CC-9",
            22 to "LS-2026-001",
            23 to "ART-42",
        )

        val result = InvoicePositionRowMapper.fromRow(row, InvoicePositionRowMapper.InvoiceIdentity(1L, ""), "SELLER-9" to "BUYER-9", listOf("beleg1.pdf", "beleg2.pdf"))

        assertThat(result.taxCategoryCode).isEqualTo("AE")
        assertThat(result.taxRuleCode).isEqualTo("TR-1")
        assertThat(result.exemptionReasonCode).isEqualTo("EXEMPT-CODE")
        assertThat(result.exemptionReasonText).isEqualTo("Innergemeinschaftliche Lieferung")
        assertThat(result.taxLegalBasis).isEqualTo("Art. 138 MwStSystRL")
        assertThat(result.netAmount).isEqualTo(Money(BigDecimal("100.00")))
        assertThat(result.vatAmount).isEqualTo(Money.ZERO)
        assertThat(result.grossAmount).isEqualTo(Money(BigDecimal("100.00")))
        assertThat(result.servicePeriodFrom).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(result.servicePeriodTo).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(result.costCenter).isEqualTo("CC-9")
        assertThat(result.deliveryNoteReference).isEqualTo("LS-2026-001")
        assertThat(result.articleNumber).isEqualTo("ART-42")
        assertThat(result.sellerItemIdentifier).isEqualTo("SELLER-9")
        assertThat(result.buyerItemIdentifier).isEqualTo("BUYER-9")
        assertThat(result.receiptsCount).isEqualTo(2)
        assertThat(result.receiptFileNames).isEqualTo("beleg1.pdf, beleg2.pdf")
    }

    @Test
    fun `a BT-155 without a BT-156, and the reverse, both write only the one identifier present`() {
        val sellerOnly = InvoicePositionRowMapper.fromRow(blankRow(), InvoicePositionRowMapper.InvoiceIdentity(1L, ""), "SELLER-ONLY" to null, emptyList())
        val buyerOnly = InvoicePositionRowMapper.fromRow(blankRow(), InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to "BUYER-ONLY", emptyList())

        assertThat(sellerOnly.sellerItemIdentifier).isEqualTo("SELLER-ONLY")
        assertThat(sellerOnly.buyerItemIdentifier).isNull()
        assertThat(buyerOnly.sellerItemIdentifier).isNull()
        assertThat(buyerOnly.buyerItemIdentifier).isEqualTo("BUYER-ONLY")
    }

    @Test
    fun `itemId reads the join-key column the batch lookups are keyed by`() {
        val row = blankRow().withOverrides(0 to 77L)
        assertThat(InvoicePositionRowMapper.itemId(row)).isEqualTo(77L)
    }

    @Test
    fun `itemId falls back to zero when the join-key column is null`() {
        assertThat(InvoicePositionRowMapper.itemId(blankRow())).isEqualTo(0L)
    }

    @Test
    fun `RK-6 - maps a TRIP row's own fixed net price, null for every other row`() {
        val fixed = InvoicePositionRowMapper.fromRow(
            blankRow().withOverrides(3 to InvoiceItemCategory.TRIP, 25 to Money(BigDecimal("250.00"))),
            InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList(),
        )
        val calculated = InvoicePositionRowMapper.fromRow(blankRow().withOverrides(3 to InvoiceItemCategory.TRIP), InvoicePositionRowMapper.InvoiceIdentity(1L, ""), null to null, emptyList())

        assertThat(fixed.tripFixedNetPrice).isEqualTo(Money(BigDecimal("250.00")))
        assertThat(calculated.tripFixedNetPrice).isNull()
    }

    // ── InvoicePositionRowMapper.tripDetailRows: trip -> 1 + n + m rows ─────────────────────────

    private fun position(fixedNetPrice: Money? = null) = InvoicePositionRowMapper.fromRow(
        blankRow().withOverrides(1 to "3", 3 to InvoiceItemCategory.TRIP, 25 to fixedNetPrice),
        InvoicePositionRowMapper.InvoiceIdentity(9L, "IP-2026-01-00009"), null to null, emptyList(),
    )

    private fun expense(kind: TripExpenseKind = TripExpenseKind.HOTEL, netAmount: Money = Money(BigDecimal("100.00")), receiptFileName: String? = null) =
        TripExpenseRowInput(
            kind = kind,
            description = "Hotelübernachtung",
            travelDate = LocalDate.of(2026, 4, 1),
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.PIECE,
            unitPriceNet = netAmount,
            netAmount = netAmount,
            receiptFileName = receiptFileName,
        )

    private fun perDiemDay(claimableAmount: Money = Money(BigDecimal("50.00"))) = PerDiemDayRowInput(
        date = LocalDate.of(2026, 4, 2),
        isArrivalDeparture = true,
        breakfastDeduction = true,
        lunchDeduction = false,
        dinnerDeduction = false,
        employerContribution = Money(BigDecimal("5.00")),
        claimableAmount = claimableAmount,
    )

    @Test
    fun `a trip with n expenses and m per-diem days expands into exactly 1 + n + m rows, expenses before per-diem days`() {
        val rows = InvoicePositionRowMapper.tripDetailRows(position(), listOf(expense(), expense(kind = TripExpenseKind.TRANSPORT)), listOf(perDiemDay()))

        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.tripKind }).containsExactly(TripExpenseKind.HOTEL, TripExpenseKind.TRANSPORT, null)
        assertThat(rows.last().perDiem).isNotNull()
    }

    @Test
    fun `every detail row is numbered continuously off the position's own positionId`() {
        val rows = InvoicePositionRowMapper.tripDetailRows(position(), listOf(expense(), expense()), listOf(perDiemDay()))

        // Position numbers are padded to 3 digits with unpadded sub-indices
        assertThat(rows.map { it.detailNumber }).containsExactly("003.1", "003.2", "003.3")
        assertThat(rows.map { it.positionId }).containsExactly("003.1", "003.2", "003.3")
        assertThat(rows).allMatch { it.parentPositionId == "3" && it.level == 1 }
    }

    @Test
    fun `every detail row inherits the position's own invoiceId, invoiceNumber and currency`() {
        val rows = InvoicePositionRowMapper.tripDetailRows(position(), listOf(expense()), emptyList())

        assertThat(rows.single().invoiceId).isEqualTo(9L)
        assertThat(rows.single().invoiceNumber).isEqualTo("IP-2026-01-00009")
        assertThat(rows.single().currency).isEqualTo(Currency.EUR)
    }

    @Test
    fun `an expense detail row carries its own kind, description, quantity, unit, price, net amount and receipt file name`() {
        val row = InvoicePositionRowMapper.tripDetailRows(
            position(),
            listOf(expense(kind = TripExpenseKind.MILEAGE, netAmount = Money(BigDecimal("42.00")), receiptFileName = "taxi.pdf")),
            emptyList(),
        ).single()

        assertThat(row.tripKind).isEqualTo(TripExpenseKind.MILEAGE)
        assertThat(row.description).isEqualTo("Hotelübernachtung")
        assertThat(row.travelDate).isEqualTo(LocalDate.of(2026, 4, 1))
        assertThat(row.unitPriceNet).isEqualTo(Money(BigDecimal("42.00")))
        assertThat(row.netAmount).isEqualTo(Money(BigDecimal("42.00")))
        assertThat(row.receiptsCount).isEqualTo(1)
        assertThat(row.receiptFileNames).isEqualTo("taxi.pdf")
        assertThat(row.perDiem).isNull()
    }

    @Test
    fun `an expense with no receipt of its own maps a zero receipt count and no file name`() {
        val row = InvoicePositionRowMapper.tripDetailRows(position(), listOf(expense(receiptFileName = null)), emptyList()).single()

        assertThat(row.receiptsCount).isZero()
        assertThat(row.receiptFileNames).isNull()
    }

    @Test
    fun `a per-diem detail row carries its own date, deductions, employer contribution and claimable amount, no tripKind`() {
        val row = InvoicePositionRowMapper.tripDetailRows(position(), emptyList(), listOf(perDiemDay(claimableAmount = Money(BigDecimal("60.00"))))).single()

        assertThat(row.tripKind).isNull()
        assertThat(row.travelDate).isEqualTo(LocalDate.of(2026, 4, 2))
        assertThat(row.netAmount).isEqualTo(Money(BigDecimal("60.00")))
        val perDiem = requireNotNull(row.perDiem)
        assertThat(perDiem.isArrivalDeparture).isTrue()
        assertThat(perDiem.breakfastDeduction).isTrue()
        assertThat(perDiem.lunchDeduction).isFalse()
        assertThat(perDiem.dinnerDeduction).isFalse()
        assertThat(perDiem.employerContribution).isEqualTo(Money(BigDecimal("5.00")))
        assertThat(perDiem.claimableAmount).isEqualTo(Money(BigDecimal("60.00")))
    }

    @Test
    fun `README border case 1 - a fixed-price trip with no expenses or per-diem days expands into zero detail rows`() {
        val rows = InvoicePositionRowMapper.tripDetailRows(position(fixedNetPrice = Money(BigDecimal("400.00"))), emptyList(), emptyList())

        assertThat(rows).isEmpty()
    }
}
