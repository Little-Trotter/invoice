package de.littletrotter.ui.view.invoice.report

import de.littletrotter.testing.jobView
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.dialog.Dialog
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [KositReportDialog]'s per-message meta row (code/position combinations), blank
 *  failure details and an erroring stage without an engine message. */
class KositReportDialogBoundaryKaribuTest {

    private val mapper = jacksonObjectMapper()
    private val tr: (String) -> String = { key -> UiTranslations.tr(key) }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun texts(): List<String> =
        flatten(_get<Dialog>()).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun job(status: GenerationJobStatus, kositReport: String?, resultRefs: String? = null) = jobView {
        this.status = status
        this.kositReport = kositReport
        this.resultRefs = resultRefs
    }

    @Test
    fun `happy path - a raw KoSIT XML report shows code and position of every finding`() {
        val xml = """<report valid="false"><message id="P1" level="error" code="BR-01"><text>Pflichtfeld fehlt</text></message></report>"""

        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, xml))

        val all = texts()
        assertTrue(all.contains("BR-01"), "the rule code chip")
        assertTrue(all.contains("P1"), "the position chip")
        assertTrue(all.any { it.contains("Pflichtfeld fehlt") })
    }

    @Test
    fun `boundary - a finding with only a position shows the position but no code chip`() {
        val xml = """<report valid="false"><message id="P7" level="error"><text>Ohne Regel</text></message></report>"""

        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, xml))

        val all = texts()
        assertTrue(all.contains("P7"))
        assertTrue(all.any { it.contains("Ohne Regel") })
    }

    @Test
    fun `boundary - a finding with a blank rule id and no position renders no meta row`() {
        val payload = KositReportPayload(
            status = "INVALID",
            details = listOf(ValidationMessage(severity = "ERROR", message = "Leere Regel", ruleId = " ")),
        )

        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, mapper.writeValueAsString(payload)))

        val all = texts()
        assertTrue(all.any { it.contains("Leere Regel") })
        assertFalse(all.contains(" "), "no blank code chip")
    }

    @Test
    fun `boundary - a failed job with a blank stored error shows the reason without a detail suffix`() {
        KositReportDialog(tr).open(job(GenerationJobStatus.FAILED, kositReport = null, resultRefs = """{"error":"   "}"""))

        assertTrue(texts().none { it.endsWith(":    ") || it.endsWith(": ") }, "a blank detail is never appended")
    }

    @Test
    fun `boundary - an erroring stage with a blank engine error falls back to the no-findings row`() {
        val run = ValidationRun(
            overall = KositOutcome.ERROR,
            startedAt = "2026-09-01T00:00:00Z",
            stages = listOf(ValidationStageResult(stage = "BLANK_ENGINE_STAGE", outcome = KositOutcome.ERROR, engineError = " ")),
        )
        val payload = KositReportPayload(status = "ERROR", validationRun = run)

        KositReportDialog(tr).open(job(GenerationJobStatus.COMPLETED, mapper.writeValueAsString(payload)))

        assertTrue(texts().any { it.contains("BLANK_ENGINE_STAGE") }, "the stage still appears, as a no-findings row")
    }
}
