package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.orderedDistinctReceipts
import de.littletrotter.xpdf.ExportLane
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/** The measured stages of one export job; [tag] is the bounded `stage` label value. */
enum class ExportStage {
    RENDER,
    ANNEX,
    MUSTANG,
    SYNTAX,
    SCHEMATRON,
    KOSIT,
    PDFA,
    VERAPDF,
    WATERMARK,
    STORE,
    ;

    val tag: String = name.lowercase()
}

/**
 * Number of receipts an export carries, as a bounded label. The policy caps an invoice at
 * [ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE] (19) receipts, so [MANY] (6-19) is the open end:
 * a larger count can only reach this class if the cap is bypassed, and is clamped into [MANY]
 * rather than creating a label value per count. Raise the cap and this enum together.
 */
enum class ReceiptClass(val tag: String) {
    NONE("0"),
    FEW("1-5"),
    MANY("6-19"),
    ;

    companion object {
        private const val FEW_LIMIT = 5

        fun of(receiptCount: Int): ReceiptClass = when {
            receiptCount <= 0 -> NONE
            receiptCount <= FEW_LIMIT -> FEW
            else -> MANY
        }
    }
}

/**
 * Per-stage and per-job timers of the PDF export, exported through Micrometer/Prometheus:
 * `xpdf_export_stage_seconds{stage,lane,receipts}` and
 * `xpdf_export_job_seconds{lane,receipts,outcome}`. Percentile histograms are published so p50/p95
 * are computed in Prometheus; the bucket range is bounded to keep series count small.
 *
 * Stage timers only record inside a [job] scope (the lane and receipt class come from it). Code
 * paths outside an export job, such as offer rendering, therefore add nothing to these series.
 * A stage that is skipped simply never calls [record].
 */
@ApplicationScoped
class ExportStageTimers @Inject constructor(private val registry: MeterRegistry) {

    private class Scope(val lane: String, val receipts: ReceiptClass)

    private val scope = ThreadLocal<Scope?>()

    /** Times the whole export of [invoice]; the outcome tag is `failure` when [block] throws. */
    fun <T> job(invoice: InvoiceDto, block: () -> T): T {
        val previous = scope.get()
        val active = Scope(ExportLane.current(), ReceiptClass.of(invoice.orderedDistinctReceipts().size))
        scope.set(active)
        val sample = Timer.start(registry)
        var outcome = OUTCOME_FAILURE
        try {
            val result = block()
            outcome = OUTCOME_SUCCESS
            return result
        } finally {
            scope.set(previous)
            sample.stop(timer(JOB_METER, "lane", active.lane, "receipts", active.receipts.tag, "outcome", outcome))
        }
    }

    /** Times [block] as [stage]; time is recorded even when [block] throws. */
    fun <T> record(stage: ExportStage, block: () -> T): T {
        val active = scope.get() ?: return block()
        val sample = Timer.start(registry)
        try {
            return block()
        } finally {
            sample.stop(timer(STAGE_METER, "stage", stage.tag, "lane", active.lane, "receipts", active.receipts.tag))
        }
    }

    private fun timer(name: String, vararg tags: String): Timer =
        Timer.builder(name)
            .tags(*tags)
            .publishPercentileHistogram()
            .minimumExpectedValue(MIN_EXPECTED)
            .maximumExpectedValue(MAX_EXPECTED)
            .register(registry)

    private companion object {
        const val STAGE_METER = "xpdf.export.stage"
        const val JOB_METER = "xpdf.export.job"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        val MIN_EXPECTED: Duration = Duration.ofMillis(10)
        val MAX_EXPECTED: Duration = Duration.ofMinutes(5)
    }
}
