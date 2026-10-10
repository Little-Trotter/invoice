package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.dom.Element
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.invoice.InvoiceOverviewSort
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Closes branch-coverage gaps in [InvoiceListView] that
 * [InvoiceListViewKaribuTest]/[InvoiceListViewLiveUpdateTest] do not reach: the grid sort
 * listener's per-column/direction branches (same pattern as
 * [de.littletrotter.ui.view.masterdata.RecipientViewCoverageKaribuTest]), the status-chip label
 * per [InvoiceStatus], [InvoiceListView.restoreFilterState]'s query-parameter edge cases
 * (unparsable/unknown/absent values for every field), the `hasPendingValidationInResultSet`
 * short-circuit, the year-quick-pick clear/no-op paths, the `refreshTable` re-entrancy guard, and
 * the id-missing row-click branch. New surface only — the `recipientFilter` lazy-combo
 * regression tests live in [InvoiceListViewKaribuTest] itself, mirroring the same placement in
 * [PrintJobListViewKaribuTest].
 */
class InvoiceListViewCoverageKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): InvoiceListView = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

    // ── reflection helpers ──────────────────────────────────────────────────────

    // Filter fields live in the view's InvoiceListFilterBar collaborator; everything else on the view.
    private fun fieldOwner(view: InvoiceListView, name: String): Pair<Class<*>, Any> =
        if (name in FILTER_BAR_FIELDS) InvoiceListFilterBar::class.java to view.filterBar else InvoiceListView::class.java to view

    private inline fun <reified T> privateField(view: InvoiceListView, name: String): T {
        val (type, target) = fieldOwner(view, name)
        val f = type.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    private fun setPrivateField(view: InvoiceListView, name: String, value: Any?) {
        val (type, target) = fieldOwner(view, name)
        val f = type.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    private fun setBooleanField(view: InvoiceListView, name: String, value: Boolean) {
        val (type, target) = fieldOwner(view, name)
        val f = type.getDeclaredField(name)
        f.isAccessible = true
        f.setBoolean(target, value)
    }

    private fun grid(view: InvoiceListView): com.vaadin.flow.component.grid.Grid<InvoiceOverviewDto> = privateField(view, "grid")

    private fun currentSortField(view: InvoiceListView): InvoiceOverviewSortField = privateField(view, "currentSortField")

    private fun currentSortDir(view: InvoiceListView): de.littletrotter.domain.shared.SortDirection = privateField(view, "currentSortDir")

    private fun pageSize(view: InvoiceListView): Int = privateField<Int>(view, "pageSize")

    private fun recipientFilter(view: InvoiceListView): ComboBox<RecipientSummaryDto> = privateField(view, "recipientFilter")

    private fun senderFilter(view: InvoiceListView): ComboBox<SenderSummaryDto> = privateField(view, "senderFilter")

    private fun dateFromFilter(view: InvoiceListView): DatePicker = privateField(view, "dateFromFilter")

    private fun dateToFilter(view: InvoiceListView): DatePicker = privateField(view, "dateToFilter")

    private fun yearQuickPick(view: InvoiceListView): ComboBox<Int> = privateField(view, "yearQuickPick")

    private fun validationFilter(view: InvoiceListView): ComboBox<ValidationStatus> = privateField(view, "validationFilter")

    private fun reverseChargeFilter(view: InvoiceListView): ComboBox<Boolean> = privateField(view, "reverseChargeFilter")

    private fun filterField(view: InvoiceListView): TextField = privateField(view, "filterField")

    private fun statusFilter(view: InvoiceListView): InvoiceStatus? = privateField(view, "statusFilter")

    private fun pageSizeSelect(view: InvoiceListView): ComboBox<Int> = privateField(view, "pageSizeSelect")

    private fun clearYearQuickPickIfStale(view: InvoiceListView) = view.filterBar.clearYearQuickPickIfStale()

    private fun refreshTable(view: InvoiceListView, reset: Boolean = true) {
        val m = InvoiceListView::class.java.getDeclaredMethod("refreshTable", Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(view, reset)
    }

    private fun hasPendingValidationInResultSet(view: InvoiceListView): Boolean {
        val m = InvoiceListView::class.java.getDeclaredMethod("hasPendingValidationInResultSet", InvoiceExportFilter::class.java)
        m.isAccessible = true
        return m.invoke(view, view.filterBar.currentFilter()) as Boolean
    }

    private fun buildStatusChip(view: InvoiceListView, status: InvoiceStatus): de.littletrotter.ui.component.StatusGlyph =
        view.gridColumns.buildStatusChip(status, null)

    private fun navigateToDetail(view: InvoiceListView, dto: InvoiceOverviewDto) {
        val m = InvoiceListView::class.java.getDeclaredMethod("navigateToDetail", InvoiceOverviewDto::class.java)
        m.isAccessible = true
        m.invoke(view, dto)
    }

    private fun toggleStatusFilter(view: InvoiceListView, status: InvoiceStatus) {
        val m = InvoiceListView::class.java.getDeclaredMethod("toggleStatusFilter", InvoiceStatus::class.java)
        m.isAccessible = true
        m.invoke(view, status)
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
        status = InvoiceStatus.SENT,
        validationStatus = ValidationStatus.NOT_VALIDATED,
    )

    // ── grid sort listener — mapped columns, else fallback, cleared order, both directions ─────

    private fun verifySortedWith(field: InvoiceOverviewSortField, dir: de.littletrotter.domain.shared.SortDirection) {
        verify(invoiceListQuery, org.mockito.kotlin.atLeastOnce()).findPage(any(), org.mockito.kotlin.argThat<InvoiceListPage> { sort == InvoiceOverviewSort(field, dir) })
    }

    @Test
    fun `sorting the invoiceNumber column ascending sets INVOICE_NUMBER ASC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("invoiceNumber")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(InvoiceOverviewSortField.INVOICE_NUMBER, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.ASC, currentSortDir(view))
        verifySortedWith(InvoiceOverviewSortField.INVOICE_NUMBER, de.littletrotter.domain.shared.SortDirection.ASC)
    }

    @Test
    fun `sorting the seller column descending sets SELLER_NAME DESC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("seller")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(InvoiceOverviewSortField.SELLER_NAME, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.DESC, currentSortDir(view))
    }

    @Test
    fun `sorting the recipient column ascending sets RECIPIENT_NAME ASC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("recipient")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(InvoiceOverviewSortField.RECIPIENT_NAME, currentSortField(view))
    }

    @Test
    fun `sorting the itemsCount column descending sets ITEMS_COUNT DESC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("itemsCount")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(InvoiceOverviewSortField.ITEMS_COUNT, currentSortField(view))
    }

    @Test
    fun `sorting the grossAmount column ascending sets GROSS_AMOUNT ASC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("grossAmount")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(InvoiceOverviewSortField.GROSS_AMOUNT, currentSortField(view))
    }

    @Test
    fun `sorting the status column descending sets STATUS DESC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("status")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(InvoiceOverviewSortField.STATUS, currentSortField(view))
    }

    @Test
    fun `sorting the validationStatus column ascending sets VALIDATION_STATUS ASC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("validationStatus")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        assertEquals(InvoiceOverviewSortField.VALIDATION_STATUS, currentSortField(view))
    }

    @Test
    fun `sorting the invoiceDate column falls through the else branch to INVOICE_DATE`() {
        val view = newView()
        val column = grid(view).getColumnByKey("invoiceDate")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, currentSortField(view))
    }

    @Test
    fun `clearing the grid sort order resets to the default INVOICE_DATE DESC`() {
        val view = newView()
        val column = grid(view).getColumnByKey("seller")!!
        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))

        grid(view).sort(emptyList())

        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.DESC, currentSortDir(view))
    }

    // ── plain grid columns — blank-fallback vs. real value (never rendered by a headless test
    // that only ever calls setItems, since column value providers only run when the grid actually
    // formats a row) ─────────────────────────────────────────────────────────────────────────────

    private fun formattingRow(
        invoiceNumber: String = "RE-2026-00001",
        invoiceDate: LocalDate? = LocalDate.of(2026, 1, 2),
        sellerName: String? = "Seller GmbH",
        recipientName: String? = "Acme Corp",
        grossAmount: de.littletrotter.shared.Money? = de.littletrotter.shared.Money.of("10.00"),
        currency: de.littletrotter.shared.Currency = de.littletrotter.shared.Currency.EUR,
    ) = InvoiceOverviewDto(
        id = 1L,
        invoiceNumber = invoiceNumber,
        typeCode = null,
        sellerName = sellerName,
        sellerCity = null,
        recipientName = recipientName,
        grossAmount = grossAmount,
        paymentLabel = null,
        freeText = null,
        itemsCount = 3,
        invoiceDate = invoiceDate,
        status = InvoiceStatus.SENT,
        validationStatus = ValidationStatus.NOT_VALIDATED,
        currency = currency,
    )

    @Test
    fun `the plain grid columns render their blank-fallback text when the optional fields are null-ish`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(
            formattingRow(invoiceNumber = "", invoiceDate = null, sellerName = null, recipientName = null),
        )

        assertEquals("-", row[0], "invoiceNumber column")
        assertEquals("—", row[1], "invoiceDate column")
        assertEquals("", row[2], "seller column")
        assertEquals("-", row[3], "recipient column")
    }

    @Test
    fun `the plain grid columns render their real values when present`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(
            formattingRow(invoiceNumber = "RE-2026-00042", sellerName = "Seller GmbH", recipientName = "Acme Corp"),
        )

        assertEquals("RE-2026-00042", row[0], "invoiceNumber column")
        assertTrue(row[1].isNotBlank() && row[1] != "—", "invoiceDate column should render the formatted date, was: ${row[1]}")
        assertEquals("Seller GmbH", row[2], "seller column")
        assertEquals("Acme Corp", row[3], "recipient column")
    }

    // ── status chip — one label per InvoiceStatus ─────────────────────────────

    @Test
    fun `buildStatusChip exposes the enum's own label as tooltip and accessible name`() {
        val view = newView()

        InvoiceStatus.entries.forEach { status ->
            val tile = buildStatusChip(view, status)

            assertTrue(tile.hasClassName("status-glyph"), "status=$status classes=${tile.className}")
            assertEquals("img", tile.element.getAttribute("role"), "status=$status")
            assertEquals(UiTranslations.tr(status.labelKey), tile.element.getAttribute("aria-label"), "status=$status")
            assertEquals(UiTranslations.tr(status.labelKey), tile.element.getAttribute("title"), "status=$status")
            assertEquals("", tile.text, "status=$status")
        }
    }

    // ── hasPendingValidationInResultSet — the vsFilter short-circuit ────────────────────────────

    @Test
    fun `hasPendingValidationInResultSet short-circuits to false under a different terminal validation filter`() {
        val view = newView()
        validationFilter(view).value = ValidationStatus.VALID

        val result = hasPendingValidationInResultSet(view)

        assertFalse(result)
        verify(invoiceListQuery, never()).count(org.mockito.kotlin.argThat<InvoiceExportFilter> { validationStatus == ValidationStatus.PENDING })
    }

    @Test
    fun `hasPendingValidationInResultSet queries PENDING rows when no validation filter is active`() {
        val view = newView()
        whenever(
            invoiceListQuery.count(org.mockito.kotlin.argThat<InvoiceExportFilter> { validationStatus == ValidationStatus.PENDING }),
        ).thenReturn(1L)

        assertTrue(hasPendingValidationInResultSet(view))
    }

    @Test
    fun `hasPendingValidationInResultSet queries PENDING rows when the validation filter is itself PENDING`() {
        val view = newView()
        validationFilter(view).value = ValidationStatus.PENDING
        whenever(
            invoiceListQuery.count(org.mockito.kotlin.argThat<InvoiceExportFilter> { validationStatus == ValidationStatus.PENDING }),
        ).thenReturn(0L)

        assertFalse(hasPendingValidationInResultSet(view))
    }

    // ── year quick pick — null clear (early return) and the stale-range clear ──────────────────

    @Test
    fun `clearing the year quick pick after a selection leaves Von and Bis untouched`() {
        val view = newView()
        yearQuickPick(view).value = 2025
        val fromBefore = dateFromFilter(view).value
        val toBefore = dateToFilter(view).value

        yearQuickPick(view).value = null

        assertEquals(fromBefore, dateFromFilter(view).value)
        assertEquals(toBefore, dateToFilter(view).value)
    }

    @Test
    fun `editing Von away from the selected quick-pick year clears the quick pick`() {
        val view = newView()
        yearQuickPick(view).value = 2025

        dateFromFilter(view).value = LocalDate.of(2025, 2, 1)

        assertNull(yearQuickPick(view).value)
    }

    @Test
    fun `editing Bis away from the selected quick-pick year clears the quick pick`() {
        val view = newView()
        yearQuickPick(view).value = 2025

        dateToFilter(view).value = LocalDate.of(2025, 11, 30)

        assertNull(yearQuickPick(view).value)
    }

    @Test
    fun `refreshYearOptions preserves the selected year when it is still in the new option list`() {
        val view = newView()
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2026, 2025))
        yearQuickPick(view).value = 2025

        // A recipient change re-runs refreshYearOptions; the mocked years still include 2025.
        val recipient = Recipient().apply { id = 1L; name = "Acme" }
        recipientFilter(view).value = recipient.toRecipientSummaryDto()

        assertEquals(2025, yearQuickPick(view).value)
    }

    @Test
    fun `refreshYearOptions clears the selected year when it is no longer in the new option list`() {
        val view = newView()
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2026, 2025))
        yearQuickPick(view).value = 2025

        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2026))
        val recipient = Recipient().apply { id = 1L; name = "Acme" }
        recipientFilter(view).value = recipient.toRecipientSummaryDto()

        assertNull(yearQuickPick(view).value)
    }

    // ── refreshTable re-entrancy guard ──────────────────────────────────────────────────────────

    @Test
    fun `refreshTable is a no-op while isRefreshing is already true`() {
        val view = newView()
        setBooleanField(view, "isRefreshing", true)

        refreshTable(view)

        verify(invoiceListQuery, never()).findPage(any(), any())
    }

    @Test
    fun `refreshTable is a no-op while isInitializing is already true`() {
        val view = newView()
        setBooleanField(view, "isInitializing", true)

        refreshTable(view)

        verify(invoiceListQuery, never()).findPage(any(), any())
    }

    // ── row click with a missing invoice id ─────────────────────────────────────────────────────

    private fun clientRoundtripNotificationTexts(): List<String> {
        MockVaadin.clientRoundtrip()
        val texts = mutableListOf<String>()
        fun walk(element: Element) {
            element.component.ifPresent { c -> if (c is Span) texts += c.text }
            element.children.forEach(::walk)
        }
        walk(UI.getCurrent()!!.element)
        return texts
    }

    @Test
    fun `clicking a row with no invoice id shows an error notification instead of navigating`() {
        val view = newView()

        navigateToDetail(view, minimalRow(id = null))

        assertTrue(
            clientRoundtripNotificationTexts().any { it == UiTranslations.tr("invoice.list.error.invoiceIdMissing") },
            "expected the invoiceIdMissing error notification to be shown",
        )
    }

    // ── restoreFilterState / applyQueryParameters — size query parameter ───────────────────────

    @Test
    fun `restoreFilterState ignores a non-numeric size query parameter, keeping the default page size`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("size" to "lots")))

        assertEquals(20, pageSize(view))
    }

    @Test
    fun `restoreFilterState defaults a non-positive size query parameter to 20`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("size" to "0")))

        assertEquals(20, pageSize(view))
    }

    // ── default sort: newest invoices first, session never overrides it (PO order) ────────────

    @Test
    fun `a pristine view defaults to invoice date descending`() {
        val view = newView()
        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.DESC, currentSortDir(view))
    }

    @Test
    fun `a session-restored entry keeps the filters but always re-enters with the newest-first default sort`() {
        val view = newView()
        // Simulate what an earlier visit persisted: a filter plus (formerly) a manual sort. The
        // state class no longer carries sort at all, so entry must land on the default.
        com.vaadin.flow.server.VaadinSession.getCurrent().setAttribute(
            InvoiceListFilterState::class.java,
            InvoiceListFilterState(filterText = "Acme", status = InvoiceStatus.DRAFT),
        )
        view.restoreFilterState(QueryParameters.empty())

        assertEquals("Acme", privateField<com.vaadin.flow.component.textfield.TextField>(view, "filterField").value, "the session filter must restore")
        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, currentSortField(view), "a fresh entry never inherits a sort from the session")
        assertEquals(de.littletrotter.domain.shared.SortDirection.DESC, currentSortDir(view))
    }

    @Test
    fun `an explicit URL sort still wins over the default`() {
        val view = newView()
        view.restoreFilterState(QueryParameters.simple(mapOf("sortField" to "INVOICE_NUMBER", "sortDir" to "ASC")))
        assertEquals(InvoiceOverviewSortField.INVOICE_NUMBER, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.ASC, currentSortDir(view))
    }

    // ── restoreFilterState — the page parameter must survive its own refreshTable ──────────────

    @Test
    fun `restoreFilterState keeps an explicit URL page parameter instead of resetting to page 0`() {
        // Enough rows that page 3 exists — refreshTable clamps the index to the real page count,
        // so an empty stubbed result set would legitimately collapse any page back to 0.
        whenever(invoiceListQuery.count(any())).thenReturn(100L)
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("page" to "3")))

        // The trailing refreshTable used to run with reset = true and silently zero the page the
        // line above had just restored — the deep link ?page=3 always landed on page 1.
        assertEquals(3, privateField<Int>(view, "currentPageIndex"))
    }

    @Test
    fun `restoreFilterState without a page parameter still resets to page 0`() {
        val view = newView()
        setPrivateField(view, "currentPageIndex", 5)

        view.restoreFilterState(QueryParameters.simple(mapOf("size" to "20")))

        assertEquals(0, privateField<Int>(view, "currentPageIndex"))
    }

    @Test
    fun `restoreFilterState applies a valid positive size query parameter`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("size" to "50")))

        assertEquals(50, pageSize(view))
    }

    // ── restoreFilterState / applyQueryParameters — reverseCharge ──────────────────────────────

    @Test
    fun `an explicit reverseCharge=true query parameter sets the filter to true`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("reverseCharge" to "true")))

        assertEquals(true, reverseChargeFilter(view).value)
    }

    @Test
    fun `an explicit reverseCharge=false query parameter sets the filter to false`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("reverseCharge" to "false")))

        assertEquals(false, reverseChargeFilter(view).value)
    }

    @Test
    fun `an unrecognised reverseCharge value leaves the filter unset`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("reverseCharge" to "maybe")))

        assertNull(reverseChargeFilter(view).value)
    }

    // ── restoreFilterState / applyQueryParameters — validationStatus ───────────────────────────

    @Test
    fun `a valid validationStatus query parameter sets the filter`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("validationStatus" to "VALID")))

        assertEquals(ValidationStatus.VALID, validationFilter(view).value)
    }

    @Test
    fun `an unknown validationStatus query parameter leaves the filter unset`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("validationStatus" to "NOPE")))

        assertNull(validationFilter(view).value)
    }

    // ── restoreFilterState / applyQueryParameters — status (quick filter) ──────────────────────

    @Test
    fun `a valid status query parameter sets the quick filter`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("status" to "SENT")))

        assertEquals(InvoiceStatus.SENT, statusFilter(view))
    }

    @Test
    fun `an unknown status query parameter leaves the quick filter unset`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("status" to "BOGUS")))

        assertNull(statusFilter(view))
    }

    // ── restoreFilterState / applyQueryParameters — recipientId / senderProfileId ──────────────

    @Test
    fun `a recipientId query parameter that no longer resolves to a recipient leaves the filter unset`() {
        val view = newView()
        whenever(recipientQuery.findRecipientById(999L)).thenReturn(null)

        view.restoreFilterState(QueryParameters.simple(mapOf("recipientId" to "999")))

        assertNull(recipientFilter(view).value)
    }

    @Test
    fun `a non-numeric recipientId query parameter is ignored without consulting the facade`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("recipientId" to "abc")))

        assertNull(recipientFilter(view).value)
        verify(recipientQuery, never()).findRecipientById(any())
    }

    @Test
    fun `a senderProfileId query parameter that no longer resolves to a sender leaves the filter unset`() {
        val view = newView()
        whenever(senderQuery.findSenderById(999L)).thenReturn(null)

        view.restoreFilterState(QueryParameters.simple(mapOf("senderProfileId" to "999")))

        assertNull(senderFilter(view).value)
    }

    @Test
    fun `a non-numeric senderProfileId query parameter is ignored without consulting the facade`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("senderProfileId" to "xyz")))

        assertNull(senderFilter(view).value)
        verify(senderQuery, never()).findSenderById(any())
    }

    // ── restoreFilterState / applyQueryParameters — dateFrom / dateTo ──────────────────────────

    @Test
    fun `a valid dateFrom query parameter parses and sets the filter`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "2025-03-01")))

        assertEquals(LocalDate.of(2025, 3, 1), dateFromFilter(view).value)
    }

    @Test
    fun `an unparseable dateFrom query parameter is ignored`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateFrom" to "not-a-date")))

        assertNull(dateFromFilter(view).value)
    }

    @Test
    fun `an unparseable dateTo query parameter is ignored`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("dateTo" to "not-a-date")))

        assertNull(dateToFilter(view).value)
    }

    // ── restoreFilterState / applyQueryParameters — sortField / sortDir ────────────────────────

    @Test
    fun `a valid sortField and sortDir query parameter set the sort state`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("sortField" to "SELLER_NAME", "sortDir" to "ASC")))

        assertEquals(InvoiceOverviewSortField.SELLER_NAME, currentSortField(view))
        assertEquals(de.littletrotter.domain.shared.SortDirection.ASC, currentSortDir(view))
    }

    @Test
    fun `an unknown sortField query parameter falls back to the existing default sort field`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("sortField" to "NOT_A_FIELD")))

        assertEquals(InvoiceOverviewSortField.INVOICE_DATE, currentSortField(view))
    }

    @Test
    fun `an unknown sortDir query parameter falls back to the existing default sort direction`() {
        val view = newView()

        view.restoreFilterState(QueryParameters.simple(mapOf("sortDir" to "SIDEWAYS")))

        assertEquals(de.littletrotter.domain.shared.SortDirection.DESC, currentSortDir(view))
    }

    // ── session-restore path — recipientId/senderProfileId that no longer resolve ──────────────

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
        reverseChargePreference = de.littletrotter.domain.masterdata.ReverseChargePreference.ASK,
        billingCity = null,
        billingCountryAlpha2 = null,
        deliveryCountryAlpha2 = null,
    )

    private fun senderOf(id: Long, name: String) = SenderSummaryDto(
        id = id,
        name = name,
        vatId = null,
        isDefault = false,
        billingAddressCity = null,
    )

    @Test
    fun `session-restored recipientId and senderProfileId that no longer resolve leave both filters unset`() {
        whenever(recipientQuery.findRecipientById(5L)).thenReturn(recipientOf(5L, "Acme"))
        whenever(senderQuery.findSenderById(6L)).thenReturn(senderOf(6L, "Haupt"))
        val viewA = newView()
        recipientFilter(viewA).value = recipientOf(5L, "Acme")
        senderFilter(viewA).value = senderOf(6L, "Haupt")

        // The records backing the persisted ids are gone by the time a fresh view restores them.
        whenever(recipientQuery.findRecipientById(5L)).thenReturn(null)
        whenever(senderQuery.findSenderById(6L)).thenReturn(null)
        val viewB = newView()
        viewB.restoreFilterState(QueryParameters.empty())

        assertNull(recipientFilter(viewB).value)
        assertNull(senderFilter(viewB).value)
    }

    // ── QueryParametersBuilder — the remaining "present" branches (filter/status/reverseCharge/validationStatus) ──

    @Test
    fun `withCurrentState carries filter text, quick-filter status, reverseCharge and validationStatus into the URL`() {
        val view = newView()
        filterField(view).value = "ACME"
        toggleStatusFilter(view, InvoiceStatus.SENT)
        reverseChargeFilter(view).value = true
        validationFilter(view).value = ValidationStatus.VALID

        val (_, queryParameters) = view.buildDetailNavigationTarget(42L, null)

        assertEquals(listOf("ACME"), queryParameters.parameters["filter"])
        assertEquals(listOf("SENT"), queryParameters.parameters["status"])
        assertEquals(listOf("true"), queryParameters.parameters["reverseCharge"])
        assertEquals(listOf("VALID"), queryParameters.parameters["validationStatus"])
    }

    // ── amount column's grossAmount-null fallback ───────────────────────────────────────────────

    @Test
    fun `the amount column falls back to zero when grossAmount is null`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(formattingRow(grossAmount = null))

        assertEquals(MoneyFormatter.format(BigDecimal.ZERO, de.littletrotter.shared.Currency.EUR), row[5], "amount column")
    }

    // ── money-currency display — every row uses its own invoice's currency, never a shared
    //    assumed one; two invoices in different currencies must render two differently-labeled
    //    amounts side by side, not one currency silently applied to both ─────────────────────────

    @Test
    fun `a CHF invoice renders its amount in CHF, not the EUR fallback`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(
            formattingRow(grossAmount = de.littletrotter.shared.Money.of("50.00"), currency = de.littletrotter.shared.Currency.CHF),
        )

        assertEquals(
            MoneyFormatter.format(de.littletrotter.shared.Money.of("50.00"), de.littletrotter.shared.Currency.CHF),
            row[5],
            "amount column must carry the invoice's own CHF currency",
        )
    }

    @Test
    fun `a EUR row and a CHF row in the same grid each keep their own currency`() {
        val view = newView()
        val eurRow = formattingRow(grossAmount = de.littletrotter.shared.Money.of("119.00"), currency = de.littletrotter.shared.Currency.EUR)
        val chfRow = formattingRow(grossAmount = de.littletrotter.shared.Money.of("50.00"), currency = de.littletrotter.shared.Currency.CHF)

        val eurFormatted = grid(view)._getFormattedRow(eurRow)[5]
        val chfFormatted = grid(view)._getFormattedRow(chfRow)[5]

        assertEquals(MoneyFormatter.format(de.littletrotter.shared.Money.of("119.00"), de.littletrotter.shared.Currency.EUR), eurFormatted)
        assertEquals(MoneyFormatter.format(de.littletrotter.shared.Money.of("50.00"), de.littletrotter.shared.Currency.CHF), chfFormatted)
        assertTrue(eurFormatted != chfFormatted, "the two rows must not collapse onto the same currency label")
    }

    // ── page-size selector — clearing it keeps the current page size, no-op branch ─────────────

    @Test
    fun `clearing the page size selector keeps the current page size`() {
        val view = newView()

        pageSizeSelect(view).value = null

        assertEquals(20, pageSize(view))
    }

    // ── onDetach tolerates a null DetachEvent (never-attached / defensive path) ────────────────

    @Test
    fun `onDetach tolerates a null detach event`() {
        val view = newView()
        val m = InvoiceListView::class.java.getDeclaredMethod("onDetach", com.vaadin.flow.component.DetachEvent::class.java)
        m.isAccessible = true

        m.invoke(view, null)
    }

    // ── hasPendingValidationInResultSet — recipientFilter senderFilter narrow the query ─────────

    @Test
    fun `hasPendingValidationInResultSet passes the selected recipient and sender ids through to the count query`() {
        val view = newView()
        val recipient = recipientOf(7L, "Acme")
        val sender = senderOf(8L, "Haupt")
        recipientFilter(view).value = recipient
        senderFilter(view).value = sender
        whenever(
            invoiceListQuery.count(org.mockito.kotlin.argThat<InvoiceExportFilter> { validationStatus == ValidationStatus.PENDING && recipientId == 7L && senderProfileId == 8L }),
        ).thenReturn(1L)

        assertTrue(hasPendingValidationInResultSet(view))
        verify(invoiceListQuery).count(org.mockito.kotlin.argThat<InvoiceExportFilter> { validationStatus == ValidationStatus.PENDING && recipientId == 7L && senderProfileId == 8L })
    }

    // ── clearYearQuickPickIfStale — the "still matches" branch leaves the pick untouched ───────

    @Test
    fun `clearYearQuickPickIfStale leaves the quick pick set when Von and Bis still match its year`() {
        val view = newView()
        setBooleanField(view, "isApplyingYearQuickPick", true)
        dateFromFilter(view).value = LocalDate.of(2025, 1, 1)
        dateToFilter(view).value = LocalDate.of(2025, 12, 31)
        setBooleanField(view, "isApplyingYearQuickPick", false)
        yearQuickPick(view).value = 2025

        clearYearQuickPickIfStale(view)

        assertEquals(2025, yearQuickPick(view).value)
    }

    // ── applyFilterState (session restore) — recipientId/senderProfileId that still resolve ───

    @Test
    fun `a session-restored recipientId and senderProfileId that still resolve set both filters`() {
        val recipient = recipientOf(5L, "Acme")
        val sender = senderOf(6L, "Haupt")
        whenever(recipientQuery.findRecipientById(5L)).thenReturn(recipient)
        whenever(senderQuery.findSenderById(6L)).thenReturn(sender)
        com.vaadin.flow.server.VaadinSession.getCurrent().setAttribute(
            InvoiceListFilterState::class.java,
            InvoiceListFilterState(recipientId = 5L, senderProfileId = 6L),
        )
        val view = newView()

        view.restoreFilterState(QueryParameters.empty())

        assertEquals(recipient, recipientFilter(view).value)
        assertEquals(sender, senderFilter(view).value)
    }

    // ── reflectCurrentStateIntoUrl — tolerates a null current UI during a session restore ──────

    @Test
    fun `reflectCurrentStateIntoUrl tolerates a null current UI during a session restore`() {
        com.vaadin.flow.server.VaadinSession.getCurrent().setAttribute(
            InvoiceListFilterState::class.java,
            InvoiceListFilterState(filterText = "Acme"),
        )
        val view = newView()
        val ui = UI.getCurrent()
        UI.setCurrent(null)
        try {
            view.restoreFilterState(QueryParameters.empty())
        } finally {
            UI.setCurrent(ui)
        }

        assertEquals("Acme", filterField(view).value, "the session filter must still restore with no current UI")
    }

    // ── updatePageTitle — tolerates a null current UI at construction time ─────────────────────

    @Test
    fun `updatePageTitle tolerates a null current UI`() {
        val ui = UI.getCurrent()
        UI.setCurrent(null)
        try {
            newView()
        } finally {
            UI.setCurrent(ui)
        }
    }

    // ── restoreFilterState — the sort-column-key mapping for every InvoiceOverviewSortField ────

    @Test
    fun `restoreFilterState resolves the grid sort column key for every sort field`() {
        InvoiceOverviewSortField.entries.forEach { field ->
            val view = newView()

            view.restoreFilterState(QueryParameters.simple(mapOf("sortField" to field.name, "sortDir" to "ASC")))

            assertEquals(field, currentSortField(view), "sortField=$field")
        }
    }

    private companion object {
        val FILTER_BAR_FIELDS = setOf(
            "filterField", "validationFilter", "reverseChargeFilter", "recipientFilter", "senderFilter",
            "dateFromFilter", "dateToFilter", "yearQuickPick", "statusFilter", "activeFilterChip", "isApplyingYearQuickPick",
        )
    }
}
