package de.littletrotter.ui

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.UIDetachedException
import de.littletrotter.application.invoice.port.outbound.EventPublisher
import de.littletrotter.application.invoice.port.outbound.InvoiceStatusPublisher
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.message.MessageRecorded
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import org.jboss.logging.Logger
import java.io.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

@ApplicationScoped
class UiEventBus : Serializable, EventPublisher, InvoiceStatusPublisher {
    private companion object {
        private const val serialVersionUID = 1L
        private const val DEFAULT_BADGE_REFRESH_MS = 2000L
    }

    private val log = Logger.getLogger(javaClass)
    private val listeners = CopyOnWriteArrayList<UiEventListener>()
    private val messageListeners = CopyOnWriteArrayList<MessageListener>()

    /** Window within which message signals collapse into one refresh per UI. */
    internal var badgeRefreshMs: Long = DEFAULT_BADGE_REFRESH_MS

    /** Single daemon thread: refreshes are short, and coalescing bounds their rate. A plain
     *  scheduled pool because the delay is the point; the handler reads with the actor captured on
     *  the request thread, so it needs no propagated request context. Threads start lazily. */
    internal var scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "ui-message-badge-refresh").also { it.isDaemon = true }
    }

    data class JobFinishedEvent(val jobId: Long, val invoiceId: Long?, val success: Boolean)

    data class InvoiceStatusChangedEvent(val invoiceId: Long, val status: InvoiceStatus)

    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    /** Like [registerOffLock] for status changes: [handler] runs on the publishing thread without
     *  the session lock and enters `ui.access` itself. */
    fun registerStatusChanged(ui: UI, handler: Consumer<InvoiceStatusChangedEvent>): Registration {
        val listener = StatusListener(ui, handler)
        statusListeners.add(listener)
        return Registration { statusListeners.remove(listener) }
    }

    fun publish(event: InvoiceStatusChangedEvent) {
        log.debugf("Publishing InvoiceStatusChangedEvent for invoice %d", event.invoiceId)
        for (listener in statusListeners) {
            if (listener.ui.isClosing || !listener.ui.isAttached) {
                statusListeners.remove(listener)
                continue
            }
            try {
                listener.handler.accept(event)
            } catch (e: Exception) {
                // One failing UI must not stop delivery to the other open sessions.
                log.warnf(e, "Failed to dispatch InvoiceStatusChangedEvent invoiceId=%d to UI %s", event.invoiceId, listener.ui)
            }
        }
    }

    override fun publishInvoiceStatusChanged(invoiceId: Long, status: InvoiceStatus) {
        publish(InvoiceStatusChangedEvent(invoiceId, status))
    }

    fun register(ui: UI, handler: Consumer<JobFinishedEvent>): Registration = add(UiEventListener(ui, handler, offLock = false))

    /** Like [register], but [handler] is invoked on the publishing thread WITHOUT the session lock:
     *  it must do its own data loading off-lock and enter `ui.access` only to touch components
     *  (see the two-phase `eventTick` helpers), so a slow query never blocks the session. */
    fun registerOffLock(ui: UI, handler: Consumer<JobFinishedEvent>): Registration =
        add(UiEventListener(ui, handler, offLock = true))

    /** Runs [handler] on the bus's own scheduler thread, WITHOUT the session lock, after message-center
     *  messages were committed; the handler enters `ui.access` itself to touch components. Signals
     *  are coalesced per UI: however many messages arrive within [badgeRefreshMs], [handler] runs once
     *  at the end of the window, so it always sees the final state. */
    fun registerMessageRecorded(ui: UI, handler: Runnable): Registration {
        val listener = MessageListener(ui, handler)
        messageListeners.add(listener)
        return Registration {
            listener.active = false
            listener.pendingRefresh?.cancel(false)
            messageListeners.remove(listener)
        }
    }

    /** Only signals: it runs on the recording thread, and a bulk run recording hundreds of messages
     *  must neither slow those jobs with count queries nor flood the browser with pushes. The query
     *  and the push happen later on [scheduler], at most once per UI and window. */
    fun onMessageRecorded(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: MessageRecorded) {
        log.debugf("Signalling %s to %d listeners", event, messageListeners.size)
        for (listener in messageListeners) {
            if (listener.ui.isClosing || !listener.ui.isAttached) {
                messageListeners.remove(listener)
                continue
            }
            if (listener.pending.compareAndSet(false, true)) {
                listener.pendingRefresh = scheduler.schedule({ refresh(listener) }, badgeRefreshMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun refresh(listener: MessageListener) {
        listener.pending.set(false)
        if (!listener.active) return
        try {
            listener.handler.run()
        } catch (e: Exception) {
            // Independent UIs share this thread: one failing badge refresh must not stop later ones.
            log.warnf(e, "Failed to refresh message badge for UI %s", listener.ui)
        }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    internal fun messageListenerCount(): Int = messageListeners.size

    private fun add(listener: UiEventListener): Registration {
        listeners.add(listener)
        return Registration { listeners.remove(listener) }
    }

    /** Test seam for the navigation-leak Karibu test: exposes the live listener count so
     *  a test can assert it returns to baseline after repeated attach/detach cycles, without
     *  reaching into the private [listeners] field via reflection. */
    internal fun listenerCount(): Int = listeners.size

    fun publish(event: JobFinishedEvent) {
        log.debugf("Publishing JobFinishedEvent for job %d", event.jobId)
        val iterator = listeners.iterator()
        while (iterator.hasNext()) {
            val listener = iterator.next()
            if (listener.ui.isClosing || !listener.ui.isAttached) {
                listeners.remove(listener)
                continue
            }
            try {
                if (listener.offLock) {
                    listener.handler.accept(event)
                } else {
                    listener.ui.access {
                        listener.handler.accept(event)
                    }
                }
            } catch (expected: UIDetachedException) {
                // The UI closed/detached between the isClosing/isAttached check above and
                // this access call itself (UI.access throws synchronously the moment the session
                // is already gone): an expected race under concurrent navigation/session teardown,
                // not a bug. Drop the event for this listener and stop tracking it; iteration
                // continues for the rest.
                listeners.remove(listener)
            } catch (e: Exception) {
                // AP-EXC-2 — kept broad on purpose: this dispatches to N independent UIs in a
                // fan-out loop, one handler misbehaving (or its UI closing mid-access) must not
                // stop delivery to the rest. Logged with the jobId/invoiceId the event carries so a
                // failed live-update is discoverable per job, not just "some UI, some time".
                log.warnf(
                    e,
                    "Failed to dispatch JobFinishedEvent jobId=%d invoiceId=%s to UI %s",
                    event.jobId,
                    event.invoiceId,
                    listener.ui,
                )
            }
        }
    }

    private class MessageListener(val ui: UI, val handler: Runnable) {
        val pending = AtomicBoolean(false)

        @Volatile
        var active = true

        @Volatile
        var pendingRefresh: ScheduledFuture<*>? = null
    }

    private class StatusListener(val ui: UI, val handler: Consumer<InvoiceStatusChangedEvent>)

    private class UiEventListener(val ui: UI, val handler: Consumer<JobFinishedEvent>, val offLock: Boolean)

    fun interface Registration {
        fun unregister()
    }

    // Adapter method to satisfy application-level EventPublisher
    override fun publishJobFinished(jobId: Long, invoiceId: Long?, success: Boolean) {
        publish(JobFinishedEvent(jobId, invoiceId, success))
    }
}
