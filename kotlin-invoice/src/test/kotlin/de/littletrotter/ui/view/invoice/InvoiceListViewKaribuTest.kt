package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.streams.toList
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO UX fix (2026-08-25) — the reverse-charge column renders a neutral badge with tooltip
 * only when reverseCharge is true, and an empty cell when false. This test verifies badge
 * presence and tooltip attribute.
 *
 * This suite also covers the validation-chip UNAVAILABLE/INVALID split, the
 * column-header legend popover, the chip's click-navigation target, and the quick-filter chip row
 * (counts, toggle, no-N+1) — same reflection-into-private-members idiom throughout, since
 * [InvoiceListView] is never attached to a UI in these tests (no route registration needed).
 */
class InvoiceListViewKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()

        // Mock default behavior
        // countInvoiceOverviewGroupedByValidationStatus's 7 parameters all carry default values on
        // a single method (not an overload chain like countInvoiceOverview above) — Mockito can
        // only stub/verify it with every parameter supplied as a matcher; a partial-arity call
        // here would route through Kotlin's synthetic `$default` bridge and desync Mockito's
        // matcher stack ("N matchers expected, M recorded").
        whenever(invoiceListQuery.countByStatus(any())).thenReturn(emptyMap())
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun buildReverseChargeCell(view: InvoiceListView, reverseCharge: Boolean): Span =
        view.gridColumns.buildReverseChargeCell(reverseCharge)

    private fun buildReverseChargeHeader(view: InvoiceListView): Span = view.gridColumns.buildReverseChargeHeader()

    @Test
    fun reverseCellRendersNeutralBadgeWhenTrue() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val cell = buildReverseChargeCell(view, true)

        assertEquals("RC", cell.text)
        assertTrue(cell.classNames.contains("status-chip--neutral"))
        assertEquals("Reverse Charge — Steuerschuldumkehr (§ 13b UStG / Art. 196 MwStSystRL)", cell.element.getAttribute("title"))
    }

    @Test
    fun reverseCellRendersEmptyWhenFalse() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val cell = buildReverseChargeCell(view, false)

        assertEquals("", cell.text)
        assertFalse(cell.classNames.contains("status-chip"))
    }

    @Test
    fun headerCarriesTooltip() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val header = buildReverseChargeHeader(view)

        assertEquals("RC", header.text)
        assertEquals("Reverse Charge — Steuerschuldumkehr (§ 13b UStG / Art. 196 MwStSystRL)", header.element.getAttribute("title"))
    }

    // ── PO fix (2026-08-25) — plain Kunde column replaces the combined Anbieter/Kunde column ────

    private fun grid(view: InvoiceListView): com.vaadin.flow.component.grid.Grid<*> {
        val field = InvoiceListView::class.java.getDeclaredField("grid")
        field.isAccessible = true
        return field.get(view) as com.vaadin.flow.component.grid.Grid<*>
    }

    @Test
    fun gridHasAPlainKundeColumnInsteadOfTheCombinedAnbieterKundeColumn() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        val recipientColumn = g.getColumnByKey("recipient")
        assertEquals("Rechnungsempfänger", recipientColumn.headerText)

        // the seller column is still separate from the recipient column
        val sellerColumn = g.getColumnByKey("seller")
        assertEquals("Rechnungsaussteller", sellerColumn.headerText)

        // no column carries the old combined header text anymore
        assertTrue(g.columns.none { it.headerText == "Anbieter / Kunde" })
    }

    // ── finding-fixes (2026-08-31, PO order) — "Nr." was a duplicate of "Rechnungsnummer" ────────

    @Test
    fun gridHasOnlyOneInvoiceNumberColumnNotTheOldDuplicateNrColumn() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        assertTrue(g.columns.none { it.headerText == "Nr." }, "the duplicate 'Nr.' column must be gone")
        assertEquals(1, g.columns.count { it.headerText == "Rechnungsnummer" }, "exactly one column must show the invoice number")

        val numberColumn = g.getColumnByKey("invoiceNumber")
        assertEquals("Rechnungsnummer", numberColumn.headerText)
        assertTrue(
            numberColumn.getSortOrder(com.vaadin.flow.data.provider.SortDirection.ASCENDING).anyMatch { it.sorted == "invoiceNumber" },
            "the surviving column must actually be sortable by invoiceNumber — the old 'Nr.' column claimed sortable=true but had no sort property and silently sorted by date instead",
        )
    }

    // ── finding-fixes (2026-08-31, PO order) — "10.000,00 €" must always be fully readable ───────

    private fun formatCurrency(view: InvoiceListView, amount: java.math.BigDecimal, currency: de.littletrotter.shared.Currency): String =
        view.gridColumns.formatCurrency(amount, currency)

    @Test
    fun grossAmountColumnIsWideEnoughForAFourDigitGermanAmount() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        val amountColumn = g.getColumnByKey("grossAmount")
        // Content-sized, never growing: a four-digit amount must not be clipped by a fixed width.
        assertTrue(amountColumn.isAutoWidth)
        assertEquals(0, amountColumn.flexGrow)

        val rendered = formatCurrency(view, java.math.BigDecimal("10000.00"), de.littletrotter.shared.Currency.EUR)
        // R5 (PO decision: money-currency-display) -- the currency glyph and its non-breaking
        // space are gone; the format is now the amount, an ordinary space, and the ISO code
        // ("EUR"), fixed regardless of runtime locale.
        assertEquals("10.000,00 EUR", rendered, "the full amount must render, not a clipped/rounded variant")
    }

    // ── UNAVAILABLE is its own amber face, distinct from red INVALID ─────────────────────────

    private fun buildValidationCell(view: InvoiceListView, vs: ValidationStatus, invoiceId: Long?): com.vaadin.flow.component.Component =
        view.gridColumns.buildValidationCell(vs, invoiceId)

    @Test
    fun validationTileUnavailableIsDistinctFromInvalid() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val invalidTile = buildValidationCell(view, ValidationStatus.INVALID, null)
        val unavailableTile = buildValidationCell(view, ValidationStatus.UNAVAILABLE, null)

        assertTrue(invalidTile.element.classList.contains("status-glyph--error"))
        assertTrue(unavailableTile.element.classList.contains("status-glyph--warn"))
        assertFalse(unavailableTile.element.classList.contains("status-glyph--error"))
        assertFalse(invalidTile.element.classList.contains("status-glyph--warn"))
        assertEquals("Ungültig", invalidTile.element.getAttribute("aria-label"))
        assertEquals("Nicht prüfbar", unavailableTile.element.getAttribute("aria-label"))
        assertEquals("", invalidTile.element.text)
    }

    // ── column-header legend popover ──────────────────────────────────────────────────────────

    @Test
    fun validationLegendOpensADialogWithAllFiveStatesAndOneSentenceEach() {
        // openValidationLegend moved out of InvoiceListView
        // into InvoiceListColumnLegends.kt (generalised onto the shared LegendDialog builder the
        // new Status column header now also uses), so this is a direct call, not reflection.
        openValidationLegend { key -> UiTranslations.tr(key) }

        val dialog = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoice.validation.legend.title"), dialog.headerTitle)

        val sentenceKeys = listOf("notValidated", "pending", "valid", "invalid", "unavailable")
        val renderedTexts = dialog.children.toList()
            .flatMap { component -> component.element.findComponentsText() }
        sentenceKeys.forEach { key ->
            val sentence = UiTranslations.tr("invoice.validation.legend.$key.sentence")
            assertTrue(renderedTexts.any { it == sentence }, "legend is missing the sentence for '$key': $sentence")
        }
    }

    /** Collects the text of every descendant [Span]/[NativeButton] under this element — used to
     *  read the legend dialog's content without depending on a specific layout nesting depth. */
    private fun com.vaadin.flow.dom.Element.findComponentsText(): List<String> {
        val texts = mutableListOf<String>()
        fun walk(el: com.vaadin.flow.dom.Element) {
            texts += el.text ?: ""
            el.children.forEach { walk(it) }
        }
        walk(this)
        return texts
    }

    // ── Both tile columns use the shared header component,
    //    their sort key/property is unchanged by the header swap ────────────────────────────────

    @Test
    fun statusColumnHeaderIsTheSharedTileColumnHeaderWithTheColumnWord() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        val header = g.getColumnByKey("status").headerComponent
        assertEquals(UiTranslations.tr("invoice.table.status"), header.element.getAttribute("title"))
        assertTrue(header.element.getAttribute("aria-label").isNotBlank())
        assertTrue(header.element.classList.contains("tile-column-header"))
    }

    @Test
    fun validationColumnHeaderIsTheSharedTileColumnHeaderWithTheColumnWord() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        val header = g.getColumnByKey("validationStatus").headerComponent
        assertEquals(UiTranslations.tr("invoice.table.validation"), header.element.getAttribute("title"))
        assertTrue(header.element.getAttribute("aria-label").isNotBlank())
        assertTrue(header.element.classList.contains("tile-column-header"))
    }

    @Test
    fun statusAndValidationColumnsKeepTheirOwnSortPropertyAfterTheHeaderSwap() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        val statusColumn = g.getColumnByKey("status")
        assertTrue(
            statusColumn.getSortOrder(com.vaadin.flow.data.provider.SortDirection.ASCENDING).anyMatch { it.sorted == "status" },
            "the status column's sort property must stay 'status', unaffected by the header component swap",
        )
        val validationColumn = g.getColumnByKey("validationStatus")
        assertTrue(
            validationColumn.getSortOrder(com.vaadin.flow.data.provider.SortDirection.ASCENDING).anyMatch { it.sorted == "validationStatus" },
            "the validationStatus column's sort property must stay 'validationStatus', unaffected by the header component swap",
        )
    }

    @Test
    fun statusAndValidationColumnsAreContentSizedAndFixed() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val g = grid(view)

        for (key in listOf("status", "validationStatus")) {
            val column = g.getColumnByKey(key)
            assertTrue(column.isAutoWidth, "the $key column is sized to its content")
            assertEquals(0, column.flexGrow, "the $key column takes no extra space")
        }
    }

    // ── chip click navigation target (callback-level, no CDI navigation) ───────────────────────

    @Test
    fun clickableChipNavigatesToTheInvoicesValidationFocus() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val chip = buildValidationCell(view, ValidationStatus.INVALID, 42L)

        // The tile must be a real <button> (native keyboard activation), not a static chip.
        assertTrue(chip is NativeButton, "a clickable row tile must be a NativeButton, was ${chip::class}")
        assertEquals("Ungültig — ${UiTranslations.tr("invoice.validation.openDetail")}", chip.element.getAttribute("aria-label"))

        // buildDetailNavigationTarget is `internal` (friend-module visible), so it's called
        // directly here — no reflection needed, unlike the private members above.
        val (routeParameters, queryParameters) = view.buildDetailNavigationTarget(42L, "validation")

        assertEquals("42", routeParameters.get("id").orElse(null))
        assertEquals(listOf("validation"), queryParameters.parameters["focus"])
    }

    @Test
    fun plainRowNavigationCarriesNoFocusParameter() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val (_, queryParameters) = view.buildDetailNavigationTarget(42L, null)

        assertFalse(queryParameters.parameters.containsKey("focus"))
    }

    // ── quick-filter chip row ─────────────────────────────────────────────────────────────────

    private fun quickFilterRow(view: InvoiceListView): Div {
        val field = InvoiceListView::class.java.getDeclaredField("quickFilterRow")
        field.isAccessible = true
        return field.get(view) as Div
    }

    private fun statusQuickFilter(view: InvoiceListView): de.littletrotter.domain.invoice.InvoiceStatus? {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("statusFilter")
        field.isAccessible = true
        return field.get(view.filterBar) as de.littletrotter.domain.invoice.InvoiceStatus?
    }

    private fun refreshTable(view: InvoiceListView) {
        val method = InvoiceListView::class.java.getDeclaredMethod("refreshTable", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(view, true)
    }

    @Test
    fun quickFiltersRenderNonZeroCountsAndHideZeroStates() {
        whenever(invoiceListQuery.countByStatus(any()))
            .thenReturn(
                mapOf(
                    de.littletrotter.domain.invoice.InvoiceStatus.DRAFT to 597L,
                    de.littletrotter.domain.invoice.InvoiceStatus.ISSUED to 12L,
                    de.littletrotter.domain.invoice.InvoiceStatus.CANCELLED to 0L,
                ),
            )

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        refreshTable(view)

        val chips = quickFilterRow(view).children.toList().filterIsInstance<NativeButton>()
        assertEquals(2, chips.size, "CANCELLED has zero count and must not render a chip")
        assertTrue(chips.any { it.text == "Entwurf 597" }, "actual: ${chips.map { it.text }}")
        assertTrue(chips.any { it.text == "Versendet 12" }, "actual: ${chips.map { it.text }}")
    }

    @Test
    fun clickingAQuickFilterChipTogglesTheStatusFilterAndRequeriesTheProvider() {
        whenever(invoiceListQuery.countByStatus(any()))
            .thenReturn(mapOf(de.littletrotter.domain.invoice.InvoiceStatus.ISSUED to 5L))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        refreshTable(view)

        val toggle = InvoiceListView::class.java.getDeclaredMethod("toggleStatusFilter", de.littletrotter.domain.invoice.InvoiceStatus::class.java)
        toggle.isAccessible = true
        toggle.invoke(view, de.littletrotter.domain.invoice.InvoiceStatus.ISSUED)

        assertEquals(de.littletrotter.domain.invoice.InvoiceStatus.ISSUED, statusQuickFilter(view))
        // the data provider must be re-queried with the toggled filter, not just the UI re-drawn
        verify(invoiceListQuery, times(1)).findPage(org.mockito.kotlin.argThat<InvoiceExportFilter> { status == de.littletrotter.domain.invoice.InvoiceStatus.ISSUED }, any())

        val activeChip = quickFilterRow(view).children.toList().filterIsInstance<NativeButton>().first { it.text == "Versendet 5" }
        assertTrue(activeChip.hasClassName("quick-filter-chip--active"), "the toggled-on chip must carry the active modifier class")

        // second click un-toggles — the provider is re-queried with status=null again, same as the
        // very first refreshTable(view) call above (hence times(2), not times(1)).
        toggle.invoke(view, de.littletrotter.domain.invoice.InvoiceStatus.ISSUED)
        assertNull(statusQuickFilter(view))
        verify(invoiceListQuery, times(2)).findPage(org.mockito.kotlin.argThat<InvoiceExportFilter> { status == null }, any())
    }

    @Test
    fun refreshTableIssuesExactlyOneBatchedGroupedCountQueryNeverOnePerStatus() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        refreshTable(view)

        // The whole point of the batched query: one GROUP BY backs every chip's count, not N
        // calls to countInvoiceOverview (one per InvoiceStatus) — that N+1 shape is exactly what
        // this pins against.
        verify(invoiceListQuery, times(1)).countByStatus(any())
    }

    // ── Kunde/Anbieter/Zeitraum filters ───────────────────────────────────────────────────────

    private fun recipientFilter(view: InvoiceListView): com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.application.dto.RecipientSummaryDto> {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("recipientFilter")
        field.isAccessible = true
        return field.get(view.filterBar).requireAs()
    }

    private fun senderFilter(view: InvoiceListView): com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.application.dto.SenderSummaryDto> {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("senderFilter")
        field.isAccessible = true
        return field.get(view.filterBar).requireAs()
    }

    private fun dateFromFilter(view: InvoiceListView): com.vaadin.flow.component.datepicker.DatePicker {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("dateFromFilter")
        field.isAccessible = true
        return field.get(view.filterBar) as com.vaadin.flow.component.datepicker.DatePicker
    }

    private fun dateToFilter(view: InvoiceListView): com.vaadin.flow.component.datepicker.DatePicker {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("dateToFilter")
        field.isAccessible = true
        return field.get(view.filterBar) as com.vaadin.flow.component.datepicker.DatePicker
    }

    private fun yearQuickPick(view: InvoiceListView): com.vaadin.flow.component.combobox.ComboBox<Int> {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("yearQuickPick")
        field.isAccessible = true
        return field.get(view.filterBar).requireAs()
    }

    private fun recipientOf(id: Long, name: String) = de.littletrotter.application.dto.RecipientSummaryDto(
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

    private fun senderOf(id: Long, name: String, isDefault: Boolean = false) = de.littletrotter.application.dto.SenderSummaryDto(
        id = id,
        name = name,
        vatId = null,
        isDefault = isDefault,
        billingAddressCity = null,
    )

    @Test
    fun toolbarRendersRecipientSenderDateRangeAndYearQuickPickControls() {
        // Additive widening: recipientFilter is now a lazy combo (findRecipients/
        // countRecipients), findAllRecipients() alone no longer feeds its data provider.
        // findRecipients has 5 params with defaults (pageIndex, pageSize, filterText, sortField,
        // sortDir) — every one of them must be supplied as a matcher or Kotlin's default-argument
        // bridge desyncs Mockito's matcher stack (same caveat already documented on
        // countInvoiceOverviewGroupedByValidationStatus's own stub above).
        whenever(recipientQuery.findRecipients(any(), any(), anyOrNull(), any(), any())).thenReturn(listOf(recipientOf(1L, "Acme Corp")))
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(senderOf(2L, "Seller One")))
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2026, 2025))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val recipientItems = recipientFilter(view).dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()
        val senderItems = senderFilter(view).dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()
        val yearItems = yearQuickPick(view).dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList()

        assertEquals(listOf("Acme Corp"), recipientItems.map { it.name })
        assertEquals(listOf("Seller One"), senderItems.map { it.name })
        assertEquals(listOf(2026, 2025), yearItems)
        assertTrue(dateFromFilter(view).isClearButtonVisible)
        assertTrue(dateToFilter(view).isClearButtonVisible)
    }

    // ── recipientFilter is now a lazy combo (RecipientQueryPort.findRecipients/
    // countRecipients), not findAllRecipients()'s capped-at-60 eager
    // list. These drive the combo's own DataProvider with a typed filter — exactly how the
    // ComboBox itself queries once wired via lazyItems — as the regression test proving the old
    // cap's bug is gone. ─────────────────────────────────────────────────────────────────────────

    private fun recipientFilterDataProvider(view: InvoiceListView): com.vaadin.flow.data.provider.DataProvider<de.littletrotter.application.dto.RecipientSummaryDto, String> =
        recipientFilter(view).dataProvider.requireAs()

    private fun recipientQuery(offset: Int = 0, limit: Int = 60, filter: String? = null) =
        com.vaadin.flow.data.provider.Query<de.littletrotter.application.dto.RecipientSummaryDto, String>(offset, limit, emptyList(), null, filter)

    @Test
    fun `recipientFilter's lazy data provider finds a recipient beyond the old 60-row cap via a typed filter`() {
        // Alphabetically the 61st customer — findAllRecipients()'s old page-0-of-60 fetch could
        // never have returned this, no matter what the user typed; that is the whole point.
        val beyondCap = recipientOf(161L, "Zeta Logistik #61")
        whenever(recipientQuery.findRecipients(0, 60, "Zeta")).thenReturn(listOf(beyondCap))
        whenever(recipientQuery.countRecipients("Zeta")).thenReturn(1L)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val result = recipientFilterDataProvider(view).fetch(recipientQuery(filter = "Zeta")).toList()
        val size = recipientFilterDataProvider(view).size(recipientQuery(filter = "Zeta"))

        assertEquals(listOf(beyondCap), result)
        assertEquals(1, size)
        verify(recipientQuery).findRecipients(0, 60, "Zeta")
        verify(recipientQuery).countRecipients("Zeta")
    }

    @Test
    fun `recipientFilter's lazy data provider returns nothing for a filter with no match, without falling back to the eager list`() {
        whenever(recipientQuery.findRecipients(0, 60, "Nichts-passt")).thenReturn(emptyList())
        whenever(recipientQuery.countRecipients("Nichts-passt")).thenReturn(0L)
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val result = recipientFilterDataProvider(view).fetch(recipientQuery(filter = "Nichts-passt")).toList()
        val size = recipientFilterDataProvider(view).size(recipientQuery(filter = "Nichts-passt"))

        assertTrue(result.isEmpty())
        assertEquals(0, size)
    }

    @Test
    fun `recipientFilter's lazy data provider normalises a blank typed filter to the unfiltered fetch`() {
        val recipient = recipientOf(5L, "Acme")
        whenever(recipientQuery.findRecipients(0, 60, null)).thenReturn(listOf(recipient))
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val result = recipientFilterDataProvider(view).fetch(recipientQuery(filter = "   ")).toList()

        assertEquals(listOf(recipient), result)
        verify(recipientQuery).findRecipients(0, 60, null)
    }

    @Test
    fun selectingAYearQuickPickSetsVonAndBisToJanFirstAndDecThirtyFirst() {
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2025))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        yearQuickPick(view).value = 2025

        assertEquals(java.time.LocalDate.of(2025, 1, 1), dateFromFilter(view).value)
        assertEquals(java.time.LocalDate.of(2025, 12, 31), dateToFilter(view).value)
    }

    @Test
    fun defaultSortWithoutUrlParamsIsInvoiceDateDescending() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)

        val sortFieldField = InvoiceListView::class.java.getDeclaredField("currentSortField")
        sortFieldField.isAccessible = true
        val sortDirField = InvoiceListView::class.java.getDeclaredField("currentSortDir")
        sortDirField.isAccessible = true

        assertEquals(de.littletrotter.application.dto.InvoiceOverviewSortField.INVOICE_DATE, sortFieldField.get(view))
        assertEquals(de.littletrotter.application.dto.SortDirection.DESC, sortDirField.get(view))
    }

    @Test
    fun withCurrentStateRoundTripsRecipientSenderAndDateRangeThroughTheUrl() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(senderOf(2L, "Seller One")))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        recipientFilter(view).value = recipientOf(1L, "Acme Corp")
        senderFilter(view).value = senderOf(2L, "Seller One")
        dateFromFilter(view).value = java.time.LocalDate.of(2025, 3, 1)
        dateToFilter(view).value = java.time.LocalDate.of(2025, 6, 30)

        val (_, queryParameters) = view.buildDetailNavigationTarget(42L, null)

        assertEquals(listOf("1"), queryParameters.parameters["recipientId"])
        assertEquals(listOf("2"), queryParameters.parameters["senderProfileId"])
        assertEquals(listOf("2025-03-01"), queryParameters.parameters["dateFrom"])
        assertEquals(listOf("2025-06-30"), queryParameters.parameters["dateTo"])
    }

    // ── session-held filter/sort state ────────────────────────────────────────────────────────

    private fun filterField(view: InvoiceListView): com.vaadin.flow.component.textfield.TextField {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("filterField")
        field.isAccessible = true
        return field.get(view.filterBar) as com.vaadin.flow.component.textfield.TextField
    }

    private fun validationFilter(view: InvoiceListView): com.vaadin.flow.component.combobox.ComboBox<ValidationStatus> {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("validationFilter")
        field.isAccessible = true
        return field.get(view.filterBar).requireAs()
    }

    private fun sessionFilterState(): InvoiceListFilterState? =
        com.vaadin.flow.server.VaadinSession.getCurrent()?.getAttribute(InvoiceListFilterState::class.java)

    @Test
    fun everyRefreshPersistsTheCurrentFilterSetIntoTheSession() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        recipientFilter(view).value = recipientOf(1L, "Acme Corp")
        dateFromFilter(view).value = java.time.LocalDate.of(2025, 3, 1)

        val state = sessionFilterState()
        assertEquals(1L, state?.recipientId)
        assertEquals(java.time.LocalDate.of(2025, 3, 1), state?.dateFrom)
    }

    /**
     * PO: "Ich selektiere bei Rechnung und gehe weiter und verliere die Einstellungen." —
     * navigating in via the menu link (no query string at all, unlike the round trip through
     * [InvoiceDetailView]'s own "back to list" button) must still restore the last-used filters,
     * from the session. [InvoiceListView.restoreFilterState] is called directly with an empty
     * [QueryParameters] here, standing in for that menu-link navigation (a real router round trip
     * to a CDI-injected view isn't drivable in this test setup — see [buildDetailNavigationTarget]'s
     * KDoc for the same constraint on that method).
     */
    @Test
    fun reenteringWithNoRelevantUrlParamsRestoresFiltersFromTheSession() {
        whenever(recipientQuery.findRecipientById(1L)).thenReturn(recipientOf(1L, "Acme Corp"))

        val viewA = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        recipientFilter(viewA).value = recipientOf(1L, "Acme Corp")
        dateFromFilter(viewA).value = java.time.LocalDate.of(2025, 3, 1)
        dateToFilter(viewA).value = java.time.LocalDate.of(2025, 6, 30)

        // A fresh view instance stands in for "navigate away, then back" — same session, no query
        // string, exactly what the menu link produces.
        val viewB = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        viewB.restoreFilterState(QueryParameters.empty())

        assertEquals(1L, recipientFilter(viewB).value?.id)
        assertEquals(java.time.LocalDate.of(2025, 3, 1), dateFromFilter(viewB).value)
        assertEquals(java.time.LocalDate.of(2025, 6, 30), dateToFilter(viewB).value)
    }

    @Test
    fun explicitUrlParamsOverrideTheSessionHeldStateEntirely() {
        whenever(recipientQuery.findRecipientById(1L)).thenReturn(recipientOf(1L, "Acme Corp"))
        whenever(recipientQuery.findRecipientById(2L)).thenReturn(recipientOf(2L, "Beta Ltd"))

        val viewA = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        recipientFilter(viewA).value = recipientOf(1L, "Acme Corp")
        dateFromFilter(viewA).value = java.time.LocalDate.of(2025, 3, 1)

        val viewB = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        viewB.restoreFilterState(QueryParameters.simple(mapOf("recipientId" to "2")))

        assertEquals(2L, recipientFilter(viewB).value?.id, "an explicit URL recipientId must win over the session-held recipientId=1")
        assertNull(dateFromFilter(viewB).value, "the URL carrying any relevant param means the session state is not consulted at all, not merged field-by-field")
    }

    // ── toolbar row restructuring ("Neue Rechnung" first, filter row set off) ─────────────────

    private fun toolbarRows(view: InvoiceListView): List<com.vaadin.flow.component.Component> {
        val toolbarDiv = view._get<Div> { classes = "ino-toolbar" }
        return toolbarDiv.children.toList()
    }

    @Test
    fun neueRechnungButtonSitsRightInThePageHeaderActionsNotInTheFirstToolbarRow() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val shell = view.children.toList().filterIsInstance<de.littletrotter.ui.component.PageShell>().single()
        val newButton = shell.actions.children.toList().single() as com.vaadin.flow.component.button.Button
        assertEquals("Neue Rechnung", newButton.text)
        assertTrue(newButton.themeNames.contains("primary"))

        val row1 = toolbarRows(view)[0] as com.vaadin.flow.component.orderedlayout.HorizontalLayout
        assertTrue(row1.children.toList().none { it is com.vaadin.flow.component.button.Button && it.text == "Neue Rechnung" })
    }

    @Test
    fun pageTitleIsTheInvoicesNavLabel() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val title = view._get<com.vaadin.flow.component.html.H2> { classes = "lt-page-shell__title" }
        assertEquals(UiTranslations.tr(view, "nav.invoices"), title.text)
    }

    @Test
    fun secondToolbarRowIsTheTintedWrappingFilterRow() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        val row2 = toolbarRows(view)[1] as com.vaadin.flow.component.orderedlayout.HorizontalLayout

        assertTrue(row2.hasClassName("invoice-filter-row"), "the filter row must carry the tinted-background class")
        assertTrue(row2.hasClassName("toolbar-wrap"), "the filter row must wrap on narrow windows instead of overflowing")
    }

    // ── active-filter counter/reset chip ──────────────────────────────────────────────────────

    private fun activeFilterChip(view: InvoiceListView): NativeButton {
        val field = InvoiceListFilterBar::class.java.getDeclaredField("activeFilterChip")
        field.isAccessible = true
        return field.get(view.filterBar) as NativeButton
    }

    private fun clearAllFilters(view: InvoiceListView) {
        val method = InvoiceListView::class.java.getDeclaredMethod("clearAllFilters")
        method.isAccessible = true
        method.invoke(view)
    }

    @Test
    fun activeFilterChipIsHiddenWithNoFiltersAndShowsTheCorrectCountOnceFiltersAreSet() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        refreshTable(view)
        val chip = activeFilterChip(view)
        assertFalse(chip.isVisible, "no filter is active yet")

        filterField(view).value = "ACME"
        validationFilter(view).value = ValidationStatus.VALID
        recipientFilter(view).value = recipientOf(1L, "Acme Corp")

        assertTrue(chip.isVisible)
        assertEquals("3 Filter aktiv · zurücksetzen", chip.text)
    }

    @Test
    fun clearingAllFiltersResetsEveryFieldAndTheSessionHeldStateInOneShot() {
        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        filterField(view).value = "ACME"
        validationFilter(view).value = ValidationStatus.VALID
        recipientFilter(view).value = recipientOf(1L, "Acme Corp")
        assertEquals(1L, sessionFilterState()?.recipientId, "sanity check: the filter must have reached the session before clearing")

        clearAllFilters(view)

        assertEquals("", filterField(view).value)
        assertNull(validationFilter(view).value)
        assertNull(recipientFilter(view).value)
        assertFalse(activeFilterChip(view).isVisible)
        assertNull(sessionFilterState()?.recipientId, "clearing filters must clear the session-held state too")
        assertNull(sessionFilterState()?.status)
    }

    // ── spec-default-seller-preselect — "Neue Rechnung" dialog: seller ComboBox ─────────────────

    private fun openInvoiceCreateDialog(view: InvoiceListView) {
        val method = InvoiceListView::class.java.getDeclaredMethod("openInvoiceCreateDialog")
        method.isAccessible = true
        method.invoke(view)
    }

    private fun dialogForm(dialog: Dialog): com.vaadin.flow.component.formlayout.FormLayout =
        dialog.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()

    private fun dialogSellerCombo(dialog: Dialog): com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.application.dto.SenderSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()[0].requireAs()

    private fun dialogRecipientCombo(dialog: Dialog): com.vaadin.flow.component.combobox.ComboBox<de.littletrotter.application.dto.RecipientSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<com.vaadin.flow.component.combobox.ComboBox<*>>()[1].requireAs()

    /** [Dialog.DialogFooter] attaches its children as a *virtual* child element, which plain
     *  `Element.getChildren()`/`HasComponentsOfType.getChildren()` traversal does not see at all —
     *  Karibu's own [_get] search does (it is built to look inside Dialog headers/footers), so the
     *  create button is located through it instead of manual tree-walking. */
    private fun dialogCreateButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("invoice.dialog.create") }

    private fun minimalInvoiceDetailDto(id: Long = 99L) = de.littletrotter.application.dto.InvoiceDetailDto(
        id = id,
        invoiceNumber = "INV-2026-00099",
        status = de.littletrotter.domain.invoice.InvoiceStatus.DRAFT.ordinal,
        invoiceDate = java.time.LocalDate.now(),
        netAmount = de.littletrotter.shared.Money.ZERO,
        vatAmount = de.littletrotter.shared.Money.ZERO,
        grossAmount = de.littletrotter.shared.Money.ZERO,
        sender = null,
        recipient = null,
        items = emptyList(),
        latestGenerationJob = null,
    )

    @Test
    fun `the default seller is preselected while every active seller stays selectable`() {
        val other = senderOf(1L, "Acme")
        val default = senderOf(2L, "Beta", isDefault = true)
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(other, default))

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        val sellerCombo = dialogSellerCombo(dialog)

        assertEquals(default, sellerCombo.value, "the default seller must be preselected")
        assertEquals(
            listOf(other, default),
            sellerCombo.dataProvider.fetch(com.vaadin.flow.data.provider.Query()).toList(),
            "every active seller must remain selectable, not just the default",
        )
    }

    @Test
    fun `selecting a different seller passes that seller's id to createInvoiceWithRecipient, not the default's`() {
        val default = senderOf(1L, "Acme", isDefault = true)
        val other = senderOf(2L, "Beta")
        val recipient = recipientOf(10L, "Kunde GmbH")
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(default, other))
        // invoice-from-customer-row (spec amendment) — InvoiceCreateDialog's combo/create-button
        // enable check reads the true count via countRecipients, never findAllRecipients' length.
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenReturn(minimalInvoiceDetailDto())

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        dialogSellerCombo(dialog).value = other
        dialogRecipientCombo(dialog).value = recipient
        dialogCreateButton(dialog).click()

        verify(createInvoiceUseCase, times(1)).createInvoiceWithRecipient(eq(2L), eq(10L), anyOrNull())
    }

    @Test
    fun `no seller profile at all keeps the blocking early exit with the reworded message`() {
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        assertEquals(
            UiTranslations.tr("invoice.dialog.noSeller"),
            dialog.children.toList().flatMap { it.element.findComponentsText() }.first { it.isNotBlank() },
        )
        assertTrue(
            dialog.children.toList().none { it is com.vaadin.flow.component.formlayout.FormLayout },
            "the blocking dialog must not render the seller/recipient form at all",
        )
    }

    @Test
    fun `several profiles without a default preselect nothing and the create button requires a choice`() {
        val first = senderOf(1L, "Acme")
        val second = senderOf(2L, "Beta")
        val recipient = recipientOf(10L, "Kunde GmbH")
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(first, second))
        whenever(recipientQuery.countRecipients(anyOrNull())).thenReturn(1L)

        val view = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE)
        openInvoiceCreateDialog(view)

        val dialog = _get<Dialog>()
        val sellerCombo = dialogSellerCombo(dialog)
        assertNull(sellerCombo.value, "with several profiles and no default, nothing must be preselected")

        dialogRecipientCombo(dialog).value = recipient
        dialogCreateButton(dialog).click()

        verify(createInvoiceUseCase, org.mockito.kotlin.never()).createInvoiceWithRecipient(any(), any(), anyOrNull())
    }
}
