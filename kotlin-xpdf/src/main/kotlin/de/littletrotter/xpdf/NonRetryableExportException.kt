package de.littletrotter.xpdf

/**
 * Base for an export failure that is a property of the invoice itself: rendering the same invoice
 * again yields the same failure, so a retry only repeats the full render and the backoff wait
 * while holding the worker thread that every other queued job is waiting for. The job dispatcher
 * reports such a failure at once instead of retrying it.
 */
abstract class NonRetryableExportException(message: String) : RuntimeException(message)
