package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._fireShortcut
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.KeyModifier
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The positions grid's inline editor bindings for cleared/absent values, the trip row's
 * summary line without trip details, and the Alt+N add-item shortcut.
 *
 * Exercises [InvoiceItemGrid]/[InvoiceItemsTab] directly over mocked ports (component
 * fixtures), replacing the former `ItemDialogTestFixtures.view(...)` full-view fixture.
 */
class InvoiceDetailViewItemGridEditorBoundaryKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val standard = ItemDialogTestFixtures.rate("S19", "S", "19")
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(standard))
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(anyOrNull())).thenReturn(emptyList())
        whenever(b2bFieldValueQueries.findB2bFieldValuesForOwners(any(), any())).thenReturn(emptyMap())
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun item(category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT, unitPrice: Money? = Money(BigDecimal("10.00"))) = ItemDto(
        id = 11L, positionId = "1", description = "Pos 1", quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = unitPrice, taxRate = null,
        taxRateRefId = null, netAmount = null, vatAmount = null, grossAmount = null,
        category = category.name, sortOrder = 1, receiptIds = emptyList(), itemDescription = null,
    )

    private fun invoice(items: List<ItemDto>, status: InvoiceStatus = InvoiceStatus.DRAFT) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = status.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null,
    )

    private fun openEditor(editInvoiceItemsUseCase: EditInvoiceItemsUseCase, row: ItemDto): DataTable<ItemDto> {
        val grid = InvoiceItemGrid(taxRateQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, time = FIXED_TIME_SOURCE)
            .apply { onInlineSave = { command -> editInvoiceItemsUseCase.updateInvoiceItem(1L, command) } }
            .build(host, invoice(listOf(row)), listOf(row), enableInlineEdit = true)
        UI.getCurrent().add(grid)
        MockVaadin.clientRoundtrip()
        grid.editor.editItem(row)
        MockVaadin.clientRoundtrip()
        return grid
    }

    private fun numberEditor(grid: DataTable<ItemDto>, headerKey: String): NumberField =
        grid.columns.single { it.headerText == UiTranslations.tr(headerKey) }.editorComponent as NumberField

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    @Test
    fun `happy path - Alt+N on an editable invoice opens the add-item dialog`() {
        val itemsTab = testItemsTab(
            taxRateQueries = taxRateQueries,
            b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
            b2bFieldValueQueries = b2bFieldValueQueries,
        )
        UI.getCurrent().add(itemsTab.apply { render(invoice(emptyList())) })

        UI.getCurrent()._fireShortcut(Key.KEY_N, KeyModifier.ALT)

        assertTrue(_find<Dialog>().any { it.isOpened }, "the goods dialog is open")
    }

    @Test
    fun `boundary - clearing the inline quantity and price editors saves zero, never null`() {
        val row = item()
        val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(any(), any())).thenReturn(invoice(listOf(row)))
        val grid = openEditor(editInvoiceItemsUseCase, row)

        numberEditor(grid, "invoiceDetail.items.column.quantity").value = null
        numberEditor(grid, "invoiceDetail.items.column.unitPriceNet").value = null
        grid.editor.save()

        // ItemDto's quantity/unitPriceNet are now `val`; the editor no longer mutates the
        // bean in place (see ItemGridEditingContext's KDoc), so the observable contract is the
        // command actually sent to the backend, not the discarded row instance.
        val commandCaptor = argumentCaptor<InvoiceItemCommand>()
        verify(editInvoiceItemsUseCase).updateInvoiceItem(any(), commandCaptor.capture())
        assertEquals(0, commandCaptor.firstValue.quantity.compareTo(BigDecimal.ZERO))
        assertEquals(0, commandCaptor.firstValue.unitPriceNet.amount.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `boundary - an item without a unit price opens the price editor at zero`() {
        val grid = openEditor(mock(), item(unitPrice = null))

        assertEquals(0.0, numberEditor(grid, "invoiceDetail.items.column.unitPriceNet").value)
    }

    @Test
    fun `boundary - choosing and then clearing the inline tax rate removes the rate from the item`() {
        val row = item()
        val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(any(), any())).thenReturn(invoice(listOf(row)))
        val grid = openEditor(editInvoiceItemsUseCase, row)
        val taxBox = grid.columns.mapNotNull { it.editorComponent }.filterIsInstance<ComboBox<*>>().single().requireAs<ComboBox<TaxRateDto>>()

        // Select a rate and then clear it again within the same buffered edit session;
        // the pending tax fields must reflect the final, explicit `null`, not the previously
        // selected rate (see wireInlineEditing's save-listener KDoc).
        taxBox.value = standard
        taxBox.value = null
        grid.editor.save()

        val commandCaptor = argumentCaptor<InvoiceItemCommand>()
        verify(editInvoiceItemsUseCase).updateInvoiceItem(any(), commandCaptor.capture())
        assertNull(commandCaptor.firstValue.taxRateId)
    }

    @Test
    fun `boundary - a trip row without trip details shows only the calculated segment`() {
        val row = item(InvoiceItemCategory.TRIP).copy(trip = null)
        val grid = InvoiceItemGrid(taxRateQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, time = FIXED_TIME_SOURCE)
        val gridMethod = grid.javaClass.getDeclaredMethod("tripSummaryLine", Component::class.java, ItemDto::class.java)
        gridMethod.isAccessible = true

        val summary = gridMethod.invoke(grid, host, row) as String

        assertEquals(UiTranslations.tr("invoiceDetail.trip.summary.calculated"), summary)
    }
}
