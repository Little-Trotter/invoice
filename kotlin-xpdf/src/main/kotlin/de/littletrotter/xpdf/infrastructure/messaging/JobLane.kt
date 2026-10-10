package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.documentjob.DocumentJobSubjects
import de.littletrotter.xpdf.readEnv

/**
 * One independently consumed class of document jobs: the subject it listens on, the durable
 * consumer that tracks its progress and the number of render threads it owns. Bulk jobs (mass
 * issue runs) and interactive jobs (a document a user is waiting for) share one stream but not
 * a consumer or a thread pool, so a long bulk backlog can never delay an interactive document.
 */
data class JobLane(
  val name: String,
  val subject: String,
  val durableName: String,
  val workerThreads: Int,
) {

  companion object {
    const val BULK_DURABLE = "kotlin-xpdf-group"
    const val INTERACTIVE_DURABLE = "kotlin-xpdf-interactive"

    /**
     * Interactive documents are single, user-triggered renders; one thread is enough and keeps the
     * heap reserve small. The bulk thread count derived from the heap ceiling already subtracts it.
     */
    const val DEFAULT_INTERACTIVE_THREADS = 1

    /**
     * An explicit `JOB_WORKER_THREADS`/`job.worker.threads` wins; without one the count is derived
     * from this JVM's heap ceiling (see [JobConsumer.defaultWorkerThreads]).
     */
    fun bulk(subject: String = DocumentJobSubjects.JOBS, durableName: String = BULK_DURABLE): JobLane =
      JobLane(
        "bulk",
        subject,
        durableName,
        configuredThreads("job.worker.threads", "JOB_WORKER_THREADS") ?: JobConsumer.defaultWorkerThreads(),
      )

    /** `JOB_INTERACTIVE_WORKER_THREADS`/`job.interactive.worker.threads` override the single default thread. */
    fun interactive(
      subject: String = DocumentJobSubjects.INTERACTIVE_JOBS,
      durableName: String = INTERACTIVE_DURABLE,
    ): JobLane =
      JobLane(
        "interactive",
        subject,
        durableName,
        configuredThreads("job.interactive.worker.threads", "JOB_INTERACTIVE_WORKER_THREADS") ?: DEFAULT_INTERACTIVE_THREADS,
      )

    private fun configuredThreads(property: String, env: String): Int? =
      (System.getProperty(property) ?: readEnv(env))?.toInt()
  }
}
