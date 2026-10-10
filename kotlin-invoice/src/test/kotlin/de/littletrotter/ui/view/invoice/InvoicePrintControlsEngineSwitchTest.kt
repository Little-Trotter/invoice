package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.reference.ValidationCapabilityId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * The Factur-X engine selector in [InvoicePrintControls]
 * is gated by [ValidationCapabilityId.FACTURX_ENGINE]; disabled it keeps showing only Mustang.
 * Relocated from the former monolithic `InvoiceExportPanel` (there named
 * `InvoiceExportPanelTest`, with no distinguishing suffix) into [InvoicePrintControls], which now
 * owns the engine selector.
 */
class InvoicePrintControlsEngineSwitchTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun buildControls(facturXEngineEnabled: Boolean): InvoicePrintControls {
        val gate = if (facturXEngineEnabled) {
            validationSettingsGate()
        } else {
            validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE)
        }

        return InvoicePrintControls(
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
            validationSettingsGate = gate,
        )
    }

    private fun findRadioButtonGroup(root: Component): RadioButtonGroup<*>? {
        if (root is RadioButtonGroup<*>) return root
        val children = root.children.toList()
        for (child in children) {
            val found = findRadioButtonGroup(child)
            if (found != null) return found
        }
        return null
    }

    /** The selector's visibility now also requires Factur-X eligibility, not
     *  only the [ValidationCapabilityId.FACTURX_ENGINE] switch this test class is about. Gives
     *  every invoice built here a FR seller so that axis is always satisfied and these tests stay
     *  about the switch alone; [InvoicePrintControlsFacturXEligibilityTest] covers the eligibility
     *  axis on its own. */
    private fun invoice(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-$id", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 1L, name = "Absender GmbH", vatId = "FR12345678901"),
        recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    @Test
    fun `T8-10 FACTURX_ENGINE enabled shows the engine selector`() {
        val controls = buildControls(facturXEngineEnabled = true)
        controls.render(invoice())

        assertTrue(findRadioButtonGroup(controls) != null, "engine selector must be visible when the capability is on")
    }

    @Test
    fun `T8-10 FACTURX_ENGINE disabled hides the engine selector`() {
        val controls = buildControls(facturXEngineEnabled = false)
        controls.render(invoice())

        assertFalse(findRadioButtonGroup(controls) != null, "disabling the T9 capability must hide the selector")
    }

    @Test
    fun `T8 the selector defaults to the invoice's current durable engine`() {
        val controls = buildControls(facturXEngineEnabled = true)
        val inv = invoice().copy(documentEngine = DocumentEngine.FACTURX.ordinal)
        controls.render(inv)

        val radioGroup = findRadioButtonGroup(controls)
        assertTrue(radioGroup != null)
        assertTrue(radioGroup.requireAs<RadioButtonGroup<DocumentEngine>>().value == DocumentEngine.FACTURX)
    }
}
