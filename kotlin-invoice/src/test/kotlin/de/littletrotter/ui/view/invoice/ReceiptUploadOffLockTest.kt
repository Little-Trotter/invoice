package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import com.vaadin.flow.server.Command
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

/** [runOffLock]: the upload work runs off-lock, its outcome is shown in a short access. */
class ReceiptUploadOffLockTest {
    private val outcomes = mutableListOf<Result<String>>()
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

    private fun currentUi(detached: Boolean = false): UI = mock<UI>().also { ui ->
        if (detached) {
            whenever(ui.access(any())).thenThrow(UIDetachedException())
        } else {
            whenever(ui.access(any())).thenAnswer { (it.arguments[0] as Command).execute(); null }
        }
        UI.setCurrent(ui)
    }

    @Test
    fun `happy path - the work result reaches the access phase after the work finished`() {
        currentUi()
        val order = mutableListOf<String>()

        runOffLock(null, { order += "work"; "receipt" }) { order += "done"; outcomes += it }

        assertEquals(listOf("work", "done"), order)
        assertEquals("receipt", outcomes.single().getOrThrow())
        PollingViewLifecycle.awaitTriggeredTicks(timeoutMs = 100) {}
    }

    @Test
    fun `boundary - a failing work is delivered as a failed result, not thrown`() {
        currentUi()

        runOffLock(null, { error("storage down") }) { outcomes += it }

        assertEquals("storage down", outcomes.single().exceptionOrNull()?.message)
        PollingViewLifecycle.awaitTriggeredTicks(timeoutMs = 100) {}
    }

    @Test
    fun `boundary - a UI detached before the outcome is shown drops it`() {
        currentUi(detached = true)

        runOffLock(null, { "receipt" }) { outcomes += it }

        assertTrue(outcomes.isEmpty())
        PollingViewLifecycle.awaitTriggeredTicks(timeoutMs = 100) {}
    }

    @Test
    fun `boundary - without any UI the call stays synchronous`() {
        UI.setCurrent(null)

        runOffLock(null, { "receipt" }) { outcomes += it }
        runOffLock(null, { error("boom") }) { outcomes += it }

        assertEquals(2, outcomes.size)
        assertTrue(outcomes[1].isFailure)
    }
}
