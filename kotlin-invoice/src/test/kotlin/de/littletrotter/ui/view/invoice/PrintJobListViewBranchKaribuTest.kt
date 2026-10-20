package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.invoice.port.inbound.PrintJobOverviewQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [PrintJobListView] branches beyond the existing suites: day-node identity, a job node
 * as hierarchy parent, a session filter whose recipient no longer exists, and a date-to-only
 * filter driving the reset button and the day grid.
 */
class PrintJobListViewBranchKaribuTest {

    private val queries: PrintJobOverviewQueryPort = mock()
    private val contacts: RecipientQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(queries.count(any())).thenReturn(0L)
        whenever(queries.findPaged(any(), any(), any())).thenReturn(emptyList())
        whenever(queries.findDaySummaries(any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun newView() = PrintJobListView(queries, contacts, mock<DocumentStore>(), UiEventBus(), time = FIXED_TIME_SOURCE)

    private inline fun <reified T : Any> field(view: PrintJobListView, name: String): T =
        PrintJobListView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view).requireAs()

    private fun day(date: LocalDate) = PrintJobDaySummary(date, 3, 0)

    private fun row() = PrintJobOverviewRow(
        jobId = 1L, invoiceId = 10L, invoiceNumber = "RE-2026-00001", recipientName = "Acme",
        status = GenerationJobStatus.COMPLETED, createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
        finishedAt = null, attempts = 0, lastError = null, llmStatus = null, pdfObjectKey = null, xmlObjectKey = null,
    )

    @Test
    fun `happy path - day nodes are identified by their day alone`() {
        val a = PrintJobTreeNode.DayNode(day(LocalDate.of(2026, 8, 26)))
        val same = PrintJobTreeNode.DayNode(PrintJobDaySummary(LocalDate.of(2026, 8, 26), 99, 5))

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, PrintJobTreeNode.DayNode(day(LocalDate.of(2026, 8, 27))))
        assertFalse(a.equals(PrintJobTreeNode.JobNode(row())))
        assertEquals("DayNode(day=2026-08-26)", a.toString())
    }

    @Test
    fun `boundary - a job node as hierarchy parent has no children`() {
        val provider: AbstractBackEndHierarchicalDataProvider<PrintJobTreeNode, Void> = field(newView(), "treeDataProvider")
        val query = HierarchicalQuery<PrintJobTreeNode, Void>(0, 50, emptyList(), null, null, PrintJobTreeNode.JobNode(row()))

        assertEquals(0, provider.getChildCount(query))
        assertEquals(0L, provider.fetchChildren(query).count())
    }

    @Test
    fun `boundary - a stored recipient filter that no longer resolves is dropped, the dates stay`() {
        VaadinSession.getCurrent()!!.setAttribute(
            PrintJobListFilterState::class.java,
            PrintJobListFilterState(recipientId = 404L, dateFrom = LocalDate.of(2026, 8, 1), dateTo = null),
        )
        whenever(contacts.findRecipientById(404L)).thenReturn(null)
        val view = newView()

        view.beforeEnter(mock())

        assertNull(field<ComboBox<Recipient>>(view, "customerFilter").value)
        assertEquals(LocalDate.of(2026, 8, 1), field<DatePicker>(view, "dateFromFilter").value)
    }

    @Test
    fun `boundary - a date-to-only filter shows the reset button`() {
        whenever(queries.findDaySummaries(any())).thenReturn(listOf(day(LocalDate.of(2026, 8, 26))))
        val view = newView()
        UI.getCurrent()!!.add(view)
        view.beforeEnter(mock())

        field<DatePicker>(view, "dateToFilter").value = LocalDate.of(2026, 8, 31)
        field<DatePicker>(view, "dateToFilter").value = LocalDate.of(2026, 8, 30)

        assertTrue(field<com.vaadin.flow.component.button.Button>(view, "resetFilterButton").isVisible)
    }
}
