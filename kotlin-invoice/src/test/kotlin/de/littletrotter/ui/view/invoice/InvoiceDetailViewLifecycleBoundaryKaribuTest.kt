package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.settings.FeatureFlag
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** [InvoiceDetailView]'s lifecycle edges: settings gate, refresh while mid-edit, focus
 *  parameter during entry mode, a job event before any invoice is loaded, and the entry pane's
 *  own "Done" exit. */
class InvoiceDetailViewLifecycleBoundaryKaribuTest {

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
        DirectPollTicks.restore()
    }

    private fun beforeEnter(view: InvoiceDetailView, id: Long, query: String) {
        val event = BeforeEnterEvent(
            VaadinService.getCurrent().router, NavigationTrigger.PROGRAMMATIC,
            Location("invoice/$id", QueryParameters.fromString(query)), InvoiceDetailView::class.java,
            RouteParameters("id", id.toString()), UI.getCurrent(), emptyList(),
        )
        view.beforeEnter(event)
    }

    @Test
    fun `happy path - the per-diem affordances follow the settings gate`() {
        val gate: FeatureSettingsGate = mock()
        whenever(gate.isEnabled(FeatureFlag.PER_DIEM_UI)).thenReturn(false)

        // perDiemUiEnabled: moved off InvoiceDetailView into TripDialogLauncher (the only
        // reader — see its own KDoc); the harness's Positionen tab wires the same gate through.
        val harness = DetailViewTestHarness(featureSettingsGate = gate)

        assertFalse(
            harness.invoiceItemsTab.invoiceItemDialogs.tripDialogLauncher.perDiemUiEnabled,
            "a switched-off PER_DIEM_UI flag hides per-diem creation",
        )
    }

    @Test
    fun `boundary - a data refresh while the overview is mid-edit keeps the entry workspace`() {
        val harness = DetailViewTestHarness()
        val view = harness.view
        val invoice = DetailViewTestHarness.dto()
        view.renderInvoice(invoice)
        view.enterEntryMode(invoice)

        val reloaded = invoice.copy(invoiceNumber = "INV-1b")
        view.refreshAfterDataChange(reloaded)

        assertNull(view.detailTabs.tabSheet, "no tabs are rebuilt while the entry workspace is shown")
        assertEquals("INV-1b", view.invoice?.invoiceNumber)
    }

    @Test
    fun `boundary - the validation focus parameter is ignored while the view is mid-edit`() {
        val harness = DetailViewTestHarness()
        whenever(harness.invoiceDetailQueryPort.findInvoiceDetailDto(1L)).thenReturn(DetailViewTestHarness.dto())
        harness.view.overviewEditMode = true

        beforeEnter(harness.view, 1L, "focus=validation")

        assertNull(harness.view.detailTabs.tabSheet, "the entry workspace has no tabs to focus")
        assertTrue(harness.view.overviewEditMode)
    }

    @Test
    fun `boundary - a job-finished event without an invoice id reaching an empty view is ignored`() {
        val harness = DetailViewTestHarness()
        UI.getCurrent().add(harness.view)

        harness.uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = 5L, invoiceId = null, success = true))
        DirectPollTicks.drain()

        verify(harness.invoiceDetailQueryPort, never()).findInvoiceDetailDto(any())
    }

    @Test
    fun `boundary - without the audit journal service no journal panel is built`() {
        val harness = DetailViewTestHarness(auditJournalQueryService = null)

        assertNull(harness.view.detailTabs.buildJournalPanel(DetailViewTestHarness.dto()))
    }

    @Test
    fun `boundary - the entry pane's done button without changes leaves entry mode`() {
        val harness = DetailViewTestHarness()
        val view = harness.view
        val invoice = DetailViewTestHarness.dto()
        view.renderInvoice(invoice)
        view.invoice = invoice
        view.enterEntryMode(invoice)
        UI.getCurrent().add(view)

        DetailViewTestHarness.flatten(view as Component).filterIsInstance<Button>()
            .single { it.text == UiTranslations.tr("invoiceDetail.overview.done") }
            .click()

        assertFalse(view.overviewEditMode)
        assertTrue(view.detailTabs.tabSheet != null, "the document view with its tabs is back")
    }
}
