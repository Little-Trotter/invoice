package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.ui.UiEventBus

/**
 * Live updates for [InvoiceListView] (PO report 30.08.2026): a fast [UiEventBus] path plus a poll
 * fallback for a worker running in a separate process, whose in-memory event bus never reaches
 * this JVM — the same [PollingViewLifecycle] InvoiceDetailView and PrintJobListView use (QOD-3).
 *
 * Both paths work in three phases so no query runs under the session lock: [capture] reads the
 * component state in a short access, [load] queries off-lock and returns an immutable result,
 * [apply] touches components only and reports whether it rendered the result.
 */
internal class InvoiceListLiveUpdates(
    private val uiEventBus: UiEventBus,
    private val isViewAttached: () -> Boolean,
    private val capture: () -> InvoiceListQuery,
    private val load: (InvoiceListQuery) -> InvoiceListLoad,
    private val hasPendingValidation: (InvoiceExportFilter) -> Boolean,
    private val apply: (InvoiceListLoad) -> Boolean,
) {

    private val pollingLifecycle = PollingViewLifecycle()

    // Whether the previous poll tick still saw a PENDING invoice in the current filter's result
    // set — needed for one final refresh on the tick where the last one leaves (see
    // [shouldRefreshOnPollTick]). Always re-queried live, never derived from the rendered page.
    @Volatile
    private var sawPendingValidationInResultSet = false

    fun attach(ui: UI) {
        // F4 — seeded true so the first poll tick after entering always refreshes once, even when
        // a validation's whole PENDING lifetime fell between navigation and that tick.
        sawPendingValidationInResultSet = true
        pollingLifecycle.attach(
            ui,
            LIVE_UPDATE_POLL_INTERVAL_MS,
            register = { u ->
                // A push queued before the view detached can still arrive; guard on both.
                uiEventBus.registerOffLock(
                    u,
                    eventTick(
                        u,
                        capture = capture,
                        load = { query, _ -> load(query) },
                        apply = { result, _ ->
                            if (isViewAttached() && u.isAttached) apply(result)
                        },
                    ),
                )
            },
            tick = pollTick(
                capture = capture,
                load = { query ->
                    val hadPending = sawPendingValidationInResultSet
                    val hasPending = hasPendingValidation(query.filter)
                    sawPendingValidationInResultSet = hasPending
                    if (shouldRefreshOnPollTick(hadPending, hasPending)) load(query) else null
                },
                apply = { result -> if (result != null && isViewAttached()) apply(result) },
            ),
        )
    }

    fun detach(ui: UI?) {
        pollingLifecycle.detach(ui)
    }
}
