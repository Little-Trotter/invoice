package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.ui.UiEventBus
import java.util.function.Consumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceListLiveUpdates] in isolation: the off-lock event-bus path and the poll fallback's
 * pending-validation edge detection, both in capture / load / apply phases.
 */
class InvoiceListViewLiveUpdatesKaribuTest {

    private val uiEventBus: UiEventBus = mock()
    private var viewAttached = true
    private var pending = false
    private var failing = false
    private var loadFails = false
    private var applyAccepts = true
    private var currentQuery = query()
    private val loads = mutableListOf<InvoiceListQuery>()
    private val applied = mutableListOf<InvoiceListLoad>()

    private fun query(filter: InvoiceExportFilter = InvoiceExportFilter()) = InvoiceListQuery(
        filter, 0, 25, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, null,
    )

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        whenever(uiEventBus.registerOffLock(any(), any())).thenReturn(UiEventBus.Registration { })
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun updates(captureQuery: () -> InvoiceListQuery = { currentQuery }) = InvoiceListLiveUpdates(
        uiEventBus = uiEventBus,
        isViewAttached = { viewAttached },
        capture = captureQuery,
        load = {
            loads += it
            if (loadFails) error("db down")
            InvoiceListLoad(it, 0, 1, 0L, emptyList(), emptyMap())
        },
        hasPendingValidation = { if (failing) error("db down") else pending },
        apply = { applied += it; applyAccepts },
    )

    private fun attached(): Pair<InvoiceListLiveUpdates, UI> {
        val ui = UI.getCurrent()!!
        val updates = updates()
        updates.attach(ui)
        return updates to ui
    }

    private fun capturedHandler(ui: UI): Consumer<UiEventBus.JobFinishedEvent> {
        val captor = argumentCaptor<Consumer<UiEventBus.JobFinishedEvent>>()
        verify(uiEventBus).registerOffLock(eq(ui), captor.capture())
        return captor.firstValue
    }

    private fun poll(ui: UI) = DirectPollTicks.firePoll(ui)

    private fun deliver(ui: UI, jobId: Long = 77L) {
        capturedHandler(ui).accept(UiEventBus.JobFinishedEvent(jobId = jobId, invoiceId = 1L, success = true))
        PollTickTestSeam.awaitTriggeredTicks { DirectPollTicks.drain() }
    }

    @Test
    fun `happy path - a finished job event loads the captured query and applies it`() {
        val (_, ui) = attached()

        deliver(ui)

        assertEquals(listOf(currentQuery), loads)
        assertEquals(1, applied.size)
    }

    @Test
    fun `boundary - an event arriving after the view detached applies nothing`() {
        val (_, ui) = attached()
        viewAttached = false

        deliver(ui)

        assertEquals(emptyList<InvoiceListLoad>(), applied)
    }

    @Test
    fun `boundary - a failing event load does not apply`() {
        val (_, ui) = attached()
        loadFails = true

        deliver(ui)

        assertEquals(emptyList<InvoiceListLoad>(), applied)
    }

    @Test
    fun `boundary - the first poll tick always refreshes, later ticks only while or right after something is pending`() {
        val (_, ui) = attached()

        poll(ui)
        assertEquals(1, applied.size, "seeded tick after entering refreshes once")
        poll(ui)
        assertEquals(1, applied.size, "nothing pending, nothing seen pending: no refresh")

        pending = true
        poll(ui)
        assertEquals(2, applied.size)
        pending = false
        poll(ui)
        assertEquals(3, applied.size, "the tick where the last pending invoice leaves still refreshes")
        poll(ui)
        assertEquals(3, applied.size)
        assertEquals(3, loads.size, "no list query on ticks that decide against a refresh")
    }

    @Test
    fun `boundary - detach stops the poll fallback`() {
        val (updates, ui) = attached()

        updates.detach(ui)
        poll(ui)

        assertEquals(0, applied.size)
    }

    @Test
    fun `boundary - a failing pending query refreshes nothing and the next tick recovers`() {
        val (_, ui) = attached()
        poll(ui) // consume the seeded tick
        failing = true

        poll(ui)
        assertEquals(1, applied.size, "a failed load must not refresh")

        failing = false
        pending = true
        poll(ui)
        assertEquals(2, applied.size)
    }

    @Test
    fun `boundary - a failing list load applies nothing`() {
        val (_, ui) = attached()
        loadFails = true

        poll(ui)

        assertEquals(0, applied.size)
    }

    @Test
    fun `boundary - the state is captured before the query and its filter handed to it unchanged`() {
        val ui = UI.getCurrent()!!
        val seen = mutableListOf<InvoiceExportFilter>()
        val filter = InvoiceExportFilter(filterText = "acme")
        InvoiceListLiveUpdates(
            uiEventBus = uiEventBus,
            isViewAttached = { true },
            capture = { query(filter) },
            load = { InvoiceListLoad(it, 0, 1, 0L, emptyList(), emptyMap()) },
            hasPendingValidation = { seen += it; false },
            apply = { true },
        ).attach(ui)

        poll(ui)

        assertEquals(listOf(filter), seen)
    }

    @Test
    fun `boundary - a refresh decided off-lock is dropped when the view detached meanwhile`() {
        val (_, ui) = attached()
        viewAttached = false

        poll(ui)

        assertEquals(0, applied.size)
    }
}
