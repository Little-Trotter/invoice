package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import de.littletrotter.ui.UiEventBus
import io.quarkus.arc.Arc
import io.quarkus.arc.ManagedContext
import org.jboss.logging.Logger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** PO report 30.08.2026 — shared poll-fallback interval for [InvoiceListView] and
 *  [PrintJobListView]: the same 2-second contract both use for the identical worker/web-process
 *  gap. [InvoiceDetailView] deliberately keeps its own, wider [DETAIL_POLL_INTERVAL_MS] instead of
 *  this constant — see that constant's KDoc. */
internal const val LIVE_UPDATE_POLL_INTERVAL_MS = 2_000

/** Custom DOM event [PollingViewLifecycle.attach] dispatches [VISIBILITY_RESYNC_SCRIPT] against
 *  a returning tab's `UI.getElement()` — see that script's own KDoc for why the forwarding is
 *  needed at all. `internal` (not `private`) so the Karibu pin can fire it directly via
 *  `Element._fireDomEvent` instead of driving a real browser. */
internal const val VISIBILITY_RESYNC_EVENT_NAME = "lt-poll-resync"

/**
 * `visibilitychange`/`focus` fire on `document`/`window` only, never on an [com.vaadin.flow.dom.Element] —
 * Flow's own DOM-listener RPC listens on the client node the element represents, so it cannot
 * observe either directly. This script bridges the two: it forwards both browser events as a
 * [VISIBILITY_RESYNC_EVENT_NAME] custom event dispatched on `this` (the element [UI.getElement]
 * that `executeJs` runs against, i.e. the whole page for the browser tab's lifetime), which
 * [PollingViewLifecycle.attach] then listens for with a plain, Flow-native
 * `Element.addEventListener` to trigger one immediate [PollingViewLifecycle] refresh — PO
 * decision 2026-09-02 (Chrome throttles/freezes the client poll timer; a returning tab must show
 * truth instantly instead of waiting out the rest of the tick interval). The handler is stashed
 * on the element itself and any previous one removed first, so re-running this script on a
 * second [PollingViewLifecycle.attach] against the same still-open UI (navigating between the
 * three consuming views without a full page reload) rewires exactly one `document`/`window`
 * listener instead of stacking up a new pair every time.
 */
internal val VISIBILITY_RESYNC_SCRIPT = """
    const target = this;
    if (target.__ltPollResyncHandler) {
        document.removeEventListener('visibilitychange', target.__ltPollResyncHandler);
        window.removeEventListener('focus', target.__ltPollResyncHandler);
    }
    target.__ltPollResyncHandler = () => {
        if (document.visibilityState === 'visible') {
            target.dispatchEvent(new CustomEvent('$VISIBILITY_RESYNC_EVENT_NAME'));
        }
    };
    document.addEventListener('visibilitychange', target.__ltPollResyncHandler);
    window.addEventListener('focus', target.__ltPollResyncHandler);
""".trimIndent()

/** Test seam usable from other modules (the lifecycle itself is module-internal): lets headless
 *  view tests wait for the poll ticks they triggered instead of asserting before the off-lock
 *  load has finished. */
object PollTickTestSeam {
    /** Blocks until every poll- or visibility-triggered tick finished its apply phase or was
     *  dropped, calling [drainUiQueue] each round so applies queued behind the test thread's
     *  session lock can run. Fails after [timeoutMs]. */
    fun awaitTriggeredTicks(drainUiQueue: () -> Unit) = PollingViewLifecycle.awaitTriggeredTicks(drainUiQueue = drainUiQueue)
}

/** One poll tick, split so no database call runs while the session lock is held: what [start]
 *  loads runs on the tick executor, what it applies runs in a short `ui.access`. */
internal fun interface PollTick {
    fun start(ui: UI, isActive: () -> Boolean, finished: () -> Unit)
}

/** [load] runs off-lock (database and use-case calls) and its result is handed to [apply], which
 *  runs inside a short `ui.access` and only touches components. A failing [load] never reaches
 *  [apply]; [apply] is skipped once the lifecycle was detached in the meantime. */
