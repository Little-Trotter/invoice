package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.orderedDistinctReceipts
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.concurrent.Semaphore

/**
 * Admission by weight: the sum of the receipt bytes of the jobs running at the same time stays
 * within a budget, shared by both lanes. Worker threads alone count jobs, not bytes; a document
 * with 19 receipts needs many times the memory of one without, so a few heavy jobs must not run
 * together just because threads are free.
 *
 * A job waits inside [admit] while the budget is taken. The caller's heartbeat keeps running on
 * its own scheduler, so the message stays leased and is not redelivered while it waits. Permits are
 * granted in arrival order, so a heavy job is not starved by a stream of light ones.
 */
class JobWeightBudget(val budgetKib: Int) {

  private val semaphore = Semaphore(budgetKib, true)

  /**
   * Runs [work] once [weightKib] is free and gives it back afterwards. A job heavier than the whole
   * budget is admitted alone, clamped to the budget, instead of waiting forever. A weight of zero
   * (no receipts) never waits. Interruptible: an interrupt while waiting propagates.
   */
  fun <T> admit(weightKib: Int, work: () -> T): T {
    val permits = weightKib.coerceIn(0, budgetKib)
    if (permits == 0) return work()
    semaphore.acquire(permits)
    try {
      return work()
    } finally {
      semaphore.release(permits)
    }
  }

  companion object {
    private const val BYTES_PER_KIB = 1024L

    /** Effectively no limit; for direct construction where admission is not under test. */
    fun unlimited(): JobWeightBudget = JobWeightBudget(Int.MAX_VALUE)

    /**
     * The receipt bytes a job brings. The job message carries receipt references without sizes,
     * so each distinct receipt counts with the size every receipt is reduced to at upload.
     */
    fun weightKib(invoice: InvoiceDto): Int =
      (invoice.orderedDistinctReceipts().size * (ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES / BYTES_PER_KIB)).toInt()
  }
}

/**
 * Provides the one budget both lanes share: `xpdf.job-weight-budget-kib` when set, otherwise the
 * value derived from the heap headroom (see [RenderSizing]).
 */
@ApplicationScoped
class JobWeightBudgetProducer {

  @Produces
  @Singleton
  fun jobWeightBudget(
    @ConfigProperty(name = "xpdf.job-weight-budget-kib") configuredKib: Optional<Int>,
  ): JobWeightBudget = JobWeightBudget(configuredKib.orElseGet { RenderSizing.resolve().weightBudgetKib })
}
