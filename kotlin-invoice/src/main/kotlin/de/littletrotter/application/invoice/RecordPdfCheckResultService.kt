package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.PdfCheckVerdict
import de.littletrotter.application.invoice.port.inbound.RecordPdfCheckResultUseCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Commits the veraPDF verdict first, then lets a send that was waiting for it continue. The two
 * steps are separate transactions on purpose: the continuation reads the committed verdict, and a
 * failure in it must not undo the stored verdict (the continuation never throws). Both steps lock
 * the job row, so a send decision made concurrently either sees the verdict or finishes first and
 * is picked up by the continuation.
 */
@ApplicationScoped
class RecordPdfCheckResultService @Inject constructor(
    private val resultHandler: PdfCheckResultHandler,
    private val sendResolver: GenerationJobResultHandler,
) : RecordPdfCheckResultUseCase {

    override fun record(verdict: PdfCheckVerdict) {
        val appended = resultHandler.append(verdict) ?: return
        if (appended.resumeSend) sendResolver.resolveSendOutcome(appended.job)
    }
}
