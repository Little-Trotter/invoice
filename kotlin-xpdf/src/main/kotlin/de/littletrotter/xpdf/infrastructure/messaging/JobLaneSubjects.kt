package de.littletrotter.xpdf.infrastructure.messaging

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Where the two job lanes read from: the bulk subject with its durable consumer and the interactive
 * subject. Kept apart from [JobConsumerLanes] so the consumer wiring does not also carry routing
 * configuration.
 */
@ApplicationScoped
class JobLaneSubjects @Inject constructor(
  @param:ConfigProperty(name = "nats.subject.document-jobs", defaultValue = "document-jobs")
  private val bulkSubject: String,
  @param:ConfigProperty(name = "nats.consumer.document-jobs", defaultValue = JobLane.BULK_DURABLE)
  private val bulkDurable: String,
  @param:ConfigProperty(name = "nats.subject.document-jobs-interactive", defaultValue = "document-jobs-interactive")
  private val interactiveSubject: String,
) {
  fun lanes(): List<JobLane> = listOf(JobLane.bulk(bulkSubject, bulkDurable), JobLane.interactive(interactiveSubject))
}
