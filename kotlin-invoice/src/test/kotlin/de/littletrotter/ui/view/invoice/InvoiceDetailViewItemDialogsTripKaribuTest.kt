package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort

/**
 * [TripDialogLauncher]/[InvoiceItemDialogs.openTripDialog] are orchestration only: they
 * construct [TripDialog] with this view's own data facades and wire its save callbacks back into
 * [InvoiceItemDialogs.saveNewTrip]/[InvoiceItemDialogs.updateTripItem]. [TripDialogKaribuTest]
 * instantiates [TripDialog] directly and never goes through this entry point, so the wiring itself
 * (including the positionId handed to the update callback) was never exercised.
 *
 * Exercises [InvoiceItemDialogs] directly (a component fixture) instead of going through
 * [InvoiceDetailView].
 */
class InvoiceDetailViewItemDialogsTripKaribuTest {

    private val taxRateQueries = mock<TaxRateQueryPort>()
    private val perDiemRateQueries = mock<PerDiemRateQueryPort>()
    private val editInvoiceItemsUseCase = mock<EditInvoiceItemsUseCase>()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        whenever(taxRateQueries.findReferenceTaxRate(Country.DE, LocalDate.of(2026, 6, 1)))
            .thenReturn(TaxRateDto(id = 1L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), country = Country.DE, isActive = true, isSelectable = true))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun itemDialogs(): InvoiceItemDialogs {
        val (_, dialogs) = ItemDialogTestFixtures.itemComponents(taxRateQueries, editInvoiceItemsUseCase)
        dialogs.bind(InvoiceDetailState())
        return dialogs
    }

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null,
        items = emptyList(), latestGenerationJob = null,
    )

    private fun tripItem(positionId: String = "7") = ItemDto(
        id = 42L, positionId = positionId, description = "Kundenbesuch", quantity = BigDecimal.ONE,
        unit = "FLAT_RATE", unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = "TRIP",
    )

    private fun dialog(): Dialog = _get<Dialog>()

    private fun titleField(dlg: Dialog): TextField =
        dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<TextField>()[0]

    private fun saveButton(dlg: Dialog): com.vaadin.flow.component.button.Button =
        dlg._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("app.save") }

    @Test
    fun `happy path - saving a brand-new trip position routes through InvoiceItemDialogs' own saveNewTrip`() {
        whenever(editInvoiceItemsUseCase.addTripToInvoice(eq(1L), any())).thenReturn(invoice())

        itemDialogs().openTripDialog(host, invoice())
        val dlg = dialog()
        titleField(dlg).value = "Workshop Berlin"
        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).addTripToInvoice(eq(1L), any())
        assertTrue(!dlg.isOpened, "a valid save must close the dialog")
    }

    @Test
    fun `boundary - editing an existing trip position opens in edit mode with its data prefilled`() {
        itemDialogs().openTripDialog(host, invoice(), tripItem())
        val dlg = dialog()

        assertTrue(dlg.headerTitle.contains(UiTranslations.tr(dlg, "invoiceDetail.trip.dialog.title")))
        assertTrue(titleField(dlg).value == "Kundenbesuch")
    }

    @Test
    fun `boundary - saving an edited trip position routes to updateTripItem with that position's own id, not a stale one`() {
        whenever(editInvoiceItemsUseCase.updateTripInInvoice(eq(1L), eq("7"), any())).thenReturn(invoice())

        itemDialogs().openTripDialog(host, invoice(), tripItem(positionId = "7"))
        val dlg = dialog()
        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).updateTripInInvoice(eq(1L), eq("7"), any())
    }

    @Test
    fun `boundary - a repository failure while saving a new trip is caught and reported, never left to crash the click`() {
        whenever(editInvoiceItemsUseCase.addTripToInvoice(eq(1L), any())).thenThrow(RuntimeException("boom"))

        itemDialogs().openTripDialog(host, invoice())
        val dlg = dialog()
        titleField(dlg).value = "Workshop Berlin"
        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).addTripToInvoice(eq(1L), any())
    }
}
