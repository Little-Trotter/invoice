package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import com.vaadin.flow.server.Command
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** [eventTick]: event-bus refreshes load off the session lock and apply inside a short access. */
class EventTickTest {
    private val event = UiEventBus.JobFinishedEvent(jobId = 7L, invoiceId = 1L, success = true)
    private val applied = mutableListOf<Pair<String, UiEventBus.JobFinishedEvent>>()
    private var previous: Executor? = null

    @BeforeEach
    fun direct() {
        previous = PollingViewLifecycle.triggerExecutor
        PollingViewLifecycle.triggerExecutor = Executor { it.run() }
    }

    @AfterEach
    fun restore() {
        PollingViewLifecycle.triggerExecutor = previous!!
        UI.setCurrent(null)
    }

    private fun syncUi(): UI = mock<UI>().also { ui ->
        whenever(ui.access(any())).thenAnswer { (it.arguments[0] as Command).execute(); null }
        UI.setCurrent(ui)
    }

    private fun detachedUi(): UI = mock<UI>().also { ui ->
        whenever(ui.access(any())).thenThrow(UIDetachedException())
        UI.setCurrent(ui)
    }

    private fun assertNothingPending() = PollingViewLifecycle.awaitTriggeredTicks(timeoutMs = 100) {}

    private fun record(r: String, e: UiEventBus.JobFinishedEvent) {
        applied += r to e
    }

    @Test
    fun `load result is applied together with the event`() {
        eventTick<String>(syncUi(), { "loaded-${it.jobId}" }, ::record).accept(event)

        assertEquals(listOf("loaded-7" to event), applied)
        assertNothingPending()
    }

    @Test
    fun `a null load result renders nothing`() {
        eventTick<String>(syncUi(), { null }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }

    @Test
    fun `a failing load never reaches apply and does not propagate`() {
        eventTick<String>(syncUi(), { throw IllegalStateException("db down") }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }

    @Test
    fun `a UI detached between load and apply drops the result`() {
        eventTick<String>(detachedUi(), { "x" }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }

    @Test
    fun `an executor that rejects the tick is tolerated and leaves nothing pending`() {
        PollingViewLifecycle.triggerExecutor = Executor { throw RejectedExecutionException("full") }

        eventTick<String>(syncUi(), { "x" }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }

    @Test
    fun `captured variant reads state under access, loads on the snapshot and applies`() {
        eventTick<Int, String>(syncUi(), { 41 }, { c, e -> "snap-${c + 1}-${e.jobId}" }, ::record).accept(event)

        assertEquals(listOf("snap-42-7" to event), applied)
        assertNothingPending()
    }

    @Test
    fun `captured variant skips apply when load returns null or throws`() {
        eventTick<Int, String>(syncUi(), { 1 }, { _, _ -> null }, ::record).accept(event)
        eventTick<Int, String>(syncUi(), { 1 }, { _, _ -> throw IllegalStateException("boom") }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }

    @Test
    fun `captured variant tolerates a UI detached before the capture and a failing capture`() {
        eventTick<Int, String>(detachedUi(), { 1 }, { _, _ -> "x" }, ::record).accept(event)
        eventTick<Int, String>(syncUi(), { throw IllegalStateException("component gone") }, { _, _ -> "x" }, ::record).accept(event)

        assertTrue(applied.isEmpty())
        assertNothingPending()
    }
}
