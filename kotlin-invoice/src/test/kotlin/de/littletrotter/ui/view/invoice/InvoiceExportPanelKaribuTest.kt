package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.application.dto.InvoiceDetailDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import de.littletrotter.test.FIXED_TIME_SOURCE

/** [InvoiceExportPanel] is pure composition: it owns no rendering logic of its own,
 *  only delegates to [InvoicePrintControls]/[InvoiceGenerationJobsPanel]. Both sub-panels are
 *  real instances (mocked leaf ports), Mockito-spied so these tests can verify delegation without
 *  re-testing their own render logic (covered by their own dedicated test suites) — a bare
 *  Mockito `mock()` of a Vaadin `Component` subclass skips the real constructor entirely, leaving
 *  its `Element` null and crashing on `add()`; `spy()` wraps a genuinely constructed instance. */
class InvoiceExportPanelKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun printControls(): InvoicePrintControls = spy(
        InvoicePrintControls(
            invoiceDetailQueryPort = mock(),
            recipientQueryPort = mock(),
            generationJobCreationService = mock(),
            documentJobPublisher = mock(),
            llmCheckAvailability = LlmCheckAvailability(),
        ),
    )

    private fun jobsPanel(): InvoiceGenerationJobsPanel = spy(
        InvoiceGenerationJobsPanel(generationJobQueryService = mock(), invoiceDetailQueryPort = mock(), documentStore = mock(), time = FIXED_TIME_SOURCE),
    )

    @Test
    fun `happy path - render adds both sub-panels as children and delegates the invoice to each`() {
        val printControls = printControls()
        val jobsPanel = jobsPanel()
        val panel = InvoiceExportPanel(printControls, jobsPanel)
        val dto = invoice()

        panel.render(dto)

        val cardChildren = panel.children.toList().flatMap { it.children.toList() }
        assertTrue(cardChildren.any { it is InvoicePrintControls }, "the card must contain InvoicePrintControls")
        assertTrue(cardChildren.any { it is InvoiceGenerationJobsPanel }, "the card must contain InvoiceGenerationJobsPanel")
        verify(printControls).render(dto)
        verify(jobsPanel).render(dto)
    }

    @Test
    fun `boundary - refresh before the first render falls back to render instead of a no-op`() {
        val printControls = printControls()
        val jobsPanel = jobsPanel()
        val panel = InvoiceExportPanel(printControls, jobsPanel)
        val dto = invoice()

        panel.refresh(dto)

        verify(printControls).render(dto)
        verify(jobsPanel).render(dto)
    }

    @Test
    fun `boundary - refresh after a render delegates to both sub-panels' own refresh, never rebuilding the card`() {
        val printControls = printControls()
        val jobsPanel = jobsPanel()
        val panel = InvoiceExportPanel(printControls, jobsPanel)
        val dto = invoice()
        panel.render(dto)
        val cardBefore = panel.children.toList().single()

        panel.refresh(dto)

        assertSame(cardBefore, panel.children.toList().single(), "refresh must not rebuild the card")
        verify(printControls).refresh(dto)
        verify(jobsPanel).refresh(dto)
        // InvoiceGenerationJobsPanel.refresh never rebuilds its Grid once render() has run once
        // (see its own KDoc); InvoicePrintControls.refresh is a full rebuild by design (`refresh
        // = render`), so its own render() legitimately runs a second time via that delegation.
        verify(jobsPanel).render(dto)
    }

    @Test
    fun `boundary - onReload get and set both delegate to InvoicePrintControls, the only sub-panel with a reload trigger`() {
        val printControls = InvoicePrintControls(
            invoiceDetailQueryPort = mock(),
            recipientQueryPort = mock(),
            generationJobCreationService = mock(),
            documentJobPublisher = mock(),
            llmCheckAvailability = LlmCheckAvailability(),
        )
        val jobsPanel = mock<InvoiceGenerationJobsPanel>()
        val panel = InvoiceExportPanel(printControls, jobsPanel)
        var invoked = false

        panel.onReload = { invoked = true }
        printControls.onReload()

        assertTrue(invoked, "setting InvoiceExportPanel.onReload must reach InvoicePrintControls.onReload")
        assertEquals(printControls.onReload, panel.onReload)
    }

    @Test
    fun `boundary - bind forwards the InvoiceDetailState to InvoicePrintControls only`() {
        val printControls = mock<InvoicePrintControls>()
        val jobsPanel = mock<InvoiceGenerationJobsPanel>()
        val panel = InvoiceExportPanel(printControls, jobsPanel)
        val state = InvoiceDetailState()

        panel.bind(state)

        verify(printControls).bind(state)
    }
}
