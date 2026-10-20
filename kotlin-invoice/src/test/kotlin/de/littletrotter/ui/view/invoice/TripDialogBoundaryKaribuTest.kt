package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/**
 * [TripDialog] and its extracted [TripExpenseRowsEditor]: the mileage rate follows the
 * row's date and vehicle type, the footer's calculated net follows those money changes, save is
 * refused for a missing description or a per-diem block without a rate, and the receipts button of
 * a saved trip opens the position's [ReceiptDialog].
 */
class TripDialogBoundaryKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val perDiemRateQueries: PerDiemRateQueryPort = mock()
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    private val mileageRateResolver: MileageRatePrefillQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        whenever(taxRateQueries.findReferenceTaxRate(Country.DE, LocalDate.of(2026, 6, 1))).thenReturn(
            TaxRateDto(
                name = "DE Standard",
                rate = TaxRatePercent(BigDecimal("19")),
                country = Country.DE,
                code = "DE_STANDARD_19",
                category = "S",
                isActive = true,
                isSelectable = true,
                isReference = true,
            ),
        )
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invoice() = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null,
        items = emptyList(), latestGenerationJob = null,
    )

    private fun savedTrip() = ItemDto(
        id = 42L, positionId = "3", description = "Kundenbesuch", quantity = BigDecimal.ONE, unit = "FLAT_RATE",
        unitPriceNet = null, taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = "TRIP",
        trip = TripDto(detailedOnInvoice = false, fixedNetPrice = null, calculatedNet = Money.ZERO, expenses = emptyList(), perDiemDays = emptyList()),
    )

    private fun rate(perKm: String) = BigDecimal(perKm)

    private fun open(perDiemUiEnabled: Boolean = false, existing: ItemDto? = null, onSaveNew: (TripCommand) -> Long? = { 1L }): Dialog {
        TripDialog(
            tripEntryQueryPort = TripEntryQueries(taxRateQueries, perDiemRateQueries),
            receiptPorts = TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()),
            recipientQueryPort = mock<RecipientQueryPort>(),
            hotelRateResolver = mock<HotelRatePrefillQueryPort>(),
            mileageRateResolver = mileageRateResolver,
            perDiemCalculator = mock<PerDiemCalculator>(),
            perDiemUiEnabled = perDiemUiEnabled,
            invoice = invoice(),
            existingItem = existing,
            saveCallbacks = TripSaveCallbacks(onSaveNew, {}), today = FIXED_TODAY,
        ).open()
        return _get<Dialog>()
    }

    private fun flatten(component: Component): List<Component> {
        val children = if (component is TabSheet) {
            (0 until component.tabCount).map { component.getComponent(component.getTabAt(it)) }
        } else {
            component.children.toList()
        }
        return listOf(component) + children.flatMap { flatten(it) }
    }

    private fun selectEditTab(dlg: Dialog) {
        flatten(dlg).filterIsInstance<TabSheet>().single().selectedIndex = 1
    }

    private fun button(dlg: Dialog, text: String): Button = flatten(dlg).filterIsInstance<Button>().single { it.text == text }

    private fun addMileageRow(dlg: Dialog) {
        selectEditTab(dlg)
        button(dlg, "+ ${UiTranslations.tr("invoiceDetail.trip.kind.mileage")}").click()
    }

    private fun priceField(dlg: Dialog): NumberField = flatten(dlg).filterIsInstance<NumberField>().single { it.width == "8rem" }
    private fun quantityField(dlg: Dialog): NumberField = flatten(dlg).filterIsInstance<NumberField>().single { it.width == "6rem" }
    private fun dateField(dlg: Dialog): DatePicker = flatten(dlg).filterIsInstance<DatePicker>().single { it.width == "9.5rem" }

    private fun vehicleBox(dlg: Dialog): ComboBox<VehicleType> =
        flatten(dlg).filterIsInstance<ComboBox<*>>().single { it.width == "12rem" }.requireAs()

    private fun titleField(dlg: Dialog): TextField =
        dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<TextField>()[0]

    private fun calculatedLine(dlg: Dialog): String =
        flatten(dlg).filterIsInstance<Span>().filter { it.classNames.contains("lt-amount-preview") }[0].text

    private fun notificationTexts(): List<String> = getNotifications().map { de.littletrotter.ui.toastText(it) }

    @Test
    fun `happy path - a mileage row follows date and vehicle type rates and the footer shows km times rate`() {
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, FIXED_TODAY)).thenReturn(rate("0.30"))
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, LocalDate.of(2026, 3, 1))).thenReturn(rate("0.25"))
        whenever(mileageRateResolver.ratePerKm(VehicleType.OTHER_MOTOR_VEHICLE, LocalDate.of(2026, 3, 1))).thenReturn(rate("0.20"))
        val dlg = open()
        addMileageRow(dlg)

        dateField(dlg).value = LocalDate.of(2026, 3, 1)
        assertEquals(0.25, priceField(dlg).value, "the rate valid on the travel date replaces today's rate")
        quantityField(dlg).value = 100.0
        assertTrue(calculatedLine(dlg).replace(".", ",").contains("25,00"), "100 km x 0.25: ${calculatedLine(dlg)}")

        vehicleBox(dlg).value = VehicleType.OTHER_MOTOR_VEHICLE
        assertEquals(0.20, priceField(dlg).value)
        assertTrue(calculatedLine(dlg).replace(".", ",").contains("20,00"), "100 km x 0.20: ${calculatedLine(dlg)}")
    }

    @Test
    fun `boundary - an unknown mileage rate for the new date keeps the entered price`() {
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, FIXED_TODAY)).thenReturn(rate("0.30"))
        val dlg = open()
        addMileageRow(dlg)

        dateField(dlg).value = LocalDate.of(2020, 1, 1)
        vehicleBox(dlg).value = VehicleType.OTHER_MOTOR_VEHICLE

        assertEquals(0.30, priceField(dlg).value, "no resolved rate must never blank or zero the price")
    }

    @Test
    fun `boundary - the vehicle type combo labels both vehicle types`() {
        val dlg = open()
        addMileageRow(dlg)

        val labels = vehicleBox(dlg).itemLabelGenerator
        assertEquals(UiTranslations.tr("invoiceDetail.travel.mileage.vehicleType.car"), labels.apply(VehicleType.CAR))
        assertEquals(UiTranslations.tr("invoiceDetail.travel.mileage.vehicleType.other"), labels.apply(VehicleType.OTHER_MOTOR_VEHICLE))
    }

    @Test
    fun `boundary - an expense row without description blocks save`() {
        var saved: TripCommand? = null
        val dlg = open(onSaveNew = { saved = it; 1L })
        titleField(dlg).value = "Messe"
        selectEditTab(dlg)
        button(dlg, "+ ${UiTranslations.tr("invoiceDetail.trip.kind.other")}").click()

        dlg._get<Button> { text = UiTranslations.tr("app.save") }.click()

        assertNull(saved)
        assertTrue(dlg.isOpened)
        assertTrue(notificationTexts().contains(UiTranslations.tr("invoiceDetail.trip.expense.description.required")))
    }

    @Test
    fun `boundary - a per-diem entry without a rate blocks save`() {
        var saved: TripCommand? = null
        val dlg = open(perDiemUiEnabled = true, onSaveNew = { saved = it; 1L })
        titleField(dlg).value = "Messe"
        val periodPickers = dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<DatePicker>()
        periodPickers[0].value = LocalDate.of(2026, 6, 1)
        periodPickers[1].value = LocalDate.of(2026, 6, 2)
        selectEditTab(dlg)
        button(dlg, UiTranslations.tr("invoiceDetail.perDiem.add")).click()

        dlg._get<Button> { text = UiTranslations.tr("app.save") }.click()

        assertNull(saved)
        assertTrue(notificationTexts().contains(UiTranslations.tr("invoiceDetail.perDiem.error.rateRequired")))
    }

    @Test
    fun `boundary - the receipts button of a saved trip opens the position receipt dialog`() {
        val dlg = open(existing = savedTrip())

        val receiptsButton = button(dlg, UiTranslations.tr("invoiceDetail.trip.receipts.title"))
        assertTrue(receiptsButton.isEnabled)
        receiptsButton.click()

        assertEquals(2, _find<Dialog>().size, "the receipt dialog opens on top of the trip dialog")
    }

    @Test
    fun `boundary - the receipts button of an unsaved trip stays disabled`() {
        val dlg = open()

        assertFalse(button(dlg, UiTranslations.tr("invoiceDetail.trip.receipts.title")).isEnabled)
    }

    @Test
    fun `boundary - clicking the unsaved trip's disabled receipts button opens no receipt dialog`() {
        val dlg = open()
        val receiptsButton = button(dlg, UiTranslations.tr("invoiceDetail.trip.receipts.title"))

        // The button is disabled for an unsaved position (Karibu's own .click() would no-op for
        // a disabled component), so the click listener is fired directly.
        com.vaadin.flow.component.ComponentUtil.fireEvent(receiptsButton, com.vaadin.flow.component.ClickEvent(receiptsButton))

        assertEquals(1, _find<Dialog>().size, "no second (receipt) dialog may open for an unsaved position")
    }

    @Test
    fun `boundary - a saved trip with existing receipts shows the non-zero count label`() {
        whenever(invoiceReceiptQueryPort.countReceiptsForItem(42L)).thenReturn(3L)
        val dlg = open(existing = savedTrip())

        val label = flatten(dlg).filterIsInstance<Span>()
            .first { it.text == UiTranslations.tr("invoiceDetail.items.receipts.count", "3") }
        assertEquals(UiTranslations.tr("invoiceDetail.items.receipts.count", "3"), label.text)
    }

    @Test
    fun `boundary - switching the only mileage row to other resets kind-specific prefill`() {
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, FIXED_TODAY)).thenReturn(rate("0.30"))
        val dlg = open()
        addMileageRow(dlg)
        val kindBox: ComboBox<TripExpenseKind> = flatten(dlg).filterIsInstance<ComboBox<*>>().single { it.width == "9rem" }.requireAs()

        kindBox.value = TripExpenseKind.OTHER

        assertEquals(0.0, priceField(dlg).value)
        assertFalse(vehicleBox(dlg).isVisible, "the vehicle type only belongs to mileage rows")
    }
}
