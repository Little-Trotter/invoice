package de.littletrotter.application.invoice

import de.littletrotter.application.export.TripExportNotFoundException
import de.littletrotter.application.export.TripWorkbookExportPort
import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.InvoiceItemUnit.KILOMETER
import de.littletrotter.domain.value.Email
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [InvoiceDtoMapper] fills the export DTO's
 * [de.littletrotter.shared.InvoiceItemDto.category]/`.trip` for a TRIP position; every other
 * category carries `trip = null`.
 */
class InvoiceDtoMapperTripTest {

    private fun invoiceWithTrip(trip: InvoiceItem): Invoice {
        val sender = SenderProfile().apply { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") }
        val serviceItem = InvoiceItem().apply {
            category = InvoiceItemCategory.SERVICE
            description = "Beratung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.HOUR
            unitPriceNet = Money.of("100.00")
        }.also { it.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }) }
        serviceItem.calculateAmounts()

        val invoice = Invoice().apply { id = 1L; senderProfile = sender }
        invoice.addItem(serviceItem)
        invoice.addItem(trip)
        return invoice
    }

    @Test
    fun `a SERVICE position carries category SERVICE and no trip detail`() {
        val invoice = Invoice().apply {
            id = 1L
            senderProfile = SenderProfile().apply { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") }
        }
        val service = InvoiceItem().apply {
            category = InvoiceItemCategory.SERVICE
            description = "Beratung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.HOUR
            unitPriceNet = Money.of("100.00")
        }.also { it.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }) }
        service.calculateAmounts()
        invoice.addItem(service)

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val itemDto = dto.items.single()
        assertEquals(InvoiceItemCategory.SERVICE, itemDto.category)
        assertNull(itemDto.trip)
    }

    @Test
    fun `a TRIP position carries category TRIP with expenses and per-diem days, calculatedNet independent of the printed net`() {
        val dayRate = PerDiemRate().apply { id = 4L; fullDayRate = Money.of("28.00") }
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise Hamburg"
            itemDescription = "Kundentermin"
            tripDetailedOnInvoice = true
            tripFixedNetPrice = Money.of("500.00")
        }
        trip.tripExpenses.add(
            TripExpense().apply {
                invoiceItem = trip
                kind = TripExpenseKind.MILEAGE
                description = "120 km"
                travelDate = LocalDate.of(2026, 2, 8)
                quantity = BigDecimal("120")
                unit = InvoiceItemUnit.KILOMETER
                unitPriceNet = Money.of("0.30")
                calculateNetAmount()
            },
        )
        trip.perDiemEntries.add(
            TravelExpenseEntry().apply {
                invoiceItem = trip
                travelDate = LocalDate.of(2026, 2, 8)
                isArrivalDeparture = false
                breakfastDeduction = false; lunchDeduction = false; dinnerDeduction = false
                calculatedAmount = Money.of("28.00")
                perDiemRate = dayRate
            },
        )
        val invoice = invoiceWithTrip(trip)
        // TRIP always inherits the invoice's main-service rate (R2) — resolved by the invoice's
        // own extension in production (TripService); the mapper only reads the frozen snapshot.
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val tripDto = dto.items.single { it.category == InvoiceItemCategory.TRIP }
        val detail = requireNotNull(tripDto.trip)
        assertTrue(detail.detailedOnInvoice)
        assertEquals(Money.of("500.00"), detail.fixedNetPrice)
        // calculatedNet (36.00 mileage + 28.00 per-diem = 64.00) stays independent of the printed
        // fixed price (500.00) — the Excel Delta column needs both.
        assertEquals(Money.of("64.00"), detail.calculatedNet)
        assertEquals(Money.of("500.00"), tripDto.netAmount, "the printed net follows the fixed price")

        val expenseDto = detail.expenses.single()
        assertEquals("trip.kind.mileage", expenseDto.kindLabelKey)
        assertEquals("120 km", expenseDto.description)
        assertEquals(Money.of("36.00"), expenseDto.netAmount)

        val dayDto = detail.perDiemDays.single()
        assertEquals(Money.of("28.00"), dayDto.claimableAmount)
    }

    @Test
    fun `a per-diem day's baseRate is the arrival-departure rate for such a day and the full-day rate otherwise`() {
        val dayRate = PerDiemRate().apply { id = 4L; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00") }
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise Hamburg"
        }
        trip.perDiemEntries.add(
            TravelExpenseEntry().apply {
                invoiceItem = trip
                travelDate = LocalDate.of(2026, 2, 8)
                isArrivalDeparture = true
                breakfastDeduction = false; lunchDeduction = false; dinnerDeduction = false
                calculatedAmount = Money.of("14.00")
                fullDayRateSnapshot = Money.of("28.00")
                arrivalDepartureRateSnapshot = Money.of("14.00")
                perDiemRate = dayRate
            },
        )
        trip.perDiemEntries.add(
            TravelExpenseEntry().apply {
                invoiceItem = trip
                travelDate = LocalDate.of(2026, 2, 9)
                isArrivalDeparture = false
                breakfastDeduction = false; lunchDeduction = false; dinnerDeduction = false
                calculatedAmount = Money.of("28.00")
                fullDayRateSnapshot = Money.of("28.00")
                arrivalDepartureRateSnapshot = Money.of("14.00")
                perDiemRate = dayRate
            },
        )
        val invoice = invoiceWithTrip(trip)
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val detail = requireNotNull(dto.items.single { it.category == InvoiceItemCategory.TRIP }.trip)
        val days = detail.perDiemDays.sortedBy { it.date }
        assertEquals(Money.of("14.00"), days[0].baseRate, "an arrival/departure day's own base rate")
        assertEquals(Money.of("28.00"), days[1].baseRate, "a full day's own base rate")
    }

    @Test
    fun `a TRIP position without a fixed price prints its calculated sum, calculatedNet matches`() {
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Taxi zum Flughafen"
        }
        trip.tripExpenses.add(
            TripExpense().apply {
                invoiceItem = trip
                kind = TripExpenseKind.OTHER
                description = "Taxi"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.FLAT_RATE
                unitPriceNet = Money.of("40.00")
                calculateNetAmount()
            },
        )
        val invoice = invoiceWithTrip(trip)
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val tripDto = dto.items.single { it.category == InvoiceItemCategory.TRIP }
        val detail = requireNotNull(tripDto.trip)
        assertNull(detail.fixedNetPrice)
        assertEquals(Money.of("40.00"), detail.calculatedNet)
        assertEquals(Money.of("40.00"), tripDto.netAmount)
    }

    @Test
    fun `a blank travel-time description prints the goodwill text only when no rate was provided`() {
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Kundentermin"
        }
        fun expense(expenseKind: TripExpenseKind, text: String, withRate: Boolean) = TripExpense().apply {
            invoiceItem = trip
            kind = expenseKind
            description = text
            rateProvided = withRate
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money.of("10.00")
            calculateNetAmount()
        }
        trip.tripExpenses.add(expense(TripExpenseKind.TRAVEL_TIME, "", withRate = false))
        trip.tripExpenses.add(expense(TripExpenseKind.TRAVEL_TIME, " ", withRate = true))
        trip.tripExpenses.add(expense(TripExpenseKind.OTHER, "", withRate = false))
        trip.tripExpenses.add(expense(TripExpenseKind.TRAVEL_TIME, "Anreise Vortag", withRate = false))
        val invoice = invoiceWithTrip(trip)
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val expenses = requireNotNull(InvoiceDtoMapper().mapToDto(invoice).items.single { it.category == InvoiceItemCategory.TRIP }.trip).expenses

        assertEquals(
            listOf(TripExpense.TRAVEL_TIME_KULANZ_TEXT, "", "", "Anreise Vortag"),
            expenses.map { it.description },
        )
    }

    @Test
    fun `a buyer item description is trimmed, a blank or missing one maps to null`() {
        val invoice = Invoice().apply {
            id = 1L
            senderProfile = SenderProfile().apply { name = "Seller GmbH"; email = Email.ofNullable("seller@example.org") }
        }
        listOf(" Schraube M8 ", "   ", null).forEach { buyerText ->
            val item = InvoiceItem().apply {
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                buyerItemDescription = buyerText
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
            }.also { it.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }) }
            item.calculateAmounts()
            invoice.addItem(item)
        }

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        assertEquals(listOf("Schraube M8", null, null), dto.items.map { it.buyerItemDescription })
    }

    @Test
    fun `a trip expense's unit label prints the kilometre symbol regardless of document language and quantity`() {
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Business trip London"
        }
        trip.tripExpenses.add(
            TripExpense().apply {
                invoiceItem = trip
                kind = TripExpenseKind.MILEAGE
                description = "1 km"
                quantity = BigDecimal.ONE
                unit = KILOMETER
                unitPriceNet = Money.of("0.30")
                calculateNetAmount()
            },
        )
        trip.tripExpenses.add(
            TripExpense().apply {
                invoiceItem = trip
                kind = TripExpenseKind.MILEAGE
                description = "5 km"
                quantity = BigDecimal("5")
                unit = KILOMETER
                unitPriceNet = Money.of("0.30")
                calculateNetAmount()
            },
        )
        val invoice = invoiceWithTrip(trip)
        // The expense's own unitLabel goes through the same invoice-label rule as the item's
        // unitLabel: kilometres are a physical measure and print as their symbol, invariant of the
        // document language and of the singular (quantity == 1) / plural (quantity != 1) outcome.
        invoice.documentLanguage = "en"
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val detail = requireNotNull(dto.items.single { it.category == InvoiceItemCategory.TRIP }.trip)
        assertEquals(KILOMETER.symbol, detail.expenses[0].unitLabel)
        assertEquals(KILOMETER.symbol, detail.expenses[1].unitLabel)
    }

    @Test
    fun `a per-diem day without a travel date maps to a null date`() {
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise ohne Datum"
        }
        trip.perDiemEntries.add(
            TravelExpenseEntry().apply {
                invoiceItem = trip
                // travelDate left null — an entry can reach the mapper before it is fully
                // captured (the print/export path runs for DRAFT invoices too, see
                // InvoiceDtoMapper.mapToDto's own KDoc on the "draft" field); the date is
                // optional on the wire (PerDiemDayExportDto.date: String?) for exactly this case.
                isArrivalDeparture = false
                breakfastDeduction = false; lunchDeduction = false; dinnerDeduction = false
                calculatedAmount = Money.of("28.00")
                description = "Workshop Nürnberg"
            },
        )
        val invoice = invoiceWithTrip(trip)
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()

        val dto = InvoiceDtoMapper().mapToDto(invoice)

        val detail = requireNotNull(dto.items.single { it.category == InvoiceItemCategory.TRIP }.trip)
        assertNull(detail.perDiemDays.single().date)
        assertEquals(Money.of("28.00"), detail.perDiemDays.single().claimableAmount)
        assertEquals("Workshop Nürnberg", detail.perDiemDays.single().description)
    }

    // ── The trip workbook rendered at job-publish time ─────────────────────────────────────

    private fun tripInvoice(documentLanguage: String? = null, purpose: String? = null, recipient: Recipient? = null): Invoice {
        val trip = InvoiceItem().apply {
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise Hamburg"
            itemDescription = purpose
        }
        trip.tripExpenses.add(
            TripExpense().apply {
                invoiceItem = trip
                kind = TripExpenseKind.OTHER
                description = "Taxi"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.PIECE
                unitPriceNet = Money.of("30.00")
                calculateNetAmount()
            },
        )
        val invoice = invoiceWithTrip(trip)
        invoice.invoiceNumber = InvoiceNumber("RE-2026-0007")
        invoice.recipient = recipient
        documentLanguage?.let { invoice.documentLanguage = it }
        trip.applyTaxRule(TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) })
        trip.calculateAmounts()
        return invoice
    }

    private fun renderingPort(
        onView: (TripWorkbookView) -> Unit = {},
        onTranslate: (translate: (String) -> String) -> Unit = {},
    ): TripWorkbookExportPort {
        val port: TripWorkbookExportPort = mock()
        whenever(port.writeTripWorkbook(any<TripWorkbookView>(), any(), any())).thenAnswer { call ->
            onView(call.getArgument(0))
            onTranslate(call.getArgument(1))
            call.getArgument<OutputStream>(2).write(byteArrayOf(1, 2, 3))
            null
        }
        return port
    }

    @Test
    fun `the trip workbook is rendered, stored in the exchange under its sha256 key and referenced from the DTO`() {
        val exchange: PayloadExchange = mock()
        val dto = InvoiceDtoMapper(payloadExchange = exchange, tripWorkbookExportPort = renderingPort()).mapToDto(tripInvoice())

        val ref = dto.items.single { it.category == InvoiceItemCategory.TRIP }.trip!!.workbookRef!!
        assertTrue(ref.key.startsWith("lt:tripwb:"), ref.key)
        assertEquals(ref.key.removePrefix("lt:tripwb:"), ref.sha256)
        assertEquals("RE-2026-0007-dienstreise-2.xlsx", ref.fileName)
        verify(exchange).put(eq(ref.key), any(), eq(86_400L))
        assertEquals(WordingProfile.DEFAULT.code, dto.meta.wordingProfile)
    }

    @Test
    fun `the workbook labels resolve term placeholders for the profile handed in - Dienstreise for BERATUNG`() {
        var translate: ((String) -> String)? = null
        val port = renderingPort(onTranslate = { translate = it })
        val dto = InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = port)
            .mapToDto(tripInvoice(), wordingProfile = WordingProfile.BERATUNG)

        assertEquals(WordingProfile.BERATUNG.code, dto.meta.wordingProfile)
        val subtitle = translate!!("tripExport.cover.subtitlePattern")
        assertTrue(subtitle.contains("Dienstreise"), subtitle)
        assertTrue(!subtitle.contains("{term:"), subtitle)
        assertEquals("no.such.key", translate!!("no.such.key"))
    }

    @Test
    fun `the default profile and the English document language resolve Dienstreise and Business trip`() {
        var german: ((String) -> String)? = null
        InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = renderingPort(onTranslate = { german = it })).mapToDto(tripInvoice())
        assertTrue(german!!("tripExport.cover.subtitlePattern").contains("Dienstreise"))

        var english: ((String) -> String)? = null
        InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = renderingPort(onTranslate = { english = it })).mapToDto(tripInvoice(documentLanguage = "en"))
        assertTrue(english!!("tripExport.cover.subtitlePattern").contains("Business trip"))
    }

    @Test
    fun `the workbook view carries a trimmed purpose, none for a blank one, and the recipient name when there is one`() {
        val views = mutableListOf<TripWorkbookView>()
        val port = renderingPort(onView = { views += it })
        val mapper = InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = port)

        mapper.mapToDto(tripInvoice(purpose = "  Kundentermin ", recipient = Recipient().apply { name = "Kunde AG" }))
        mapper.mapToDto(tripInvoice(purpose = "   "))
        mapper.mapToDto(tripInvoice())

        assertEquals(listOf("Kundentermin", null, null), views.map { it.purpose })
        assertEquals(listOf("Kunde AG", null, null), views.map { it.customerName })
    }

    @Test
    fun `a failing workbook render degrades to no reference instead of failing the mapping`() {
        val notFound: TripWorkbookExportPort = mock()
        whenever(notFound.writeTripWorkbook(any<TripWorkbookView>(), any(), any())).thenThrow(TripExportNotFoundException(1L, "2"))
        assertNull(InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = notFound).mapToDto(tripInvoice()).tripRef())

        val ioFailure: TripWorkbookExportPort = mock()
        whenever(ioFailure.writeTripWorkbook(any<TripWorkbookView>(), any(), any())).thenAnswer { throw IOException("disk") }
        assertNull(InvoiceDtoMapper(payloadExchange = mock(), tripWorkbookExportPort = ioFailure).mapToDto(tripInvoice()).tripRef())
    }

    @Test
    fun `without a port or without an exchange the trip maps without a workbook reference`() {
        assertNull(InvoiceDtoMapper().mapToDto(tripInvoice()).tripRef())
        assertNull(InvoiceDtoMapper(tripWorkbookExportPort = renderingPort()).mapToDto(tripInvoice()).tripRef())
    }

    private fun InvoiceDto.tripRef() = items.single { it.category == InvoiceItemCategory.TRIP }.trip!!.workbookRef
}
