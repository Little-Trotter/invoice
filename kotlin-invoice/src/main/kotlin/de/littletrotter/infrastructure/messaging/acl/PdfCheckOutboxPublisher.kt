package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.invoice.port.outbound.PdfCheckOrder
import de.littletrotter.application.invoice.port.outbound.PdfCheckRequestPort
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.shared.documentjob.DocumentJobWireCodec
import de.littletrotter.shared.documentjob.PdfCheckRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject

/**
 * Encodes a check order in the document job contract and appends it to the transactional outbox;
 * the relay publishes it once the surrounding transaction committed. The check id is derived from
 * the job, so an order written twice names the same check.
 */
@ApplicationScoped
class PdfCheckOutboxPublisher @Inject constructor(
    private val outbox: OutboxRepository,
    private val appended: Event<OutboxAppended>,
) : PdfCheckRequestPort {

    override fun request(order: PdfCheckOrder) {
        val request = PdfCheckRequest(
            checkId = "verapdf-${order.jobId}",
            jobId = order.jobId.toString(),
            objectKey = order.objectKey,
            sha256 = order.sha256,
            correlationId = order.correlationId,
        )
        outbox.append(AGGREGATE_TYPE, order.jobId.toString(), DocumentJobSubjects.PDF_CHECKS, DocumentJobWireCodec.encodePdfCheckRequest(request))
        appended.fire(OutboxAppended(DocumentJobSubjects.PDF_CHECKS))
    }

    private companion object {
        const val AGGREGATE_TYPE = "GENERATION_JOB"
    }
}
