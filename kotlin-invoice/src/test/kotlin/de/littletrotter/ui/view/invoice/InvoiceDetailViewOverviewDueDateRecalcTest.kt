package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.UiEventBus
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [InvoiceDetailView.buildEntryPane]'s live due-date recalculation (the
 * `paymentTermBox`/`invoiceDateField` value-change listeners around the `loading` flag) had never
 * actually fired: every existing entry-pane test only exercises the initial `binder.readBean` load,
 * during which `loading` is still `true` and both listeners return immediately. [computeDueDate]
 * itself is pinned elsewhere as a pure function, but the wiring that calls it on a real user edit —
 * and its two elvis-operator early-return guards (no payment term selected / no invoice date set) —
 * was not.
 */
class InvoiceDetailViewOverviewDueDateRecalcTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val refs = ReferenceDataMocks()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun paymentTerm(days: Int, id: Long = 1L): PaymentTermDto =
        PaymentTermDto(id = id, namesByLanguageCode = mapOf("de" to "Netto $days"), fallbackLanguageCode = "de", dueInDays = days)

    private fun createView(terms: List<PaymentTermDto>): InvoiceDetailView {
        whenever(refs.paymentTermQueries.findAllPaymentTerms()).thenReturn(terms)
        whenever(refs.paymentTermQueries.findDefaultPaymentTerm()).thenReturn(null)
        terms.forEach { term -> whenever(refs.paymentTermQueries.findPaymentTermById(term.id!!)).thenReturn(term) }
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val recipientQueryPort = mock<RecipientQueryPort>()
        val generationJobQueryService = mock<GenerationJobQueryService>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryService = generationJobQueryService,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort)),
                exportPanel = testExportPanel(invoiceDetailQueryPort, recipientQueryPort, generationJobQueryService),
            ),
            invoiceEntryPane = testEntryPane(
                recipientQueryPort = recipientQueryPort,
                invoiceDetailQueryPort = invoiceDetailQueryPort,
                refs = refs,
            ),
        )
    }

    private fun invoice(invoiceDate: LocalDate?, paymentTermId: Long? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = invoiceDate,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        paymentTermId = paymentTermId, dueDate = null,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun buildPane(view: InvoiceDetailView, current: InvoiceDetailDto): EntryPaneResult {
        val result = view.invoiceEntryPane.build(view, current, onInvoiceUpdated = { view.invoice = it }, onDone = {})
        com.vaadin.flow.component.UI.getCurrent().add(result.component)
        MockVaadin.clientRoundtrip()
        return result
    }

    private fun paymentTermBox(component: Component): ComboBox<PaymentTermDto> =
        flatten(component).filterIsInstance<ComboBox<*>>().single { it.label == tr("invoiceDetail.paymentTerm") }.requireAs()

    private fun invoiceDateField(component: Component): DatePicker =
        flatten(component).filterIsInstance<DatePicker>().single { it.label == tr("invoiceDetail.invoiceDate") }

    private fun dueDateField(component: Component): DatePicker =
        flatten(component).filterIsInstance<DatePicker>().single { it.label == tr("invoiceDetail.dueDate") }

    private fun tr(key: String): String = de.littletrotter.ui.i18n.UiTranslations.tr(key)

    @Test
    fun `changing the invoice date after load recomputes the due date from the selected payment term`() {
        val term = paymentTerm(days = 14)
        val view = createView(listOf(term))
        val pane = buildPane(view, invoice(invoiceDate = LocalDate.of(2026, 1, 1), paymentTermId = term.id))

        invoiceDateField(pane.component).value = LocalDate.of(2026, 3, 1)

        assertEquals(LocalDate.of(2026, 3, 15), dueDateField(pane.component).value)
    }

    @Test
    fun `changing the payment term after load recomputes the due date from the current invoice date`() {
        val term30 = paymentTerm(days = 30, id = 1L)
        val term7 = paymentTerm(days = 7, id = 2L)
        val view = createView(listOf(term30, term7))
        val pane = buildPane(view, invoice(invoiceDate = LocalDate.of(2026, 1, 1), paymentTermId = term30.id))

        paymentTermBox(pane.component).value = term7

        assertEquals(LocalDate.of(2026, 1, 8), dueDateField(pane.component).value)
    }

    @Test
    fun `changing the invoice date while no payment term is selected does not touch the due date`() {
        val term = paymentTerm(days = 14)
        val view = createView(listOf(term))
        val pane = buildPane(view, invoice(invoiceDate = LocalDate.of(2026, 1, 1), paymentTermId = null))
        paymentTermBox(pane.component).value = null
        val before = dueDateField(pane.component).value

        invoiceDateField(pane.component).value = LocalDate.of(2026, 3, 1)

        assertEquals(before, dueDateField(pane.component).value)
    }

    @Test
    fun `clearing the payment term after load does not touch the due date`() {
        val term = paymentTerm(days = 14)
        val view = createView(listOf(term))
        val pane = buildPane(view, invoice(invoiceDate = LocalDate.of(2026, 1, 1), paymentTermId = term.id))
        val before = dueDateField(pane.component).value

        paymentTermBox(pane.component).value = null

        assertEquals(before, dueDateField(pane.component).value)
    }

    @Test
    fun `clearing the invoice date after load leaves the due date untouched since computeDueDate returns null`() {
        val term = paymentTerm(days = 14)
        val view = createView(listOf(term))
        // dueDate is loaded straight from the persisted DTO (not recomputed) — an explicit,
        // already-stored value here is what makes "computeDueDate(null, ...) returns null, so the
        // listener's own `if (newDue != null)` guard leaves it alone" observable.
        val current = invoice(invoiceDate = LocalDate.of(2026, 1, 1), paymentTermId = term.id).copy(dueDate = LocalDate.of(2026, 1, 15))
        val pane = buildPane(view, current)
        assertEquals(LocalDate.of(2026, 1, 15), dueDateField(pane.component).value)

        invoiceDateField(pane.component).value = null

        assertEquals(LocalDate.of(2026, 1, 15), dueDateField(pane.component).value)
    }
}
