package de.littletrotter.application.invoice

import com.fasterxml.jackson.core.JsonProcessingException
import de.littletrotter.application.invoice.port.outbound.PdfCheckOrder
import de.littletrotter.application.invoice.port.outbound.PdfCheckRequestPort
import de.littletrotter.domain.invoice.VeraPdfCheckState
import de.littletrotter.domain.invoice.veraPdfCheckStateOf
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Requests the veraPDF check for a document whose report carries the check as pending. Runs in
 * the transaction that completes the job, so the request is stored with the document and a
 * crash after commit loses nothing.
 *
 * The decision to check was taken when the job was published (the report says pending); switching
 * the check off afterwards does not withdraw it, the late verdict is simply appended.
 */
@ApplicationScoped
class PdfCheckRequestScheduler @Inject constructor(
    private val requests: PdfCheckRequestPort,
) {
    private val log = Logger.getLogger(PdfCheckRequestScheduler::class.java)

    fun scheduleIfPending(jobId: Long, correlationId: String?, resultRefs: String?, kositReport: String?) {
        if (veraPdfCheckStateOf(kositReport) != VeraPdfCheckState.PENDING) return
        val refs = refsOf(resultRefs)
        val objectKey = refs["objectKey"]
        val sha256 = refs["sha256"]
        if (objectKey.isNullOrBlank() || sha256.isNullOrBlank()) {
            // A stored PDF always comes with both; without them there is nothing to claim.
            log.errorf("Job %d awaits a veraPDF check but its result carries no stored PDF (objectKey/sha256)", jobId)
            return
        }
        requests.request(PdfCheckOrder(jobId, objectKey, sha256, correlationId))
    }

    private fun refsOf(resultRefs: String?): Map<String, String> =
        try {
            resultRefs?.let { GenerationJobResultRefsCodec.MAPPER.readValue(it, GenerationJobResultRefsCodec.MAP_TYPE) }.orEmpty()
        } catch (e: JsonProcessingException) {
            log.warn("Result refs are not a String map, no veraPDF check can be requested: ${e.message}")
            emptyMap()
        }
}
