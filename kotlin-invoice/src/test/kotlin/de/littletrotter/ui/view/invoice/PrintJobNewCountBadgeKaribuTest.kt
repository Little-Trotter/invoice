package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.router.HasErrorParameter
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.invoice.port.inbound.PrintJobOverviewQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The "N new" badge of [PrintJobListView]: visibility rule, count definition (entered
 * fields, last 30 minutes, after the last grid load), recount on field change without a grid
 * reload, and the Search reset. Push/poll wiring is pinned in [PrintJobListViewLiveUpdateTest] and
 * [PrintJobListViewCoverageKaribuTest].
 */
class PrintJobNewCountBadgeKaribuTest {

    private val printJobOverviewQueryService: PrintJobOverviewQueryPort = mock()
    private val contactDataFacade: RecipientQueryPort = mock()
    private val documentStore: DocumentStore = mock()
    private val uiEventBus = UiEventBus()
    private val recipient = RecipientSummaryDto(
        id = 7L,
        name = "Acme",
        customerNumber = null,
        shortHash = null,
        contactPerson = null,
        routingId = null,
        vatId = null,
        customerType = null,
        vatIdVerificationStatus = null,
        reverseChargePreference = ReverseChargePreference.ASK,
        billingCity = null,
        billingCountryAlpha2 = null,
        deliveryCountryAlpha2 = null,
    )

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java, PrintJobListView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(emptyList())
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
        DirectPollTicks.restore()
    }

    private fun attachedView(): PrintJobListView {
        // The real system clock, not the class default's fixed instant: this suite seeds jobs
        // with OffsetDateTime.now()-relative timestamps and expects the "new since" window to
        // track real time the same way PrintJobListView did before it read TimeSource.
        val view = PrintJobListView(
            printJobOverviewQueryService, contactDataFacade, documentStore, uiEventBus,
            de.littletrotter.shared.time.ClockTimeSource(java.time.Clock.systemUTC()),
        )
        UI.getCurrent()!!.add(view)
        view.beforeEnter(mock())
        return view
    }

    private inline fun <reified T : Any> privateField(view: PrintJobListView, name: String): T {
        val f = PrintJobListView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun badge(view: PrintJobListView): PrintJobNewCountBadge = privateField(view, "newCountBadge")
    private fun customerFilter(view: PrintJobListView): ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
    private fun dateFromFilter(view: PrintJobListView): DatePicker = privateField(view, "dateFromFilter")
    private fun dateToFilter(view: PrintJobListView): DatePicker = privateField(view, "dateToFilter")
    private fun searchButton(view: PrintJobListView): Button = privateField(view, "searchButton")

    /** Publishes on the real bus and drains the UI access queue: the Karibu session holds the lock
     *  while the test runs, so a `UI.access` command only executes once the queue is run. */
    private fun jobFinished() {
        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 1L, invoiceId = 10L, success = true))
        MockVaadin.runUIQueue(true)
    }

    /** The badge's own count queries: unrestricted statuses plus a creation lower bound (the
     *  active-job poll count restricts statuses, the grid queries carry no lower bound). */
    private fun isBadgeCount(f: GenerationJobFilter) = f.statuses.isEmpty() && f.createdFrom != null

    private fun stubBadgeCount(count: Long) {
        whenever(printJobOverviewQueryService.count(argThat { isBadgeCount(this) })).thenReturn(count)
    }

    private fun lastBadgeCountFilter(): GenerationJobFilter {
        val captor = argumentCaptor<GenerationJobFilter>()
        verify(printJobOverviewQueryService, atLeastOnce()).count(captor.capture())
        return captor.allValues.last { isBadgeCount(it) }
    }

    @Test
    fun `E2-1 no filter field has a value so the badge never shows however many jobs finished`() {
        stubBadgeCount(9L)
        val view = attachedView()

        jobFinished()

        assertFalse(badge(view).isVisible)
        assertEquals(0L, badge(view).count)
    }

    @Test
    fun `E2-2 filter set but zero matching jobs in the window leaves the badge absent`() {
        stubBadgeCount(0L)
        val view = attachedView()
        customerFilter(view).value = recipient

        jobFinished()

        assertFalse(badge(view).isVisible)
    }

    @Test
    fun `E2-3 filter set and N matching new jobs shows exactly N with the count in words`() {
        val view = attachedView()
        customerFilter(view).value = recipient
        stubBadgeCount(3L)

        jobFinished()

        val badge = badge(view)
        assertTrue(badge.isVisible)
        assertEquals("3 neu", badge.text)
        assertEquals("3 neue Aufträge", badge.element.getAttribute("aria-label"))
        assertEquals("3 neue Aufträge", badge.element.getAttribute("title"))
        assertTrue(badge.classNames.contains("new-jobs-badge"))
    }

    @Test
    fun `E2-3 clearing every filter field hides a shown badge again`() {
        val view = attachedView()
        stubBadgeCount(3L)
        customerFilter(view).value = recipient
        assertTrue(badge(view).isVisible)

        customerFilter(view).value = null

        assertFalse(badge(view).isVisible)
    }

    @Test
    fun `changing a field recounts the badge without reloading the grid`() {
        val view = attachedView()
        stubBadgeCount(4L)

        customerFilter(view).value = recipient

        assertEquals(4L, badge(view).count)
        verify(printJobOverviewQueryService, times(1)).findDaySummaries(any())
    }

    @Test
    fun `E2-4 the count query is bounded to the last 30 minutes even when the grid was loaded long ago`() {
        val view = attachedView()
        val gridLoadedField = PrintJobListView::class.java.getDeclaredField("lastGridLoadAt").apply { isAccessible = true }
        gridLoadedField.set(view, OffsetDateTime.now().minusHours(2))
        customerFilter(view).value = recipient
        val before = OffsetDateTime.now()

        jobFinished()

        val from = lastBadgeCountFilter().createdFrom!!
        assertFalse(from.isBefore(before.minusMinutes(NEW_JOBS_WINDOW_MINUTES).minusSeconds(5)))
        assertFalse(from.isAfter(before.minusMinutes(NEW_JOBS_WINDOW_MINUTES).plusSeconds(5)))
    }

    @Test
    fun `E2-4 the count query never reaches back before the last grid load`() {
        val testStart = OffsetDateTime.now()
        val view = attachedView()
        customerFilter(view).value = recipient

        jobFinished()

        assertFalse(lastBadgeCountFilter().createdFrom!!.isBefore(testStart))
    }

    @Test
    fun `E2-5 the count query carries the entered recipient and date range, a later range start wins`() {
        val view = attachedView()
        val tomorrow = LocalDate.now().plusDays(1)
        customerFilter(view).value = recipient
        dateFromFilter(view).value = tomorrow
        dateToFilter(view).value = tomorrow.plusDays(2)

        jobFinished()

        val filter = lastBadgeCountFilter()
        assertEquals(7L, filter.recipientId)
        assertEquals(tomorrow.atStartOfDay(GermanTime.ZONE).toOffsetDateTime(), filter.createdFrom)
        assertEquals(tomorrow.plusDays(2).atTime(23, 59, 59).atZone(GermanTime.ZONE).toOffsetDateTime(), filter.createdTo)
        assertTrue(filter.statuses.isEmpty())
    }

    @Test
    fun `E2-9 Search reloads the grid and resets the badge, the next event counts only newer jobs`() {
        val view = attachedView()
        customerFilter(view).value = recipient
        stubBadgeCount(3L)
        jobFinished()
        assertTrue(badge(view).isVisible)

        val beforeSearch = OffsetDateTime.now()
        searchButton(view).click()

        assertFalse(badge(view).isVisible)
        verify(printJobOverviewQueryService, times(2)).findDaySummaries(any())

        jobFinished()
        assertFalse(
            lastBadgeCountFilter().createdFrom!!.isBefore(beforeSearch),
            "the recount after Search must not reach back over the reloaded rows",
        )
    }

    @Test
    fun `repeating the same count leaves a shown badge untouched and a new count replaces the text`() {
        val view = attachedView()
        customerFilter(view).value = recipient
        stubBadgeCount(2L)

        jobFinished()
        jobFinished()
        assertTrue(badge(view).isVisible)
        assertEquals("2 neu", badge(view).text)

        stubBadgeCount(5L)
        jobFinished()
        assertEquals("5 neu", badge(view).text)
        assertEquals("5 neue Aufträge", badge(view).element.getAttribute("aria-label"))
    }
}
