package de.littletrotter.ui.view.masterdata

import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.RouteParameters
import de.littletrotter.application.dto.RecipientSortField
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.createGridDataProvider
import de.littletrotter.ui.view.invoice.InvoiceCreateDialog
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.component.ActionIcons

private const val RECIPIENT_CUSTOMER_NUMBER_KEY = "customerNumber"
private const val RECIPIENT_CONTACT_PERSON_KEY = "contactPerson"

/**
 * [RecipientView]'s grid/list section, extracted as a composer: the toolbar (filter +
 * "new" button), the grid's columns/sorting and its per-row "Rechnung erstellen" action, and the
 * lazy [RecipientQueryPort]-backed data provider behind it. Operates on the [grid]
 * [RecipientView] still constructs and owns (so the existing field-name reflection in the Karibu
 * tests keeps working); this class owns only the behaviour around it.
 */
internal class RecipientGridComposer(
    private val grid: DataTable<RecipientSummaryDto>,
    private val recipientQuery: RecipientQueryPort,
    // Needed only to construct [InvoiceCreateDialog] for the per-row "Rechnung erstellen"
    // action ([openInvoiceCreateDialogFor]); the grid/list section itself reads through
    // [recipientQuery] alone.
    private val senderQuery: SenderQueryPort,
    private val createInvoiceUseCase: CreateInvoiceUseCase,
    private val tr: (String) -> String,
    private val openEditor: (RecipientSummaryDto?) -> Unit,
) {

    private var currentFilter: String = ""
    private var currentSortField: RecipientSortField = RecipientSortField.NAME
    private var currentSortDir: SortDirection = SortDirection.ASC

    fun buildFilter(): TextField {
        val filterField = TextField()
        filterField.placeholder = tr("recipient.filter.placeholder")
        filterField.prefixComponent = VaadinIcon.SEARCH.create()
        filterField.isClearButtonVisible = true
        filterField.addValueChangeListener { e -> applyFilter(e.value.trim().lowercase()) }
        return filterField
    }

    fun buildNewButton(): Button = AppButtons.primary(tr("button.new"), ActionIcons.ADD) { openEditor(null) }

    private fun applyFilter(text: String) {
        currentFilter = text
        // grid.dataProvider is never null when this fires: RecipientView (this class's only real
        // caller) always calls configureGrid() immediately followed by refreshGrid() at
        // construction, before the toolbar's filter field (built by buildToolbar, wired to this
        // listener) can even receive a value-change event.
        grid.dataProvider.refreshAll()
    }

    fun configureGrid() {
        grid.setSizeFull()
        grid.addColumn(RecipientSummaryDto::customerNumber)
            .setHeader(tr("recipient.grid.customerNumber")).setSortable(true).setSortProperty(RECIPIENT_CUSTOMER_NUMBER_KEY).setKey(RECIPIENT_CUSTOMER_NUMBER_KEY).asCompactColumn()
        grid.addColumn(RecipientSummaryDto::name)
            .setHeader(tr("recipient.grid.name")).setSortable(true).setSortProperty("name").setKey("name").asTextColumn(TextColumnWidth.MEDIUM)
        grid.addColumn { r: RecipientSummaryDto -> UiNotifications.formatCityCountry(r.billingCity, r.billingCountryAlpha2) }
            .setHeader(tr("recipient.grid.cityCountry")).setSortable(true).setSortProperty("city").setKey("city").asTextColumn(TextColumnWidth.SMALL)
        grid.addColumn(RecipientSummaryDto::contactPerson)
            .setHeader(tr("recipient.grid.contactPerson")).setSortable(true).setSortProperty(RECIPIENT_CONTACT_PERSON_KEY).setKey(RECIPIENT_CONTACT_PERSON_KEY).asTextColumn(TextColumnWidth.SMALL)
        grid.addColumn { it.shortHash ?: "—" }
            .setHeader(tr("recipient.grid.shortHash"))
            .asCompactColumn()
        grid.addColumn(ComponentRenderer { r: RecipientSummaryDto -> buildCreateInvoiceButton(r) })
            .asActionColumn()
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                currentSortField = RecipientSortField.NAME
                currentSortDir = SortDirection.ASC
            } else {
                currentSortField = when (order.sorted.key) {
                    RECIPIENT_CUSTOMER_NUMBER_KEY -> RecipientSortField.CUSTOMER_NUMBER
                    "city" -> RecipientSortField.CITY
                    RECIPIENT_CONTACT_PERSON_KEY -> RecipientSortField.CONTACT_PERSON
                    else -> RecipientSortField.NAME
                }
                currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) SortDirection.ASC else SortDirection.DESC
            }
            refreshGrid()
        }
        grid.asSingleSelect().addValueChangeListener { e -> e.value?.let { openEditor(it) } }
    }

    /**
     * invoice-from-customer-row — the per-row "Rechnung erstellen" action. Same click pattern as
     * [de.littletrotter.ui.view.invoice.InvoiceListView.buildValidationCell]: a real [NativeButton]
     * with `stopPropagation()`, so the grid's own `asSingleSelect()` listener above (which opens
     * the editor dialog) never also fires for this click.
     */
    private fun buildCreateInvoiceButton(recipient: RecipientSummaryDto): NativeButton {
        val label = tr("recipient.grid.createInvoice")
        return NativeButton().apply {
            addClassName("recipient-create-invoice-button")
            add(VaadinIcon.FILE_ADD.create())
            element.setAttribute("aria-label", label)
            element.setAttribute("title", label)
            element.addEventListener("click") { openInvoiceCreateDialogFor(recipient) }.stopPropagation()
        }
    }

    /** Opens [InvoiceCreateDialog] preselected for [recipient]; on create, navigates straight to
     *  the new invoice's detail view. */
    fun openInvoiceCreateDialogFor(recipient: RecipientSummaryDto) {
        InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase, preselectedRecipient = recipient) { createdInvoiceId ->
            UI.getCurrent()?.navigate(InvoiceDetailView::class.java, RouteParameters("id", createdInvoiceId.toString()))
        }.open()
    }

    fun refreshGrid() {
        val provider = createGridDataProvider(
            fetch = { pageIndex, limit, f ->
                recipientQuery.findRecipients(
                    pageIndex,
                    limit,
                    f,
                    when (currentSortField) {
                        RecipientSortField.CUSTOMER_NUMBER -> RECIPIENT_CUSTOMER_NUMBER_KEY
                        RecipientSortField.NAME -> "name"
                        RecipientSortField.CITY -> "city"
                        RecipientSortField.CONTACT_PERSON -> RECIPIENT_CONTACT_PERSON_KEY
                    },
                    currentSortDir,
                )
            },
            count = { f -> recipientQuery.countRecipients(f) },
            // Must read the CURRENT filter on every query: applyFilter only refreshes the
            // provider, and a captured snapshot would pin the grid to the initial (empty)
            // filter forever.
            filterSupplier = { currentFilter.blankToNull() },
        )
        grid.dataProvider = provider
    }
}
