package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.domain.invoice.addTripExpense
import de.littletrotter.domain.invoice.addPerDiemEntry
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.export.port.outbound.TripExportNotFoundException
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.aTripExpense
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.infrastructure.export.workbook.readWorkbook
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Properties
import de.littletrotter.domain.value.CustomerNumber

/**
 * [TripWorkbookExportService] assembles the trip workbook's four sheets (`Export`,
 * `Dienstreise`, `Posten`, `Verpflegung`) from a real [Invoice]/[InvoiceItem] domain fixture through
 * [InvoiceDtoMapper], the same "read the actual mapper output, never a shortcut" discipline
 * [de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportServiceTest] already
 * follows for the per-invoice workbook — a POI reader accepting the produced bytes ("opens without
 * repair") is exercised implicitly by every [readWorkbook] call below.
 */
class TripWorkbookExportServiceTest {

    private val identityTranslate: (String) -> String = { key -> key }
    private val applicationIdentity = ApplicationIdentity("9.9.9-test")

    private fun buildService(repository: InvoiceRepository): TripWorkbookExportService =
        TripWorkbookExportService(
            repository, InvoiceDtoMapper(), SystemAuditActorProvider(), applicationIdentity,
            de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        )

    private fun repositoryReturning(invoice: Invoice): InvoiceRepository {
        val repository: InvoiceRepository = mock()
        whenever(repository.findByIdWithItems(invoice.id!!)).thenReturn(invoice)
        return repository
    }

    /** A minimal, valid invoice carrying exactly one TRIP position — every test starts here and
     *  customises the trip item before calling [invoiceWith]. */
    private fun tripItem(): InvoiceItem = anInvoiceItem(
        category = InvoiceItemCategory.TRIP,
        description = "Workshop Hamburg",
        itemDescription = "Kundentermin vor Ort",
        servicePeriodFrom = LocalDate.of(2026, 2, 8),
        servicePeriodTo = LocalDate.of(2026, 2, 9),
        costCenter = "KST-42",
    )