internal fun <T> pollTick(load: () -> T, apply: (T) -> Unit) = PollTick { ui, isActive, finished ->
    PollingViewLifecycle.runServerTick(ui, load, { if (isActive()) apply(it) }, finished = finished)
}

/** Like the plain [pollTick], but [capture] first reads the component state [load] depends on
 *  inside a short `ui.access` (no I/O), and [load] then works on that snapshot off-lock. */
internal fun <C, T> pollTick(capture: () -> C, load: (C) -> T, apply: (T) -> Unit) =
    PollTick { ui, isActive, finished ->
        PollingViewLifecycle.runCapturedServerTick(ui, capture, load, { if (isActive()) apply(it) }, finished = finished)
    }

/** Event-bus counterpart of [pollTick] for [UiEventBus.registerOffLock]: [load] runs off-lock on
 *  the tick executor and returns null for "nothing to render"; a non-null result is handed to
 *  [apply] inside a short `ui.access`. A failing [load] is logged and never reaches [apply]. */
internal fun <T : Any> eventTick(
    ui: UI,
    load: (UiEventBus.JobFinishedEvent) -> T?,
    apply: (T, UiEventBus.JobFinishedEvent) -> Unit,
): Consumer<UiEventBus.JobFinishedEvent> =
    Consumer { event ->
        PollingViewLifecycle.dispatchEventTick(ui) { finished ->
            PollingViewLifecycle.runServerTick(
                ui,
                { load(event) },
                { result -> result?.let { apply(it, event) } },
                finished = finished,
            )
        }
    }

/** Like the plain [eventTick], but [capture] first reads the component state [load] depends on
 *  inside a short `ui.access` (no I/O), and [load] then works on that snapshot off-lock. */
internal fun <C, T : Any> eventTick(
    ui: UI,
    capture: () -> C,
    load: (C, UiEventBus.JobFinishedEvent) -> T?,
    apply: (T, UiEventBus.JobFinishedEvent) -> Unit,
): Consumer<UiEventBus.JobFinishedEvent> =
    Consumer { event ->
        PollingViewLifecycle.dispatchEventTick(ui) { finished ->
            PollingViewLifecycle.runCapturedServerTick(
                ui,
                capture,
                { captured -> load(captured, event) },
                { result -> result?.let { apply(it, event) } },
                finished = finished,
            )
        }
    }

/**
 * QOD-3 — the poll-fallback + [UiEventBus] attach/detach lifecycle shared by [InvoiceDetailView],
 * [InvoiceListView] and [PrintJobListView]: all three register a fast [UiEventBus] path plus a
 * guaranteed poll-listener fallback (the worker can run in a separate process, so its own
 * in-memory event bus never reaches this JVM's listeners) and had, before this extraction, an
 * identical nine-line `onDetach` and structurally identical `onAttach` to show for it. The poll
 * interval stays a parameter — see [DETAIL_POLL_INTERVAL_MS]'s KDoc for why the three views must
 * not share one value.
 *
 * PO fix 2026-09-02 ("Chrome shows no live updates, Firefox does") — Chrome throttles/freezes the
 * client-side JS timer that drives [ui].pollInterval far more aggressively than Firefox
 * (background-tab intensive throttling, Energy Saver, tab freezing), so a throttled/frozen timer
 * silently stops the ONLY channel a separate-process worker's status change had left. Two
 * complementary, server-side additions close that gap without touching [ui].pollInterval or the
 * three views themselves:
 * - [runServerTick] on [TICK_EXECUTOR] — a shared, `@Push`-delivered tick that runs the poll the
 *   same way the client poll listener already does, but from a server-owned timer Chrome cannot
 *   throttle. Purely additive: the client [pollRegistration] below is left running too (Firefox
 *   and Chrome-foreground tabs already worked off it, and running both costs nothing but one
 *   extra idempotent poll call on whichever channel is slower on a given tick).
 * - the [VISIBILITY_RESYNC_SCRIPT] listener — one immediate poll the instant a backgrounded
 *   tab regains visibility/focus, instead of waiting out whatever is left of the current interval.
 */
