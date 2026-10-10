package de.littletrotter.ui

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.function.Consumer

/**
 * [UiEventBus] — the fan-out event bus [JobFinishedEvent] listeners register against. Every UI
 * mock stands in for a real Vaadin UI that would otherwise need a live session; `access` is
 * stubbed to run the given `Command` synchronously, which is exactly what a same-thread test needs
 * from the async contract.
 */
class UiEventBusTest {

    private fun liveUi(): UI {
        val ui = mock<UI>()
        whenever(ui.isClosing).thenReturn(false)
        whenever(ui.isAttached).thenReturn(true)
        whenever(ui.access(any())).thenAnswer {
            (it.arguments[0] as com.vaadin.flow.server.Command).execute()
            null
        }
        return ui
    }

    @Test
    fun `happy path - publish delivers the event to a registered listener via UI access`() {
        val bus = UiEventBus()
        val ui = liveUi()
        var received: UiEventBus.JobFinishedEvent? = null

        bus.register(ui, Consumer { received = it })
        bus.publish(UiEventBus.JobFinishedEvent(jobId = 1L, invoiceId = 2L, success = true))

        assertEquals(UiEventBus.JobFinishedEvent(1L, 2L, true), received)
    }

    @Test
    fun `boundary - publish fans out to every registered listener, one UI each`() {
        val bus = UiEventBus()
        val uiA = liveUi()
        val uiB = liveUi()
        val receivedA = mutableListOf<UiEventBus.JobFinishedEvent>()
        val receivedB = mutableListOf<UiEventBus.JobFinishedEvent>()
        bus.register(uiA, Consumer { receivedA.add(it) })
        bus.register(uiB, Consumer { receivedB.add(it) })

        bus.publish(UiEventBus.JobFinishedEvent(5L, null, false))

        assertEquals(1, receivedA.size)
        assertEquals(1, receivedB.size)
    }

    @Test
    fun `boundary - a throwing subscriber does not stop delivery to the rest`() {
        val bus = UiEventBus()
        val throwingUi = liveUi()
        val healthyUi = liveUi()
        var healthyReceived = false
        bus.register(throwingUi, Consumer { throw IllegalStateException("boom") })
        bus.register(healthyUi, Consumer { healthyReceived = true })

        bus.publish(UiEventBus.JobFinishedEvent(9L, 9L, true))

        assertTrue(healthyReceived)
    }

