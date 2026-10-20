package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.HardToReadPage
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityCheckException
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.invoice.port.outbound.ReceiptPageProbe
import de.littletrotter.shared.receipt.readability.ReadabilityPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.math.roundToInt

/** Applies the readability thresholds from `kotlin-shared` to the measured pages of an upload. */
@ApplicationScoped
class ReceiptReadabilityService @Inject constructor(
    private val probe: ReceiptPageProbe,
) : ReceiptReadabilityQueryPort {

    override fun findHardToReadPages(pdfData: ByteArray): List<HardToReadPage> =
        measure(pdfData)
            .filter { it.readability.suspect }
            .mapNotNull { page ->
                page.previewPng?.let { HardToReadPage(page.readability.pageNumber, page.readability.effectiveDpi?.roundToInt(), it) }
            }

    // Image decoding and resampling signal bad input with these two; anything else is a real defect and propagates.
    private fun measure(pdfData: ByteArray) = try {
        probe.probe(pdfData, ReadabilityPolicy.MAX_PAGES_CHECKED)
    } catch (e: IllegalArgumentException) {
        throw ReceiptReadabilityCheckException("Readability measurement failed", e)
    } catch (e: IllegalStateException) {
        throw ReceiptReadabilityCheckException("Readability measurement failed", e)
    }
}