    private fun invoiceWith(trip: InvoiceItem, invoiceId: Long = 1L, currency: Currency = Currency.EUR): Invoice {
        val invoice = anInvoice(
            id = invoiceId,
            invoiceNumber = InvoiceNumber("RE-2026-0001"),
            invoiceDate = LocalDate.of(2026, 2, 10),
            currency = currency,
            senderProfile = aSenderProfile { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") },
            recipient = aRecipient { name = "Kunde GmbH"; customerNumber = CustomerNumber("K-1") },
            items = listOf(trip),
        )
        trip.applyTaxRule(TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()
        return invoice
    }

    private fun expense(kind: TripExpenseKind, description: String, quantity: String, unitPrice: String, item: InvoiceItem): TripExpense =
        aTripExpense(
            invoiceItem = item,
            kind = kind,
            description = description,
            travelDate = LocalDate.of(2026, 2, 8),
            quantity = BigDecimal(quantity),
            unit = InvoiceItemUnit.FLAT_RATE,
            unitPriceNet = Money.of(unitPrice),
        ).also { it.calculateNetAmount() }

    private fun perDiemDay(
        item: InvoiceItem,
        amount: String,
        description: String = "Workshop Nürnberg",
        date: LocalDate = LocalDate.of(2026, 2, 8),
        participant: String = "",
        arrivalDeparture: Boolean = false,
        breakfast: Boolean = false,
        lunch: Boolean = false,
        dinner: Boolean = false,
        baseRate: String = amount,
        employerContribution: String = "0.00",
    ): TravelExpenseEntry =
        TravelExpenseEntry().edit {
            invoiceItem = item
            travelDate = date
            this.participant = participant
            isArrivalDeparture = arrivalDeparture
            breakfastDeduction = breakfast
            lunchDeduction = lunch
            dinnerDeduction = dinner
            calculatedAmount = Money.of(amount)
            fullDayRateSnapshot = Money.of(baseRate)
            arrivalDepartureRateSnapshot = Money.of(baseRate)
            this.employerContribution = Money.of(employerContribution)
            this.description = description
            perDiemRate = PerDiemRate().edit { id = 1L; fullDayRate = Money.of("28.00") }
        }

    // ── Sheet order and names ────────────────────────────────────────────────────────────────

    @Test
    fun `writes four sheets including a dedicated Verpflegung sheet`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.HOTEL, "Hotel Stern", "1", "120.00", trip))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.numberOfSheets).isEqualTo(4)
            assertThat(listOf(0, 1, 2, 3).map { wb.getSheetName(it) }).containsExactly("Export", "Dienstreise", "Posten", "Verpflegung")
        }
    }

    @Test
    fun `the Export cover names the product line and the trip subtitle, record counts match the two data sheets`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.HOTEL, "Hotel Stern", "1", "120.00", trip))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val exportSheet = wb.getSheet("Export")
            assertThat(exportSheet.getRow(0).getCell(0).stringCellValue).isEqualTo(applicationIdentity.productLine())
            assertThat(exportSheet.getRow(1).getCell(0).stringCellValue)
                .contains("Workshop Hamburg")
                .contains("RE-2026-0001")
        }
    }

    // ── Dienstreise sheet values ─────────────────────────────────────────────────────────────

    @Test
    fun `Dienstreise sheet carries the trip's own field values, tax treatment and receipt count`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.HOTEL, "Hotel Stern", "1", "120.00", trip))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Dienstreise")
            assertThat(sheet.getRow(1).getCell(1).stringCellValue).isEqualTo("Workshop Hamburg")
            assertThat(sheet.getRow(2).getCell(1).stringCellValue).isEqualTo("Kundentermin vor Ort")
            assertThat(sheet.getRow(3).getCell(1).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 8))
            assertThat(sheet.getRow(4).getCell(1).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 9))
            assertThat(sheet.getRow(5).getCell(1).stringCellValue).isEqualTo("RE-2026-0001")
            assertThat(sheet.getRow(6).getCell(1).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 10))
            assertThat(sheet.getRow(7).getCell(1).stringCellValue).isEqualTo("Kunde GmbH")
            assertThat(sheet.getRow(8).getCell(1).stringCellValue).isEqualTo("KST-42")
            assertThat(sheet.getRow(9).getCell(1).stringCellValue).contains("19").contains("Landesreferenzsatz der Anbieterregistrierung")
            assertThat(sheet.getRow(10).getCell(1).numericCellValue).isEqualTo(120.00)
            assertThat(sheet.getRow(13).getCell(1).numericCellValue).isEqualTo(0.0) // one receipt-free trip
        }
    }

    @Test
    fun `without a fixed price, the fixed-price and delta rows both show the no-value label`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.OTHER, "Taxi", "1", "40.00", trip))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Dienstreise")
            assertThat(sheet.getRow(11).getCell(1).stringCellValue).isEqualTo("—")
            assertThat(sheet.getRow(12).getCell(1).stringCellValue).isEqualTo("—")
        }
    }

    // ── Fixed price vs. calculated, the Δ formula and its highlight ─────────────────────────

    @Test
    fun `a fixed price writes a Δ formula (fixed minus calculated) that evaluates correctly and is highlighted`() {
        val trip = tripItem().edit { tripFixedNetPrice = Money.of("500.00") }
        trip.addTripExpense(expense(TripExpenseKind.MILEAGE, "120 km", "120", "0.30", trip)) // 36.00
        trip.addPerDiemEntry(perDiemDay(trip, "28.00", participant = "Kundentermin"))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Dienstreise")
            assertThat(sheet.getRow(10).getCell(1).numericCellValue).isEqualTo(64.00) // calculated net
            assertThat(sheet.getRow(11).getCell(1).numericCellValue).isEqualTo(500.00) // fixed price
            val deltaCell = sheet.getRow(12).getCell(1)
            assertThat(deltaCell.cellFormula).isEqualTo("B12-B11")
            evaluate(wb, deltaCell)
            assertThat(deltaCell.numericCellValue).isEqualTo(436.00)

            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isEqualTo(1)
            val formatting = sheet.sheetConditionalFormatting.getConditionalFormattingAt(0)
            val region = formatting.formattingRanges.single()
            assertThat(region.firstRow).isEqualTo(12)
            assertThat(region.lastRow).isEqualTo(12)
            assertThat(region.firstColumn).isEqualTo(1)
        }
    }

    // ── Empty trip, fixed price only ─────────────────────────────────────────────────────────

    @Test
    fun `an empty trip with only a fixed price still produces a valid workbook - Posten has only the total row, Delta equals the fixed price`() {
        val trip = tripItem().edit { tripFixedNetPrice = Money.of("300.00") }
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            val posten = wb.getSheet("Posten")
            assertThat(posten.physicalNumberOfRows).isEqualTo(2) // header + total row only
            val totalRow = posten.getRow(1)
            evaluate(wb, totalRow.getCell(7))
            assertThat(totalRow.getCell(7).numericCellValue).isEqualTo(0.0) // no expenses/per-diem days: a plain 0, not a SUM formula

            val detail = wb.getSheet("Dienstreise")
            val deltaCell = detail.getRow(12).getCell(1)
            evaluate(wb, deltaCell)
            assertThat(deltaCell.numericCellValue).isEqualTo(300.00)
        }
    }

    // ── Posten rows and formulas ──────────────────────────────────────────────────────────────

    @Test
    fun `Posten writes expenses while Verpflegung carries the per-diem detail`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.HOTEL, "Hotel Stern", "1", "120.00", trip))
        trip.addTripExpense(expense(TripExpenseKind.MILEAGE, "120 km", "120", "0.30", trip))
        trip.addPerDiemEntry(perDiemDay(trip, "28.00", participant = "Kundentermin"))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Posten")
            assertThat(sheet.physicalNumberOfRows).isEqualTo(4) // header + 2 expenses + total

            val hotelRow = sheet.getRow(1)
            assertThat(hotelRow.getCell(0).stringCellValue).isEqualTo("${trip.positionId}.1")
            assertThat(hotelRow.getCell(7).cellFormula).isEqualTo("E2*G2")
            evaluate(wb, hotelRow.getCell(7))
            assertThat(hotelRow.getCell(7).numericCellValue).isEqualTo(120.00)

            val mileageRow = sheet.getRow(2)
            assertThat(mileageRow.getCell(0).stringCellValue).isEqualTo("${trip.positionId}.2")
            assertThat(mileageRow.getCell(7).cellFormula).isEqualTo("E3*G3")
            evaluate(wb, mileageRow.getCell(7))
            assertThat(mileageRow.getCell(7).numericCellValue).isEqualTo(36.00)

            val perDiemSheet = wb.getSheet("Verpflegung")
            assertThat(perDiemSheet.physicalNumberOfRows).isEqualTo(4)
            assertThat(perDiemSheet.getRow(1).getCell(0).stringCellValue).isEqualTo("Verpflegungspauschale")
            assertThat(perDiemSheet.getRow(1).getCell(1).stringCellValue).isEqualTo("Workshop Nürnberg · 24 Std.")
            assertThat(perDiemSheet.getRow(1).getCell(5).numericCellValue).isEqualTo(28.00)
            assertThat(perDiemSheet.getRow(2).getCell(2).stringCellValue).isEqualTo("Summe")
            assertThat(perDiemSheet.getRow(2).getCell(5).numericCellValue).isEqualTo(28.00)
            assertThat(perDiemSheet.getRow(3).getCell(0).stringCellValue).isEqualTo("1 Tag · Kundentermin")

            val totalRow = sheet.getRow(3)
            assertThat(totalRow.getCell(7).cellFormula).isEqualTo("SUM(H2:H3)")
            evaluate(wb, totalRow.getCell(7))
            assertThat(totalRow.getCell(7).numericCellValue).isEqualTo(156.00)
        }
    }

    @Test
    fun `Verpflegung scope does not invent a participant for blank names`() {
        val trip = tripItem()
        trip.addPerDiemEntry(perDiemDay(trip, "28.00", participant = "   "))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.getSheet("Verpflegung").getRow(3).getCell(0).stringCellValue).isEqualTo("1 Tag")
        }
    }

    @Test
    fun `Verpflegung mirrors overview order descriptions amounts and scope`() {
        val trip = tripItem()
        trip.addPerDiemEntry(
            perDiemDay(
                trip, amount = "23.00", date = LocalDate.of(2026, 2, 10), participant = "Max Muster",
                baseRate = "28.00", employerContribution = "5.00",
            ),
        )
        trip.addPerDiemEntry(
            perDiemDay(
                trip, amount = "8.00", date = LocalDate.of(2026, 2, 8), participant = "Max Muster",
                arrivalDeparture = true, breakfast = true, baseRate = "14.00",
            ),
        )
        trip.addPerDiemEntry(
            perDiemDay(
                trip, amount = "2.00", date = LocalDate.of(2026, 2, 8), participant = "Erika Beispiel",
                arrivalDeparture = true, lunch = true, dinner = true, baseRate = "14.00",
            ),
        )
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, germanBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Verpflegung")
            assertThat((0..6).map { sheet.getRow(0).getCell(it).stringCellValue }).containsExactly(
                "Art", "Beschreibung", "Datum", "Menge", "Einzelpreis", "Gesamt", "Beleg",
            )
            assertThat((1..3).map { sheet.getRow(it).getCell(2).localDateTimeCellValue.toLocalDate() }).containsExactly(
                LocalDate.of(2026, 2, 8), LocalDate.of(2026, 2, 8), LocalDate.of(2026, 2, 10),
            )
            assertThat((1..3).map { sheet.getRow(it).getCell(1).stringCellValue }).containsExactly(
                "Workshop Nürnberg · An-/Abreise · − Früh. · Max Muster",
                "Workshop Nürnberg · An-/Abreise · − Mittag − Abend · Erika Beispiel",
                "Workshop Nürnberg · 24 Std. · Max Muster",
            )
            assertThat((1..3).map { sheet.getRow(it).getCell(3).numericCellValue }).containsOnly(BigDecimal.ONE.toDouble())
            assertThat((1..3).map { sheet.getRow(it).getCell(4).numericCellValue }).containsExactly(14.0, 14.0, 28.0)
            assertThat((1..3).map { sheet.getRow(it).getCell(5).numericCellValue }).containsExactly(8.0, 2.0, 23.0)
            assertThat((1..3).map { sheet.getRow(it).getCell(6).cellType })
                .allMatch { it == org.apache.poi.ss.usermodel.CellType.BLANK }
            assertThat(sheet.getRow(4).getCell(2).stringCellValue).isEqualTo("Summe")
            assertThat(sheet.getRow(4).getCell(5).numericCellValue).isEqualTo(33.0)
            assertThat(sheet.getRow(5).getCell(0).stringCellValue).isEqualTo("2 Tage · 2 Teilnehmer")
        }
    }

    // ── 100 expenses (README border case 7) ──────────────────────────────────────────────────

    @Test
    fun `100 expenses write 100 rows plus the total row, sheet still opens`() {
        val trip = tripItem()
        repeat(EXPENSE_COUNT) { i ->
            trip.addTripExpense(expense(TripExpenseKind.OTHER, "Posten $i", "1", "10.00", trip))
        }
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            val sheet = wb.getSheet("Posten")
            assertThat(sheet.physicalNumberOfRows).isEqualTo(EXPENSE_COUNT + 2) // header + 100 + total
            assertThat(sheet.getRow(EXPENSE_COUNT).getCell(0).stringCellValue).isEqualTo("${trip.positionId}.$EXPENSE_COUNT")
            val totalRow = sheet.getRow(EXPENSE_COUNT + 1)
            evaluate(wb, totalRow.getCell(7))
            assertThat(totalRow.getCell(7).numericCellValue).isEqualTo(EXPENSE_COUNT * 10.00)
        }
    }

    // ── EN locale ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an EN translate function renders the sheets' field and column labels in English`() {
        val trip = tripItem()
        trip.addTripExpense(expense(TripExpenseKind.HOTEL, "Hotel Stern", "1", "120.00", trip))
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, englishBundleTranslate(), output)

        readWorkbook(output.toByteArray()).use { wb ->
            val detail = wb.getSheet("Dienstreise")
            assertThat(detail.getRow(0).getCell(0).stringCellValue).isEqualTo("Field")
            assertThat(detail.getRow(1).getCell(0).stringCellValue).isEqualTo("Title")
            assertThat(detail.getRow(7).getCell(0).stringCellValue).isEqualTo("Recipient")

            val posten = wb.getSheet("Posten")
            assertThat(posten.getRow(0).getCell(1).stringCellValue).isEqualTo("Kind")
            assertThat(posten.getRow(0).getCell(7).stringCellValue).isEqualTo("Net")
        }
    }

    // ── Not found ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown invoice id throws TripExportNotFoundException, never writing anything`() {
        val repository: InvoiceRepository = mock()
        whenever(repository.findByIdWithItems(404L)).thenReturn(null)

        val output = ByteArrayOutputStream()
        assertThatThrownBy { buildService(repository).writeTripWorkbook(404L, "1", identityTranslate, output) }
            .isInstanceOf(TripExportNotFoundException::class.java)
        assertThat(output.toByteArray()).isEmpty()
    }

    @Test
    fun `a SERVICE position at the given id throws TripExportNotFoundException - nothing to export`() {
        val service = anInvoiceItem(
            category = InvoiceItemCategory.SERVICE,
            description = "Beratung",
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.HOUR,
            unitPriceNet = Money.of("100.00"),
        )
        val invoice = invoiceWith(service)

        val output = ByteArrayOutputStream()
        assertThatThrownBy { buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, service.positionId, identityTranslate, output) }
            .isInstanceOf(TripExportNotFoundException::class.java)
    }

    @Test
    fun `a positionId absent from the invoice's items throws TripExportNotFoundException`() {
        val trip = tripItem()
        val invoice = invoiceWith(trip)

        val output = ByteArrayOutputStream()
        assertThatThrownBy { buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, "no-such-position", identityTranslate, output) }
            .isInstanceOf(TripExportNotFoundException::class.java)
        assertThat(output.toByteArray()).isEmpty()
    }

    @Test
    fun `the matching item is found even when preceded by other, non-matching positions`() {
        val service = anInvoiceItem(
            category = InvoiceItemCategory.SERVICE,
            description = "Beratung",
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.HOUR,
            unitPriceNet = Money.of("100.00"),
        )
        val trip = tripItem()
        val invoice = anInvoice(
            id = 1L,
            invoiceNumber = InvoiceNumber("RE-2026-0001"),
            invoiceDate = LocalDate.of(2026, 2, 10),
            currency = Currency.EUR,
            senderProfile = aSenderProfile { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") },
            recipient = aRecipient { name = "Kunde GmbH"; customerNumber = CustomerNumber("K-1") },
            items = listOf(service, trip),
        )
        listOf(service, trip).forEach {
            it.applyTaxRule(TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) })
            it.calculateAmounts()
        }

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)
        val workbook = readWorkbook(output.toByteArray())
        assertThat(workbook.getSheet("Dienstreise")).isNotNull
    }

    @Test
    fun `an invoice with no recipient still exports, leaving customerName blank`() {
        val trip = tripItem()
        val invoice = anInvoice(
            id = 1L,
            invoiceNumber = InvoiceNumber("RE-2026-0001"),
            invoiceDate = LocalDate.of(2026, 2, 10),
            currency = Currency.EUR,
            senderProfile = aSenderProfile { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") },
            items = listOf(trip),
        )
        trip.applyTaxRule(TaxRate().edit { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val output = ByteArrayOutputStream()
        buildService(repositoryReturning(invoice)).writeTripWorkbook(1L, trip.positionId, identityTranslate, output)
        val workbook = readWorkbook(output.toByteArray())
        assertThat(workbook.getSheet("Dienstreise")).isNotNull
    }

    // ── File name rule ────────────────────────────────────────────────────────────────────────

    @Test
    fun `fileName on the service instance delegates to the tripWorkbookFileName rule`() {
        val service = buildService(mock())
        assertThat(service.fileName("RE/2026/001", 1L, "3")).isEqualTo(tripWorkbookFileName("RE/2026/001", 1L, "3"))
    }

    @Test
    fun `tripWorkbookFileName is lower-case, sanitises slashes and falls back to the invoice id when blank`() {
        assertThat(tripWorkbookFileName("RE/2026/001", 1L, "3")).isEqualTo("dienstreise_re-2026-001_3.xlsx")
        assertThat(tripWorkbookFileName("", 42L, "1")).isEqualTo("dienstreise_id-42_1.xlsx")
        assertThat(tripWorkbookFileName("   ", 42L, "1")).isEqualTo("dienstreise_id-42_1.xlsx")
        assertThat(tripWorkbookFileName("RE-2026-002", 7L, "2")).isEqualTo("dienstreise_re-2026-002_2.xlsx")
    }

    private fun evaluate(workbook: XSSFWorkbook, cell: org.apache.poi.ss.usermodel.Cell) {
        workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)
    }

    /** Reads the real EN bundle off the test classpath — the same file `messages.properties` the
     *  running application resolves `en` locale labels from — so this test proves the actual
     *  bundle content renders, not a hand-picked string. */
    private fun englishBundleTranslate(): (String) -> String = bundleTranslate("messages.properties")

    /** As [englishBundleTranslate], for `messages_de.properties` — used whenever a test asserts
     *  actual composed wording (the subtitle sentence, the tax-treatment sentence, the "—"
     *  no-value marker): [identityTranslate] echoes the key itself, which never contains the
     *  `{0}`/real text a [java.text.MessageFormat] pattern or a plain bundle value would. */
    private fun germanBundleTranslate(): (String) -> String = bundleTranslate("messages_de.properties")

    /** Real callers of this `(String) -> String` shape are always term-substitution-aware
     *  (`UiTranslations.tr`/`TranslationService.getTranslation`); [TermSubstitution.apply] is a
     *  no-op on this particular bundle value now that the PO decision of 13.09.2026 made a
     *  business trip's wording ("Dienstreise") a plain literal, no longer a `{term:...}` marker —
     *  [WordingProfile.HANDWERK] is passed here only because [TermResolver.resolve] still needs a
     *  profile argument, not because the trip text depends on it. */
    private fun bundleTranslate(bundleFileName: String): (String) -> String {
        val bundle = Properties().also { props ->
            javaClass.classLoader.getResourceAsStream("i18n/$bundleFileName")!!.use { props.load(it.reader(Charsets.UTF_8)) }
        }
        val locale = if (bundleFileName.endsWith("_de.properties")) java.util.Locale.GERMAN else java.util.Locale.ENGLISH
        return { key ->
            val raw = bundle.getProperty(key) ?: key
            TermSubstitution.apply(raw) { term -> TermResolver.resolve(WordingProfile.HANDWERK, locale, term) }
        }
    }

    private companion object {
        const val EXPENSE_COUNT = 100
    }
}
