package de.littletrotter.fixture

import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import java.time.OffsetDateTime

/**
 * Property-style access to the stored state of a [GenerationJob], as a loaded row carries it.
 *
 * A test needs a job in any status without walking the state machine, plus the creation fields and timestamps
 * a loaded row carries; none of these has a single-field write path.
 */
class GenerationJobEditor internal constructor(val entity: GenerationJob) {
    var lastError: String?
        get() = entity.lastError
        set(value) { if (value != null) entity.recordLastError(value) else PersistenceEmulation.hydrate(entity, "lastError", null) }
    var delegatedStreamSeq: Long?
        get() = entity.delegatedStreamSeq
        set(value) { if (value != null) entity.recordDelegatedStreamSeq(value) else PersistenceEmulation.hydrate(entity, "delegatedStreamSeq", null) }
    var resultRefs: String?
        get() = entity.resultRefs
        set(value) { if (value != null) entity.updateResultRefs(value) else PersistenceEmulation.hydrate(entity, "resultRefs", null) }
    var id: Long?
        get() = entity.id
        set(value) { PersistenceEmulation.assignId(entity, value) }
    var invoice: Invoice?
        get() = entity.invoice
        set(value) { PersistenceEmulation.hydrate(entity, "invoice", value) }
    var jobType: String
        get() = entity.jobType
        set(value) { PersistenceEmulation.hydrate(entity, "jobType", value) }
    var status: GenerationJobStatus
        get() = entity.status
        set(value) { PersistenceEmulation.hydrate(entity, "status", value) }
    var kositReport: String?
        get() = entity.kositReport
        set(value) { PersistenceEmulation.hydrate(entity, "kositReport", value) }
    var correlationId: String?
        get() = entity.correlationId
        set(value) { PersistenceEmulation.hydrate(entity, "correlationId", value) }
    var createdAt: OffsetDateTime
        get() = entity.createdAt
        set(value) { PersistenceEmulation.hydrate(entity, "createdAt", value) }
    var startedAt: OffsetDateTime?
        get() = entity.startedAt
        set(value) { PersistenceEmulation.hydrate(entity, "startedAt", value) }
    var finishedAt: OffsetDateTime?
        get() = entity.finishedAt
        set(value) { PersistenceEmulation.hydrate(entity, "finishedAt", value) }
    var delegatedAt: OffsetDateTime?
        get() = entity.delegatedAt
        set(value) { PersistenceEmulation.hydrate(entity, "delegatedAt", value) }
    var attempts: Int
        get() = entity.attempts
        set(value) { PersistenceEmulation.hydrate(entity, "attempts", value) }
    var llmStatus: String?
        get() = entity.llmStatus
        set(value) { PersistenceEmulation.hydrate(entity, "llmStatus", value) }
    var llmOptimize: Boolean
        get() = entity.llmOptimize
        set(value) { PersistenceEmulation.hydrate(entity, "llmOptimize", value) }
    var issued: Boolean
        get() = entity.issued
        set(value) { PersistenceEmulation.hydrate(entity, "issued", value) }
    var trigger: GenerationJobTrigger
        get() = entity.trigger
        set(value) { PersistenceEmulation.hydrate(entity, "trigger", value) }
    var lane: DocumentJobLane
        get() = entity.lane
        set(value) { PersistenceEmulation.hydrate(entity, "lane", value) }
}

/** Edits [GenerationJob] in place through its domain operations. */
fun GenerationJob.edit(block: GenerationJobEditor.() -> Unit): GenerationJob = also { GenerationJobEditor(it).block() }

/**
 * A blank generation job; scenarios that walk the state machine call its transition methods on the result.
 */
fun aGenerationJob(block: GenerationJobEditor.() -> Unit = {}): GenerationJob = GenerationJob().edit(block)
