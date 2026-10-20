package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import com.vaadin.flow.server.Command
import io.quarkus.arc.ManagedContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** [PollingViewLifecycle.runServerTick]'s CDI request-context handling: the context must span the
 *  whole `ui.access` call on the tick thread and also the command when a foreign thread runs it. */
class PollingViewLifecycleRequestContextTest {

    /** Per-thread active flag, mimicking how ArC binds the request context to the calling thread. */
    private val flag = ThreadLocal.withInitial { false }

    private val requestContext: ManagedContext = mock<ManagedContext>().also { ctx ->
        whenever(ctx.isActive).thenAnswer { flag.get() }
        whenever(ctx.activate()).thenAnswer { flag.set(true); null }
        whenever(ctx.terminate()).thenAnswer { flag.set(false); null }
    }

    private fun uiWithAccess(answer: (Command) -> Unit): UI = mock<UI>().also { ui ->
        whenever(ui.access(any())).thenAnswer { answer(it.arguments[0] as Command); null }
    }

    private fun syncUi(): UI = uiWithAccess { it.execute() }

    @Test
    fun `happy path - the context is activated once around access and terminated afterwards`() {
        val ui = syncUi()
        var polls = 0

        PollingViewLifecycle.runServerTick(ui, {}, { polls++ }, { requestContext })

        assertEquals(1, polls)
        verify(requestContext, times(1)).activate()
        verify(requestContext, times(1)).terminate()
        inOrder(requestContext, ui) {
            verify(requestContext).activate()
            verify(ui).access(any())
            verify(requestContext).terminate()
        }
    }

    @Test
    fun `boundary - the context is still active after the command body returned, during unlock and push flush`() {
        var activeAfterCommand: Boolean? = null
        val ui = uiWithAccess {
            it.execute()
            activeAfterCommand = flag.get()
        }

        PollingViewLifecycle.runServerTick(ui, {}, {}, { requestContext })

        assertEquals(true, activeAfterCommand)
        assertFalse(flag.get())
    }

    @Test
    fun `boundary - an already active context is reused, never activated or terminated`() {
        flag.set(true)
        var polls = 0

        PollingViewLifecycle.runServerTick(syncUi(), {}, { polls++ }, { requestContext })

        assertEquals(1, polls)
        verify(requestContext, never()).activate()
        verify(requestContext, never()).terminate()
        flag.set(false)
    }

    @Test
    fun `boundary - a failing poll propagates and still terminates the context`() {
        assertThrows(IllegalStateException::class.java) {
            PollingViewLifecycle.runServerTick(syncUi(), {}, { throw IllegalStateException("facade down") }, { requestContext })
        }

        verify(requestContext, times(1)).terminate()
        assertFalse(flag.get())
    }

    @Test
    fun `boundary - without a context the poll runs directly`() {
        var polls = 0

        PollingViewLifecycle.runServerTick(syncUi(), {}, { polls++ }, { null })

        assertEquals(1, polls)
        verify(requestContext, never()).activate()
    }

    @Test
    fun `boundary - a command executed later on a foreign thread activates its own context`() {
        val stored = AtomicReference<Command>()
        val ui = uiWithAccess { stored.set(it) }
        val polls = AtomicReference<Int>(0)
        val pollThreadActive = AtomicBoolean(false)
        val callerThread = Thread.currentThread()
        val pollThread = AtomicReference<Thread>()

        PollingViewLifecycle.runServerTick(
            ui,
            {},
            {
                polls.updateAndGet { it + 1 }
                pollThread.set(Thread.currentThread())
                pollThreadActive.set(flag.get())
            },
            { requestContext },
        )
        assertEquals(0, polls.get())
        assertFalse(flag.get())

        val executor = Executors.newSingleThreadExecutor()
        try {
            val foreignFlagAfter = executor.submit<Boolean> {
                stored.get().execute()
                flag.get()
            }.get(10, TimeUnit.SECONDS)
            assertFalse(foreignFlagAfter)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, polls.get())
        assertTrue(pollThreadActive.get())
        assertTrue(pollThread.get() !== callerThread)
        verify(requestContext, times(2)).activate()
        verify(requestContext, times(2)).terminate()
    }

    @Test
    fun `boundary - a detached UI is swallowed and the outer context is still terminated`() {
        val ui = mock<UI>()
        whenever(ui.access(any())).thenThrow(UIDetachedException())

        PollingViewLifecycle.runServerTick(ui, {}, {}, { requestContext })

        verify(requestContext, times(1)).activate()
        verify(requestContext, times(1)).terminate()
        assertFalse(flag.get())
    }
}
