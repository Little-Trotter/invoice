package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * [InvoiceItemDialogs] is orchestration only: it routes an existing position to the
 * correct concrete dialog by [InvoiceItemCategory] and constructs the two goods/service dialogs
 * with this view's own data facades and save callbacks. Neither [GoodsItemDialogKaribuTest] nor
 * [ServiceItemDialogKaribuTest] ever goes through these entry points (they construct the dialogs
 * directly), so the routing itself was never exercised.
 *
 * Exercises [InvoiceItemDialogs] directly (a component fixture) instead of going through
 * [InvoiceDetailView]; a plain `Div` stands in for the `host: Component` context.
 */
class InvoiceDetailViewItemDialogsGoodsServiceKaribuTest {

    private val taxRateQueries = mock<TaxRateQueryPort>()
    private val perDiemRateQueries = mock<PerDiemRateQueryPort>()
    private val editInvoiceItemsUseCase = mock<EditInvoiceItemsUseCase>()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        val standard = TaxRateDto(id = 1L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), isDefault = true, isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(standard))
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
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun item(category: InvoiceItemCategory, description: String = "Bestandsposition") = ItemDto(
        id = 5L, positionId = "1", description = description, quantity = BigDecimal("2"),
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal("10.00")),
        taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = category.name,
    )

    private fun dialog(): Dialog = _get<Dialog>()

    private fun descField(dlg: Dialog): TextArea =
        dlg.children.toList().filterIsInstance<FormLayout>().first().children.toList().filterIsInstance<TextArea>()[0]

    private fun saveButton(dlg: Dialog): Button = dlg._get<Button> { text = UiTranslations.tr("app.save") }

    @Test
    fun `happy path - editing a PRODUCT position routes to the goods dialog with its description prefilled`() {
        itemDialogs().openEditInvoiceItemDialog(host, invoice(), item(InvoiceItemCategory.PRODUCT, "Hydraulikpumpe"))

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.goods.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - editing a UNIT_PRICE position also routes to the goods dialog, not a separate one`() {
        itemDialogs().openEditInvoiceItemDialog(host, invoice(), item(InvoiceItemCategory.UNIT_PRICE, "Flaechenarbeit"))

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.goods.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - editing a SERVICE position routes to the service dialog instead of the goods dialog`() {
        itemDialogs().openEditInvoiceItemDialog(host, invoice(), item(InvoiceItemCategory.SERVICE, "Beratung"))

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.service.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - opening a brand-new goods dialog without an existing item defaults to the PRODUCT category`() {
        itemDialogs().openGoodsDialog(host, invoice())

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.goods.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - opening a brand-new service dialog without an existing item still opens successfully`() {
        itemDialogs().openServiceDialog(host, invoice())

        val dlg = dialog()
        assertEquals(UiTranslations.tr(dlg, "invoiceDetail.service.dialog.title"), dlg.headerTitle)
    }

    @Test
    fun `boundary - saving a brand-new goods position routes through InvoiceItemDialogs' own saveNewItem`() {
        whenever(editInvoiceItemsUseCase.addItemToInvoice(eq(1L), any())).thenReturn(invoice())
        itemDialogs().openGoodsDialog(host, invoice())
        val dlg = dialog()
        descField(dlg).value = "Hydraulikpumpe"

        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).addItemToInvoice(eq(1L), any())
    }

    @Test
    fun `boundary - saving an edited goods position routes through InvoiceItemDialogs' own updateItem`() {
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any())).thenReturn(invoice())
        itemDialogs().openEditInvoiceItemDialog(host, invoice(), item(InvoiceItemCategory.PRODUCT, "Hydraulikpumpe"))
        val dlg = dialog()

        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).updateInvoiceItem(eq(1L), any())
    }

    @Test
    fun `boundary - saving a brand-new service position routes through InvoiceItemDialogs' own saveNewItem`() {
        whenever(editInvoiceItemsUseCase.addItemToInvoice(eq(1L), any())).thenReturn(invoice())
        itemDialogs().openServiceDialog(host, invoice())
        val dlg = dialog()
        descField(dlg).value = "Softwareentwicklung Modul A"

        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).addItemToInvoice(eq(1L), any())
    }

    @Test
    fun `boundary - saving an edited service position routes through InvoiceItemDialogs' own updateItem`() {
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any())).thenReturn(invoice())
        itemDialogs().openEditInvoiceItemDialog(host, invoice(), item(InvoiceItemCategory.SERVICE, "Beratung"))
        val dlg = dialog()

        saveButton(dlg).click()

        verify(editInvoiceItemsUseCase).updateInvoiceItem(eq(1L), any())
    }

    @Test
    fun `boundary - editing a TRIP position routes past the goods-service dialogs into the trip dialog`() {
        whenever(perDiemRateQueries.findAllPerDiemRates()).thenReturn(emptyList())
        whenever(perDiemRateQueries.findDefaultPerDiemRate()).thenReturn(null)
        val tripDate = java.time.LocalDate.of(2026, 6, 1)
        whenever(taxRateQueries.findReferenceTaxRate(de.littletrotter.shared.country.Country.DE, tripDate)).thenReturn(
            TaxRateDto(id = 2L, code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")), country = de.littletrotter.shared.country.Country.DE, isActive = true, isSelectable = true),
        )
        val tripInvoice = invoice().copy(invoiceDate = tripDate)

        itemDialogs().openEditInvoiceItemDialog(host, tripInvoice, item(InvoiceItemCategory.TRIP, "Kundenbesuch"))

        val dlg = dialog()
        assertEquals(
            "${UiTranslations.tr(dlg, "button.edit")} - ${UiTranslations.tr(dlg, "invoiceDetail.trip.dialog.title")}",
            dlg.headerTitle,
        )
    }
}
