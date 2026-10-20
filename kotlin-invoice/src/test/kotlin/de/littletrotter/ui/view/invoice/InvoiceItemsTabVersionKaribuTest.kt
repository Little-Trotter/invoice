package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * One user saving several item changes in a row must never trip the optimistic lock on their
 * own writes: every save hands back a newer invoice version, and the next save — including an
 * inline grid edit on a grid that was built before those saves — has to send that newer version.
 */
class InvoiceItemsTabVersionKaribuTest {

    private val editInvoiceItemsUseCase = mock<EditInvoiceItemsUseCase>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun item(id: Long, sortOrder: Int, quantity: String, price: String) = ItemDto(
        id = id, positionId = sortOrder.toString(), description = "Position $sortOrder", quantity = BigDecimal(quantity),
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal(price)), taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = InvoiceItemCategory.PRODUCT.name, sortOrder = sortOrder,
    )

    private fun invoice(version: Long, items: List<ItemDto>) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = items, latestGenerationJob = null, version = version,
    )

    private fun addCommand(description: String, quantity: String, price: String) = InvoiceItemCommand(
        category = InvoiceItemCategory.PRODUCT, description = description, quantity = BigDecimal(quantity),
        unit = InvoiceItemUnit.PIECE, unitPriceNet = Money(BigDecimal(price)),
    )

    @Test
    fun `add, add, then inline edit sends the version returned by the second add`() {
        val itemA = item(id = 10L, sortOrder = 1, quantity = "3", price = "25.00")
        val itemB = item(id = 11L, sortOrder = 2, quantity = "1", price = "99.00")
        val empty = invoice(version = 0L, items = emptyList())
        val afterA = invoice(version = 1L, items = listOf(itemA))
        val afterB = invoice(version = 2L, items = listOf(itemA, itemB))
        whenever(editInvoiceItemsUseCase.addItemToInvoice(eq(1L), any())).thenReturn(afterA, afterB)
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(eq(1L), any())).thenReturn(invoice(version = 3L, items = listOf(itemA, itemB)))

        val tab = testItemsTab(editInvoiceItemsUseCase = editInvoiceItemsUseCase)
        // Same wiring as InvoiceDetailTabs: a committed item change refreshes the tab in place.
        val state = InvoiceDetailState().apply { onInvoiceChanged { tab.refresh(it) } }
        tab.invoiceItemDialogs.bind(state)
        UI.getCurrent().add(tab)
        tab.render(empty)

        // Each add dialog is opened from the toolbar row, which refresh rebuilds with the latest snapshot.
        tab.invoiceItemDialogs.saveNewItem(tab, empty, addCommand("A", "3", "25.00"))
        tab.invoiceItemDialogs.saveNewItem(tab, afterA, addCommand("B", "1", "99.00"))

        val grid = requireNotNull(tab.itemsGrid)
        grid.editor.editItem(itemA)
        MockVaadin.clientRoundtrip()
        val quantityHeader = UiTranslations.tr("invoiceDetail.items.column.quantity")
        (grid.columns.single { it.headerText == quantityHeader }.editorComponent as NumberField).value = 4.0
        assertTrue(grid.editor.save())

        val sent = argumentCaptor<InvoiceItemCommand>()
        verify(editInvoiceItemsUseCase).updateInvoiceItem(eq(1L), sent.capture())
        assertEquals(2L, sent.firstValue.expectedVersion)
        assertEquals(BigDecimal.valueOf(4.0), sent.firstValue.quantity)
    }
}
