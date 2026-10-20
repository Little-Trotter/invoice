package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.TripEntryQueries
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.invoice.ItemEntryQueries
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.test.FIXED_TIME_SOURCE

private fun minimalItemCommand() = InvoiceItemCommand(
    category = InvoiceItemCategory.SERVICE,
    description = "Test",
    quantity = BigDecimal.ONE,
    unit = InvoiceItemUnit.HOUR,
    unitPriceNet = Money.ZERO,
)

/**
 * A save triggered from another tab (item add/edit/delete, recalculation, status
 * transition) must not silently discard an in-progress, unsaved Erfassen edit on the Übersicht tab
 * by forcing a full re-render out from under it.
 *
 * [InvoiceItemDialogs.saveNewItem]/[InvoiceItemDialogs.updateItem] now report through
 * [InvoiceDetailState.applyChange] directly (bound via [InvoiceItemDialogs.bind]), the same
 * defer-while-editMode contract [InvoiceDetailState] already owns () — exercised here
 * without a whole [InvoiceDetailView].
 */
class InvoiceDetailViewCrossTabGuardTest {

    private val host = Div()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()

    private fun dialogs(editInvoiceItemsUseCase: EditInvoiceItemsUseCase): Pair<InvoiceItemDialogs, InvoiceDetailState> {
        val receiptDialogLauncher = ReceiptDialogLauncher(mock(), mock(), mock(), mock())
        val tripDialogLauncher = TripDialogLauncher(TripEntryQueries(taxRateQueries, mock()), recipientQueryPort, receiptDialogLauncher, time = FIXED_TIME_SOURCE)
        val itemDialogs = InvoiceItemDialogs(
            editInvoiceItemsUseCase = editInvoiceItemsUseCase,
            itemEntryQueryPort = ItemEntryQueries(mock(), mock(), mock(), mock(), taxRateQueries),
            tripDialogLauncher = tripDialogLauncher, time = FIXED_TIME_SOURCE,
        )
        val state = InvoiceDetailState()
        itemDialogs.bind(state)
        return itemDialogs to state
    }

    private fun minimalDto(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id,
        invoiceNumber = "INV-2026-03-00042",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = Money.ZERO,
        vatAmount = Money.ZERO,
        grossAmount = Money.ZERO,
        sender = null,
        recipient = null,
        items = emptyList(),
        latestGenerationJob = null,
    )

    @Test
    fun `saveNewItem defers applyChange while Uebersicht is mid-edit`() {
        val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
        whenever(editInvoiceItemsUseCase.addItemToInvoice(any(), any())).thenReturn(minimalDto())
        val (itemDialogs, state) = dialogs(editInvoiceItemsUseCase)
        state.enterEditMode()
        var rendered = 0
        state.onInvoiceChanged { rendered++ }
        val invoice = minimalDto()

        itemDialogs.saveNewItem(host, invoice, minimalItemCommand())

        assertTrue(state.pendingChange, "save must mark a refresh as pending instead of rendering immediately")
        assertEquals(0, rendered, "no full re-render should have happened while editMode is true")
    }

    @Test
    fun `saveNewItem applies immediately when Uebersicht is not being edited`() {
        val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
        whenever(editInvoiceItemsUseCase.addItemToInvoice(any(), any())).thenReturn(minimalDto())
        val (itemDialogs, state) = dialogs(editInvoiceItemsUseCase)
        var rendered = 0
        state.onInvoiceChanged { rendered++ }
        val invoice = minimalDto()

        itemDialogs.saveNewItem(host, invoice, minimalItemCommand())

        assertTrue(!state.pendingChange)
        assertTrue(rendered > 0, "outside Erfassen mode a save should render immediately")
    }

    @Test
    fun `updateItem defers applyChange while Uebersicht is mid-edit`() {
        val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
        whenever(editInvoiceItemsUseCase.updateInvoiceItem(any(), any())).thenReturn(minimalDto())
        val (itemDialogs, state) = dialogs(editInvoiceItemsUseCase)
        state.enterEditMode()
        var rendered = 0
        state.onInvoiceChanged { rendered++ }
        val invoice = minimalDto()

        itemDialogs.updateItem(host, invoice, minimalItemCommand())

        assertTrue(state.pendingChange)
        assertEquals(0, rendered)
    }
}
