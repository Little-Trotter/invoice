package de.littletrotter.e2e.trip

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.export.TripWorkbookExportPort
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Properties
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The trip workbook and the invoice workbook's `Positionen` sheet
 * read back from a REAL, persisted trip via the REAL [TripWorkbookExportPort]/
 * [InvoiceWorkbookExportPort] beans, never a hand-built [de.littletrotter.application.export
 * .TripWorkbookView]/[de.littletrotter.application.export.InvoicePositionExportRow] the way
 * [de.littletrotter.infrastructure.export.workbook.trip.TripWorkbookExportServiceTest]/
 * [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriterTest] already do at
 * unit level — this suite's own value is the DB round trip those unit tests skip.
 *
 * Column layout is read by header label (the real German bundle,
 * `i18n/messages_de.properties` on the classpath), not by hard-coded index, so this suite does not
 * silently rot if a column is inserted — only if a label text itself changes.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class TripExcelExportE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var tripWorkbookExportPort: TripWorkbookExportPort

    @Inject
    lateinit var invoiceWorkbookExportPort: InvoiceWorkbookExportPort

    private val createdInvoiceIds = mutableListOf<Long>()

    /** The issued-invoice freeze trigger `trg_trip_expense_before_write` rejects a DELETE of an issued invoice's own
     *  `trip_expense` rows -- a fixture that reaches SENT with a trip in place must be excluded
     *  from [createdInvoiceIds] right after that transition (same convention
     *  [de.littletrotter.e2e.trip.TripLifecycleE2EIT] uses). */
    private fun excludeFromCleanup(invoiceId: Long) {
        createdInvoiceIds.remove(invoiceId)
    }

    @AfterEach
    fun cleanup() {
        // Each id gets its own transaction, tolerated individually: one undeletable fixture must
        // never roll back the removal of every other id in the same batch.
        createdInvoiceIds.forEach { id ->
            runCatching { QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, id)?.let(em::remove) } }
        }
        createdInvoiceIds.clear()
    }

    /** Reads the real DE bundle off the classpath (kotlin-invoice's `i18n/messages_de.properties`,
     *  reachable here via `quarkus.index-dependency`) — the exact convention
     *  [de.littletrotter.infrastructure.export.workbook.trip.TripWorkbookExportServiceTest
     *  .germanBundleTranslate] already uses, so this suite's column-header lookups match real
     *  wording, not an echoed key.
     *
     *  Every value also runs through [TermSubstitution]
     *  ([WordingProfile.DEFAULT], this suite never switches profiles): at the time, the wording
     *  rewrite had `tripExport.cover.subtitlePattern` carry a `{term:trip.singular}` marker, and
     *  `TripWorkbookExportService` runs `MessageFormat.format` on the resolved value with real
     *  positional args — an unresolved marker made `MessageFormat` throw
     *  `IllegalArgumentException("can't parse argument number: term:trip.singular")` for every
     *  test in this suite (reproduced live: this test failed this way the moment this worktree picked
     *  up that wording change, same root cause fixed in `InvoiceDtoMapper.documentLanguageTranslator`). The PO
     *  decision of 13.09.2026 later dropped that marker entirely (`tripExport.cover
     *  .subtitlePattern` now carries the literal "Dienstreise"/"Business trip"), but this fixture
     *  keeps applying [TermSubstitution] regardless — it is a no-op on that value now and still
     *  exercises the product term wherever another bundle value under test carries one. */
    private fun germanBundleTranslate(): (String) -> String {
        val bundle = Properties().also { props ->
            javaClass.classLoader.getResourceAsStream("i18n/messages_de.properties")!!.use { props.load(it.reader(Charsets.UTF_8)) }
        }
        return { key ->
            val raw = bundle.getProperty(key) ?: key
            TermSubstitution.apply(raw) { term -> TermResolver.resolve(WordingProfile.DEFAULT, java.util.Locale.GERMAN, term) }
        }
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────────────────────

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun cellText(cell: Cell?): String = when (cell?.cellType) {
        null -> ""
        CellType.STRING -> cell.stringCellValue
        CellType.NUMERIC -> cell.numericCellValue.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        else -> cell.toString()
    }

    private fun headerIndex(sheet: Sheet, label: String): Int {
        val header = sheet.getRow(0)
        for (i in 0 until header.lastCellNum) {
            if (cellText(header.getCell(i)) == label) return i
        }
        error("column '$label' not found on sheet '${sheet.sheetName}'")
    }

    private fun evaluate(workbook: XSSFWorkbook, cell: Cell) {
        workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)
    }

    // ─── TX-1 — trip workbook ─────────────────────────────────────────────────────────────────

    @Test
    fun `TX-1 trip workbook - sheets, Dienstreise values equal the DB, Posten rows equal expenses and days, formulas evaluate to the DB net, Delta for fixed price`() {
        val tag = "TX1"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().apply {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E TX-1"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Frankfurt",
                    purpose = "Kundentermin",
                    periodFrom = LocalDate.of(2026, 4, 1), periodTo = LocalDate.of(2026, 4, 2),
                    fixedNetPrice = Money.of("200.00"),
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.MILEAGE, description = "120 km", quantity = BigDecimal("120"), unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30"))),
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = LocalDate.of(2026, 4, 1), isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }

        val output = ByteArrayOutputStream()
        tripWorkbookExportPort.writeTripWorkbook(invoiceId, positionId, germanBundleTranslate(), output)

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            assertEquals(4, wb.numberOfSheets)
            assertEquals(listOf("Export", "Dienstreise", "Posten", "Verpflegung"), (0 until 4).map { wb.getSheetName(it) })

            val detail = wb.getSheet("Dienstreise")
            assertEquals("$tag Dienstreise Frankfurt", detail.getRow(1).getCell(1).stringCellValue)
            assertEquals("Kundentermin", detail.getRow(2).getCell(1).stringCellValue)
            // 120 km x 0.30 = 36.00 (expense) + 28.00 (per-diem day) = 64.00.
            assertEquals(64.00, detail.getRow(10).getCell(1).numericCellValue, 0.001, "calculated net must equal the DB expenses+per-diem sum")
            assertEquals(200.00, detail.getRow(11).getCell(1).numericCellValue, 0.001, "fixed price as stored")
            val deltaCell = detail.getRow(12).getCell(1)
            evaluate(wb, deltaCell)
            assertEquals(136.00, deltaCell.numericCellValue, 0.001, "Delta = fixed (200.00) - calculated (64.00)")

            val posten = wb.getSheet("Posten")
            assertEquals(3, posten.physicalNumberOfRows, "header + 1 expense + total; per-diem days have their own sheet")
            val expenseRow = posten.getRow(1)
            assertEquals("$positionId.1", expenseRow.getCell(0).stringCellValue)
            evaluate(wb, expenseRow.getCell(7))
            assertEquals(36.00, expenseRow.getCell(7).numericCellValue, 0.001, "mileage net must evaluate to the DB value")
            val totalRow = posten.getRow(2)
            evaluate(wb, totalRow.getCell(7))
            assertEquals(36.00, totalRow.getCell(7).numericCellValue, 0.001)

            val perDiem = wb.getSheet("Verpflegung")
            assertEquals(4, perDiem.physicalNumberOfRows, "header + 1 per-diem day + total + overview scope")
            assertEquals("Verpflegungspauschale", perDiem.getRow(1).getCell(0).stringCellValue)
            assertEquals("Team-Reise Verpflegung · 24 Std.", perDiem.getRow(1).getCell(1).stringCellValue)
            assertEquals(28.00, perDiem.getRow(1).getCell(5).numericCellValue, 0.001)
            assertEquals("Summe", perDiem.getRow(2).getCell(2).stringCellValue)
            assertEquals(28.00, perDiem.getRow(2).getCell(5).numericCellValue, 0.001)
            assertEquals("1 Tag · Kundentermin", perDiem.getRow(3).getCell(0).stringCellValue)
        }
    }

    // ─── TX-2 — invoice workbook mode A (level-0/level-1 rows) and mode B (CSV excludes details) ─

    @Test
    fun `TX-2 invoice workbook mode A - level-0 and level-1 rows, invoice sum ignores details -- mode B CSV excludes detail rows`() {
        val tag = "TX2"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        val tripPositionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Stuttgart",
                    expenses = listOf(
                        TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00")),
                        TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("30.00")),
                    ),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        excludeFromCleanup(invoiceId)
        val invoiceNet = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.totals.netAmount.amount }

        // ── Mode A ────────────────────────────────────────────────────────────────────────────
        val output = ByteArrayOutputStream()
        invoiceWorkbookExportPort.writeWorkbook(invoiceId, "$tag e2e", germanBundleTranslate(), output)

        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            assertEquals(listOf("Export", "Rechnungen", "Positionen", "Journal"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })

            val sheet = wb.getSheet("Positionen")
            val levelIdx = headerIndex(sheet, "Ebene")
            val parentIdx = headerIndex(sheet, "Übergeordnet")
            val positionIdx = headerIndex(sheet, "Positionsnummer")
            val netIdx = headerIndex(sheet, "Netto")

            data class Row(val level: String, val parent: String, val positionId: String, val net: Double)
            val rows = (1..sheet.lastRowNum).mapNotNull { r ->
                val row = sheet.getRow(r) ?: return@mapNotNull null
                if (row.getCell(positionIdx) == null && row.getCell(levelIdx) == null) return@mapNotNull null
                Row(
                    level = cellText(row.getCell(levelIdx)),
                    parent = cellText(row.getCell(parentIdx)),
                    positionId = cellText(row.getCell(positionIdx)),
                    net = row.getCell(netIdx)?.let { if (it.cellType == CellType.NUMERIC) it.numericCellValue else 0.0 } ?: 0.0,
                )
            }

            val levelZero = rows.filter { it.level == "0" }
            val levelOne = rows.filter { it.level == "1" }
            assertEquals(2, levelZero.size, "one level-0 row per position: service + trip")
            assertEquals(2, levelOne.size, "one level-1 row per trip expense: hotel + taxi")
            assertTrue(levelOne.all { it.parent == tripPositionId }, "every detail row's Übergeordnet must name the owning trip position")

            val levelZeroSum = levelZero.sumOf { it.net }
            assertEquals(0, invoiceNet.setScale(2).compareTo(BigDecimal.valueOf(levelZeroSum).setScale(2)), "invoice-level control must sum only level-0 rows, never double-counting the detail rows")
        }

        // ── Mode B — bulk CSV, scoped to this test's own recipient so the shared database's
        //    other rows never pollute the count. ──────────────────────────────────────────────
        val csvOutput = ByteArrayOutputStream()
        invoiceWorkbookExportPort.writePositionsCsv(InvoiceExportFilter(recipientId = parties.recipientId), germanBundleTranslate(), csvOutput)
        val csvText = String(csvOutput.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
        val lines = csvText.split("\r\n").filter { it.isNotBlank() }
        // header + 2 level-0 rows (service, trip) -- the 2 detail rows must never appear.
        assertEquals(3, lines.size, "mode B CSV must carry only level-0 rows: $lines")
    }
}
