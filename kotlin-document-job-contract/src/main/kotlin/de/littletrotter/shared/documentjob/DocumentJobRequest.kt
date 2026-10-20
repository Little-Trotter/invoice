package de.littletrotter.shared.documentjob

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A document job as it travels on the jobs subject. Field order is the wire order the invoice
 * producer has always written; absent optional fields are omitted, never written as `null`, so a
 * message from an older producer and one from this type are indistinguishable to a reader.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentJobRequest(
  val id: String,
  val type: String,
  val correlationId: String? = null,
  /**
   * Rendering engine name resolved by the producer. A plain string, not an enum: the engine
   * catalogue belongs to the producer's domain and an unknown value must stay readable so the
   * worker can name it in a failure result. Absent reads as the original Mustang path.
   */
  val engine: String? = null,
  val payload: Map<String, Any>? = null,
  val options: DocumentJobOptions? = null,
  /**
   * Subject the worker publishes this job's result on. Absent means [DocumentJobSubjects.RESULTS],
   * so producers that predate the field keep working unchanged and their messages stay
   * byte-identical; read it through [resultSubjectOrDefault].
   */
  val resultSubject: String? = null,
) {
  /** The subject the result goes to, falling back to the shared results subject. */
  @JsonIgnore
  fun resultSubjectOrDefault(): String = resultSubject ?: DocumentJobSubjects.RESULTS
}

/**
 * Per-job switches. Only switched-on values are written (class-level default suppression), which
 * is exactly how the producer has always built the map; an absent key reads as `false`.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class DocumentJobOptions(
  /** When true, the worker sends the ZUGFeRD XML and the KoSIT result to the LLM and stores an improvement specification. */
  val llmOptimize: Boolean = false,
  /** When true, the worker skips the KoSIT validation step entirely. */
  val skipValidation: Boolean = false,
  /** Legacy name from before the LLM unification; kept so queued older messages still activate the LLM step. */
  val ollamaOptimize: Boolean = false,
  /**
   * When true, the optional veraPDF accreditation layer runs on top of the mandatory PDF/A
   * baseline. Defaults to `false` so a message from a producer that predates the option never
   * gains a check it did not ask for during a rolling deploy.
   */
  val veraPdfCheck: Boolean = false,
  /**
   * Whether the embedded KoSIT validator runs for documents other than XRechnung. Defaults to
   * `true` and only `false` is written, so a message from a producer that predates the option
   * keeps validating exactly as before.
   */
  val kositCheck: Boolean = true,
  val outputIntent: String? = null,
)
