package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DocumentGenerated
import de.littletrotter.domain.event.GenerationJobFailed
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [GenerationJobUiPushHandler] is now the SOLE place the Vaadin UI push
 * ([EventPublisher.publishJobFinished]) is called for a document-generation outcome; the direct
 * calls that used to sit alongside `GenerationJobService.markCompleted`/`markFailed`'s own
 * domain-event firing (in `GenerationJobResultConsumer`) are gone — this is where that behavior
 * is pinned now.
 */
class GenerationJobUiPushHandlerTest {

    @Test
    fun `onDocumentGenerated publishes a successful job-finished event`() {
        val uiEventBus: EventPublisher = mock()
        val handler = GenerationJobUiPushHandler(uiEventBus)

        handler.onDocumentGenerated(DocumentGenerated(invoiceId = 77L, invoiceNumber = "RE-1", generationJobId = 123L, resultRefs = null))

        verify(uiEventBus).publishJobFinished(123L, 77L, true)
    }

    @Test
    fun `onGenerationJobFailed publishes a failed job-finished event`() {
        val uiEventBus: EventPublisher = mock()
        val handler = GenerationJobUiPushHandler(uiEventBus)

        handler.onGenerationJobFailed(GenerationJobFailed(invoiceId = 99L, invoiceNumber = "RE-2", generationJobId = 555L, reason = "worker crashed"))

        verify(uiEventBus).publishJobFinished(555L, 99L, false)
    }

    @Test
    fun `a publish failure does not propagate`() {
        val uiEventBus: EventPublisher = mock()
        whenever(uiEventBus.publishJobFinished(123L, 77L, true)).thenThrow(RuntimeException("no active UI session"))
        val handler = GenerationJobUiPushHandler(uiEventBus)

        // Must not throw.
        handler.onDocumentGenerated(DocumentGenerated(invoiceId = 77L, invoiceNumber = "RE-1", generationJobId = 123L, resultRefs = null))
    }

    // ── L24 (fix-l24-draft-job-status-never-pushed) — this handler is deliberately the one
    // consumer that never reads `issued`: a draft's job finishing/failing must push exactly like
    // an issued one's, otherwise the versions-panel row never learns the job left its
    // pre-completion status. ─────────────────────────────────────────────────────────────────

    @Test
    fun `onDocumentGenerated publishes for a draft (issued=false) job too`() {
        val uiEventBus: EventPublisher = mock()
        val handler = GenerationJobUiPushHandler(uiEventBus)

        handler.onDocumentGenerated(DocumentGenerated(invoiceId = 77L, invoiceNumber = "RE-1", generationJobId = 123L, resultRefs = null, issued = false))

        verify(uiEventBus).publishJobFinished(123L, 77L, true)
    }

    @Test
    fun `onGenerationJobFailed publishes for a draft (issued=false) job too`() {
        val uiEventBus: EventPublisher = mock()
        val handler = GenerationJobUiPushHandler(uiEventBus)

        handler.onGenerationJobFailed(GenerationJobFailed(invoiceId = 99L, invoiceNumber = "RE-2", generationJobId = 555L, reason = "worker crashed", issued = false))

        verify(uiEventBus).publishJobFinished(555L, 99L, false)
    }

    @Test
    fun `each event dispatches to its own independent publish call`() {
        val uiEventBus: EventPublisher = mock()
        val handler = GenerationJobUiPushHandler(uiEventBus)

        handler.onGenerationJobFailed(GenerationJobFailed(invoiceId = 1L, invoiceNumber = "RE-3", generationJobId = 700L, reason = "boom"))
        handler.onDocumentGenerated(DocumentGenerated(invoiceId = 1L, invoiceNumber = "RE-3", generationJobId = 701L, resultRefs = "{}"))

        verify(uiEventBus).publishJobFinished(700L, 1L, false)
        verify(uiEventBus).publishJobFinished(701L, 1L, true)
    }
}
