package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireDomEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import com.vaadin.flow.dom.DomEvent
import com.vaadin.flow.server.Command
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import tools.jackson.databind.node.JsonNodeFactory

/**
 * QOD-3 (PO fix 2026-09-02, "Chrome shows no live updates, Firefox does") — pins the two additions
 * [PollingViewLifecycle] got on top of the pre-existing client-poll fallback: a server-owned
 * [PollingViewLifecycle.attach] tick delivered via `UI.access`/`@Push` (independent of the
 * client-side timer Chrome throttles/freezes in background tabs) and the visibility/focus re-sync
 * DOM listener that refreshes a returning tab immediately. All three consuming views
 * ([InvoiceDetailView]/[InvoiceListView]/[PrintJobListView]) share this one class, so pinning it
 * here — rather than duplicating the same assertions three times — is the actual QOD-3 point.
 *
 * Deliberately never fires a [com.vaadin.flow.component.PollEvent] anywhere in this file: doing so
 * would let the pre-existing client-poll listener carry the assertion instead of the new
 * server-tick/visibility-resync paths this file exists to cover.
 */
class PollingViewLifecycleKaribuTest {

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** A no-op [UiEventBus.Registration] — none of these tests exercise the fast event-bus path,
     *  only the poll-fallback lifecycle built around it. */
    private fun noopRegister(@Suppress("UNUSED_PARAMETER") ui: UI): UiEventBus.Registration = UiEventBus.Registration {}

    private fun fireVisibilityResync(ui: UI) {
        ui.element._fireDomEvent(
            DomEvent(ui.element, VISIBILITY_RESYNC_EVENT_NAME, JsonNodeFactory.instance.objectNode()),
        )
    }

    // ── server-driven tick ──────────────────────────────────────────────────────

    @Test
    fun `attach arms a server-side tick that refreshes on its own, without any client poll`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var pollCount = 0

        lifecycle.attach(ui, 20, register = ::noopRegister, tick = pollTick(load = {}, apply = { pollCount++ }))
        try {
            // The tick runs on PollingViewLifecycle's own shared executor thread and delivers via
            // UI.access, which — like UiEventBus.publish's own background-thread access calls —
            // only queues while this test thread holds the session lock; clientRoundtrip is what
            // actually drains that queue, same idiom InvoiceDetailViewLiveUpdateTest already uses.
            Thread.sleep(200)
            MockVaadin.clientRoundtrip()

            assertTrue(pollCount >= 1, "the server tick never ran onPoll within 200ms of a 20ms interval")
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `detach on a lifecycle that was never attached is a harmless no-op, with or without a UI`() {
        // Every other test in this file only ever detaches after a successful attach — this pins
        // the other side of each `?.` null-check in detach (all four registrations plus the
        // restored poll interval), both with a real, still-live UI and with none at all (mirrors
        // PollingViewLifecycle.detach's own `ui: UI?` — a real caller passes null when the
        // DetachEvent no longer carries one).
        PollingViewLifecycle().detach(null)
        PollingViewLifecycle().detach(UI.getCurrent())
    }

    @Test
    fun `detach cancels the server-side tick so no further onPoll calls happen`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var pollCount = 0

        lifecycle.attach(ui, 20, register = ::noopRegister, tick = pollTick(load = {}, apply = { pollCount++ }))
        try {
            Thread.sleep(200)
            MockVaadin.clientRoundtrip()
            assertTrue(pollCount >= 1, "precondition: at least one tick must have run before detach")
        } finally {
            lifecycle.detach(ui)
        }
        val countAtDetach = pollCount

        Thread.sleep(200)
        MockVaadin.clientRoundtrip()

        assertEquals(countAtDetach, pollCount, "a tick fired after detach — the executor task was not cancelled")
    }

    @Test
    fun `a tick against a UI whose session is already gone swallows UIDetachedException and never calls onPoll`() {
        val ui: UI = mock()
        whenever(ui.access(any())).thenThrow(UIDetachedException())
        var called = false

        PollingViewLifecycle.runServerTick(ui, {}, { called = true })

        assertFalse(called, "onPoll must not run once UI.access itself reports the UI detached")
    }

    // ── visibility/focus re-sync ─────────────────────────────────────────────────

    @Test
    fun `a visibility resync event triggers an immediate refresh`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var pollCount = 0

        // A long interval keeps the server tick from also firing during this test's short
        // runtime, isolating the assertion to the DOM-event path.
        lifecycle.attach(ui, 60_000, register = ::noopRegister, tick = pollTick(load = {}, apply = { pollCount++ }))
        try {
            fireVisibilityResync(ui)
            DirectPollTicks.drain()

            assertEquals(1, pollCount)
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `detach removes the visibility resync listener so a later event is a no-op`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var pollCount = 0

        lifecycle.attach(ui, 60_000, register = ::noopRegister, tick = pollTick(load = {}, apply = { pollCount++ }))
        try {
            fireVisibilityResync(ui)
            DirectPollTicks.drain()
            assertEquals(1, pollCount)
        } finally {
            lifecycle.detach(ui)
        }

        fireVisibilityResync(ui)
        DirectPollTicks.drain()

        assertEquals(1, pollCount, "the visibility resync listener kept firing after detach")
    }

    // ── two-phase tick: load off-lock, apply in access ─────────────────────────────

    @Test
    fun `a tick loads on the executor thread without the session lock and applies the loaded value`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        val loadThread = AtomicReference<Thread>()
        val lockHeldDuringLoad = AtomicBoolean(true)
        val applied = CopyOnWriteArrayList<String>()

