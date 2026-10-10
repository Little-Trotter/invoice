package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.HotelRateResolver
import de.littletrotter.application.travel.MileageRateResolver
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/**
 * The row model behind [TripExpenseRowsEditor]: per-kind prefill (hotel rate via the
 * recipient's billing country, mileage rate via date and vehicle type), loading of saved rows,
 * ordering bounds, save derivations and receipt exclusivity.
 */
class TripExpenseRowsEditorModelTest {

    private val contacts: RecipientQueryPort = mock()
    private val hotelRates: HotelRateResolver = mock()
    private val mileageRates: MileageRateResolver = mock()

    private fun invoice(recipient: PartyDto? = PartyDto(id = 7L, name = "Kunde")) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = recipient,
        items = emptyList(), latestGenerationJob = null,
    )

    private fun prefill(recipient: PartyDto? = PartyDto(id = 7L, name = "Kunde"), hotel: HotelRateResolver? = hotelRates, mileage: MileageRateResolver? = mileageRates) =
        TripExpenseRowPrefill(contacts, hotel, mileage, invoice(recipient), today = FIXED_TODAY)

    private fun expense(sortOrder: Int, kind: String = "OTHER", unit: String? = "PIECE", receiptId: Long? = null) = TripExpenseDto(
        id = sortOrder.toLong(), invoiceItemId = 42L, kind = kind, description = "Posten $sortOrder",
        travelDate = LocalDate.of(2026, 5, sortOrder), quantity = BigDecimal("2"), unit = unit,
        unitPriceNet = Money.of("10.00"), netAmount = Money.of("20.00"), receiptId = receiptId, sortOrder = sortOrder,
    )

    private fun savedTrip(vararg expenses: TripExpenseDto) = ItemDto(
        id = 42L, positionId = "3", description = "Reise", quantity = BigDecimal.ONE, unit = "FLAT_RATE",
        unitPriceNet = null, taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = "TRIP",
        trip = TripDto(detailedOnInvoice = false, fixedNetPrice = null, calculatedNet = Money.ZERO, expenses = expenses.toList(), perDiemDays = emptyList()),
    )

    private fun address(country: Country): AddressDto = AddressDto(id = null, street = null, postalCode = null, city = null, countryAlpha2 = country.alpha2)

    private fun hotelRate(amount: String) = HotelRate().apply { ratePerNight = Money.of(amount) }

    @Test
    fun `happy path - a hotel row takes the nightly rate of the recipient billing country`() {
        val addressAT = address(Country.AT)
        whenever(contacts.findBillingAddress(7L)).thenReturn(addressAT)
        whenever(hotelRates.resolveRatePerNight(any(), any())).thenReturn(hotelRate("95.00"))
        val rows = TripExpenseRowList(prefill(), null, today = FIXED_TODAY)

        val row = rows.add(TripExpenseKind.HOTEL)

        assertEquals(InvoiceItemUnit.DAY, row.unit)
        assertEquals(BigDecimal.ONE, row.quantity)
        assertEquals(Money.of("95.00"), row.unitPriceNet)
        assertEquals(Money.of("95.00"), rows.total())
    }

    @Test
    fun `boundary - a hotel row without a recipient billing address or rate starts at zero`() {
        whenever(contacts.findBillingAddress(7L)).thenReturn(null)
        assertEquals(Money.ZERO, TripExpenseRowList(prefill(), null, today = FIXED_TODAY).add(TripExpenseKind.HOTEL).unitPriceNet)

        assertEquals(Money.ZERO, TripExpenseRowList(prefill(recipient = null), null, today = FIXED_TODAY).add(TripExpenseKind.HOTEL).unitPriceNet)
        assertEquals(Money.ZERO, TripExpenseRowList(prefill(recipient = PartyDto(id = null, name = "Neu")), null, today = FIXED_TODAY).add(TripExpenseKind.HOTEL).unitPriceNet)

        val addressFR = address(Country.FR)
        whenever(contacts.findBillingAddress(7L)).thenReturn(addressFR)
        whenever(hotelRates.resolveRatePerNight(any(), any())).thenReturn(null)
        assertEquals(Money.ZERO, TripExpenseRowList(prefill(), null, today = FIXED_TODAY).add(TripExpenseKind.HOTEL).unitPriceNet)
        assertEquals(Money.ZERO, TripExpenseRowList(prefill(hotel = null), null, today = FIXED_TODAY).add(TripExpenseKind.HOTEL).unitPriceNet)
    }

    @Test
    fun `boundary - mileage prefill uses the car rate and falls back to zero without resolver or rate`() {
        // TripExpenseRowPrefill.today is threaded in explicitly (FIXED_TODAY here) rather than
        // read off the clock — a plain UI helper may not do that itself; see that field's own KDoc.
        whenever(mileageRates.resolveRatePerKm(VehicleType.CAR, FIXED_TODAY)).thenReturn(MileageRate().apply { ratePerKm = BigDecimal("0.30") })
        val withRate = TripExpenseRowList(prefill(), null, today = FIXED_TODAY).add(TripExpenseKind.MILEAGE)
        assertEquals(Money.of("0.30"), withRate.unitPriceNet)
        assertEquals(InvoiceItemUnit.KILOMETER, withRate.unit)
        assertEquals(VehicleType.CAR, withRate.vehicleType)

        assertEquals(Money.ZERO, TripExpenseRowList(prefill(mileage = null), null, today = FIXED_TODAY).add(TripExpenseKind.MILEAGE).unitPriceNet)
        assertNull(prefill().mileageRatePerKm(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2020, 1, 1)))
        assertNull(prefill(mileage = null).mileageRatePerKm(VehicleType.CAR, null))
        assertEquals(BigDecimal("0.30"), prefill().mileageRatePerKm(VehicleType.CAR, null), "no date means today's rate")
    }

    @Test
    fun `boundary - switching a kind re-derives unit quantity and rate but keeps the description`() {
        val rows = TripExpenseRowList(prefill(), null, today = FIXED_TODAY)
        val row = rows.add(TripExpenseKind.TRAVEL_TIME)
        assertFalse(row.rateProvided)
        assertEquals(InvoiceItemUnit.HOUR, row.unit)
        row.description = "Anreise"

        rows.changeKind(row, TripExpenseKind.TRANSPORT)

        assertTrue(row.rateProvided)
        assertEquals(InvoiceItemUnit.FLAT_RATE, row.unit)
        assertEquals(BigDecimal.ONE, row.quantity)
        assertEquals("Anreise", row.description)
    }

    @Test
    fun `boundary - saved rows load in sort order and a missing unit falls back to piece`() {
        val rows = TripExpenseRowList(prefill(), savedTrip(expense(2, unit = null), expense(1, kind = "HOTEL", unit = "DAY")), today = FIXED_TODAY)

        val loaded = rows.snapshot()
        assertEquals(listOf("Posten 1", "Posten 2"), loaded.map { it.description })
        assertEquals(InvoiceItemUnit.DAY, loaded[0].unit)
        assertEquals(InvoiceItemUnit.PIECE, loaded[1].unit)
        assertEquals(listOf(1, 2), rows.commands().map { it.sortOrder })
        assertEquals(Money.of("40.00"), rows.total())
        assertEquals(0, TripExpenseRowList(prefill(), savedTrip().copy(trip = null), today = FIXED_TODAY).size)
    }

    @Test
    fun `boundary - moves outside the row range are refused without reordering`() {
        val rows = TripExpenseRowList(prefill(), savedTrip(expense(1), expense(2)), today = FIXED_TODAY)

        assertFalse(rows.move(0, -1))
        assertFalse(rows.move(1, 2))
        assertEquals(listOf("Posten 1", "Posten 2"), rows.snapshot().map { it.description })

        assertTrue(rows.move(1, 0))
        assertEquals(listOf("Posten 2", "Posten 1"), rows.snapshot().map { it.description })
        rows.removeAt(0)
        assertEquals(1, rows.size)
        assertFalse(rows.isEmpty())
    }

    @Test
    fun `boundary - a blank description blocks save except on travel time rows`() {
        val rows = TripExpenseRowList(prefill(), null, today = FIXED_TODAY)
        rows.add(TripExpenseKind.TRAVEL_TIME)
        assertFalse(rows.hasMissingDescription(), "travel time needs no description")

        rows.add(TripExpenseKind.OTHER)
        assertTrue(rows.hasMissingDescription())
    }

    @Test
    fun `boundary - receipts assigned to other rows are reported, the own row's receipt is not`() {
        val rows = TripExpenseRowList(prefill(), savedTrip(expense(1, receiptId = 100L), expense(2, receiptId = 200L), expense(3)), today = FIXED_TODAY)

        assertEquals(setOf(200L), rows.receiptIdsAssignedElsewhere(0))
        assertEquals(setOf(100L, 200L), rows.receiptIdsAssignedElsewhere(2))
    }
}