    @Test
    fun `boundary - unregister stops further delivery to that listener`() {
        val bus = UiEventBus()
        val ui = liveUi()
        var callCount = 0
        val registration = bus.register(ui, Consumer { callCount++ })

        registration.unregister()
        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertEquals(0, callCount)
        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `a closing UI is skipped and dropped from the listener set`() {
        val bus = UiEventBus()
        val closingUi = mock<UI>()
        whenever(closingUi.isClosing).thenReturn(true)
        var called = false
        bus.register(closingUi, Consumer { called = true })

        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertFalse(called)
        assertEquals(0, bus.listenerCount())
        verify(closingUi, never()).access(any())
    }

    @Test
    fun `a detached UI is skipped and dropped from the listener set`() {
        val bus = UiEventBus()
        val detachedUi = mock<UI>()
        whenever(detachedUi.isClosing).thenReturn(false)
        whenever(detachedUi.isAttached).thenReturn(false)
        var called = false
        bus.register(detachedUi, Consumer { called = true })

        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertFalse(called)
        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `a UIDetachedException raised by access during the race is swallowed and drops the listener`() {
        val bus = UiEventBus()
        val ui = mock<UI>()
        whenever(ui.isClosing).thenReturn(false)
        whenever(ui.isAttached).thenReturn(true)
        doAnswer { throw UIDetachedException() }.whenever(ui).access(any())
        bus.register(ui, Consumer { })

        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `listenerCount reflects registrations and unregistrations`() {
        val bus = UiEventBus()
        val ui1 = liveUi()
        val ui2 = liveUi()

        val r1 = bus.register(ui1, Consumer { })
        bus.register(ui2, Consumer { })
        assertEquals(2, bus.listenerCount())

        r1.unregister()
        assertEquals(1, bus.listenerCount())
    }

    @Test
    fun `an off-lock listener is invoked on the publishing thread without entering UI access`() {
        val bus = UiEventBus()
        val ui = liveUi()
        var received: UiEventBus.JobFinishedEvent? = null
        bus.registerOffLock(ui, Consumer { received = it })

        bus.publish(UiEventBus.JobFinishedEvent(3L, 4L, true))

        assertEquals(UiEventBus.JobFinishedEvent(3L, 4L, true), received)
        verify(ui, never()).access(any())
    }

    @Test
    fun `a throwing off-lock listener does not stop delivery to the rest and stays registered`() {
        val bus = UiEventBus()
        var healthyReceived = false
        bus.registerOffLock(liveUi(), Consumer { throw IllegalStateException("boom") })
        bus.registerOffLock(liveUi(), Consumer { healthyReceived = true })

        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertTrue(healthyReceived)
        assertEquals(2, bus.listenerCount())
    }

    @Test
    fun `a detached UI is dropped from the off-lock listeners without invoking the handler`() {
        val bus = UiEventBus()
        val ui = liveUi()
        whenever(ui.isAttached).thenReturn(false)
        var invoked = false
        bus.registerOffLock(ui, Consumer { invoked = true })

        bus.publish(UiEventBus.JobFinishedEvent(1L, null, true))

        assertFalse(invoked)
        assertEquals(0, bus.listenerCount())
    }

    @Test
    fun `publishJobFinished adapts to publish with the same field values`() {
        val bus = UiEventBus()
        val ui = liveUi()
        var received: UiEventBus.JobFinishedEvent? = null
        bus.register(ui, Consumer { received = it })

        bus.publishJobFinished(jobId = 42L, invoiceId = 7L, success = false)

        assertEquals(UiEventBus.JobFinishedEvent(42L, 7L, false), received)
    }

    private class ManualScheduler {
        val tasks = mutableListOf<Runnable>()
        val executor: java.util.concurrent.ScheduledExecutorService = mock<java.util.concurrent.ScheduledExecutorService>().also { mock ->
            whenever(mock.schedule(any<Runnable>(), any(), any())).thenAnswer {
                tasks.add(it.arguments[0] as Runnable)
                mock<java.util.concurrent.ScheduledFuture<*>>()
            }
        }

        fun runAll() {
            val due = tasks.toList()
            tasks.clear()
            due.forEach { it.run() }
        }
    }

    private fun busWith(scheduler: ManualScheduler) = UiEventBus().also { it.scheduler = scheduler.executor }

    private val recorded = de.littletrotter.application.message.MessageRecorded

    @Test
    fun `a burst of events collapses into one refresh per UI and window`() {
        val scheduler = ManualScheduler()
        val bus = busWith(scheduler)
        var runsA = 0
        var runsB = 0
        bus.registerMessageRecorded(liveUi()) { runsA++ }
        bus.registerMessageRecorded(liveUi()) { runsB++ }

        repeat(100) { bus.onMessageRecorded(recorded) }
        assertEquals(0, runsA)
        assertEquals(2, scheduler.tasks.size)
        scheduler.runAll()

        assertEquals(1, runsA)
        assertEquals(1, runsB)
        bus.onMessageRecorded(recorded)
        scheduler.runAll()
        assertEquals(2, runsA)
    }

    @Test
    fun `unregister before the window ends cancels the refresh`() {
        val scheduler = ManualScheduler()
        val bus = busWith(scheduler)
        var runs = 0
        val registration = bus.registerMessageRecorded(liveUi()) { runs++ }

        bus.onMessageRecorded(recorded)
        registration.unregister()
        scheduler.runAll()

        assertEquals(0, runs)
        assertEquals(0, bus.messageListenerCount())
    }

    @Test
    fun `listeners of closed UIs are dropped and a failing handler does not stop later refreshes`() {
        val scheduler = ManualScheduler()
        val bus = busWith(scheduler)
        val closed = mock<UI>()
        whenever(closed.isClosing).thenReturn(true)
        bus.registerMessageRecorded(closed) { error("must not run") }
        var failing = 0
        var healthy = 0
        bus.registerMessageRecorded(liveUi()) { failing++; error("boom") }
        bus.registerMessageRecorded(liveUi()) { healthy++ }

        bus.onMessageRecorded(recorded)
        scheduler.runAll()
        bus.onMessageRecorded(recorded)
        scheduler.runAll()

        assertEquals(2, failing)
        assertEquals(2, healthy)
        assertEquals(2, bus.messageListenerCount())
    }

    @Test
    fun `shutdown stops the scheduler`() {
        val scheduler = ManualScheduler()
        busWith(scheduler).shutdown()
        verify(scheduler.executor).shutdownNow()
    }

    @Test
    fun `the default scheduler runs a refresh after the window`() {
        val bus = UiEventBus()
        bus.badgeRefreshMs = 1
        val ran = java.util.concurrent.CountDownLatch(1)
        bus.registerMessageRecorded(liveUi()) { ran.countDown() }

        bus.onMessageRecorded(recorded)

        assertTrue(ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        bus.shutdown()
    }
}
