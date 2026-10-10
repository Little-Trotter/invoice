package de.littletrotter.application.invoice

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The `GenerationJob.resultRefs` JSON codec shared by [GenerationJobResultHandler],
 * [GenerationJobLlmResultHandler] and [GenerationJobRecoveryService] (moved verbatim from
 * `GenerationJobService`'s own private companion object, which all three now replace).
 */
internal object GenerationJobResultRefsCodec {
    val MAPPER: ObjectMapper = ObjectMapper()
    val MAP_TYPE: TypeReference<Map<String, String>> = object : TypeReference<Map<String, String>>() {}

    /** Key into the job's `result_refs` JSON map carrying the LLM analysis outcome. */
    const val RESULT_REF_LLM_STATUS = "llmStatus"
}
