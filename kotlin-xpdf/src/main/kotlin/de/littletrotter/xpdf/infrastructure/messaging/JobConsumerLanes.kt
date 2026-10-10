package de.littletrotter.xpdf.infrastructure.messaging

import io.nats.client.Connection
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

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
  @param:ConfigProperty(name = "nats.subject.document-jobs", defaultValue = "document-jobs")
  private val bulkSubject: String,
  @param:ConfigProperty(name = "nats.consumer.document-jobs", defaultValue = JobLane.BULK_DURABLE)
  private val bulkDurable: String,
  @param:ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = "document-jobs-interactive")
  private val interactiveSubject: String,
) {

  @PostConstruct
  fun subscribeAll() {
    listOf(JobLane.bulk(bulkSubject, bulkDurable), JobLane.interactive(interactiveSubject))
      .forEach { lane ->
        JobConsumer(connection, connection.jetStream(), poisonMessageHandler, jobDispatcher, quoteJobProcessor, lane).subscribe()
      }
  }
}