        lifecycle.attach(
            ui,
            20,
            register = ::noopRegister,
            tick = pollTick(
                load = {
                    loadThread.set(Thread.currentThread())
                    lockHeldDuringLoad.set(ui.session.hasLock())
                    "loaded"
                },
                apply = { applied += it },
            ),
        )
        try {
            Thread.sleep(200)
            MockVaadin.clientRoundtrip()

            assertTrue(applied.isNotEmpty() && applied.all { it == "loaded" })
            assertTrue(loadThread.get() !== Thread.currentThread())
            assertFalse(lockHeldDuringLoad.get(), "load ran while holding the session lock")
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `a failing load never reaches apply and the next tick still runs`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var loads = 0
        var applies = 0

        lifecycle.attach(
            ui,
            60_000,
            register = ::noopRegister,
            tick = pollTick(
                load = {
                    loads++
                    check(loads != 1) { "db down" }
                    loads
                },
                apply = { applies++ },
            ),
        )
        try {
            fireVisibilityResync(ui)
            DirectPollTicks.drain()
            assertEquals(0, applies, "apply must not run after a failed load")

            fireVisibilityResync(ui)
            DirectPollTicks.drain()
            assertEquals(1, applies, "a failed tick must not block the following one")
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `a tick arriving while the previous one is still waiting for its apply is dropped`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var loads = 0

        lifecycle.attach(ui, 60_000, register = ::noopRegister, tick = pollTick(load = { loads++ }, apply = {}))
        try {
            // The test thread holds the session lock, so the first apply stays queued: the tick is in flight.
            fireVisibilityResync(ui)
            fireVisibilityResync(ui)
            assertEquals(1, loads)

            DirectPollTicks.drain()
            fireVisibilityResync(ui)
            DirectPollTicks.drain()
            assertEquals(2, loads)
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `an apply that was queued before detach is not executed afterwards`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var applies = 0

        lifecycle.attach(ui, 60_000, register = ::noopRegister, tick = pollTick(load = {}, apply = { applies++ }))
        fireVisibilityResync(ui)
        lifecycle.detach(ui)
        DirectPollTicks.drain()

        assertEquals(0, applies)
    }

    @Test
    fun `the capture variant reads state under the lock, then loads from that snapshot and applies`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        val applied = CopyOnWriteArrayList<String>()

        lifecycle.attach(
            ui,
            60_000,
            register = ::noopRegister,
            tick = pollTick(
                capture = { "snapshot" },
                load = { "$it-loaded" },
                apply = { applied += it },
            ),
        )
        try {
            fireVisibilityResync(ui)
            DirectPollTicks.drain()

            assertEquals(listOf("snapshot-loaded"), applied)
        } finally {
            lifecycle.detach(ui)
        }
    }

    @Test
    fun `runCapturedServerTick - a detached UI neither loads nor applies and reports finished`() {
        val ui: UI = mock()
        whenever(ui.access(any())).thenThrow(UIDetachedException())
        var loaded = false
        var finished = false

        PollingViewLifecycle.runCapturedServerTick(
            ui,
            capture = {},
            load = { loaded = true },
            apply = { fail("must not apply") },
            finished = { finished = true },
        )

        assertFalse(loaded)
        assertTrue(finished)
    }

    @Test
    fun `runCapturedServerTick - a failing capture reports finished and never loads`() {
        val ui: UI = mock()
        whenever(ui.access(any())).thenAnswer { (it.arguments[0] as Command).execute(); null }
        var loaded = false
        var finished = false

        assertThrows(IllegalStateException::class.java) {
            PollingViewLifecycle.runCapturedServerTick(
                ui,
                capture = { throw IllegalStateException("component gone") },
                load = { loaded = true },
                apply = {},
                finished = { finished = true },
            )
        }

        assertFalse(loaded)
        assertTrue(finished)
    }

    @Test
    fun `runCapturedServerTick - a failing load is logged on the offload thread and never applied`() {
        val ui: UI = mock()
        whenever(ui.access(any())).thenAnswer { (it.arguments[0] as Command).execute(); null }
        var applied = false
        var finished = false

        PollingViewLifecycle.runCapturedServerTick(
            ui,
            capture = {},
            load = { throw IllegalStateException("db down") },
            apply = { applied = true },
            requestContext = { null },
            offLock = { it.run() },
            finished = { finished = true },
        )

        assertFalse(applied)
        assertTrue(finished)
    }

    @Test
    fun `runServerTick - a failing load never opens an access`() {
        val ui: UI = mock()

        assertThrows(IllegalStateException::class.java) {
            PollingViewLifecycle.runServerTick(ui, { throw IllegalStateException("db down") }, {}, { null })
        }

        verify(ui, never()).access(any())
    }

    @Test
    fun `awaitTriggeredTicks returns only after a triggered tick applied, and fails when none can finish`() {
        val ui = UI.getCurrent()!!
        val lifecycle = PollingViewLifecycle()
        var applies = 0
        lifecycle.attach(ui, 60_000, register = ::noopRegister, tick = pollTick(load = {}, apply = { applies++ }))
        try {
            fireVisibilityResync(ui)
            PollTickTestSeam.awaitTriggeredTicks { MockVaadin.runUIQueue() }
            assertEquals(1, applies)

            fireVisibilityResync(ui) // the apply stays queued: the drain below never runs it
            assertThrows(IllegalStateException::class.java) {
                PollingViewLifecycle.awaitTriggeredTicks(timeoutMs = 50) {}
            }
            PollingViewLifecycle.awaitTriggeredTicks { MockVaadin.runUIQueue() }
        } finally {
            lifecycle.detach(ui)
        }
    }
}
