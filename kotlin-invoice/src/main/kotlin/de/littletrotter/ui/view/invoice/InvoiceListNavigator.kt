package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications

/**
 * Navigation out of [InvoiceListView] into [InvoiceDetailView]: row click, validation tile and
 * the new-invoice dialog. Every target carries the list's current filter/sort/paging state
 * ([currentQueryParameters]) so "back to list" restores it.
 */
internal class InvoiceListNavigator(
    private val recipientQuery: RecipientQueryPort,
    private val senderQuery: SenderQueryPort,
    private val createInvoiceUseCase: CreateInvoiceUseCase,
    private val invoiceListQuery: InvoiceListQueryPort,
    private val translations: TranslationProvider,
    private val currentQueryParameters: () -> QueryParameters,
    private val onStaleRow: () -> Unit,
) {

    /**
     * ST-B6 — a row click carries the grid's cached row, which may be stale by click time.
     * Re-resolves the id first: gone → "not found" notification plus [onStaleRow] (a grid
     * refresh), never a navigation that lands [InvoiceDetailView] on an id it cannot render.
     */
    fun openRow(inv: InvoiceOverviewDto) {
        val id = inv.id
        if (id == null) {
            UiNotifications.error(translations.tr("invoice.list.error.invoiceIdMissing"))
            return
        }
        if (!invoiceListQuery.exists(id)) {
            UiNotifications.error(translations.tr("invoiceDetail.error.notFound", id.toString()))
            onStaleRow()
            return
        }
        navigate(id, focus = null)
    }

    /** The validation tile's click target: opens the detail view on its validation area. */
    fun openValidation(invoiceId: Long) {
        navigate(invoiceId, focus = FOCUS_VALIDATION)
    }

    /** Pure navigation-target builder so tests can assert "which invoice, which focus" without a
     *  real [UI.navigate] — [InvoiceDetailView] is CDI-injected and cannot be built outside a
     *  container. */
    fun detailTarget(id: Long, focus: String?): Pair<RouteParameters, QueryParameters> {
        val state: Map<String, List<String>> = currentQueryParameters().parameters
        val parameters = if (focus == null) state else state + (PARAM_FOCUS to listOf(focus))
        return RouteParameters(PARAM_ID, id.toString()) to QueryParameters(parameters)
    }

    /** invoice-from-customer-row — the dialog lives in [InvoiceCreateDialog]; this only wires the
     *  navigation to the created invoice. */
    fun openCreateDialog() {
        InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { createdInvoiceId -> navigate(createdInvoiceId, focus = null) }.open()
    }

    private fun navigate(id: Long, focus: String?) {
        val (parameters, queryParameters) = detailTarget(id, focus)
        UI.getCurrent()?.navigate(InvoiceDetailView::class.java, parameters, queryParameters)
    }

    private companion object {
        const val PARAM_ID = "id"
        const val PARAM_FOCUS = "focus"
        const val FOCUS_VALIDATION = "validation"
    }
}
