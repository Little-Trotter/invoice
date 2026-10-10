package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [InvoiceItemDialogs.saveNewTrip]/[InvoiceItemDialogs.updateTripItem] on a successful
 * save: the bound [InvoiceDetailState] takes over the returned invoice unconditionally (even
 * mid-edit — only the render/listener call defers, see [InvoiceDetailState.applyChange]'s own
 * KDoc), a new trip reports the id of the newest position (the receipts button needs it), and a
 * failed update leaves the state's invoice untouched.
 *
 * Exercises [InvoiceItemDialogs] directly (a component fixture) instead of going through
 * [InvoiceDetailView].
 */
class InvoiceDetailViewTripSaveKaribuTest {

    private val editInvoiceItemsUseCase: EditInvoiceItemsUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val host = Div()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** The trip dialog is opened from the entry pane, so the state is in edit mode and defers the
     *  full re-render until the pane closes. */
    private fun editingDialogs(): Pair<InvoiceItemDialogs, InvoiceDetailState> {
        val (_, dialogs) = ItemDialogTestFixtures.itemComponents(taxRateQueries, editInvoiceItemsUseCase, recipientQueryPort)
        val state = InvoiceDetailState()
        dialogs.bind(state)
        state.enterEditMode()
        return dialogs to state
    }

    private fun item(id: Long, sortOrder: Int) = ItemDto(
        id = id, positionId = "$sortOrder", description = "Pos $sortOrder", quantity = BigDecimal.ONE,
        unit = "FLAT_RATE", unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, category = "TRIP", sortOrder = sortOrder,
    )

    private fun invoice(items: List<ItemDto> = emptyList()): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender"), recipient = null,
        items = items, latestGenerationJob = null,
    )

    private fun command() = TripCommand(
        title = "Workshop", purpose = null, periodFrom = null, periodTo = null, detailedOnInvoice = false,
        fixedNetPrice = null, expenses = emptyList(), perDiemDays = emptyList(), costCenter = null,
    )

    @Test
    fun `happy path - a new trip returns the id of the newest position and the state shows the saved invoice`() {
        val saved = invoice(listOf(item(10L, 1), item(12L, 3), item(11L, 2)))
        whenever(editInvoiceItemsUseCase.addTripToInvoice(eq(1L), any())).thenReturn(saved)
        val (dialogs, state) = editingDialogs()

        val newId = dialogs.saveNewTrip(host, invoice(), command())

        assertEquals(12L, newId)
        assertSame(saved, state.dto)
    }

    @Test
    fun `boundary - a saved invoice without positions returns no id`() {
        whenever(editInvoiceItemsUseCase.addTripToInvoice(eq(1L), any())).thenReturn(invoice())
        val (dialogs, _) = editingDialogs()

        assertNull(dialogs.saveNewTrip(host, invoice(), command()))
    }

    @Test
    fun `boundary - a successful trip update replaces the state's invoice`() {
        val saved = invoice(listOf(item(10L, 1)))
        whenever(editInvoiceItemsUseCase.updateTripInInvoice(eq(1L), eq("1"), any())).thenReturn(saved)
        val (dialogs, state) = editingDialogs()
        state.dto = invoice()

        dialogs.updateTripItem(host, invoice(), "1", command())

        assertSame(saved, state.dto)
        assertTrue(getNotifications().isEmpty(), "a successful update reports no error")
    }

    @Test
    fun `boundary - a failed trip update keeps the state's invoice and reports the error`() {
        whenever(editInvoiceItemsUseCase.updateTripInInvoice(eq(1L), eq("1"), any())).thenThrow(IllegalStateException("stale"))
        val (dialogs, state) = editingDialogs()
        val before = invoice()
        state.dto = before

        dialogs.updateTripItem(host, before, "1", command())

        assertSame(before, state.dto)
        assertTrue(getNotifications().isNotEmpty())
    }
}
