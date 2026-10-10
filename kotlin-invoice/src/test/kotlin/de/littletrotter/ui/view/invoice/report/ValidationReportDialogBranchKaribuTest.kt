package de.littletrotter.ui.view.invoice.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.StreamResourceRegistry
import com.vaadin.flow.server.streams.AbstractDownloadHandler
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.InputStreamDownloadCallback
import com.vaadin.flow.server.streams.InputStreamDownloadHandler
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * KoSIT report severities end to end: [ValidationReportDialog] opened from its grid
 * action, its combined Markdown download and footer actions (copy, nested raw KoSIT detail, OK),
 * and [KositReportDialog] edge cases — a warnings-only report, a failed job without detail, blank
 * rule ids/locations, and business findings whose i18n arguments run through the default key
 * resolver.
 */
class ValidationReportDialogBranchKaribuTest {

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

    private fun job(kositReport: String?, status: GenerationJobStatus = GenerationJobStatus.COMPLETED, resultRefs: String? = null) =
        GenerationJob().apply {
            id = 11L
            this.status = status
            this.kositReport = kositReport
            this.resultRefs = resultRefs
            invoice = Invoice().apply { id = 5L; invoiceNumber = InvoiceNumber("RE-2026-0007") }
            createdAt = OffsetDateTime.now()
        }

    private fun report(vararg details: ValidationMessage, status: String = "INVALID") =
        mapper.writeValueAsString(KositReportPayload(status = status, details = details.toList()))

    private fun all(root: Component): List<Component> = listOf(root) + root.children.toList().flatMap { all(it) }

    private fun texts(root: Component): List<String> = all(root).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun openDialogs(): List<Dialog> = _find<Dialog>().filter { it.isOpened }

    private fun footerButton(dlg: Dialog, key: String): Button = dlg._get<Button> { text = UiTranslations.tr(key) }

    private fun footerAnchor(dlg: Dialog): Anchor = dlg._get<Anchor>()

    private fun downloadBytes(anchor: Anchor): ByteArray {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        val handler = resource.elementRequestHandler as AbstractDownloadHandler<*>
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        return callback.complete(DownloadEvent(mock(), mock(), mock(), UI.getCurrent()!!.element)).inputStream.readAllBytes()
    }

    private fun dialogUnderTest(): ValidationReportDialog {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        return ValidationReportDialog(tr, invoiceDetailQueryPort, mock<DocumentStore>(), time = FIXED_TIME_SOURCE)
    }

    @Test
    fun `happy path - the grid action opens the report whose download carries the combined markdown`() {
        val raw = report(ValidationMessage(severity = "ERROR", message = "Pflichtfeld fehlt", ruleId = "BR-DE-15"))

        (dialogUnderTest().buildAction(job(raw)) as Button).click()
        val dlg = openDialogs().single()
        val markdown = String(downloadBytes(footerAnchor(dlg)), Charsets.UTF_8)

        assertTrue(markdown.isNotBlank())
    }

    @Test
    fun `boundary - the footer copies, opens the raw KoSIT detail on top and closes with OK`() {
        val raw = report(ValidationMessage(severity = "WARNING", message = "Hinweis", ruleId = "BR-CO-10"))
        dialogUnderTest().open(job(raw))
        val dlg = openDialogs().single()

        footerButton(dlg, "invoiceDetail.jobs.report.copy").click()
        footerButton(dlg, "invoiceDetail.jobs.validationReport.kositDetails.open").click()
        assertEquals(2, openDialogs().size, "the raw KoSIT detail stacks on the combined report")

        footerButton(dlg, "app.ok").click()
        assertFalse(dlg.isOpened)
    }

    @Test
    fun `boundary - a warnings-only KoSIT report lists warnings and no error section`() {
        KositReportDialog(tr).open(job(report(ValidationMessage(severity = "WARNING", message = "Nur Warnung", ruleId = " ", location = " "))))
        val shown = texts(openDialogs().single())

        assertTrue(shown.any { it.contains("Nur Warnung") })
        assertTrue(shown.none { it == UiTranslations.tr("invoiceDetail.jobs.kosit.valid.noIssues") })
        assertTrue(shown.none { it.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.errors.title")) && it.first().isDigit() })
    }

    @Test
    fun `boundary - a failed job without stored detail shows the bare reason line`() {
        KositReportDialog(tr).open(job(null, status = GenerationJobStatus.FAILED, resultRefs = """{"error":""}"""))
        val shown = texts(openDialogs().single())

        assertTrue(shown.contains(UiTranslations.tr("invoiceDetail.jobs.kosit.status.invalid")))
        assertTrue(shown.none { it.endsWith(": ") })
    }

    @Test
    fun `boundary - business findings with arguments resolve through the default message key resolver`() {
        val key = "invoiceDetail.jobs.kosit.warnings"
        KositReportDialog(tr).open(
            job(report(ValidationMessage(severity = "ERROR", message = key, stage = null, args = listOf("7")))),
        )
        val shown = texts(openDialogs().single())

        assertTrue(shown.contains(UiTranslations.tr(key)), "the key renders as its translation, not as the raw key: $shown")
    }
}
