package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.html.NativeButton
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.StatusQuickFilterRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/** [invoiceQuickFilterRow] in isolation: chips per non-empty state, active marker, toggle. */
class InvoiceListViewQuickFilterRowKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()
    private val toggled = mutableListOf<InvoiceStatus>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newRow() = invoiceQuickFilterRow(InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)) { toggled += it }

    private fun chips(row: StatusQuickFilterRow<InvoiceStatus>) = row.children.toList().filterIsInstance<NativeButton>()

    @Test
    fun `happy path - one chip per state with invoices, the active one pressed, a click toggles its state`() {
        val row = newRow()

        row.render(mapOf(InvoiceStatus.DRAFT to 3L, InvoiceStatus.SENT to 12L), InvoiceStatus.SENT)

        val chips = chips(row)
        assertEquals(2, chips.size)
        val sent = chips.single { it.text.endsWith(" 12") }
        assertEquals("true", sent.element.getAttribute("aria-pressed"))
        assertTrue(sent.hasClassName("quick-filter-chip--active"))
        sent._click()
        assertEquals(listOf(InvoiceStatus.SENT), toggled)
    }

    @Test
    fun `boundary - an empty result renders no chip at all`() {
        val row = newRow()

        row.render(emptyMap(), null)

        assertTrue(chips(row).isEmpty())
    }

    @Test
    fun `boundary - zero and negative counts are hidden, never shown as empty chips`() {
        val row = newRow()

        row.render(mapOf(InvoiceStatus.DRAFT to 0L, InvoiceStatus.CANCELLED to -1L, InvoiceStatus.SENT to 1L), InvoiceStatus.DRAFT)

        val chips = chips(row)
        assertEquals(1, chips.size)
        assertFalse(chips.single().hasClassName("quick-filter-chip--active"), "the selected but empty state has no chip to mark")
    }

    @Test
    fun `boundary - a re-render replaces the previous chips instead of appending`() {
        val row = newRow()

        row.render(mapOf(InvoiceStatus.DRAFT to 3L, InvoiceStatus.SENT to 12L), null)
        row.render(mapOf(InvoiceStatus.DRAFT to 4L), null)

        assertEquals(1, chips(row).size)
        assertTrue(chips(row).single().text.endsWith(" 4"))
    }
}
