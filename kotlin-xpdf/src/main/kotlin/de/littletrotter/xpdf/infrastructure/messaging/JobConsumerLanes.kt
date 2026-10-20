package de.littletrotter.xpdf.infrastructure.messaging

import io.nats.client.Connection
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Starts one [JobConsumer] per [JobLane] (bulk and interactive). Both lanes reuse the same
 * poison-message handling and dispatching; only subject, durable consumer and worker pool differ.
 *
 * `@Startup` forces eager instantiation: a plain bean that nobody injects would otherwise never
 * run its `@PostConstruct`, and no job would ever be consumed.
 */
@Startup
@ApplicationScoped
class JobConsumerLanes @Inject constructor(
  private val connection: Connection,
  private val poisonMessageHandler: PoisonMessageHandler,
  private val jobDispatcher: JobDispatcher,
  private val quoteJobProcessor: QuoteJobProcessor,
  private val weightBudget: JobWeightBudget,
  private val subjects: JobLaneSubjects,
) {

  @PostConstruct
  fun subscribeAll() {
    logSizing()
    subjects.lanes()
      .forEach { lane ->
        JobConsumer(connection, connection.jetStream(), poisonMessageHandler, jobDispatcher, quoteJobProcessor, lane, weightBudget).subscribe()
      }
  }

  private fun logSizing() {
    val sizing = RenderSizing.resolve()
    log.info(
      "xpdf sizing: bulkThreads={} interactiveThreads={} perThreadMiB={} receiptHeadroomMiB={} weightBudgetKiB={} (heapMiB={})",
      subjects.lanes().first { it.name == "bulk" }.workerThreads,
      sizing.interactiveThreads,
      sizing.perThreadBytes / BYTES_PER_MIB,
      sizing.receiptHeadroomBytes / BYTES_PER_MIB,
      weightBudget.budgetKib,
      Runtime.getRuntime().maxMemory() / BYTES_PER_MIB,
    )
  }

  private companion object {
    const val BYTES_PER_MIB = 1024L * 1024
    val log: Logger = LoggerFactory.getLogger(JobConsumerLanes::class.java)
  }
}
