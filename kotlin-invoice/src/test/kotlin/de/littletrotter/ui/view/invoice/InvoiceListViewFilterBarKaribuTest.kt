package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.router.QueryParameters
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
import de.littletrotter.ui.format.DateFormatter
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceListFilterBar] in isolation: the three representations of the filter state
 * (query snapshot, URL parameters, session state), the scope sentence of the export dialog and
 * the reset chip. The facade is mocked; a detached [InvoiceListView] only supplies translations.
 */
class InvoiceListViewFilterBarKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()
    private var changes = 0
    private var clears = 0

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val translations by lazy { InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE) }

    private fun newBar() = InvoiceListFilterBar(recipientQuery, senderQuery, invoiceListQuery, translations, { changes++ }, { clears++ })

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

    private fun senderOf(id: Long, name: String) = SenderSummaryDto(
        id = id,
        name = name,
        vatId = null,
        isDefault = false,
        billingAddressCity = null,
    )

    private fun urlParams(bar: InvoiceListFilterBar): Map<String, String> =
        buildMap { bar.writeQueryParameters { key, value -> put(key, value) } }

    private fun fillEveryFilter(bar: InvoiceListFilterBar) {
        bar.filterField.value = "Acme"
        bar.statusFilter = InvoiceStatus.SENT
        bar.reverseChargeFilter.value = true
        bar.validationFilter.value = ValidationStatus.INVALID
        bar.recipientFilter.value = recipientOf(5L, "Acme GmbH")
        bar.senderFilter.value = senderOf(6L, "Haupt")
        bar.dateFromFilter.value = LocalDate.of(2026, 1, 1)
        bar.dateToFilter.value = LocalDate.of(2026, 3, 31)
    }

    @Test
    fun `happy path - every filter field lands in the query snapshot, the URL parameters and the session state`() {
        val bar = newBar()
        fillEveryFilter(bar)

        val filter = bar.currentFilter()
        assertEquals("Acme", filter.filterText)
        assertEquals(InvoiceStatus.SENT, filter.status)
        assertEquals(true, filter.reverseCharge)
        assertEquals(ValidationStatus.INVALID, filter.validationStatus)
        assertEquals(5L, filter.recipientId)
        assertEquals(6L, filter.senderProfileId)
        assertEquals(LocalDate.of(2026, 1, 1), filter.dateFrom)
        assertEquals(LocalDate.of(2026, 3, 31), filter.dateTo)

        assertEquals(
            mapOf(
                "filter" to "Acme", "status" to "SENT", "reverseCharge" to "true", "validationStatus" to "INVALID",
                "recipientId" to "5", "senderProfileId" to "6", "dateFrom" to "2026-01-01", "dateTo" to "2026-03-31",
            ),
            urlParams(bar),
        )
        assertEquals(
            InvoiceListFilterState("Acme", InvoiceStatus.SENT, true, ValidationStatus.INVALID, 5L, 6L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
            bar.currentFilterState(),
        )

        bar.refreshActiveFilterChip()
        assertTrue(bar.activeFilterChip.isVisible)
        assertEquals(translations.tr("invoice.filter.active", 8), bar.activeFilterChip.text)
    }

    @Test
    fun `happy path - the export scope sentence names every active filter`() {
        val bar = newBar()
        fillEveryFilter(bar)

        val scope = bar.currentScopeDescription()

        val parts = scope.split("; ")
        assertEquals(7, parts.size, "text, status, validation, reverse charge, recipient, sender, date range: $scope")
        assertTrue(parts.contains(translations.tr("invoiceListExport.scope.text", "Acme")), scope)
        assertTrue(parts.contains(translations.tr("invoiceListExport.scope.recipient", "Acme GmbH")), scope)
        assertTrue(parts.contains(translations.tr("invoiceListExport.scope.sender", "Haupt")), scope)
        assertTrue(
            parts.contains(
                translations.tr(
                    "invoiceListExport.scope.dateRange",
                    DateFormatter.format(LocalDate.of(2026, 1, 1)),
                    DateFormatter.format(LocalDate.of(2026, 3, 31)),
                ),
            ),
            scope,
        )
    }

    @Test
    fun `boundary - search text that looks like SQL or LIKE wildcards stays literal in the query and the URL`() {
        val injection = "%' OR 1=1; DROP TABLE invoice; --_"
        val bar = newBar()

        bar.filterField.value = "  $injection  "

        assertEquals(injection, bar.currentFilter().filterText, "only trimmed, never escaped or rewritten")
        assertEquals(injection, urlParams(bar)["filter"])

        val restored = newBar()
        restored.applyQueryParameters(QueryParameters.simple(mapOf("filter" to injection)))
        assertEquals(injection, restored.filterField.value)
        assertEquals(injection, restored.currentFilter().filterText)
    }

    @Test
    fun `boundary - whitespace-only search text is no filter at all`() {
        val bar = newBar()

        bar.filterField.value = "   "
        bar.refreshActiveFilterChip()

        assertNull(bar.currentFilter().filterText)
        assertFalse(urlParams(bar).containsKey("filter"))
        assertFalse(bar.activeFilterChip.isVisible, "a blank search must not count as an active filter")
        assertEquals(translations.tr("invoiceListExport.scope.all"), bar.currentScopeDescription())
    }

    @Test
    fun `boundary - a half-open date range describes only the bound that is set`() {
        val from = LocalDate.of(2026, 2, 1)
        val bar = newBar()

        bar.dateFromFilter.value = from
        assertEquals(translations.tr("invoiceListExport.scope.dateFrom", DateFormatter.format(from)), bar.currentScopeDescription())

        bar.dateFromFilter.value = null
        bar.dateToFilter.value = from
        assertEquals(translations.tr("invoiceListExport.scope.dateTo", DateFormatter.format(from)), bar.currentScopeDescription())
    }

    @Test
    fun `boundary - URL restore resolves a known sender and ignores an unknown recipient id`() {
        whenever(senderQuery.findSenderById(6L)).thenReturn(senderOf(6L, "Haupt"))
        whenever(recipientQuery.findRecipientById(404L)).thenReturn(null)
        val bar = newBar()

        bar.applyQueryParameters(QueryParameters.simple(mapOf("senderProfileId" to "6", "recipientId" to "404", "reverseCharge" to "false")))

        assertEquals(6L, bar.senderFilter.value?.id)
        assertNull(bar.recipientFilter.value, "a deleted recipient must not leave a phantom selection")
        assertEquals(false, bar.reverseChargeFilter.value)
    }

    @Test
    fun `boundary - the reset chip delegates to the view and clear empties every field`() {
        whenever(invoiceListQuery.availableYears(anyOrNull(), anyOrNull())).thenReturn(listOf(2026))
        val bar = newBar()
        fillEveryFilter(bar)
        bar.yearQuickPick.value = 2026
        bar.refreshActiveFilterChip()

        bar.activeFilterChip._click()
        assertEquals(1, clears, "the chip hands the reset to the view, which suppresses intermediate refreshes")

        bar.clear()
        assertEquals(InvoiceListFilterState(), bar.currentFilterState())
        assertNull(bar.yearQuickPick.value)
        assertTrue(urlParams(bar).isEmpty())
        assertTrue(changes > 0, "field edits notify the view")
    }
}
