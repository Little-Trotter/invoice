package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.PdfCheckOrder
import de.littletrotter.application.invoice.port.outbound.PdfCheckRequestPort
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class PdfCheckRequestSchedulerTest {

    private val port: PdfCheckRequestPort = mock()
    private val scheduler = PdfCheckRequestScheduler(port)
    private val refs = """{"objectKey":"inv/7.pdf","sha256":"ab"}"""

    private fun report(pending: Boolean, skipped: Boolean = false): String {
        val stage = ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = pending, skipped = skipped, advisory = true)
        return KositReportPayload(status = "VALID", validationRun = ValidationRun(listOf(stage), KositOutcome.VALID, startedAt = "t")).toJson()
    }

    @Test
    fun `a pending document is claimed by object key and digest`() {
        scheduler.scheduleIfPending(7L, "c-7", refs, report(pending = true))

        verify(port).request(PdfCheckOrder(7L, "inv/7.pdf", "ab", "c-7"))
    }

    @Test
    fun `a document whose check was not requested is not claimed`() {
        scheduler.scheduleIfPending(7L, null, refs, report(pending = false, skipped = true))
        scheduler.scheduleIfPending(7L, null, refs, null)

        verify(port, never()).request(any())
    }

    @Test
    fun `a result without a stored PDF cannot be claimed`() {
        scheduler.scheduleIfPending(7L, null, """{"objectKey":"inv/7.pdf"}""", report(pending = true))
        scheduler.scheduleIfPending(7L, null, null, report(pending = true))
        scheduler.scheduleIfPending(7L, null, "not json {{{", report(pending = true))

        verify(port, never()).request(any())
    }
}
