package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RecordPdfCheckResultServiceTest {

    private val resultHandler: PdfCheckResultHandler = mock()
    private val sendResolver: GenerationJobResultHandler = mock()
    private val service = RecordPdfCheckResultService(resultHandler, sendResolver)
    private val verdict = PdfCheckVerdict(7L, KositOutcome.VALID, "veraPDF 1.30.2", emptyList(), null)

    @Test
    fun `a send that waited for the verdict continues after the verdict is committed`() {
        val job = aGenerationJob { id = 7L }
        whenever(resultHandler.append(verdict)).thenReturn(PdfCheckResultHandler.Appended(job, resumeSend = true))

        service.record(verdict)

        verify(sendResolver).resolveSendOutcome(job)
    }

    @Test
    fun `a verdict nobody waited for leaves the send alone`() {
        val job = aGenerationJob { id = 7L }
        whenever(resultHandler.append(verdict)).thenReturn(PdfCheckResultHandler.Appended(job, resumeSend = false))

        service.record(verdict)

        verify(sendResolver, never()).resolveSendOutcome(org.mockito.kotlin.any())
    }

    @Test
    fun `a dropped verdict continues nothing`() {
        whenever(resultHandler.append(verdict)).thenReturn(null)

        service.record(verdict)

        verify(sendResolver, never()).resolveSendOutcome(org.mockito.kotlin.any())
    }
}
