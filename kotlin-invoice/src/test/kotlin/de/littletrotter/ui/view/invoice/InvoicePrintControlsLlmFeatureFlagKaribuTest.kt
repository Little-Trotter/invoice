package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.checkbox.Checkbox
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.LlmAnalysisFeatureConfig
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * PO decision 24.08.2026 — the deployment-level [LlmAnalysisFeatureConfig] switch is a
 * *technical* reason, not a business one: with the reference-data `OMLX_LLM_CHECK` switch on, the
 * LLM checkbox must stay in the component tree even when the deployment flag is off — disabled,
 * with a helper text naming the deployment as the reason. (Before 24.08.2026 the flag removed the
 * element; that contract is superseded.) Only the reference-data switch decides visibility, which
 * [InvoicePrintControlsValidationSwitchTest] proves — this test keeps that switch on throughout
 * and only varies the technical availability: the deployment flag and the oMLX configuration.
 * Relocated from the former monolithic `InvoiceExportPanel` into [InvoicePrintControls],
 * which now folds these three reasons into [LlmCheckAvailability].
 */
class InvoicePrintControlsLlmFeatureFlagKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `deployment flag off - the checkbox is present but disabled with the deployment reason`() {
        val controls = controls(featureEnabled = false, mlxConfigured = true)
        controls.render(invoice())
        val checkbox = singleCheckbox(controls)
        assertFalse(checkbox.isEnabled, "flag off: the checkbox must be disabled")
        assertEquals(
            UiTranslations.tr("invoiceDetail.jobs.llmCheck.disabledByDeployment.reason"),
            checkbox.helperText,
            "flag off: the helper text must name the deployment as the reason",
        )
    }

    @Test
    fun `deployment flag off and oMLX unconfigured - the deployment reason wins`() {
        val controls = controls(featureEnabled = false, mlxConfigured = false)
        controls.render(invoice())
        val checkbox = singleCheckbox(controls)
        assertFalse(checkbox.isEnabled, "both reasons: the checkbox must be disabled")
        assertEquals(
            UiTranslations.tr("invoiceDetail.jobs.llmCheck.disabledByDeployment.reason"),
            checkbox.helperText,
            "both reasons: the deployment reason is the more fundamental one and must win",
        )
    }

    @Test
    fun `deployment flag on but oMLX unconfigured - disabled with the configuration reason`() {
        val controls = controls(featureEnabled = true, mlxConfigured = false)
        controls.render(invoice())
        val checkbox = singleCheckbox(controls)
        assertFalse(checkbox.isEnabled, "unconfigured: the checkbox must be disabled")
        assertEquals(
            UiTranslations.tr("invoiceDetail.jobs.llmCheck.disabled.reason"),
            checkbox.helperText,
            "unconfigured: the helper text must name the missing oMLX configuration",
        )
    }

    @Test
    fun `deployment flag on and oMLX configured - the checkbox is present and enabled`() {
        val controls = controls(featureEnabled = true, mlxConfigured = true)
        controls.render(invoice())
        val checkbox = singleCheckbox(controls)
        assertTrue(checkbox.isEnabled, "everything available: the checkbox must be enabled")
    }

    private fun controls(featureEnabled: Boolean, mlxConfigured: Boolean): InvoicePrintControls =
        InvoicePrintControls(
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            recipientQueryPort = mock<RecipientQueryPort>(),
            generationJobCreationService = mock<GenerationJobCreationService>(),
            documentJobPublisher = mock<DocumentJobPublisher>(),
            llmCheckAvailability = LlmCheckAvailability(
                validationSettingsGate = validationSettingsGate(),
                llmAnalysisFeatureConfig = LlmAnalysisFeatureConfig(enabled = featureEnabled),
                mlxConfigured = { mlxConfigured },
            ),
        )

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun singleCheckbox(root: Component): Checkbox {
        val checkboxes = flatten(root).filterIsInstance<Checkbox>()
        assertEquals(1, checkboxes.size, "the reference-data switch is on: exactly one LLM checkbox must be in the tree")
        return checkboxes.single()
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()
}
