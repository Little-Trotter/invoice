package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.checkbox.Checkbox
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.DocumentJobPublisher
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
 * T9-06 — a disabled check leaves **no** control behind.
 *
 * The difference from `isEnabled = false` is not cosmetic: a grayed-out checkbox is an
 * operating hint about something that does not exist here. The test therefore checks the
 * component tree, not the enabled state — it would equally fail on `isVisible = false`, since
 * an invisible component still sits in the tree.
 *
 * The counter-proof belongs here too: switched on, the checkbox must exist. Without it,
 * "the switch works" would look identical to "the checkbox is never built".
 *
 * Relocated from the former monolithic `InvoiceExportPanel` into [InvoicePrintControls],
 * which now owns the LLM checkbox via [LlmCheckAvailability].
 */
class InvoicePrintControlsValidationSwitchTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `switched off - the oMLX checkbox is absent from the component tree`() {
        val controls = controls(switchedOff = true)
        controls.render(invoice())
        assertTrue(checkboxes(controls).isEmpty(), "abgeschaltet: kein Kästchen, auch kein unsichtbares")
    }

    @Test
    fun `switched on - the oMLX checkbox is present and enabled`() {
        val controls = controls(switchedOff = false)
        controls.render(invoice())
        val found = checkboxes(controls)
        assertFalse(found.isEmpty(), "eingeschaltet: das Kästchen muss da sein")
        // Everything available (deployment flag on, oMLX configured): the checkbox is also
        // operable — the technical disable reasons are covered by
        // InvoicePrintControlsLlmFeatureFlagKaribuTest.
        assertTrue(found.single().isEnabled, "switched on and everything available: the checkbox must be enabled")
    }

    private fun controls(switchedOff: Boolean): InvoicePrintControls =
        InvoicePrintControls(
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(
                validationSettingsGate =
                    if (switchedOff) validationSettingsGate(ValidationCapabilityId.OMLX_LLM_CHECK)
                    else validationSettingsGate(),
                llmAnalysisFeatureConfig = null,
                // Deterministic availability — the test host's environment must not decide
                // whether the checkbox is enabled.
                mlxConfigured = { true },
            ),
        )

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun checkboxes(root: Component): List<Checkbox> = flatten(root).filterIsInstance<Checkbox>()

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()
}