internal class PollingViewLifecycle {
    private var eventBusRegistration: UiEventBus.Registration? = null
    private var pollRegistration: com.vaadin.flow.shared.Registration? = null
    private var previousPollInterval: Int? = null
    private var serverTick: ScheduledFuture<*>? = null
    private var visibilityResyncRegistration: com.vaadin.flow.shared.Registration? = null

    /** Stores the UI's current poll interval, clamps it to intervalMs when it currently sits
     *  outside `1..intervalMs`, then registers register's event-bus callback, a poll listener
     *  that runs the poll on every client tick, a server-driven tick on the shared [TICK_EXECUTOR]
     *  that runs the same poll independent of that client timer, and the visibility/focus
     *  re-sync listener — see this class's own KDoc for why all three now coexist. */
    /** Cleared first thing in [detach]: a server tick already queued through `ui.access` (or a
     *  client poll/visibility event still in flight) must not reach the poll afterwards —
     *  `ScheduledFuture.cancel(false)` only prevents future runs, not a command that is already
     *  waiting for the session lock. Volatile because the tick executor reads it off-thread. */
    @Volatile
    private var active = false

    /** Set while one tick is between its start and the end of its apply phase: a slow query under
     *  load must not let ticks pile up behind each other, so a trigger arriving meanwhile is dropped
     *  (the next one picks up the then-current state anyway). */
    private val inFlight = AtomicBoolean(false)

