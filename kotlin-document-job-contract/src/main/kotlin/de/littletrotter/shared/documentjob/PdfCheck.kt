package de.littletrotter.shared.documentjob

import de.littletrotter.shared.ValidationMessage

/**
 * Asks the conformance service to check one stored PDF. A claim check: the PDF stays in object
 * storage under [objectKey], the message carries only the key and the digest the bytes must have.
 * A digest mismatch means the stored object is not the document that was committed, which the
 * checker reports as an engine error instead of validating the wrong file.
 *
 * [schemaVersion] only grows with incompatible changes; additive fields keep the version.
 */
data class PdfCheckRequest(
  val schemaVersion: Int = SCHEMA_VERSION,
  /** Stable per document, so a re-published request is recognised as the same check. */
  val checkId: String,
  /** The generation job that produced the document; the result is appended to it. */
  val jobId: String,
  val objectKey: String,
  val sha256: String,
  val correlationId: String? = null,
) {
  companion object {
    const val SCHEMA_VERSION = 1
  }
}

/**
 * Outcome of one [PdfCheckRequest]. [outcome] is `VALID`, `INVALID` (the document violates the
 * profile) or `ERROR` (the check could not be performed, see [engineError]); an error is never a
 * statement about the document.
 */
data class PdfCheckResult(
  val schemaVersion: Int = PdfCheckRequest.SCHEMA_VERSION,
  val checkId: String,
  val jobId: String,
  val objectKey: String,
  val sha256: String,
  val outcome: String,
  val profile: String,
  val validator: String,
  val failedRuleCount: Int = 0,
  val failedCheckCount: Int = 0,
  val findings: List<ValidationMessage> = emptyList(),
  val engineError: String? = null,
  val correlationId: String? = null,
)
