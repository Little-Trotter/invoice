package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.github.mvysny.kaributesting.v10._clickItem
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.router.HasErrorParameter
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Closes the last two [InvoiceListView] branches neither
 * [InvoiceListViewKaribuTest] nor [InvoiceListViewCoverageKaribuTest] reaches: the real
 * `UI.getCurrent()?.navigate(InvoiceDetailView::class.java, ...)` calls in
 * [InvoiceListView.navigateToInvoiceDetail] (row click) and the "Neue Rechnung" dialog's
 * `onCreated` callback. Both call sites target [InvoiceDetailView], which is CDI-injected (no
 * no-arg constructor Vaadin's default instantiator can build in a plain unit test) — every other
 * test in this package sidesteps that by calling [InvoiceListView.buildDetailNavigationTarget]
 * directly instead of driving a real click. This class takes the other approach instead
 * ([PrintJobListViewCoverageKaribuTest]'s own "clicking a job row..." test): register
 * [InvoiceDetailView] as a real route via [Routes] so the router actually attempts it, then let the
 * resulting instantiation failure resolve to Vaadin's own `InternalServerError` view rather than
 * propagating — the route it tried (naming the exact invoice id) is proof the navigate call ran, a
 * dedicated file (not the shared [InvoiceListViewKaribuTest] / [InvoiceListViewCoverageKaribuTest]
 * `MockVaadin.setup()`) so this route registration cannot affect either suite's existing tests.
 */
class InvoiceListViewNavigationKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun grid(view: InvoiceListView): com.vaadin.flow.component.grid.Grid<InvoiceOverviewDto> {
        val f = InvoiceListView::class.java.getDeclaredField("grid")
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun refreshTable(view: InvoiceListView) {
        val m = InvoiceListView::class.java.getDeclaredMethod("refreshTable", Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(view, true)
    }

    private fun minimalRow(id: Long? = 1L) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "RE-2026-00001",
        typeCode = null,
        sellerName = "Seller",
        sellerCity = null,
        recipientName = "Acme",
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 1,
        invoiceDate = LocalDate.of(2026, 8, 26),
        status = InvoiceStatus.ISSUED,
        validationStatus = ValidationStatus.NOT_VALIDATED,
    )

    @Test
    fun `clicking a grid row with a valid invoice id reaches the navigate call for that invoice's detail view`() {
        whenever(
            invoiceListQuery.count(any()),
        ).thenReturn(1L)
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(listOf(minimalRow(id = 42L)))
        // navigateToDetail re-resolves the id before navigating; the row must still
        // exist (not deleted since the grid loaded) for a real click to reach the navigate call.
        whenever(invoiceListQuery.exists(42L)).thenReturn(true)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val ui = UI.getCurrent()!!
        ui.add(view)
        refreshTable(view)

        grid(view)._clickItem(0)

        assertEquals("invoice/42", ui.internals.activeViewLocation.path)
    }

    @Test
    fun `ST-B6 - clicking a row whose invoice was deleted since load shows the not-found notification, refreshes the grid, and does not navigate`() {
        whenever(
            invoiceListQuery.count(any()),
        ).thenReturn(1L)
        whenever(
            invoiceListQuery.findPage(any(), any()),
        ).thenReturn(listOf(minimalRow(id = 42L)))
        // The row is still in the grid's own cached page, but the invoice is already gone by
        // click time — the exact race this test guards against.
        whenever(invoiceListQuery.exists(42L)).thenReturn(false)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val ui = UI.getCurrent()!!
        ui.add(view)
        val beforeClickPath = ui.internals.activeViewLocation.path
        refreshTable(view)

        grid(view)._clickItem(0)

        assertEquals(beforeClickPath, ui.internals.activeViewLocation.path, "a stale row click must never navigate")
        val notification = com.github.mvysny.kaributesting.v10.getNotifications().single()
        val text = flatten(notification).filterIsInstance<com.vaadin.flow.component.html.Span>().joinToString(" ") { it.text ?: "" }
        assertTrue(
            text.contains("42"),
            "expected the not-found notification naming the invoice id, got: $text",
        )
        // findInvoiceOverview is stubbed identically every call, so a second invocation (the
        // refresh the stale-row handling triggers) beyond the initial refreshTable() call above
        // is the proof the grid actually reloaded, not just that the click handler returned quietly.
        org.mockito.kotlin.verify(invoiceListQuery, org.mockito.kotlin.atLeast(2)).findPage(any(), any())
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    // ── "Neue Rechnung" dialog — onCreated callback ─────────────────────────────────────────────

    private fun openInvoiceCreateDialog(view: InvoiceListView) {
        val m = InvoiceListView::class.java.getDeclaredMethod("openInvoiceCreateDialog")
        m.isAccessible = true
        m.invoke(view)
    }

    private fun senderOf(id: Long, name: String, isDefault: Boolean = false) = SenderSummaryDto(
        id = id,
        name = name,
        vatId = null,
        isDefault = isDefault,
        billingAddressCity = null,
    )

    private fun recipientOf(id: Long, name: String) = RecipientSummaryDto(
        id = id,
        name = name,
        customerNumber = "CUST-$id",
        shortHash = null,
        contactPerson = null,
        routingId = null,
        vatId = null,
        customerType = null,
        vatIdVerificationStatus = null,
        reverseChargePreference = ReverseChargePreference.ASK,
        billingCity = null,
        billingCountryAlpha2 = null,
        deliveryCountryAlpha2 = null,
    )

    private fun dialogForm(dialog: Dialog): com.vaadin.flow.component.formlayout.FormLayout =
        dialog.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()

    private fun dialogRecipientCombo(dialog: Dialog): com.vaadin.flow.component.combobox.ComboBox<RecipientSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()[1]
            .requireAs()

    private fun dialogCreateButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("invoice.dialog.create") }

    private fun minimalInvoiceDetailDto(id: Long) = de.littletrotter.application.dto.InvoiceDetailDto(
        id = id,
        invoiceNumber = "INV-2026-00099",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = de.littletrotter.shared.Money.ZERO,
        vatAmount = de.littletrotter.shared.Money.ZERO,
        grossAmount = de.littletrotter.shared.Money.ZERO,
        sender = null,
        recipient = null,
        items = emptyList(),
        latestGenerationJob = null,
    )

    @Test
    fun `creating an invoice from the dialog navigates to its detail view`() {
        val default = senderOf(1L, "Acme", isDefault = true)
        val recipient = recipientOf(10L, "Kunde GmbH")
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(default))
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenReturn(minimalInvoiceDetailDto(id = 77L))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val ui = UI.getCurrent()!!
        ui.add(view)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        dialogRecipientCombo(dialog).value = recipient
        dialogCreateButton(dialog).click()

        assertEquals("invoice/77", ui.internals.activeViewLocation.path)
    }

    @Test
    fun `an item click without an item (row vanished after a refresh) neither navigates nor throws`() {
        whenever(invoiceListQuery.count(any())).thenReturn(0L)
        whenever(invoiceListQuery.findPage(any(), any())).thenReturn(emptyList())
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val ui = UI.getCurrent()!!
        ui.add(view)
        val before = ui.internals.activeViewLocation.path
        val event: com.vaadin.flow.component.grid.ItemClickEvent<InvoiceOverviewDto> = mock()
        whenever(event.source).thenReturn(grid(view))
        whenever(event.item).thenReturn(null)

        com.vaadin.flow.component.ComponentUtil.fireEvent(grid(view), event)

        assertEquals(before, ui.internals.activeViewLocation.path)
        org.mockito.kotlin.verify(invoiceListQuery, org.mockito.kotlin.never()).exists(any())
    }
}
