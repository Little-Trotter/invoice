package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.dto.TravelExpenseDto
import de.littletrotter.application.dto.TripDto
import de.littletrotter.application.dto.TripExpenseDto
import de.littletrotter.application.export.port.inbound.TripWorkbookExportQueryPort
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.travel.port.inbound.HotelRatePrefillQueryPort
import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.test.FIXED_TODAY

/**
 * [TripDialog], the single dialog that replaced the deleted `TravelItemDialog`/
 * `PerDiemItemDialog` bridge pair. No business rule is asserted here twice (DDD): amount/rate
 * resolution is stubbed through the same resolvers `TripService` uses, never recomputed by hand.
 */
class TripDialogKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val perDiemRateQueries: PerDiemRateQueryPort = mock()
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val hotelRateResolver: HotelRatePrefillQueryPort = mock()
    private val mileageRateResolver: MileageRatePrefillQueryPort = mock()
    private val perDiemCalculator: PerDiemCalculator = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        whenever(taxRateQueries.findReferenceTaxRate(Country.DE, LocalDate.of(2026, 6, 1)))
            .thenReturn(standardTaxRate(Country.DE, "19"))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invoice(
        recipient: PartyDto? = null,
        status: Int = 0,
        items: List<ItemDto> = emptyList(),
        sellerVatRegistrationCountry: String? = null,
    ) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = recipient,
        items = items, latestGenerationJob = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
    )

    private fun standardTaxRate(country: Country, percent: String, id: Long? = null) = TaxRateDto(
        id = id,
        name = "${country.alpha2} Standard",
        rate = TaxRatePercent(BigDecimal(percent)),
        country = country,
        code = "${country.alpha2}_STANDARD_$percent",
        category = "S",
        isActive = true,
        isSelectable = true,
        isReference = true,
    )

    private fun tripExpense(
        id: Long = 1L, kind: TripExpenseKind = TripExpenseKind.OTHER, netAmount: String = "10.00",
        receiptId: Long? = null, description: String = "Parken",
    ) = TripExpenseDto(
        id = id, invoiceItemId = 1L, kind = kind.name, description = description, travelDate = null,
        quantity = BigDecimal.ONE, unit = "FLAT_RATE", unitPriceNet = Money.of(netAmount),
        netAmount = Money.of(netAmount), receiptId = receiptId, sortOrder = 1,
    )

    private fun perDiemDay(
        id: Long = 1L,
        date: LocalDate = LocalDate.of(2026, 6, 1),
        participant: String = "Kundentermin",
        description: String = "Verpflegungspauschale",
        isArrivalDeparture: Boolean = true,
        breakfastDeduction: Boolean = false,
        lunchDeduction: Boolean = false,
        dinnerDeduction: Boolean = false,
    ) = TravelExpenseDto(
        id = id, invoiceItemId = 1L, travelDate = date, countryAlpha2 = "DE",
        isArrivalDeparture = isArrivalDeparture, breakfastDeduction = breakfastDeduction, lunchDeduction = lunchDeduction,
        dinnerDeduction = dinnerDeduction, calculatedAmount = Money.of("28.00"), perDiemRateId = 5L,
        fullDayRateSnapshot = Money.of("28.00"), arrivalDepartureRateSnapshot = Money.of("28.00"),
        description = description, participant = participant,
    )

    private fun existingTripItem(
        expenses: List<TripExpenseDto> = emptyList(),
        perDiemDays: List<TravelExpenseDto> = emptyList(),
        fixedNetPrice: Money? = null,
        detailedOnInvoice: Boolean = false,
    ) = ItemDto(
        id = 42L, positionId = "3", description = "Kundenbesuch München",
        quantity = BigDecimal.ONE, unit = "FLAT_RATE", unitPriceNet = null,
        taxRate = null, taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = "TRIP",
        trip = TripDto(
            detailedOnInvoice = detailedOnInvoice, fixedNetPrice = fixedNetPrice,
            calculatedNet = expenses.fold(Money.ZERO) { acc, e -> acc + e.netAmount },
            expenses = expenses, perDiemDays = perDiemDays,
        ),
    )

    private fun dialog(): Dialog = _get<Dialog>()

    private fun titleField(dlg: Dialog): TextField =
        dlg.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()
            .children.toList().filterIsInstance<TextField>()[0]

    // Type-filtered, not translated-label-based: TripDialog.open() always adds the switches row
    // (detailedOnInvoiceSwitch, fixedPriceSwitch, in that order) as the dialog's only direct
    // HorizontalLayout child — more robust than matching on rendered label text, and (unlike a
    // fixed index) unaffected by the read-only mode's own leading hint Paragraph.
    private fun switchesRow(dlg: Dialog): com.vaadin.flow.component.orderedlayout.HorizontalLayout =
        dlg.children.toList().filterIsInstance<com.vaadin.flow.component.orderedlayout.HorizontalLayout>().first()

    private fun detailedOnInvoiceSwitch(dlg: Dialog): Checkbox =
        switchesRow(dlg).children.toList().filterIsInstance<Checkbox>()[0]

    private fun fixedPriceSwitch(dlg: Dialog): Checkbox =
        switchesRow(dlg).children.toList().filterIsInstance<Checkbox>()[1]

    private fun fixedPriceField(dlg: Dialog): NumberField =
        flatten(dlg).filterIsInstance<NumberField>().single { it.label == UiTranslations.tr("invoiceDetail.trip.fixedPrice.amount") }

    private fun taxNote(dlg: Dialog): String =
        dlg.children.toList().filterIsInstance<com.vaadin.flow.component.html.Paragraph>()
            .single { it.classNames.contains("text-muted") && it.classNames.contains("m-0") }.text

    private fun saveButton(dlg: Dialog): Button = dlg._get<Button> { text = UiTranslations.tr("app.save") }
    private fun cancelButton(dlg: Dialog): Button = dlg._get<Button> { text = UiTranslations.tr("app.cancel") }

    // TabSheet only keeps the *selected* tab's content attached to the component tree
    // (Vaadin slots the others out); getComponent(Tab) reaches every tab's content regardless of
    // selection, which is what lets every existing test below keep finding "Bearbeiten"-tab
    // fields via flatten(dlg) without first having to select that tab.
    private fun flatten(component: Component): List<Component> {
        val children = if (component is TabSheet) {
            (0 until component.tabCount).map { component.getComponent(component.getTabAt(it)) }
        } else {
            component.children.toList()
        }
        return listOf(component) + children.flatMap { flatten(it) }
    }

    private fun addExpenseButton(dlg: Dialog, kindKey: String): Button =
        flatten(dlg).filterIsInstance<Button>().single { it.text == "+ ${UiTranslations.tr(kindKey)}" }

    // TabSheet disables the non-selected tab's content node (clicks/value-changes on it
    // are silently ignored, same as a real user would have to click the tab first); every test
    // below that clicks an add/move/delete button or edits a row field selects "Bearbeiten"
    // first. Pure reads (flatten(dlg) queries, genericDataView.items) work regardless.
    private fun selectEditTab(dlg: Dialog) {
        flatten(dlg).filterIsInstance<TabSheet>().single().selectedIndex = 1
    }

    // ── Save validation ──────────────────────────────────────────────────────

    @Test
    fun `save is blocked while the title is blank`() {
        var saveNewCalled = false
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ saveNewCalled = true; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        saveButton(dlg).click()

        assertFalse(saveNewCalled)
        assertTrue(dlg.isOpened)
    }

    @Test
    fun `a negative fixed price blocks save`() {
        var saveNewCalled = false
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ saveNewCalled = true; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Workshop Berlin"
        fixedPriceSwitch(dlg).value = true
        fixedPriceField(dlg).value = -5.0

        saveButton(dlg).click()

        assertFalse(saveNewCalled)
    }

    @Test
    fun `a trip title with a character the PDF fonts lack blocks save`() {
        var saveNewCalled = false
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ saveNewCalled = true; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Workshop 💩"

        saveButton(dlg).click()

        assertFalse(saveNewCalled)
        assertTrue(dlg.isOpened)
    }

    @Test
    fun `two expenses plus a title produce a matching command with two expense lines`() {
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ cmd -> captured = cmd; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Projektbesuch"
        selectEditTab(dlg)

        addExpenseButton(dlg, "invoiceDetail.trip.kind.transport").click()
        addExpenseButton(dlg, "invoiceDetail.trip.kind.other").click()
        val descriptionFields = flatten(dlg).filterIsInstance<TextField>()
            .filter { it.placeholder == UiTranslations.tr("invoiceDetail.trip.expense.description") }
        descriptionFields[0].value = "Zugticket zum Kunden"
        descriptionFields[1].value = "Parkgebühr"

        saveButton(dlg).click()

        assertFalse(dlg.isOpened, "a valid save closes the dialog")
        assertEquals("Projektbesuch", captured?.title)
        assertEquals(2, captured?.expenses?.size)
        assertEquals(TripExpenseKind.TRANSPORT, captured?.expenses?.get(0)?.kind)
        assertEquals("Zugticket zum Kunden", captured?.expenses?.get(0)?.description)
        assertEquals("Parkgebühr", captured?.expenses?.get(1)?.description)
        assertTrue(captured?.perDiemDays.isNullOrEmpty())
    }

    @Test
    fun `fixed price toggle reveals the amount field and the footer shows the delta`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        val fixedSwitch = fixedPriceSwitch(dlg)
        val fixedField = fixedPriceField(dlg)
        assertFalse(fixedField.isVisible, "hidden until the switch is on")

        val previewLines = flatten(dlg).filterIsInstance<com.vaadin.flow.component.html.Span>()
            .filter { it.classNames.contains("lt-amount-preview") }
        val onInvoiceLine = previewLines[1]
        assertFalse(onInvoiceLine.isVisible, "no fixed price yet -> no delta line")

        fixedSwitch.value = true
        fixedField.value = 500.0

        assertTrue(fixedField.isVisible)
        assertTrue(onInvoiceLine.isVisible)
        assertTrue(onInvoiceLine.text.contains("500"), "delta line: ${onInvoiceLine.text}")
    }

    @Test
    fun `fixed price preview uses the German reference tax rate by default`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        fixedPriceSwitch(dlg).value = true

        assertEquals(
            UiTranslations.tr("invoiceDetail.trip.referenceTax", taxRuleLabel(standardTaxRate(Country.DE, "19"))),
            taxNote(dlg),
        )
    }

    @Test
    fun `fixed price preview follows the selected Luxembourg VAT registration`() {
        val luReference = standardTaxRate(Country.LU, "17")
        whenever(taxRateQueries.findReferenceTaxRate(Country.LU, LocalDate.of(2026, 6, 1)))
            .thenReturn(luReference)
        TripDialog(
            TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(sellerVatRegistrationCountry = "LU"),
            saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        fixedPriceSwitch(dlg).value = true

        assertEquals(UiTranslations.tr("invoiceDetail.trip.referenceTax", taxRuleLabel(luReference)), taxNote(dlg))
    }

    @Test
    fun `fixed price preview shows the resolver error when the country has no reference rate`() {
        whenever(taxRateQueries.findReferenceTaxRate(Country.LU, LocalDate.of(2026, 6, 1)))
            .thenThrow(InvalidParameterException("No reference tax rate for LU."))
        TripDialog(
            TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(sellerVatRegistrationCountry = "LU"),
            saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        fixedPriceSwitch(dlg).value = true

        assertEquals("No reference tax rate for LU.", taxNote(dlg))
    }

    @Test
    fun `switching fixed price off keeps the country reference tax preview`() {
        val mainService = existingTripItem().copy(category = "SERVICE", taxRateRefId = 7L)
        TripDialog(
            TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(items = listOf(mainService)),
            saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        fixedPriceSwitch(dlg).value = true
        fixedPriceSwitch(dlg).value = false

        assertEquals(
            UiTranslations.tr("invoiceDetail.trip.referenceTax", taxRuleLabel(standardTaxRate(Country.DE, "19"))),
            taxNote(dlg),
        )
    }

    // ── export control ────────────────────────────────────────────────────────

    @Test
    fun `a brand-new trip renders the export button disabled and outside any download anchor`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), tripWorkbookExport = mock(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val button = dlg._get<Button> { id = TripDialog.EXPORT_BUTTON_ID }

        assertFalse(button.isEnabled)
        assertFalse(button.parent.get() is Anchor, "no download anchor around a disabled button")
    }

    @Test
    fun `a saved trip with the export port wired gets an enabled button inside a download anchor`() {
        val port: TripWorkbookExportQueryPort = mock()
        whenever(port.fileName("INV-1", 1L, "3")).thenReturn("dienstreise_inv-1_3.xlsx")
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existingTripItem(), tripWorkbookExport = port, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val button = dlg._get<Button> { id = TripDialog.EXPORT_BUTTON_ID }
        val anchor = button.parent.get() as Anchor

        assertTrue(button.isEnabled)
        assertTrue(anchor.element.hasAttribute("download"), "plain attachment download")
    }

    @Test
    fun `a saved trip without the export port keeps the button disabled`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existingTripItem(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()

        assertFalse(dialog()._get<Button> { id = TripDialog.EXPORT_BUTTON_ID }.isEnabled)
    }

    // ── Prefill through the resolvers ────────────────────────────────────────

    @Test
    fun `the hotel add button prefills the per-night rate for the recipient's billing country`() {
        val recipient = PartyDto(id = 7L, name = "Kunde")
        val billingAddress = AddressDto(id = null, street = "Rue de Paris", postalCode = "75001", city = "Paris", countryAlpha2 = Country.FR.alpha2)
        whenever(recipientQueryPort.findBillingAddress(7L)).thenReturn(billingAddress)
        whenever(hotelRateResolver.ratePerNight(eq(Country.FR), any())).thenReturn(Money.of("140.00"))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(recipient = recipient), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        addExpenseButton(dlg, "invoiceDetail.trip.kind.hotel").click()

        val priceField = flatten(dlg).filterIsInstance<NumberField>().first { it.width == "8rem" }
        assertEquals(140.0, priceField.value)
    }

    @Test
    fun `the mileage add button prefills km times the resolved rate for today and the car vehicle type`() {
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, FIXED_TODAY))
            .thenReturn(BigDecimal("0.30"))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        addExpenseButton(dlg, "invoiceDetail.trip.kind.mileage").click()

        val priceField = flatten(dlg).filterIsInstance<NumberField>().first { it.width == "8rem" }
        assertEquals(0.30, priceField.value)
        // ComboBox<VehicleType>/ComboBox<TripExpenseKind> are indistinguishable via
        // filterIsInstance (generics are erased); the vehicle-type box is the only ComboBox at
        // this width.
        val vehicleBox = flatten(dlg).filterIsInstance<ComboBox<*>>().single { it.width == "12rem" }
        assertEquals(VehicleType.CAR, vehicleBox.value)
    }

    // ── PER_DIEM_UI gating ───────────────────────────────────────────────────

    @Test
    fun `the per-diem section is hidden when PER_DIEM_UI is off`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(flatten(dlg).none { it.element.text?.contains(UiTranslations.tr("invoiceDetail.trip.perDiem.title")) == true })
    }

    @Test
    fun `existing per-diem days are preserved on save even while the flag is off`() {
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay()))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, { cmd -> captured = cmd }), today = FIXED_TODAY).open()

        saveButton(dialog()).click()

        assertEquals(1, captured?.perDiemDays?.size, "an existing per-diem day must not be silently dropped just because the switch is off")
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    @Test
    fun `the receipt combo of an expense row lists only the trip's own receipts`() {
        val ownReceipt = ReceiptRowDto(
            id = 11L, fileName = "taxi.pdf", description = "", documentDate = null,
            sizeBytes = null, uploadedAt = null, objectKey = "obj-11",
        )
        val existing = existingTripItem(expenses = listOf(tripExpense()))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(42L)).thenReturn(listOf(ownReceipt))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val receiptBox = flatten(dlg).filterIsInstance<ComboBox<ReceiptRowDto>>().single { it.width == "10rem" }

        // "no receipt" is the ComboBox's own cleared (null) value now, not a selectable
        // sentinel item, so the item list holds only the trip's real receipts.
        val items = receiptBox.genericDataView.items.toList()
        assertEquals(1, items.size)
        assertEquals(ownReceipt, items.single())
        assertNull(receiptBox.value, "nothing assigned yet, so the combo starts cleared")
    }

    // ── Escape/Cancel dirty-check ────────────────────────────────────────────

    @Test
    fun `cancel closes immediately when nothing was changed`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        cancelButton(dlg).click()

        assertFalse(dlg.isOpened)
    }

    @Test
    fun `cancel asks for confirmation once the title was edited`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Etwas eingegeben"

        cancelButton(dlg).click()

        assertTrue(dlg.isOpened, "the trip dialog itself must stay open until the confirm dialog is answered")
        assertTrue(_get<ConfirmDialog>().isOpened)
    }

    private fun openNewTripAndEdit(edit: (Dialog) -> Unit) {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        edit(dlg)
        cancelButton(dlg).click()
        assertTrue(dlg.isOpened, "an edited trip must ask before it is discarded")
        assertTrue(_get<ConfirmDialog>().isOpened)
    }

    private fun headerTextFields(dlg: Dialog): List<TextField> =
        dlg.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()
            .children.toList().filterIsInstance<TextField>()

    @Test
    fun `cancel asks for confirmation once the purpose was edited`() {
        openNewTripAndEdit { flatten(it).filterIsInstance<com.vaadin.flow.component.textfield.TextArea>().first().value = "Messebesuch" }
    }

    @Test
    fun `cancel asks for confirmation once the cost center was edited`() {
        openNewTripAndEdit { headerTextFields(it)[1].value = "KST-4" }
    }

    @Test
    fun `cancel asks for confirmation once the detailed-on-invoice switch was toggled`() {
        openNewTripAndEdit { detailedOnInvoiceSwitch(it).value = true }
    }

    @Test
    fun `editing an existing trip pre-loads title, purpose and switches, and onUpdate is called with its positionId`() {
        val existing = existingTripItem(fixedNetPrice = Money.of("250.00"), detailedOnInvoice = true)
        var updateCalled = false
        var saveNewCalled = false
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ saveNewCalled = true; 1L }, { updateCalled = true }), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertEquals("Kundenbesuch München", titleField(dlg).value)
        assertTrue(detailedOnInvoiceSwitch(dlg).value)
        assertTrue(fixedPriceSwitch(dlg).value)

        saveButton(dlg).click()

        assertTrue(updateCalled)
        assertFalse(saveNewCalled)
    }

    @Test
    fun `deleting an expense row removes it before save`() {
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ cmd -> captured = cmd; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Workshop"
        selectEditTab(dlg)
        addExpenseButton(dlg, "invoiceDetail.trip.kind.other").click()
        flatten(dlg).filterIsInstance<TextField>()
            .first { it.placeholder == UiTranslations.tr("invoiceDetail.trip.expense.description") }.value = "Parken"
        val deleteButton = flatten(dlg).filterIsInstance<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.trip.expense.delete") }

        deleteButton.click()
        saveButton(dlg).click()

        assertTrue(captured?.expenses.isNullOrEmpty())
        assertNull(captured?.fixedNetPrice)
    }

    // ── renamed labels ────────────────────────────────────────────────

    @Test
    fun `the transport and mileage kind labels carry their renamed text, no PO-TEXT marker blocked it`() {
        assertEquals("Fahrkarten", UiTranslations.tr("invoiceDetail.trip.kind.transport"))
        assertEquals("Kilometer", UiTranslations.tr("invoiceDetail.trip.kind.mileage"))
    }

    // ── kind-switch re-prefills like the add button (defect fix) ──────

    @Test
    fun `changing an existing row's kind re-prefills unit, quantity and price like the add button would`() {
        whenever(mileageRateResolver.ratePerKm(VehicleType.CAR, FIXED_TODAY))
            .thenReturn(BigDecimal("0.30"))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)
        addExpenseButton(dlg, "invoiceDetail.trip.kind.other").click()
        val kindBox = flatten(dlg).filterIsInstance<ComboBox<TripExpenseKind>>().single { it.width == "9rem" }

        kindBox.value = TripExpenseKind.MILEAGE

        val priceField = flatten(dlg).filterIsInstance<NumberField>().first { it.width == "8rem" }
        val quantityField = flatten(dlg).filterIsInstance<NumberField>().first { it.width == "6rem" }
        val unitBox = flatten(dlg).filterIsInstance<ComboBox<InvoiceItemUnit>>().single { it.width == "7rem" }
        assertEquals(0.30, priceField.value, "mileage's resolved rate, not the OTHER row's stale 0.0")
        assertEquals(0.0, quantityField.value, "mileage's own default quantity (0), not OTHER's 1")
        assertEquals(InvoiceItemUnit.KILOMETER, unitBox.value)
        val vehicleBox = flatten(dlg).filterIsInstance<ComboBox<*>>().single { it.width == "12rem" }
        assertTrue(vehicleBox.isVisible, "vehicle type becomes visible too, same as the add button")
    }

    // ── two-tab split ──────────────────────────────────────────────────

    @Test
    fun `the Posten section exposes an Übersicht and a Bearbeiten tab, Übersicht selected by default`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val tabs = flatten(dlg).filterIsInstance<TabSheet>().single()

        assertEquals(2, tabs.tabCount)
        assertEquals(UiTranslations.tr("invoiceDetail.trip.tab.overview"), tabs.getTabAt(0).label)
        assertEquals(UiTranslations.tr("invoiceDetail.trip.tab.edit"), tabs.getTabAt(1).label)
        assertEquals(tabs.getTabAt(0), tabs.selectedTab)
    }

    private fun overviewGrid(dlg: Dialog): Grid<Any> =
        flatten(dlg).filterIsInstance<Grid<*>>().single().requireAs()

    private fun overviewRowTexts(dlg: Dialog): List<List<String>> {
        val grid = overviewGrid(dlg)
        return grid.genericDataView.items.toList().filterNotNull().map { grid._getFormattedRow(it) }
    }

    @Test
    fun `the Übersicht grid lists expenses in expenseRows order and that order survives a move`() {
        val existing = existingTripItem(expenses = listOf(
            tripExpense(id = 1L, kind = TripExpenseKind.HOTEL, description = "Hotel Berlin"),
            tripExpense(id = 2L, kind = TripExpenseKind.OTHER, description = "Parkgebühr"),
        ))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertEquals(listOf("Hotel Berlin", "Parkgebühr"), overviewRowTexts(dlg).map { row -> row.first { it == "Hotel Berlin" || it == "Parkgebühr" } })

        selectEditTab(dlg)
        val moveDown = flatten(dlg).filterIsInstance<Button>()
            .first { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.trip.expense.moveDown") }
        moveDown.click()

        assertEquals(listOf("Parkgebühr", "Hotel Berlin"), overviewRowTexts(dlg).map { row -> row.first { it == "Hotel Berlin" || it == "Parkgebühr" } })
    }

    @Test
    fun `switching from Bearbeiten back to Übersicht does not lose an in-progress description edit`() {
        val existing = existingTripItem(expenses = listOf(tripExpense(id = 1L, description = "Alt")))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)
        val descriptionField = flatten(dlg).filterIsInstance<TextField>()
            .first { it.placeholder == UiTranslations.tr("invoiceDetail.trip.expense.description") }

        descriptionField.value = "Neu"
        val tabs = flatten(dlg).filterIsInstance<TabSheet>().single()
        tabs.selectedIndex = 0 // Übersicht

        assertEquals(listOf("Neu"), overviewRowTexts(dlg).map { row -> row.first { it == "Neu" } })
    }

    // ── receipt picker fixes ───────────────────────────────────────────

    @Test
    fun `the receipt field is hidden entirely, not disabled, when the trip has no receipts yet`() {
        val existing = existingTripItem(expenses = listOf(tripExpense()))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(42L)).thenReturn(emptyList())
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(flatten(dlg).filterIsInstance<ComboBox<ReceiptRowDto>>().none { it.width == "10rem" })
    }

    @Test
    fun `a receipt already assigned to another row is excluded from this row's item list`() {
        val receiptA = ReceiptRowDto(
            id = 11L, fileName = "a.pdf", description = "", documentDate = null,
            sizeBytes = null, uploadedAt = null, objectKey = "obj-11",
        )
        val receiptB = receiptA.copy(id = 12L, fileName = "b.pdf", objectKey = "obj-12")
        val existing = existingTripItem(expenses = listOf(
            tripExpense(id = 1L, receiptId = 11L, description = "Zeile 1"),
            tripExpense(id = 2L, receiptId = null, description = "Zeile 2"),
        ))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(42L)).thenReturn(listOf(receiptA, receiptB))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val receiptBoxes = flatten(dlg).filterIsInstance<ComboBox<ReceiptRowDto>>().filter { it.width == "10rem" }
        val row1Items = receiptBoxes[0].genericDataView.items.toList()
        val row2Items = receiptBoxes[1].genericDataView.items.toList()

        assertTrue(row1Items.any { it.id == 11L }, "the row's own current receipt stays selectable")
        assertTrue(row2Items.none { it.id == 11L }, "already assigned to row 1, excluded from row 2's list")
        assertTrue(row2Items.any { it.id == 12L })
    }

    @Test
    fun `clearing the receipt combo clears an assignment`() {
        val receiptA = ReceiptRowDto(
            id = 11L, fileName = "a.pdf", description = "", documentDate = null,
            sizeBytes = null, uploadedAt = null, objectKey = "obj-11",
        )
        val existing = existingTripItem(expenses = listOf(tripExpense(id = 1L, receiptId = 11L)))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(42L)).thenReturn(listOf(receiptA))
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, { cmd -> captured = cmd }), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)
        val receiptBox = flatten(dlg).filterIsInstance<ComboBox<ReceiptRowDto>>().single { it.width == "10rem" }

        receiptBox.value = null
        saveButton(dlg).click()

        assertNull(captured?.expenses?.single()?.receiptId)
    }

    // ── assignment-level hint ──────────────────────────────────────────

    @Test
    fun `a static hint near Belege explains the two assignment levels`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(
            flatten(dlg).any { it.element.text?.contains(UiTranslations.tr("invoiceDetail.trip.receipts.assignmentHint")) == true },
        )
    }

    // ── per-diem entries as addable rows ────────────────────────────────

    private fun periodFromField(dlg: Dialog): DatePicker =
        dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<DatePicker>()[0]

    private fun periodToField(dlg: Dialog): DatePicker =
        dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<DatePicker>()[1]

    private fun perDiemAddButton(dlg: Dialog): Button =
        flatten(dlg).filterIsInstance<Button>().single { it.text == UiTranslations.tr("invoiceDetail.perDiem.add") }

    private fun perDiemEntryDateBoxes(dlg: Dialog): List<ComboBox<LocalDate>> =
        flatten(dlg).filterIsInstance<ComboBox<*>>()
            .filter { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.perDiem.date") }
            .map { it.requireAs<ComboBox<LocalDate>>() }

    private fun perDiemEntryParticipantFields(dlg: Dialog): List<TextField> =
        flatten(dlg).filterIsInstance<TextField>()
            .filter { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.perDiem.entry.participant") }

    /** The per-diem block's own mandatory description field, distinguished from every
     *  other `TextField` in the dialog by its label (it has no `aria-label`, unlike the compact
     *  per-entry fields — a normal, fully labeled `FormLayout` field). */
    private fun perDiemDescriptionField(dlg: Dialog): TextField =
        flatten(dlg).filterIsInstance<TextField>().single { it.label == UiTranslations.tr("invoiceDetail.perDiem.description") }

    /** Both per-diem Section blocks stay invisible until the
     *  first entry is added; `isVisible` toggles rather than adding/removing them from the layout
     *  tree, so [flatten] (which walks `Component.children` regardless of visibility) still finds
     *  them — this reads their own `isVisible` state instead. Checks only each candidate's OWN
     *  header title (not a deep [flatten] of its subtree): the outer "Posten" Section structurally
     *  CONTAINS both nested per-diem Sections (they live inside its own [TabSheet] tab content), so
     *  a subtree-wide title search would wrongly match it too. */
    private fun perDiemSections(dlg: Dialog): List<Component> =
        flatten(dlg).filter { it.classNames.contains("sp-section") }
            .filter { section ->
                val header = section.children.toList().firstOrNull { it.classNames.contains("sp-section-header") }
                val title = header?.children?.toList()?.filterIsInstance<com.vaadin.flow.component.html.Span>()
                    ?.firstOrNull { it.classNames.contains("sp-section-title") }
                title?.text == UiTranslations.tr("invoiceDetail.trip.perDiem.title")
            }

    private fun perDiemOverviewGrid(dlg: Dialog): Grid<*> =
        dlg._get<Grid<*>> { id = TripDialog.PER_DIEM_OVERVIEW_GRID_ID }

    /** Per-diem-as-a-Posten follow-up — the Posten grid still carries no id of its
     *  own, so it is found by elimination: the one other [Grid] in the dialog once the (id'd)
     *  per-diem grid is excluded. */
    private fun expensesOverviewGrid(dlg: Dialog): Grid<*> =
        flatten(dlg).filterIsInstance<Grid<*>>().single { it.id.orElse(null) != TripDialog.PER_DIEM_OVERVIEW_GRID_ID }

    private fun perDiemOverviewColumn(grid: Grid<*>, headerKey: String): Grid.Column<*> =
        grid.columns.single { it.headerText == UiTranslations.tr(headerKey) }

    /** Reads a plain (value-provider, not component) column's formatted cell text for one row,
     *  by the column's own header key — robust against column reordering. */
    private fun perDiemOverviewValueCellText(dlg: Dialog, headerKey: String, item: Any): String {
        val grid = perDiemOverviewGrid(dlg).requireAs<Grid<Any>>()
        val index = grid.columns.indexOf(perDiemOverviewColumn(grid, headerKey))
        return grid._getFormattedRow(item)[index]
    }

    private fun perDiemOverviewDescriptionCell(grid: Grid<*>, item: Any): Component {
        val descriptionColumn = perDiemOverviewColumn(grid, "invoiceDetail.trip.expense.description")
        val renderer = descriptionColumn.renderer.requireAs<com.vaadin.flow.data.renderer.ComponentRenderer<Component, Any>>()
        return renderer.createComponent(item)
    }

    // ── Per-diem overview ergonomics follow-up ─────────────────

    @Test
    fun `the per-diem overview grid mirrors the expenses overview grid's header set, column for column`() {
        val existing = existingTripItem(
            expenses = listOf(tripExpense(id = 1L)),
            perDiemDays = listOf(perDiemDay(id = 1L)),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val expensesHeaders = expensesOverviewGrid(dlg).columns.mapNotNull { it.headerText }
        val perDiemHeaders = perDiemOverviewGrid(dlg).columns.mapNotNull { it.headerText }

        assertEquals(expensesHeaders, perDiemHeaders, "the two tables must read as one list split by section")
    }

    @Test
    fun `the participant is not repeated in Beschreibung while every entry shares the same, single participant`() {
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = "Max Muster"),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 2), participant = "Max Muster"),
            ),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val grid = perDiemOverviewGrid(dlg)
        val cell = perDiemOverviewDescriptionCell(grid, grid.genericDataView.items.toList().first())
        assertFalse(
            flatten(cell).any { it.element.text?.contains("Max Muster") == true },
            "a single participant belongs in the caption, not repeated on every row",
        )
    }

    @Test
    fun `the participant joins Beschreibung once the trip has two distinct participants`() {
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = "Max Muster"),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 1), participant = "Eva Beispiel"),
            ),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val grid = perDiemOverviewGrid(dlg)
        val items = grid.genericDataView.items.toList()
        val maxCell = perDiemOverviewDescriptionCell(grid, items.single { (it as PerDiemOverviewRow).participant == "Max Muster" })
        val evaCell = perDiemOverviewDescriptionCell(grid, items.single { (it as PerDiemOverviewRow).participant == "Eva Beispiel" })
        assertTrue(flatten(maxCell).any { it.element.text?.contains("Max Muster") == true }, "two distinct participants must be told apart in Beschreibung")
        assertTrue(flatten(evaCell).any { it.element.text?.contains("Eva Beispiel") == true })
    }

    @Test
    fun `an arrival-departure day shows the arrival-departure rate as Einzelpreis and the claimable amount as Gesamt`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"), arrivalDepartureRate = Money.of("14.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        whenever(
            perDiemCalculator.previewClaimableAmount(
                rate = rate, isArrivalDeparture = true, deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), employerContribution = Money.ZERO,
            ),
        ).thenReturn(Money.of("14.00"))
        val existing = existingTripItem(
            perDiemDays = listOf(perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), isArrivalDeparture = true)),
        )
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val item = perDiemOverviewGrid(dlg).genericDataView.items.toList().single()
        assertEquals(
            MoneyFormatter.format(Money.of("14.00")),
            perDiemOverviewValueCellText(dlg, "invoiceDetail.trip.expenses.overview.unitPrice", item),
            "the arrival/departure rate is the day's own Einzelpreis",
        )
        assertEquals(
            MoneyFormatter.format(Money.of("14.00")),
            perDiemOverviewValueCellText(dlg, "invoiceDetail.trip.expenses.overview.total", item),
            "Gesamt is the claimable amount, no deductions applied here",
        )
    }

    @Test
    fun `a full day with breakfast provided shows the full-day rate as Einzelpreis, a smaller Gesamt, and the deduction marker in Beschreibung`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"), arrivalDepartureRate = Money.of("14.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        whenever(
            perDiemCalculator.previewClaimableAmount(
                rate = rate, isArrivalDeparture = false, deductions = PerDiemMealDeductions(breakfast = true, lunch = false, dinner = false), employerContribution = Money.ZERO,
            ),
        ).thenReturn(Money.of("22.40"))
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), isArrivalDeparture = false, breakfastDeduction = true),
            ),
        )
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val grid = perDiemOverviewGrid(dlg)
        val item = grid.genericDataView.items.toList().single()
        assertEquals(
            MoneyFormatter.format(Money.of("28.00")),
            perDiemOverviewValueCellText(dlg, "invoiceDetail.trip.expenses.overview.unitPrice", item),
            "a full day's Einzelpreis is the full-day rate",
        )
        assertEquals(
            MoneyFormatter.format(Money.of("22.40")),
            perDiemOverviewValueCellText(dlg, "invoiceDetail.trip.expenses.overview.total", item),
            "Gesamt is smaller than Einzelpreis once a meal deduction applies",
        )
        val cell = perDiemOverviewDescriptionCell(grid, item)
        assertTrue(
            flatten(cell).any { it.element.text?.contains(UiTranslations.tr("invoiceDetail.trip.perDiem.overview.deduction.breakfast")) == true },
            "breakfast provided must render its own deduction marker in Beschreibung",
        )
    }

    @Test
    fun `the out-of-range warning in Beschreibung keeps its tooltip and aria-label`() {
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1))))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        // A period that does not cover the entry's own date flags it as out of range.
        periodFromField(dlg).value = LocalDate.of(2026, 7, 1)
        periodToField(dlg).value = LocalDate.of(2026, 7, 5)

        val grid = perDiemOverviewGrid(dlg)
        val cell = perDiemOverviewDescriptionCell(grid, grid.genericDataView.items.toList().single())

        val warningIcon = flatten(cell).filterIsInstance<Icon>().single { it.icon == "vaadin:warning" }
        assertEquals(UiTranslations.tr("invoiceDetail.perDiem.entry.outOfRange"), warningIcon.element.getAttribute("title"))
        assertEquals(UiTranslations.tr("invoiceDetail.perDiem.entry.outOfRange"), warningIcon.element.getAttribute("aria-label"))
    }

    @Test
    fun `the footer shows the block total and follows a changed entry`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"), arrivalDepartureRate = Money.of("14.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        whenever(
            perDiemCalculator.previewClaimableAmount(
                rate = rate, isArrivalDeparture = true, deductions = PerDiemMealDeductions(breakfast = false, lunch = false, dinner = false), employerContribution = Money.ZERO,
            ),
        ).thenReturn(Money.of("14.00"))
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = "Max Muster", isArrivalDeparture = true),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 2), participant = "Max Muster", isArrivalDeparture = true),
            ),
        )
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val grid = perDiemOverviewGrid(dlg)
        val dateColumn = perDiemOverviewColumn(grid, "invoiceDetail.trip.expenses.overview.date")
        val amountColumn = perDiemOverviewColumn(grid, "invoiceDetail.trip.expenses.overview.total")
        assertEquals(UiTranslations.tr("invoiceDetail.trip.perDiem.overview.footer.total"), dateColumn.footerText, "the Summe label sits on the date column")
        assertEquals(MoneyFormatter.format(Money.of("28.00")), amountColumn.footerText, "the footer total must equal the sum of the rows")

        // Deleting one entry (Bearbeiten tab) must recompute the footer total.
        selectEditTab(dlg)
        val deleteButtons = flatten(dlg).filterIsInstance<Button>()
            .filter { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.perDiem.entry.delete") }
        deleteButtons.first().click()

        assertEquals(MoneyFormatter.format(Money.of("14.00")), amountColumn.footerText, "the footer total must follow a deleted entry")
    }

    @Test
    fun `the scope caption names the single participant`() {
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = "Max Muster"),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 2), participant = "Max Muster"),
            ),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(
            flatten(dlg).any { it.element.text == UiTranslations.tr("invoiceDetail.trip.perDiem.overview.scope.named", 2, "Max Muster") },
            "one participant: N Tage · <name>",
        )
    }

    @Test
    fun `the scope caption counts two or more participants`() {
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = "Max Muster"),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 1), participant = "Eva Beispiel"),
            ),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(
            flatten(dlg).any { it.element.text == UiTranslations.tr("invoiceDetail.trip.perDiem.overview.scope.counted", 1, 2) },
            "two participants: N Tage · M Teilnehmer",
        )
    }

    @Test
    fun `the scope caption shows only the day count when participant names are blank`() {
        val existing = existingTripItem(
            perDiemDays = listOf(
                perDiemDay(id = 1L, date = LocalDate.of(2026, 6, 1), participant = ""),
                perDiemDay(id = 2L, date = LocalDate.of(2026, 6, 2), participant = "   "),
            ),
        )
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(
            flatten(dlg).any { it.element.text == UiTranslations.tr("invoiceDetail.trip.perDiem.overview.scope.daysOnly", 2) },
            "unnamed entries must not be counted as participants",
        )
    }

    @Test
    fun `the block description is visible read-only in the Übersicht tab, not only in Bearbeiten`() {
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay(description = "Team-Reise Genf")))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(
            flatten(dlg).any { it.element.text?.contains("Team-Reise Genf") == true },
            "the per-diem block's own description must be readable in the Übersicht tab (default-selected)",
        )
    }

    @Test
    fun `saving a trip with two non-contiguous per-diem entries round-trips correctly`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ cmd -> captured = cmd; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Dienstreise mit Lücke"
        // Seed a one-day period first so the first "+" click's bulk-fill yields exactly one
        // entry, then widen to the real trip period afterward -- isolates this test's own "two
        // independent, manually placed entries" setup from that behavior.
        periodFromField(dlg).value = LocalDate.of(2026, 6, 1)
        periodToField(dlg).value = LocalDate.of(2026, 6, 1)
        selectEditTab(dlg)

        perDiemAddButton(dlg).click() // bulk-fills just 6/1 (1 entry)
        periodToField(dlg).value = LocalDate.of(2026, 6, 5)
        perDiemAddButton(dlg).click() // one more, defaulting to 6/1 (the only existing entry's date)
        perDiemEntryDateBoxes(dlg).last().value = LocalDate.of(2026, 6, 5) // move it to a non-adjacent date
        perDiemEntryParticipantFields(dlg)[0].value = "Kundentermin Vormittag"
        perDiemEntryParticipantFields(dlg)[1].value = "Kundentermin Nachmittag"
        perDiemDescriptionField(dlg).value = "Team-Reise mit Lücke"

        saveButton(dlg).click()

        assertFalse(dlg.isOpened, "a valid save closes the dialog")
        val perDiemDays = captured?.perDiemDays?.sortedBy { it.date }
        assertEquals(listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)), perDiemDays?.map { it.date }, "two independent, non-adjacent entries must both round-trip")
        assertEquals(listOf("Kundentermin Vormittag", "Kundentermin Nachmittag"), perDiemDays?.map { it.participant }, "each entry's own participant round-trips independently")
        assertTrue(perDiemDays?.all { it.description == "Team-Reise mit Lücke" } == true, "the block's own description round-trips identically onto every entry")
    }

    @Test
    fun `existing trip saves an additional unnamed day using the German rate without a configured default`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay(description = "", participant = "")))
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, { captured = it }), today = FIXED_TODAY).open()
        val dlg = dialog()
        periodFromField(dlg).value = LocalDate.of(2026, 6, 1)
        periodToField(dlg).value = LocalDate.of(2026, 6, 1)
        selectEditTab(dlg)
        perDiemAddButton(dlg).click()
        saveButton(dlg).click()

        assertFalse(dlg.isOpened)
        assertEquals(2, captured?.perDiemDays?.size)
        assertTrue(captured!!.perDiemDays.all { it.description.isEmpty() && it.participant.isEmpty() && it.perDiemRateId == 5L })
    }

    @Test
    fun `per-diem sections occupy the full available width in overview and editor`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existingTripItem(perDiemDays = listOf(perDiemDay())), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val sections = perDiemSections(dialog())
        assertEquals(2, sections.size)
        sections.forEach { section ->
            assertEquals("100%", section.element.style.get("width"))
            assertTrue(section.hasClassName("lt-border-box"))
        }
    }

    @Test
    fun `saving a trip with two per-diem entries on the same date but different participants round-trips both`() {
        // The exact scenario from the confirmed PO mockup: Frau Müller and Herr Klein both dated
        // 12.03.2026 -- the former "one entry per date" restriction must not block this.
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ cmd -> captured = cmd; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Dienstreise Berlin"
        // Seed a one-day period first so the first "+" click's bulk-fill yields exactly one
        // entry, then widen afterward -- isolates this test's own "two participants, same date"
        // setup from that behavior.
        periodFromField(dlg).value = LocalDate.of(2026, 3, 12)
        periodToField(dlg).value = LocalDate.of(2026, 3, 12)
        selectEditTab(dlg)

        perDiemAddButton(dlg).click() // bulk-fills just 12.03 (1 entry)
        periodToField(dlg).value = LocalDate.of(2026, 3, 13)
        perDiemAddButton(dlg).click() // one more, defaulting to 12.03 (the only existing entry's date)
        perDiemEntryParticipantFields(dlg)[0].value = "Frau Müller"
        perDiemEntryParticipantFields(dlg)[1].value = "Herr Klein"
        perDiemDescriptionField(dlg).value = "Team-Reise Berlin"

        saveButton(dlg).click()

        assertFalse(dlg.isOpened, "a valid save closes the dialog")
        val perDiemDays = captured?.perDiemDays
        assertEquals(2, perDiemDays?.size, "both entries must round-trip, not be merged or blocked by the shared date")
        assertTrue(perDiemDays?.all { it.date == LocalDate.of(2026, 3, 12) } == true)
        assertEquals(setOf("Frau Müller", "Herr Klein"), perDiemDays?.map { it.participant }?.toSet())
    }

    @Test
    fun `the first plus click fills in one per-diem entry per day of the trip period`() {
        // A user who entered a multi-day period and clicked "+" once expected an entry for
        // every day, not just one.
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        periodFromField(dlg).value = LocalDate.of(2026, 6, 1)
        periodToField(dlg).value = LocalDate.of(2026, 6, 5)
        selectEditTab(dlg)

        perDiemAddButton(dlg).click()

        val dates = perDiemEntryDateBoxes(dlg).map { it.value }.sorted()
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5),
            ),
            dates,
            "one entry per day of the trip period after a single click",
        )
    }

    @Test
    fun `the per-diem block's own form shows Beschreibung, Zuschuss, Satz in that order`() {
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay()))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        val descriptionField = perDiemDescriptionField(dlg)
        assertFalse(descriptionField.isRequiredIndicatorVisible, "the block description is optional")

        val perDiemForm = flatten(dlg).filterIsInstance<FormLayout>().single { it.children.toList().contains(descriptionField) }
        val children = perDiemForm.children.toList()

        assertEquals(descriptionField, children[0], "Beschreibung must come first, per the PO mockup")
        assertTrue(children[1] is NumberField, "Zuschuss (employer contribution) must come second")
        assertTrue(children[2] is ComboBox<*>, "Satz (rate) must come third")
    }

    @Test
    fun `the per-diem entry list header shows compact symbols, not the full arrival-departure-breakfast-lunch-dinner text`() {
        // Regression guard for the symbol-column-header change (business requirement: use
        // symbols instead of full labels), the same pattern as this file's own "never label a
        // column Netto or Brutto" regression test. Scoped to the header row itself
        // (PER_DIEM_ENTRIES_HEADER_CLASS): a later change added a visible legend elsewhere in the
        // dialog that legitimately spells out the same labels in full, so a dialog-wide text
        // search would false-positive.
        val existing = existingTripItem(perDiemDays = listOf(perDiemDay()))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        val headerRow = flatten(dlg).single { it.classNames.contains(PER_DIEM_ENTRIES_HEADER_CLASS) }
        val headerComponents = flatten(headerRow)
        val headerSpans = headerComponents.filterIsInstance<com.vaadin.flow.component.html.Span>()

        listOf(
            "invoiceDetail.perDiem.breakfastProvided",
            "invoiceDetail.perDiem.lunchProvided",
            "invoiceDetail.perDiem.dinnerProvided",
        ).forEach { key ->
            val fullText = UiTranslations.tr(key)
            assertFalse(headerSpans.any { it.text == fullText }, "'$fullText' must no longer be a visible column header text")
            val symbolSpan = headerSpans.single { it.element.getAttribute("title") == fullText }
            assertEquals(fullText, symbolSpan.element.getAttribute("aria-label"), "the symbol must carry the full text as its aria-label too")
            assertTrue(symbolSpan.text.isNotBlank() && symbolSpan.text != fullText, "the visible glyph must be a compact symbol, not the full text")
        }

        // Arrival/departure uses a themed VaadinIcon, not an emoji Span (dark-mode legibility)
        // -- same tooltip/aria-label contract, no visible text at all.
        val arrivalDepartureText = UiTranslations.tr("invoiceDetail.perDiem.arrivalDeparture")
        assertFalse(headerSpans.any { it.text == arrivalDepartureText }, "'$arrivalDepartureText' must not be a visible column header text")
        val arrivalDepartureIcon = headerComponents.filterIsInstance<Icon>().single { it.element.getAttribute("title") == arrivalDepartureText }
        assertEquals(arrivalDepartureText, arrivalDepartureIcon.element.getAttribute("aria-label"), "the icon must carry the full text as its aria-label too")
    }

    @Test
    fun `the Verpflegungspauschalen section is invisible on a brand-new trip and appears once the first entry is added`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        periodFromField(dlg).value = LocalDate.of(2026, 6, 1)
        periodToField(dlg).value = LocalDate.of(2026, 6, 5)
        selectEditTab(dlg)

        val sections = perDiemSections(dlg)
        assertEquals(2, sections.size, "both tab blocks (Übersicht + Bearbeiten) must exist even while hidden")
        assertTrue(sections.all { !it.isVisible }, "a brand-new trip has zero per-diem entries -- the section stays hidden")

        perDiemAddButton(dlg).click()

        assertTrue(perDiemSections(dlg).all { it.isVisible }, "the section appears the moment the first entry is added")
    }

    @Test
    fun `the Verpflegungspauschalen section hides again once the last entry is deleted`() {
        val rate = PerDiemRateDto(id = 5L, country = Country.DE, fullDayRate = Money.of("28.00"))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(rate))
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(rate)
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        // A one-day period keeps the first "+" click's bulk-fill down to a single entry,
        // matching this test's own "the ONLY entry" premise.
        periodFromField(dlg).value = LocalDate.of(2026, 6, 1)
        periodToField(dlg).value = LocalDate.of(2026, 6, 1)
        selectEditTab(dlg)
        perDiemAddButton(dlg).click()
        assertTrue(perDiemSections(dlg).all { it.isVisible })

        val deleteButton = flatten(dlg).filterIsInstance<Button>()
            .single { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.perDiem.entry.delete") }
        deleteButton.click()

        assertTrue(perDiemSections(dlg).all { !it.isVisible }, "deleting the only entry re-hides the section")
    }

    @Test
    fun `Posten and Verpflegungspauschalen render as styled Section components, not the unstyled lt-section-title span`() {
        // "lt-section-title" carries no CSS rule anywhere in the project (the real one is
        // "sp-section-title", ui/component/Section.kt) -- a plain Span with that class read as
        // unstyled body text with no separation from what came before it. Regression guard.
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val sectionTitles = flatten(dlg)
            .filter { it.classNames.contains("sp-section") }
            .flatMap { flatten(it) }
            .filterIsInstance<com.vaadin.flow.component.html.Span>()
            .filter { it.classNames.contains("sp-section-title") }
            .map { it.text }

        assertTrue(sectionTitles.contains(UiTranslations.tr("invoiceDetail.trip.expenses.title")), "Posten must be a styled Section")
        assertTrue(sectionTitles.contains(UiTranslations.tr("invoiceDetail.trip.perDiem.title")), "Verpflegungspauschalen must be a styled Section")
        assertTrue(flatten(dlg).none { it.classNames.contains("lt-section-title") }, "the unstyled lt-section-title class must not be reintroduced")
    }

    @Test
    fun `expense and per-diem row grids never label a column Netto or Brutto`() {
        // A trip expense row's own amount column must not borrow the invoice/position-level
        // "Netto"/"Brutto" vocabulary -- it is one sub-position among several inside a single
        // TRIP position, and reusing that wording reads as if it were the position's or the
        // invoice's own net/gross total. Regression guard for the renamed
        // "invoiceDetail.trip.expenses.overview.total" key (formerly ".net" = "Netto").
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val headers = flatten(dlg).filterIsInstance<Grid<*>>()
            .flatMap { it.columns }
            .mapNotNull { it.headerText }

        assertTrue(headers.isNotEmpty(), "expected at least one grid column header in the trip dialog")
        headers.forEach { header ->
            assertFalse(header.contains("Netto", ignoreCase = true), "column header '$header' must not say Netto")
            assertFalse(header.contains("Brutto", ignoreCase = true), "column header '$header' must not say Brutto")
        }
    }

    // ── Read-only mode: a sent or cancelled trip stays readable ────────────────

    @Test
    fun `a read-only trip disables every input`() {
        val existing = existingTripItem()
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        assertTrue(titleField(dlg).isReadOnly)
        assertFalse(periodFromField(dlg).isEnabled)
        assertFalse(periodToField(dlg).isEnabled)
        assertFalse(detailedOnInvoiceSwitch(dlg).isEnabled)
        assertFalse(fixedPriceSwitch(dlg).isEnabled)
    }

    @Test
    fun `a read-only trip's state hint names ISSUED`() {
        val existing = existingTripItem()
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val expected = UiTranslations.tr("invoiceDetail.trip.readOnlyHint", UiTranslations.tr("invoice.status.issued"))
        assertTrue(flatten(dlg).any { it.element.text == expected }, "expected the ISSUED state hint: '$expected'")
    }

    @Test
    fun `a read-only trip's state hint names CANCELLED, distinct from the ISSUED hint`() {
        val existing = existingTripItem()
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.CANCELLED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val expectedCancelled = UiTranslations.tr("invoiceDetail.trip.readOnlyHint", UiTranslations.tr("invoice.status.cancelledOriginal"))
        val expectedSent = UiTranslations.tr("invoiceDetail.trip.readOnlyHint", UiTranslations.tr("invoice.status.issued"))
        assertTrue(flatten(dlg).any { it.element.text == expectedCancelled }, "expected the CANCELLED state hint: '$expectedCancelled'")
        assertNotEquals(expectedSent, expectedCancelled)
    }

    @Test
    fun `a read-only trip hides the Bearbeiten tab entirely, Übersicht stays selected`() {
        val existing = existingTripItem(expenses = listOf(tripExpense()))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val tabs = flatten(dlg).filterIsInstance<TabSheet>().single()

        assertEquals(1, tabs.tabCount, "the Bearbeiten tab must not exist at all while read-only")
        assertEquals(UiTranslations.tr("invoiceDetail.trip.tab.overview"), tabs.getTabAt(0).label)
        assertEquals(tabs.getTabAt(0), tabs.selectedTab)
    }

    @Test
    fun `a read-only trip shows no add or delete buttons at all`() {
        val existing = existingTripItem(expenses = listOf(tripExpense()), perDiemDays = listOf(perDiemDay()))
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(listOf(PerDiemRateDto(id = 5L, country = Country.DE)))
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val allButtons = flatten(dlg).filterIsInstance<Button>()
        assertTrue(allButtons.none { it.text.startsWith("+") }, "no '+' buttons while read-only")
        assertTrue(
            allButtons.none { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.trip.expense.delete") },
            "no expense delete buttons while read-only",
        )
        assertTrue(
            allButtons.none { it.element.getAttribute("aria-label") == UiTranslations.tr("invoiceDetail.perDiem.entry.delete") },
            "no per-diem delete buttons while read-only",
        )
    }

    @Test
    fun `a read-only trip's footer has exactly one button, and the workbook download stays enabled`() {
        val port: TripWorkbookExportQueryPort = mock()
        whenever(port.fileName("INV-1", 1L, "3")).thenReturn("dienstreise_inv-1_3.xlsx")
        val existing = existingTripItem()
        TripDialog(
            TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing,
            tripWorkbookExport = port, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        // Dialog.DialogFooter is a plain HasComponents, not itself a Component — its own
        // `children` throws (only a real Component supports that); its actual content lives as
        // element children of `footer.element` (Dialog.DialogFooter.getElement() returns the
        // virtual-child root every add() appends to), read back to Components via
        // Element.getComponent().
        val footerButtons = dlg.footer.element.children.toList()
            .mapNotNull { it.component.orElse(null) }
            .filterIsInstance<Button>()
        assertEquals(1, footerButtons.size, "only app.close as a direct footer button (the export control is an Anchor)")
        assertEquals(UiTranslations.tr("app.close"), footerButtons.single().text)

        val exportButton = dlg._get<Button> { id = TripDialog.EXPORT_BUTTON_ID }
        assertTrue(exportButton.isEnabled, "reading is the whole point of read-only mode")
    }

    @Test
    fun `a read-only trip's close button closes immediately, no unsaved-changes confirmation`() {
        val existing = existingTripItem()
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        val closeButton = dlg._get<Button> { text = UiTranslations.tr("app.close") }

        closeButton.click()

        assertFalse(dlg.isOpened)
    }

    @Test
    fun `save is unreachable while read-only, even invoked directly`() {
        val existing = existingTripItem()
        var saveNewCalled = false
        var updateCalled = false
        val tripDialog = TripDialog(
            TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing,
            saveCallbacks = TripSaveCallbacks({ saveNewCalled = true; 1L }, { updateCalled = true }), today = FIXED_TODAY,
        )
        tripDialog.open()

        tripDialog.save()

        assertFalse(saveNewCalled)
        assertFalse(updateCalled)
        assertTrue(dialog().isOpened, "save() must not close the dialog either")
    }

    // ── Trip expense kind display order ──────────────────────────────────

    @Test
    fun `display order guard - all trip expense kinds are covered exactly once`() {
        assertEquals(
            TripExpenseKind.entries.toSet(),
            TRIP_EXPENSE_KIND_DISPLAY_ORDER.toSet(),
            "every kind must be in the display order, no duplicates or omissions",
        )
    }

    @Test
    fun `add-button row shows kinds in display order (Unterkunft, Fahrkarten, Kilometer, Reisezeit, Sonstiges)`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        val allButtons = flatten(dlg).filterIsInstance<Button>()
        val addButtons = allButtons.filter { it.text.startsWith("+ ") }
        val expectedLabels = listOf("hotel", "transport", "mileage", "travelTime", "other")
            .map { "+ ${UiTranslations.tr("invoiceDetail.trip.kind.$it")}" }

        assertEquals(expectedLabels, addButtons.map { it.text }, "buttons must appear in display order")
    }

    @Test
    fun `all six add buttons appear in order when per-diem is on, per-diem button last`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        val allButtons = flatten(dlg).filterIsInstance<Button>()
        val addButtons = allButtons.filter { it.text.startsWith("+ ") }
        // invoiceDetail.perDiem.add's own value already starts with "+ " (PerDiemDaysEditor.addButton
        // builds its label straight from it) -- unlike the per-kind buttons, it is not re-prefixed.
        val expectedLabels = listOf("hotel", "transport", "mileage", "travelTime", "other")
            .map { "+ ${UiTranslations.tr("invoiceDetail.trip.kind.$it")}" } +
            UiTranslations.tr("invoiceDetail.perDiem.add")

        assertEquals(expectedLabels, addButtons.map { it.text }, "all six add buttons, per-diem button last")
    }

    @Test
    fun `when per-diem feature is off, no trailing gap artefact and no per-diem button`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        val allButtons = flatten(dlg).filterIsInstance<Button>()
        val addButtons = allButtons.filter { it.text.startsWith("+ ") }

        assertEquals(5, addButtons.size, "exactly five expense kind buttons, no per-diem button")
        val lastButton = addButtons.last()
        assertTrue(
            lastButton.text.contains(UiTranslations.tr("invoiceDetail.trip.kind.other")),
            "last button is Sonstiges, no trailing gap",
        )
    }

    @Test
    fun `kind combo in a row lists the same order as the add buttons (Unterkunft, Fahrkarten, Kilometer, Reisezeit, Sonstiges)`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)
        addExpenseButton(dlg, "invoiceDetail.trip.kind.mileage").click()

        // ComboBox<T>'s type argument is erased, so filterIsInstance<ComboBox<TripExpenseKind>>()
        // matches every ComboBox in the row, not just the kind one -- width is what actually tells
        // them apart (kindBox=9rem, unitBox=7rem, vehicleTypeBox=12rem, receiptBox=10rem), the same
        // idiom the other row-widget lookups in this file already use.
        val kindCombo = flatten(dlg).filterIsInstance<ComboBox<TripExpenseKind>>().single { it.width == "9rem" }
        assertEquals(TripExpenseKind.MILEAGE, kindCombo.value, "newly added row has MILEAGE selected")

        assertEquals(
            listOf(TripExpenseKind.HOTEL, TripExpenseKind.TRANSPORT, TripExpenseKind.MILEAGE, TripExpenseKind.TRAVEL_TIME, TripExpenseKind.OTHER),
            kindCombo.listDataView.items.toList(),
            "kind combo items must be in display order",
        )
    }

    @Test
    fun `read-only invoice hides the Bearbeiten tab and its add-button row`() {
        val existing = existingTripItem()
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, true, invoice(status = InvoiceStatus.ISSUED.ordinal), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()

        val tabSheet = flatten(dlg).filterIsInstance<TabSheet>().single()
        assertEquals(1, tabSheet.tabCount, "read-only mode shows only Übersicht tab, not Bearbeiten")

        val allButtons = flatten(dlg).filterIsInstance<Button>()
        val addButtons = allButtons.filter { it.text.startsWith("+ ") }
        assertEquals(0, addButtons.size, "no add-button row visible in read-only mode")
    }

    @Test
    fun `switching a row from OTHER to TRAVEL_TIME applies travel-time prefill (HOUR unit, no rate provided)`() {
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)
        addExpenseButton(dlg, "invoiceDetail.trip.kind.other").click()

        // See the display-order test above on why width, not the erased type parameter, is what
        // identifies a row's ComboBox.
        val kindCombo = flatten(dlg).filterIsInstance<ComboBox<TripExpenseKind>>().single { it.width == "9rem" }
        kindCombo.value = TripExpenseKind.TRAVEL_TIME

        val unitCombo = flatten(dlg).filterIsInstance<ComboBox<InvoiceItemUnit>>().single { it.width == "7rem" }
        assertEquals(
            UiTranslations.tr("invoiceDetail.unit.hour"),
            unitCombo.value?.let { InvoiceUnitLabels.label(dlg, it) },
            "switching to TRAVEL_TIME must set the unit to HOUR",
        )

        // The price NumberField has no label (createExpenseRowFields); width (8rem, distinct from
        // quantityField's 6rem) is the only reliable way to identify it.
        val priceField = flatten(dlg).filterIsInstance<NumberField>().single { it.width == "8rem" }
        assertNull(
            priceField.value,
            "travel-time row must have no unit price (rate not provided)",
        )
    }

    @Test
    fun `a fresh TRAVEL_TIME row shows HOUR in the unit combo and saves correctly`() {
        var captured: TripCommand? = null
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), saveCallbacks = TripSaveCallbacks({ cmd -> captured = cmd; 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        titleField(dlg).value = "Dienstreise"
        selectEditTab(dlg)
        addExpenseButton(dlg, "invoiceDetail.trip.kind.travelTime").click()

        val unitCombo = flatten(dlg).filterIsInstance<ComboBox<InvoiceItemUnit>>().single { it.width == "7rem" }
        val selectedUnit = unitCombo.value

        assertEquals(
            InvoiceItemUnit.HOUR,
            selectedUnit,
            "TRAVEL_TIME row must start with HOUR unit",
        )

        val quantityFields = flatten(dlg).filterIsInstance<NumberField>()
            .filter { it.width == "6rem" }
        quantityFields.first().value = 2.0

        saveButton(dlg).click()

        assertEquals(1, captured?.expenses?.size)
        assertEquals(TripExpenseKind.TRAVEL_TIME, captured?.expenses?.get(0)?.kind)
        assertEquals(InvoiceItemUnit.HOUR, captured?.expenses?.get(0)?.unit, "HOUR unit must be saved")
        assertEquals(0, BigDecimal("2").compareTo(captured!!.expenses[0].quantity), "quantity must survive save")
    }

    @Test
    fun `existing trip with rows reopens with persisted row order unchanged`() {
        val existing = existingTripItem(
            expenses = listOf(
                tripExpense(id = 1L, kind = TripExpenseKind.MILEAGE, description = "100 km"),
                tripExpense(id = 2L, kind = TripExpenseKind.TRAVEL_TIME, description = "2h"),
                tripExpense(id = 3L, kind = TripExpenseKind.HOTEL, description = "Nacht"),
            ),
        )
        TripDialog(TripEntryQueries(taxRateQueries, perDiemRateQueries), TripReceiptPorts(invoiceReceiptQueryPort, attachReceiptsUseCase, mock()), recipientQueryPort, hotelRateResolver, mileageRateResolver, perDiemCalculator, false, invoice(), existingItem = existing, saveCallbacks = TripSaveCallbacks({ 1L }, {}), today = FIXED_TODAY).open()
        val dlg = dialog()
        selectEditTab(dlg)

        // width (9rem), not the erased type parameter, is what actually isolates the three kind
        // combos among the rows' other ComboBoxes (unit=7rem, vehicleType=12rem).
        val kindCombos = flatten(dlg).filterIsInstance<ComboBox<TripExpenseKind>>().filter { it.width == "9rem" }
        val rowKinds = kindCombos.map { it.value }

        assertEquals(
            listOf(TripExpenseKind.MILEAGE, TripExpenseKind.TRAVEL_TIME, TripExpenseKind.HOTEL),
            rowKinds,
            "persisted row order must be restored, regardless of display order",
        )
    }

    // B8's wording guard lives in TripWordingGuardTest — a bundle scan, not a Karibu/UI test.
}
