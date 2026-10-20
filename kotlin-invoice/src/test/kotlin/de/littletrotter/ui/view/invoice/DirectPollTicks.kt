package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.PollEvent
import com.vaadin.flow.component.UI
import java.util.concurrent.Executor

/** Runs poll-triggered ticks on the calling (session-lock holding) test thread so Karibu tests stay
 *  deterministic: [install] swaps the trigger executor, [firePoll] fires a client poll and then
 *  drains the access queue where the apply phase waits, [restore] puts the shared executor back. */
object DirectPollTicks {
    private val direct = Executor { it.run() }
    private var previous: Executor? = null

    fun install() {
        previous = PollingViewLifecycle.triggerExecutor
        PollingViewLifecycle.triggerExecutor = direct
    }

    fun restore() {
        previous?.let { PollingViewLifecycle.triggerExecutor = it }
        previous = null
    }

    fun firePoll(ui: UI) {
        ComponentUtil.fireEvent(ui, PollEvent(ui, false))
        drain()
    }

    /** Waits for every off-lock tick triggered so far (poll, event or receipt upload) to finish
     *  its apply phase. */
    fun awaitTicks() = PollTickTestSeam.awaitTriggeredTicks { drain() }

    /** Runs the queued access commands only; a full client roundtrip would also flush lazily loaded
     *  components (grids) that these tests deliberately leave alone. */
    fun drain() {
        repeat(3) { MockVaadin.runUIQueue() }
    }
}