    fun attach(ui: UI, intervalMs: Int, register: (UI) -> UiEventBus.Registration, tick: PollTick) {
        active = true
        inFlight.set(false)
        // counted: the tick was triggered by a poll/visibility event and is tracked in
        // [triggeredTicksPending] until it finished or was dropped.
        fun runTick(counted: Boolean) {
            val released = AtomicBoolean(false)
            val pending = pendingTicksOf(ui)
            val release = {
                inFlight.set(false)
                if (counted && released.compareAndSet(false, true)) pending.decrementAndGet()
            }
            if (active && inFlight.compareAndSet(false, true)) {
                runCatching { tick.start(ui, { active }, release) }.onFailure {
                    release()
                    log.warn("Poll tick failed", it)
                }
            } else if (counted) {
                pending.decrementAndGet()
            }
        }
        val offLockTick: () -> Unit = {
            pendingTicksOf(ui).incrementAndGet()
            triggerExecutor.execute { runTick(counted = true) }
        }
        eventBusRegistration = register(ui)
        previousPollInterval = ui.pollInterval
        if (ui.pollInterval !in 1..intervalMs) {
            ui.pollInterval = intervalMs
        }
        // Client poll and visibility events arrive on a request thread that already holds the
        // session lock; hopping to the executor keeps the load off that lock too.
        pollRegistration = ui.addPollListener { offLockTick() }
        serverTick = TICK_EXECUTOR.scheduleWithFixedDelay(
            { runTick(counted = false) },
            intervalMs.toLong(),
            intervalMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
        ui.element.executeJs(VISIBILITY_RESYNC_SCRIPT)
        visibilityResyncRegistration = ui.element.addEventListener(VISIBILITY_RESYNC_EVENT_NAME) { offLockTick() }
    }

    /** Unregisters every listener [attach] armed, cancels [serverTick] without waiting for an
     *  in-flight run to finish (it is idempotent and — via [runServerTick] — already tolerates
     *  running against a [ui] that detaches concurrently), restores the poll interval [attach]
     *  found when a UI is still present, and nulls all fields. */
    fun detach(ui: UI?) {
        active = false
        serverTick?.cancel(false)
        serverTick = null
        visibilityResyncRegistration?.remove()
        visibilityResyncRegistration = null
        eventBusRegistration?.unregister()
        eventBusRegistration = null
        pollRegistration?.remove()
        pollRegistration = null
        if (ui != null && previousPollInterval != null) {
            ui.pollInterval = previousPollInterval!!
        }
        previousPollInterval = null
    }

    companion object {
        private val log: Logger = Logger.getLogger(PollingViewLifecycle::class.java)

        /** Shared across every attached view instance — [PollingViewLifecycle] itself is a plain,
         *  per-attach helper (`private val pollingLifecycle = PollingViewLifecycle()` in each of
         *  the three consuming views), so a per-instance executor would leak one thread pool per
         *  open browser tab. Two daemon threads: comfortably enough for this app's realistic
         *  concurrent-tab count without threads outliving the JVM on shutdown. */
        private val TICK_EXECUTOR: ScheduledExecutorService = run {
            val threadNumber = AtomicInteger(1)
            Executors.newScheduledThreadPool(2) { runnable ->
                Thread(runnable, "polling-view-lifecycle-tick-${threadNumber.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
        }

        /** Where poll and visibility triggers (which arrive on a request thread holding the session
         *  lock) hand their tick, and where a captured tick continues its load. Replaceable so
         *  single-threaded view tests can run ticks on the calling thread deterministically. */
        private const val AWAIT_TICKS_TIMEOUT_MS = 5_000L
        private const val AWAIT_TICKS_POLL_MS = 10L

        /** Pending triggered ticks per UI. Keyed weakly by UI so a tick whose `ui.access` command
         *  never ran (UI discarded without draining its queue) can only ever block awaits for that
         *  one dead UI, never those of a later session or test. */
        private val triggeredTicksPending: MutableMap<UI, AtomicInteger> =
            Collections.synchronizedMap(WeakHashMap())

        private fun pendingTicksOf(ui: UI): AtomicInteger =
            synchronized(triggeredTicksPending) { triggeredTicksPending.getOrPut(ui) { AtomicInteger(0) } }

        private fun pendingTicks(ui: UI?): Int =
            if (ui != null) {
                pendingTicksOf(ui).get()
            } else {
                synchronized(triggeredTicksPending) { triggeredTicksPending.values.sumOf { it.get() } }
            }

        /** Test seam: blocks until every tick of [ui] (default: the current UI; all UIs when there is
         *  none) triggered by a poll or visibility event has finished
         *  its apply phase or was dropped, running [drainUiQueue] each round so an apply queued
         *  behind a test thread that holds the session lock can execute. The wait is bounded by a
         *  round count (one sleep of the poll interval per round), not by reading a clock; it
         *  fails after roughly [timeoutMs]. */
        internal fun awaitTriggeredTicks(
            timeoutMs: Long = AWAIT_TICKS_TIMEOUT_MS,
            ui: UI? = UI.getCurrent(),
            drainUiQueue: () -> Unit,
        ) {
            var roundsLeft = timeoutMs / AWAIT_TICKS_POLL_MS
            while (pendingTicks(ui) > 0) {
                check(roundsLeft-- > 0) { "poll tick did not finish within ${timeoutMs}ms" }
                drainUiQueue()
                Thread.sleep(AWAIT_TICKS_POLL_MS)
            }
            drainUiQueue()
        }

        @Volatile
        internal var triggerExecutor: Executor = TICK_EXECUTOR

        /** Starts one event-bus triggered tick via [start], counted in [triggeredTicksPending] until
         *  its [start] callback signals completion, so [awaitTriggeredTicks] covers it too. A
         *  failure while starting is logged: the publisher must keep fanning out to other UIs. */
        internal fun dispatchEventTick(ui: UI, start: (finished: () -> Unit) -> Unit) {
            val pending = pendingTicksOf(ui)
            pending.incrementAndGet()
            val released = AtomicBoolean(false)
            val finished = { if (released.compareAndSet(false, true)) pending.decrementAndGet() }
            try {
                triggerExecutor.execute {
                    runCatching { start(finished) }.onFailure {
                        finished()
                        log.warn("Event tick failed for UI $ui", it)
                    }
                }
            } catch (e: RejectedExecutionException) {
                finished()
                log.warn("Event tick could not be scheduled for UI $ui", e)
            }
        }

        /**
         * The [serverTick] task body — runs on [TICK_EXECUTOR]'s own thread, so the poll can only
         * reach [ui]'s component tree via [UI.access] (which itself pushes the result once
         * @Push-enabled, the same mechanism [UiEventBus.publish] already uses from its own
         * background threads). [UI.access] throws [UIDetachedException] synchronously the moment
         * [ui]'s session is already gone — the narrow race where this tick was already handed to
         * [TICK_EXECUTOR] before [detach]'s `serverTick?.cancel` took effect, or the browser tab
         * closed between ticks. [detach] is what stops *future* ticks; this catch only keeps that
         * one unlucky, already-dispatched tick from taking down [TICK_EXECUTOR]'s shared thread.
         * `internal` (not `private`) so it stays directly unit-testable against a detached/mocked
         * [UI] without needing real scheduling or timing.
         *
         * [load] runs here on the tick thread, outside the session lock, so slow queries never block
         * other requests or push connects; only [apply] runs inside the short [UI.access]. A failing
         * [load] reaches neither [UI.access] nor [apply].
         *
         * The tick thread carries no CDI request context of its own (the client-poll path gets one
         * from its HTTP request), and database access needs one. Two situations must be covered:
         * - The session lock is free: [UI.access] runs the command synchronously on this thread and
         *   then, still inside the call, releases the lock. Releasing drains commands other threads
         *   queued meanwhile (e.g. event-bus notifications) and flushes the push response, which
         *   runs lazy `beforeClientResponse` callbacks such as the grid's data fetch. All of that
         *   happens after the command body has returned, so the context must span the whole
         *   [UI.access] call — hence the outer activation.
         * - Another thread holds the lock: the command is queued and later executed by whichever
         *   thread releases the lock, where this thread's context does not apply — hence the inner
         *   activation inside the command.
         * The context is resolved lazily through [requestContext], only when a tick actually fires;
         * plain-Karibu tests (no ArC container, so a null context) run [load] and [apply] directly.
         */
        internal fun <T> runServerTick(
            ui: UI,
            load: () -> T,
            apply: (T) -> Unit,
            requestContext: () -> ManagedContext? = { Arc.container()?.requestContext() },
            finished: () -> Unit = {},
        ) {
            var handedOff = false
            try {
                withRequestContext(requestContext) {
                    val result = load()
                    ui.access { applyAndFinish(result, apply, requestContext, finished) }
                    handedOff = true
                }
            } catch (expected: UIDetachedException) {
                // Expected under the race described above — nothing left to deliver to.
            } finally {
                // Once access accepted the command, applyAndFinish owns the completion signal.
                if (!handedOff) finished()
            }
        }

        /** Tick with a component-state snapshot: [capture] runs inside a short `ui.access` (no I/O),
         *  then [load] and [apply] continue as in the plain variant, with [load] handed to
         *  [offLock] so it never runs while the session lock is held. */
        internal fun <C, T> runCapturedServerTick(
            ui: UI,
            capture: () -> C,
            load: (C) -> T,
            apply: (T) -> Unit,
            requestContext: () -> ManagedContext? = { Arc.container()?.requestContext() },
            offLock: Executor = triggerExecutor,
            finished: () -> Unit = {},
        ) {
            var accepted = false
            try {
                ui.access {
                    var handedOff = false
                    try {
                        val captured = capture()
                        offLock.execute {
                            runCatching { runServerTick(ui, { load(captured) }, apply, requestContext, finished) }
                                .onFailure { log.warn("Poll tick failed", it) }
                        }
                        handedOff = true
                    } finally {
                        if (!handedOff) finished()
                    }
                }
                accepted = true
            } catch (expected: UIDetachedException) {
                // The UI is gone before the capture could run: nothing to load or deliver.
            } finally {
                if (!accepted) finished()
            }
        }

        private fun <T> applyAndFinish(
            result: T,
            apply: (T) -> Unit,
            requestContext: () -> ManagedContext?,
            finished: () -> Unit,
        ) {
            try {
                withRequestContext(requestContext) { apply(result) }
            } finally {
                finished()
            }
        }

        /** Runs [block] with an active request context: reuses one that is already active (or none
         *  when no container exists), otherwise activates one and always terminates it again. */
        private fun withRequestContext(requestContext: () -> ManagedContext?, block: () -> Unit) {
            val context = requestContext()
            if (context == null || context.isActive) {
                block()
            } else {
                context.activate()
                try {
                    block()
                } finally {
                    context.terminate()
                }
            }
        }
    }
}
