package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.HasErrorParameter
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceListNavigator] in isolation: targets carry the list state, the validation tile
 * adds its focus, and a missing or stale row never navigates.
 */
class InvoiceListViewNavigatorKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()
    private val invoiceListQuery: InvoiceListQueryPort = mock()
    private val uiEventBus: UiEventBus = mock()
    private var staleRefreshes = 0
    private var state: Map<String, List<String>> = mapOf("filter" to listOf("Acme"), "page" to listOf("2"))

    @BeforeEach
    fun setup() {
        val routes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java)
        val errorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(routes, errorRoutes, true))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newNavigator() = InvoiceListNavigator(
        recipientQuery = recipientQuery,
        senderQuery = senderQuery,
        createInvoiceUseCase = createInvoiceUseCase,
        invoiceListQuery = invoiceListQuery,
        translations = InvoiceListView(recipientQuery, senderQuery, createInvoiceUseCase, invoiceListQuery, uiEventBus, time = FIXED_TIME_SOURCE),
        currentQueryParameters = { QueryParameters(state) },
        onStaleRow = { staleRefreshes++ },
    )

    private fun row(id: Long?) = InvoiceOverviewDto(
        id = id,
        invoiceNumber = "RE-2026-00001",
        typeCode = null,
        sellerName = null,
        sellerCity = null,
        recipientName = null,
        grossAmount = null,
        paymentLabel = null,
        freeText = null,
        itemsCount = 0,
        invoiceDate = null,
        status = InvoiceStatus.ISSUED,
        validationStatus = ValidationStatus.VALID,
    )

    @Test
    fun `happy path - the validation tile navigates to the detail view with focus and the list state`() {
        val ui = UI.getCurrent()!!

        newNavigator().openValidation(42L)

        assertEquals("invoice/42", ui.internals.activeViewLocation.path)
        val params = ui.internals.activeViewLocation.queryParameters.parameters
        assertEquals(listOf("validation"), params["focus"])
        assertEquals(listOf("Acme"), params["filter"])
        assertEquals(listOf("2"), params["page"])
    }

    @Test
    fun `boundary - a row without id shows an error notification and neither navigates nor refreshes`() {
        val ui = UI.getCurrent()!!
        val before = ui.internals.activeViewLocation.path

        newNavigator().openRow(row(id = null))

        assertEquals(before, ui.internals.activeViewLocation.path)
        assertEquals(1, getNotifications().size)
        assertEquals(0, staleRefreshes)
        verify(invoiceListQuery, never()).exists(any())
    }

    @Test
    fun `boundary - a row deleted since load surfaces as an error notification and triggers one refresh`() {
        whenever(invoiceListQuery.exists(9L)).thenReturn(false)
        val ui = UI.getCurrent()!!
        val before = ui.internals.activeViewLocation.path

        newNavigator().openRow(row(id = 9L))

        assertEquals(before, ui.internals.activeViewLocation.path, "a stale row must never navigate")
        assertEquals(1, getNotifications().size)
        assertEquals(1, staleRefreshes)
    }

    @Test
    fun `boundary - a plain target carries no focus and passes an injection-like filter through literally`() {
        state = mapOf("filter" to listOf("%'; DROP TABLE invoice; --"))

        val (route, query) = newNavigator().detailTarget(5L, null)

        assertEquals("5", route.get("id").orElse(null))
        assertFalse(query.parameters.containsKey("focus"))
        assertEquals(listOf("%'; DROP TABLE invoice; --"), query.parameters["filter"])
    }
}
