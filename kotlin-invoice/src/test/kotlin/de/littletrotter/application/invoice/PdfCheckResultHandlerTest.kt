package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.VeraPdfCheckState
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.veraPdfCheckStateOf
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.edit
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PdfCheckResultHandlerTest {

    private val repository: GenerationJobRepository = mock()
    private val handler = PdfCheckResultHandler(repository)

    private val pendingReport: String = run {
        val stages = listOf(
            ValidationStageResult("PDFA", KositOutcome.VALID),
            ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = true, advisory = true),
        )
        KositReportPayload(
            status = "VALID",
            validationRun = ValidationRun(stages, KositOutcome.VALID, startedAt = "2026-10-12T10:00:00Z"),
        ).toJson()
    }

    private fun job(trigger: GenerationJobTrigger = GenerationJobTrigger.SEND, invoiceStatus: InvoiceStatus = InvoiceStatus.IN_REVIEW): GenerationJob =
        aGenerationJob {
            id = 7L
            this.trigger = trigger
            status = GenerationJobStatus.COMPLETED
            kositReport = pendingReport
            invoice = anInvoice(id = 900L, invoiceNumber = InvoiceNumber("RE-2025-01-00900")).also { it.forceStatusForTest(invoiceStatus) }
        }.also { whenever(repository.findByIdForUpdate(7L)).thenReturn(it) }

    private fun verdict(outcome: KositOutcome, findings: List<ValidationMessage> = emptyList(), engineError: String? = null) =
        PdfCheckVerdict(7L, outcome, "veraPDF 1.30.2", findings, engineError)

    @Test
    fun `a conforming verdict replaces the pending stage and the document stays valid`() {
        val job = job()

        val appended = handler.append(verdict(KositOutcome.VALID))

        assertNotNull(appended)
        assertEquals(VeraPdfCheckState.PASSED, veraPdfCheckStateOf(job.kositReport))
        val payload = KositReportPayload.decode(job.kositReport)!!
        assertEquals("VALID", payload.status)
        assertEquals("veraPDF 1.30.2", payload.validationRun!!.validatorVersions["verapdf"])
        verify(repository).save(job)
    }

    @Test
    fun `findings are kept as warnings of the veraPDF stage and never change the verdict`() {
        val job = job()
        val finding = ValidationMessage("ERROR", "6.3.5 font not embedded", ruleId = "6.3.5-2", stage = "PDFA")

        handler.append(verdict(KositOutcome.INVALID, listOf(finding)))

        val payload = KositReportPayload.decode(job.kositReport)!!
        assertEquals(VeraPdfCheckState.FINDINGS, veraPdfCheckStateOf(job.kositReport))
        assertEquals("VALID", payload.status, "findings must not turn the invoice's validation INVALID")
        val stored = payload.details.single()
        assertEquals("WARNING", stored.severity)
        assertEquals("VERAPDF", stored.stage)
        assertEquals("6.3.5-2", stored.ruleId)
    }

    @Test
    fun `a check that could not run is recorded as not possible with its reason`() {
        val job = job()

        handler.append(verdict(KositOutcome.ERROR, engineError = "service down"))

        assertEquals(VeraPdfCheckState.UNAVAILABLE, veraPdfCheckStateOf(job.kositReport))
        val stage = KositReportPayload.decode(job.kositReport)!!.validationRun!!.stages.single { it.stage == "VERAPDF" }
        assertEquals("service down", stage.engineError)
        assertEquals("VALID", KositReportPayload.decode(job.kositReport)!!.status)
    }

    @Test
    fun `an error without a reason still names one`() {
        val job = job()

        handler.append(verdict(KositOutcome.ERROR))

        assertEquals("unbekannter Fehler", KositReportPayload.decode(job.kositReport)!!.validationRun!!.stages.single { it.stage == "VERAPDF" }.engineError)
    }

    @Test
    fun `applying the same verdict twice leaves the same report`() {
        val job = job()

        handler.append(verdict(KositOutcome.VALID))
        val once = job.kositReport
        handler.append(verdict(KositOutcome.VALID))

        assertEquals(once, job.kositReport)
    }

    @Test
    fun `a waiting send continues only for a completed valid SEND job of an invoice still in review`() {
        job()
        assertTrue(handler.append(verdict(KositOutcome.VALID))!!.resumeSend)

        job(trigger = GenerationJobTrigger.MANUAL)
        assertFalse(handler.append(verdict(KositOutcome.VALID))!!.resumeSend)

        job(invoiceStatus = InvoiceStatus.ISSUED)
        assertFalse(handler.append(verdict(KositOutcome.VALID))!!.resumeSend)
    }

    @Test
    fun `a job that failed or whose document is invalid has nothing to continue`() {
        val failed = job().edit { status = GenerationJobStatus.FAILED }
        val appended = handler.append(verdict(KositOutcome.VALID))

        assertFalse(appended!!.resumeSend)
        assertEquals(failed, appended.job)
    }

    @Test
    fun `a verdict for an unknown job is dropped`() {
        whenever(repository.findByIdForUpdate(8L)).thenReturn(null)

        assertNull(handler.append(PdfCheckVerdict(8L, KositOutcome.VALID, "veraPDF", emptyList(), null)))
        verify(repository, never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `a job without a stage breakdown cannot take the verdict`() {
        val job = job().also { it.replaceKositReport("""{"status":"VALID"}""") }

        assertNull(handler.append(verdict(KositOutcome.VALID)))
        assertEquals("""{"status":"VALID"}""", job.kositReport)
    }
}
