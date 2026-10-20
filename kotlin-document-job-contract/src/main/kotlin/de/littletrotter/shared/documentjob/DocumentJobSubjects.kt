package de.littletrotter.shared.documentjob

/** Default NATS subject names of the document job pipeline; deployments may override them by configuration. */
object DocumentJobSubjects {
  const val JOBS = "document-jobs"
  /** Jobs a user waits for in the UI; consumed by dedicated workers so bulk jobs never delay them. */
  const val INTERACTIVE_JOBS = "document-jobs-interactive"
  const val RESULTS = "document-job-results"
  const val JOBS_DLQ = "document-jobs-dlq"
  const val RESULTS_DLQ = "document-job-results-dlq"
  /** Requests for the asynchronous PDF conformance check; the PDF itself stays in object storage. */
  const val PDF_CHECKS = "pdf-checks"
  const val PDF_CHECK_RESULTS = "pdf-check-results"
  const val PDF_CHECKS_DLQ = "pdf-checks-dlq"
}
