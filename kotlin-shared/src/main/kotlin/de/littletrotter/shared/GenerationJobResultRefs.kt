package de.littletrotter.shared

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Typed shape for the `resultRefs` envelope that flows kotlin-xpdf (producer,
 * `ResultPublisher`) → NATS `document-job-results` → kotlin-invoice (consumer,
 * `GenerationJobResultConsumer`) → `GenerationJob.resultRefs` (persisted as JSON). Before this
 * type the envelope was a bare `Map<String, String>` and every reader/writer had to know its
 * magic keys (`objectKey`/`downloadUrl`/`zugferdObjectKey`/`formatGuideline`/`llmStatus`/`error`,
 * plus the deferred-LLM-callback keys `llmModel`/`llmReportKey`/`llmError`/`llmDurationMs`, plus
 * `sha256`/`zugferdXmlSha256`) independently — this DTO is the single place that list
 * lives.
 *
 * Follows [KositReportPayload]'s own tolerant-decode pattern: an unrecognized key must never fail
 * decoding, it is simply ignored (same reasoning as that class's own KDoc — a newer producer key
 * an older reader has not learned about yet is "ignored", not "processing failure"). All fields
 * are optional: a given result only ever carries the subset relevant to its own status branch
 * (COMPLETED/FAILED/LLM_COMPLETED/LLM_FAILED — see `JobConsumer`'s branches).
 *
 * The persisted/wire shape is unchanged by this type's introduction: [toStringMap]/[fromStringMap]
 * round-trip through the exact same `Map<String, String>` (only-present-keys) shape the hand-built
 * maps this DTO replaces already produced, so `generation_job.result_refs` stays a plain JSON
 * object column — no migration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class GenerationJobResultRefs(
    val objectKey: String? = null,
    val downloadUrl: String? = null,
    val zugferdObjectKey: String? = null,
    val formatGuideline: String? = null,
    val llmStatus: String? = null,
    val llmModel: String? = null,
    val llmReportKey: String? = null,
    val llmError: String? = null,
    val llmDurationMs: String? = null,
    val error: String? = null,
    /** SHA-256 of the stored [objectKey] (PDF) bytes, see [ExportResult.sha256]. */
    val sha256: String? = null,
    /** SHA-256 of the stored [zugferdObjectKey] (XML) bytes, see
     *  [ExportResult.zugferdXmlSha256]. */
    val zugferdXmlSha256: String? = null,
    /** Object key of the signed archive package (tar.gz), see
     *  [ExportResult.archiveObjectKey]. `null` for a draft export (a package is only built for
     *  issued documents) or a legacy producer predating this field. */
    val archiveObjectKey: String? = null,
    /** SHA-256 of the stored [archiveObjectKey] bytes, see [ExportResult.archiveSha256]. */
    val archiveSha256: String? = null,
) {
    /** Renders as the same "only present keys" `Map<String, String>` shape the wire/JSON column
     *  has always carried — never writes a literal `"null"` string for an absent field. */
    fun toStringMap(): Map<String, String> = buildMap {
        objectKey?.let { put("objectKey", it) }
        downloadUrl?.let { put("downloadUrl", it) }
        zugferdObjectKey?.let { put("zugferdObjectKey", it) }
        formatGuideline?.let { put("formatGuideline", it) }
        llmStatus?.let { put("llmStatus", it) }
        llmModel?.let { put("llmModel", it) }
        llmReportKey?.let { put("llmReportKey", it) }
        llmError?.let { put("llmError", it) }
        llmDurationMs?.let { put("llmDurationMs", it) }
        error?.let { put("error", it) }
        sha256?.let { put("sha256", it) }
        zugferdXmlSha256?.let { put("zugferdXmlSha256", it) }
        archiveObjectKey?.let { put("archiveObjectKey", it) }
        archiveSha256?.let { put("archiveSha256", it) }
    }

    /** `true` when every field is absent — the closed-field equivalent of an empty map. */
    fun isEmpty(): Boolean = toStringMap().isEmpty()

    /**
     * Only the [llmStatus] field, or `null` when it is itself absent — the exact narrowing
     * `GenerationJobResultConsumer`'s FAILED branch forwards into `markFailed`'s
     * `extraResultRefs` (the worker's own llmStatus, deliberately never its [error], which
     * `markFailed`'s own `message` parameter already carries).
     */
    fun onlyLlmStatus(): GenerationJobResultRefs? = llmStatus?.let { GenerationJobResultRefs(llmStatus = it) }

    companion object {
        // Same reasoning as KositReportPayload's own tolerant mapper: an old reader must not
        // throw on a field a newer producer added.
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** Builds an instance from the wire/persisted `Map<String, String>` shape — any key not
         *  in the closed field list above is silently ignored, mirroring [decode]'s tolerance. A
         *  `null`/empty map yields an all-null (empty) instance, never `null` itself. */
        fun fromStringMap(map: Map<String, String>?): GenerationJobResultRefs {
            if (map.isNullOrEmpty()) return GenerationJobResultRefs()
            return GenerationJobResultRefs(
                objectKey = map["objectKey"],
                downloadUrl = map["downloadUrl"],
                zugferdObjectKey = map["zugferdObjectKey"],
                formatGuideline = map["formatGuideline"],
                llmStatus = map["llmStatus"],
                llmModel = map["llmModel"],
                llmReportKey = map["llmReportKey"],
                llmError = map["llmError"],
                llmDurationMs = map["llmDurationMs"],
                error = map["error"],
                sha256 = map["sha256"],
                zugferdXmlSha256 = map["zugferdXmlSha256"],
                archiveObjectKey = map["archiveObjectKey"],
                archiveSha256 = map["archiveSha256"],
            )
        }

        /** Encodes as a JSON object carrying only the present fields. */
        fun encode(refs: GenerationJobResultRefs): String = mapper.writeValueAsString(refs)

        /** Decodes a JSON object payload, or returns `null` when the input is blank/not-JSON. */
        fun decode(json: String?): GenerationJobResultRefs? {
            if (json.isNullOrBlank()) return null
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                mapper.readValue<GenerationJobResultRefs>(trimmed)
            } catch (_: Exception) {
                null
            }
        }
    }
}
