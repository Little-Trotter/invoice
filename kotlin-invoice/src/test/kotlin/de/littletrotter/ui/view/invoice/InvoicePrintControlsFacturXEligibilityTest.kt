package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.FacturXDeployment
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * The engine selector must obey **both** conditions at once: the
 * `FACTURX_ENGINE` reference-data switch (already covered by [InvoicePrintControlsEngineSwitchTest])
 * AND `FacturXEligibility.isEligible(...)`. Mirrors that test's "absent from the tree, not merely
 * disabled" contract and its switch-off case, and adds the eligibility axis: switch on with an
 * eligible invoice shows the selector, switch on with a non-eligible invoice hides it just like
 * the switch itself being off does. Relocated from the former monolithic
 * `InvoiceExportPanel` into [InvoicePrintControls], which now owns the engine selector.
 */
class InvoicePrintControlsFacturXEligibilityTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `switch on and eligible - the engine selector is present`() {
        val controls = controls(facturXEngineSwitchOn = true)
        controls.render(invoice(eligible = true))
        assertFalse(engineSelectors(controls).isEmpty(), "switch on + eligible: the selector must be there")
    }

    @Test
    fun `switch on and not eligible - the engine selector is absent from the component tree`() {
        val controls = controls(facturXEngineSwitchOn = true)
        controls.render(invoice(eligible = false))
        assertTrue(engineSelectors(controls).isEmpty(), "not eligible: no selector, not even an invisible one")
    }

    @Test
    fun `switch off - the engine selector is absent even for an eligible invoice`() {
        val controls = controls(facturXEngineSwitchOn = false)
        controls.render(invoice(eligible = true))
        assertTrue(engineSelectors(controls).isEmpty(), "switch off: no selector, regardless of eligibility")
    }

    // A DB switch left ON from an earlier deployment must not surface Factur-X output on
    // a stack without the engine.
    @Test
    fun `switch on but not deployed - the engine selector is absent even for an eligible invoice`() {
        val controls = controls(facturXEngineSwitchOn = true, deployed = false)
        controls.render(invoice(eligible = true))
        assertTrue(engineSelectors(controls).isEmpty(), "not deployed: no selector, regardless of the switch or eligibility")
    }

    private fun controls(facturXEngineSwitchOn: Boolean, deployed: Boolean = true): InvoicePrintControls =
        InvoicePrintControls(
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(),
            validationSettingsGate =
                if (facturXEngineSwitchOn) validationSettingsGate()
                else validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE),
            facturXDeployment = FacturXDeployment(deployed),
        )

    /** `eligible = true` gives the seller a FR vatId (decision 11); `eligible = false` keeps
     *  both parties in Germany — neither party is in Factur-X scope. */
    private fun invoice(eligible: Boolean): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 1L, name = "Absender GmbH", vatId = if (eligible) "FR12345678901" else "DE123456789"),
        recipient = PartyDto(id = 2L, name = "Kunde GmbH", customerNumber = "K-1", vatId = "DE987654321"),
        items = emptyList(), latestGenerationJob = null,
    )

    private fun engineSelectors(root: Component): List<RadioButtonGroup<*>> = flatten(root).filterIsInstance<RadioButtonGroup<*>>()

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()
}
