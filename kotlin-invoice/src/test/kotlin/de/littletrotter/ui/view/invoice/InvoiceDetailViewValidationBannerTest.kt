package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Persistent visibility of failed print validation. Proves the
 * panel→banner callback contract that InvoiceDetailView wires: a validation failure during print
 * job creation shows the banner with the resolved rule message, a successful print clears it, and
 * the close button dismisses it.
 *
 * The callbacks go through a bound [InvoiceDetailState]
 * ([InvoicePrintControls.bind]) instead of constructor lambdas; the subject under test is
 * [InvoicePrintControls] directly (the print button's own component), not the composed
 * [InvoiceExportPanel].
 */
class InvoiceDetailViewValidationBannerTest {

    private val generationJobCreationService = mock<GenerationJobCreationService>()
    private val documentJobPublisher = mock<DocumentJobPublisher>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `validation failure during print shows banner with the rule message`() {
        val ex = InvoiceValidationException(InvoiceValidationError.RECIPIENT_MISSING)
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenThrow(ex)

        val (panel, banner) = panelWithBanner()
        printButton(panel).click()

        assertTrue(banner.isVisible, "banner must be visible after validation failure")
        val bannerText = flatten(banner).filterIsInstance<Span>().first().text
        assertEquals(UiNotifications.resolveMessage(panel, ex), bannerText)
    }

    @Test
    fun `successful print keeps the banner hidden`() {
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = 123L })

        val (panel, banner) = panelWithBanner()
        printButton(panel).click()

        assertFalse(banner.isVisible, "banner must stay hidden after successful print")
    }

    @Test
    fun `close button dismisses the banner`() {
        val (panel, banner) = panelWithBanner()
        banner.show("test message")
        assertTrue(banner.isVisible, "banner must be visible before close")

        val closeButton = flatten(banner).filterIsInstance<Button>().first {
            it.element.getAttribute("aria-label") == UiTranslations.tr("app.close")
        }
        closeButton.click()

        assertFalse(banner.isVisible, "banner must be hidden after close button click")
    }

    @Test
    fun `successful print clears a stale banner`() {
        whenever(generationJobCreationService.createJob(any<Long>(), any(), any(), anyOrNull(), anyOrNull(), any(), any()))
            .thenReturn(GenerationJob().apply { id = 123L })

        val (panel, banner) = panelWithBanner()
        banner.show("old error")
        assertTrue(banner.isVisible, "banner must be visible before successful print")

        printButton(panel).click()

        assertFalse(banner.isVisible, "banner must be hidden after successful print")
    }

    /** Builds the print controls wired exactly like InvoiceDetailView does: real banner,
     *  callbacks into it via a bound [InvoiceDetailState]. Tests stub
     *  [GenerationJobCreationService.createJob] themselves before clicking print. */
    private fun panelWithBanner(): Pair<InvoicePrintControls, ValidationFailureBanner> {
        val banner = ValidationFailureBanner()
        val state = InvoiceDetailState()
        // `banner` as message source: for InvoiceValidationException the component is not
        // consulted (the rule key resolves via the bundle).
        state.onValidationFailure { ex -> banner.show(UiNotifications.resolveMessage(banner, ex)) }
        state.onPrintScheduled { banner.isVisible = false }

        val panel = InvoicePrintControls(
            invoiceDetailQueryPort = mock(),
            recipientQueryPort = mock(),
            generationJobCreationService = generationJobCreationService,
            documentJobPublisher = documentJobPublisher,
            llmCheckAvailability = LlmCheckAvailability(),
            validationSettingsGate = validationSettingsGate(),
        )
        panel.bind(state)
        panel.render(invoice())
        return panel to banner
    }

    private fun printButton(panel: Component): Button =
        flatten(panel).filterIsInstance<Button>().first { it.text == UiTranslations.tr("invoiceDetail.jobs.print") }

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()
}
