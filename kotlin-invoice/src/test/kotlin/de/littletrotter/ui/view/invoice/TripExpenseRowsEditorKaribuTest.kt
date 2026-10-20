package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/**
 * The "Bearbeiten" row lines of [TripExpenseRowsEditor] ([TripExpenseRowEditor]): cleared
 * fields keep the row's last value, a date change re-resolves the rate only for mileage rows,
 * receipt assignment is exclusive across rows and can be cleared, and move/delete reorder the
 * single row list both views render from.
 */
class TripExpenseRowsEditorKaribuTest {

    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val mileageRateResolver: MileageRatePrefillQueryPort = mock()
    private var changes = 0

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        changes = 0
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun invoice() = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null,
        items = emptyList(), latestGenerationJob = null,
    )

    private fun expense(sortOrder: Int, kind: String = "OTHER", receiptId: Long? = null) = TripExpenseDto(
        id = sortOrder.toLong(), invoiceItemId = 42L, kind = kind, description = "Posten $sortOrder",
        travelDate = LocalDate.of(2026, 5, sortOrder), quantity = BigDecimal("2"), unit = "PIECE",
        unitPriceNet = Money.of("10.00"), netAmount = Money.of("20.00"), receiptId = receiptId, sortOrder = sortOrder,
    )

    private fun savedTrip(vararg expenses: TripExpenseDto) = ItemDto(
        id = 42L, positionId = "3", description = "Reise", quantity = BigDecimal.ONE, unit = "FLAT_RATE",
        unitPriceNet = null, taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = "TRIP",
        trip = TripDto(detailedOnInvoice = false, fixedNetPrice = null, calculatedNet = Money.ZERO, expenses = expenses.toList(), perDiemDays = emptyList()),
    )

    private fun receipt(id: Long, name: String) = ReceiptRowDto(
        id = id, fileName = name, description = "", documentDate = null,
        sizeBytes = null, uploadedAt = null, objectKey = "obj-$id",
    )

    private fun editor(existing: ItemDto? = null): TripExpenseRowsEditor =
        TripExpenseRowsEditor(invoiceReceiptQueryPort, mock<RecipientQueryPort>(), TripRateResolvers(null, mileageRateResolver), invoice(), existing, onChange = { changes++ }, today = FIXED_TODAY).also { it.render() }

    private fun all(root: Component): List<Component> = listOf(root) + root.children.toList().flatMap { all(it) }

    private fun TripExpenseRowsEditor.numberFields(width: String) = all(container).filterIsInstance<NumberField>().filter { it.width == width }

    private fun TripExpenseRowsEditor.dates() = all(container).filterIsInstance<DatePicker>()

    private inline fun <reified T> TripExpenseRowsEditor.combos(width: String) =
        all(container).filterIsInstance<ComboBox<*>>().filter { it.width == width }.map { it.requireAs<ComboBox<T>>() }

    private fun TripExpenseRowsEditor.buttons(labelKey: String) =
        all(container).filterIsInstance<Button>().filter { it.element.getAttribute("aria-label") == UiTranslations.tr(labelKey) }

    private fun TripExpenseRowsEditor.descriptions() = commands().map { it.description }

    @Test
    fun `happy path - editing quantity and price updates the row net and reports each change`() {
        val ed = editor(savedTrip(expense(1)))

        ed.numberFields("6rem").single().value = 3.0
        ed.numberFields("8rem").single().value = 12.5

        assertEquals(Money.of("37.50"), ed.currentTotal())
        assertTrue(all(ed.container).filterIsInstance<Span>().any { it.text.replace(".", ",").contains("37,50") })
        assertEquals(2, changes)
    }

    @Test
    fun `boundary - clearing quantity or price counts as zero and marks the rate as not provided`() {
        val ed = editor(savedTrip(expense(1)))

        ed.numberFields("6rem").single().value = null
        assertEquals(Money.ZERO, ed.currentTotal())
        ed.numberFields("6rem").single().value = 1.0
        ed.numberFields("8rem").single().value = null

        val cmd = ed.commands().single()
        assertEquals(Money.ZERO, cmd.unitPriceNet)
        assertFalse(cmd.rateProvided)
    }

    @Test
    fun `boundary - cleared kind unit and vehicle combos keep the row's previous values`() {
        val ed = editor()
        ed.addButtons().single { it.text.contains(UiTranslations.tr("invoiceDetail.trip.kind.mileage")) }.click()

        ed.combos<TripExpenseKind>("9rem").single().value = null
        assertEquals(TripExpenseKind.MILEAGE, ed.commands().single().kind)

        ed.combos<InvoiceItemUnit>("7rem").single().value = InvoiceItemUnit.PIECE
        ed.combos<InvoiceItemUnit>("7rem").single().value = null
        assertEquals(InvoiceItemUnit.PIECE, ed.commands().single().unit)

        whenever(mileageRateResolver.ratePerKm(VehicleType.OTHER_MOTOR_VEHICLE, FIXED_TODAY))
            .thenReturn(BigDecimal("0.20"))
        ed.combos<VehicleType>("12rem").single().value = VehicleType.OTHER_MOTOR_VEHICLE
        ed.combos<VehicleType>("12rem").single().value = null
        assertEquals(0.20, ed.numberFields("8rem").single().value, "a cleared vehicle type keeps resolving the other-vehicle rate")
    }

    @Test
    fun `boundary - a date change on a non-mileage row never asks for a mileage rate`() {
        val ed = editor(savedTrip(expense(1, kind = "TRANSPORT")))
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, LocalDate.of(2026, 7, 1))).thenThrow(IllegalStateException("must not be asked"))

        ed.dates().single().value = LocalDate.of(2026, 7, 1)

        assertEquals(LocalDate.of(2026, 7, 1), ed.commands().single().travelDate)
        assertEquals(Money.of("10.00"), ed.commands().single().unitPriceNet)
    }

    @Test
    fun `boundary - a receipt taken by one row is offered only to that row and can be cleared again`() {
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(42L)).thenReturn(listOf(receipt(100L, "hotel.pdf"), receipt(200L, "bahn.pdf")))
        val ed = editor(savedTrip(expense(1), expense(2)))

        val first = ed.combos<ReceiptRowDto>("10rem")[0]
        first.value = first.listDataView.items.toList().single { it.id == 100L }
        assertEquals(100L, ed.commands()[0].receiptId)
        val secondOptions = ed.combos<ReceiptRowDto>("10rem")[1].listDataView.items.toList().map { it.id }
        assertEquals(listOf(200L), secondOptions, "a receipt assigned to row 1 is not offered to row 2")

        // "no receipt" is the combo's own cleared (null) value, not a selectable sentinel item.
        ed.combos<ReceiptRowDto>("10rem")[0].value = null
        assertNull(ed.commands()[0].receiptId)
    }

    @Test
    fun `boundary - moving up and deleting reorder the rows, the first row cannot move up`() {
        val ed = editor(savedTrip(expense(1), expense(2), expense(3)))
        val ups = ed.buttons("invoiceDetail.trip.expense.moveUp")
        assertFalse(ups[0].isEnabled)
        assertFalse(ed.buttons("invoiceDetail.trip.expense.moveDown")[2].isEnabled)

        ups[2].click()
        assertEquals(listOf("Posten 1", "Posten 3", "Posten 2"), ed.descriptions())

        ed.buttons("invoiceDetail.trip.expense.moveDown")[0].click()
        assertEquals(listOf("Posten 3", "Posten 1", "Posten 2"), ed.descriptions())

        ed.buttons("invoiceDetail.trip.expense.delete")[1].click()
        assertEquals(listOf("Posten 3", "Posten 2"), ed.descriptions())
    }
}
