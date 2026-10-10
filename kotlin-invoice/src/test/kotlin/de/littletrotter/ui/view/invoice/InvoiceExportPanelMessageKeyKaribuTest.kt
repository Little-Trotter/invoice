package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/** The component-aware finding-key resolver [InvoiceGenerationJobsPanel] wires into its
 *  [ValidationReportDialog] (translated key, argument substitution, unknown key). */
class InvoiceExportPanelMessageKeyKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun resolver(): (String, List<String>) -> String? {
        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryService = mock<GenerationJobQueryService>(),
            invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>(),
            documentStore = mock<DocumentStore>(), time = FIXED_TIME_SOURCE,
        )
        val dialogField = InvoiceGenerationJobsPanel::class.java.getDeclaredField("validationReportDialog").apply { isAccessible = true }
        val dialog: ValidationReportDialog = dialogField.get(panel).requireAs()
        val keyField = ValidationReportDialog::class.java.getDeclaredField("resolveMessageKey").apply { isAccessible = true }
        return keyField.get(dialog).requireAs()
    }

    @Test
    fun `happy path - a finding key resolves to its German text with the argument filled in`() {
        assertEquals(UiTranslations.tr("invoiceDetail.statusChanged", "X"), resolver()("invoiceDetail.statusChanged", listOf("X")))
    }

    @Test
    fun `boundary - an unknown finding key resolves to null so the raw finding text is shown`() {
        assertNull(resolver()("no.such.finding.key", emptyList()))
    }

    @Test
    fun `boundary - a key without arguments resolves without placeholders`() {
        assertEquals(UiTranslations.tr("app.save"), resolver()("app.save", emptyList()))
    }

    @Test
    fun `boundary - surplus arguments for a key without placeholders are ignored`() {
        assertEquals(UiTranslations.tr("app.save"), resolver()("app.save", listOf("unused")))
    }
}
