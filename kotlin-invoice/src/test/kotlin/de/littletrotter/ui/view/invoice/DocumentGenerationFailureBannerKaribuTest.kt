package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.DocumentGenerationFailure
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.RegenerateInvoiceDocumentUseCase
import de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DocumentGenerationFailureBannerKaribuTest {

    private val useCase: RegenerateInvoiceDocumentUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun invoice(failure: DocumentGenerationFailure?) = InvoiceDetailDto(
        id = 5L, invoiceNumber = "INV-5", status = InvoiceStatus.ISSUED.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        documentGenerationFailure = failure,
    )

    private fun banner(failure: DocumentGenerationFailure?, onRegenerated: () -> Unit = {}) =
        DocumentGenerationFailureBanner(useCase).also { it.render(invoice(failure), onRegenerated) }

    private fun button(banner: DocumentGenerationFailureBanner): Button? =
        flatten(banner).filterIsInstance<Button>().singleOrNull { it.text == UiTranslations.tr("documentGeneration.regenerate") }

    @Test
    fun `shows the reason and the action for a failed generation`() {
        val banner = banner(DocumentGenerationFailure("Typst crashed"))

        assertTrue(banner.isVisible)
        assertTrue(flatten(banner).filterIsInstance<Span>().any { it.text == UiTranslations.tr("documentGeneration.failed", "Typst crashed") })
        assertTrue(button(banner) != null)
    }

    @Test
    fun `a failure without reason shows the generic text`() {
        val banner = banner(DocumentGenerationFailure(""))

        assertTrue(flatten(banner).filterIsInstance<Span>().any { it.text == UiTranslations.tr("documentGeneration.failed.noReason") })
    }

    @Test
    fun `is hidden and empty without a failure`() {
        val banner = banner(null)

        assertFalse(banner.isVisible)
        assertEquals(null, button(banner))
    }

    @Test
    fun `click triggers the use case once and reloads`() {
        whenever(useCase.regenerate(5L)).thenReturn(9L)
        var reloads = 0
        val banner = banner(DocumentGenerationFailure("x")) { reloads++ }
        val action = button(banner)!!

        action.click()

        verify(useCase, times(1)).regenerate(5L)
        assertEquals(1, reloads)
        assertFalse(action.isEnabled)
    }

    @Test
    fun `a refusal re-enables the action and does not reload`() {
        whenever(useCase.regenerate(5L)).thenThrow(InvoiceGenerationAlreadyRunningException("INV-5"))
        var reloads = 0
        val banner = banner(DocumentGenerationFailure("x")) { reloads++ }
        val action = button(banner)!!

        action.click()

        assertEquals(0, reloads)
        assertTrue(action.isEnabled)
    }
}
