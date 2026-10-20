package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.masterdata.MasterdataEntityTypes
import de.littletrotter.shared.masterdata.MasterdataSubjects
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.SnapshotChunk
import de.littletrotter.shared.masterdata.SnapshotRequested
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Answers a snapshot request with the complete current inventory, in chunks below the envelope
 * limit, on `masterdata.snapshot.chunk`.
 *
 * References come before the aggregates that point at them (tax rates, payment terms, profiles,
 * articles, sellers, customers). Chunks of one answer are numbered gapless and the final one is
 * marked, so a replica can tell a complete snapshot from an interrupted one. Message ids derive
 * from request and sequence, so answering the same request again is dropped by the stream.
 * Nothing runs on a timer: only a request triggers a snapshot.
 */
@ApplicationScoped
class MasterdataSnapshotService @Inject constructor(
    private val source: MasterdataEventSource,
    private val jetStream: JetStream,
) {

    /** Publishes the answer to [request]; returns the number of chunks. */
    fun answer(request: SnapshotRequested): Int {
        val wanted = request.entityTypes ?: MasterdataEntityTypes.ALL
        val writer = ChunkWriter(request.requestId)
        MasterdataAggregate.entries
            .sortedBy { MasterdataEntityTypes.ALL.indexOf(it.type) }
            .filter { it.type in wanted }
            .forEach { aggregate -> stream(aggregate, writer) }
        return writer.finish()
    }

    private fun stream(aggregate: MasterdataAggregate, writer: ChunkWriter) {
        var afterId = 0L
        do {
            val page = source.page(aggregate, afterId, PAGE_SIZE)
            page.forEach { (_, event) -> writer.add(aggregate.type, MasterdataWireCodec.encode(event)) }
            afterId = page.lastOrNull()?.first ?: afterId
        } while (page.size == PAGE_SIZE)
    }

    /**
     * Groups entries of one entity type into chunks. A chunk is held back until its successor
     * exists, because only then it is known whether it is the last one.
     */
    private inner class ChunkWriter(private val requestId: String) {
        private var held: SnapshotChunk? = null
        private var sequence = 0
        private var entityType = ""
        private var entries = mutableListOf<String>()
        private var bytes = 0

        fun add(type: String, entry: String) {
            val size = MasterdataWireCodec.sizeInBytes(MasterdataWireCodec.encode(entry)) + 1
            check(size <= CHUNK_BUDGET) { "$type entry of $size bytes does not fit a snapshot chunk" }
            if (type != entityType || bytes + size > CHUNK_BUDGET) seal()
            entityType = type
            entries.add(entry)
            bytes += size
        }

        /** Publishes what is left, the final chunk marked; returns the number of chunks. */
        fun finish(): Int {
            seal()
            val last = held ?: SnapshotChunk(requestId, 0, MasterdataEntityTypes.ALL.first(), true, emptyList())
            publish(last.copy(last = true))
            return last.sequence + 1
        }

        private fun seal() {
            if (entries.isEmpty()) return
            held?.let(::publish)
            held = SnapshotChunk(requestId, sequence++, entityType, false, entries)
            entries = mutableListOf()
            bytes = 0
        }

        private fun publish(chunk: SnapshotChunk) {
            val options = PublishOptions.builder().messageId("$requestId-${chunk.sequence}").build()
            val payload = MasterdataWireCodec.encode(chunk).toByteArray(Charsets.UTF_8)
            check(payload.size < MasterdataWireCodec.MAX_ENVELOPE_BYTES) { "snapshot chunk of ${payload.size} bytes" }
            jetStream.publish(MasterdataSubjects.SNAPSHOT_CHUNK, payload, options)
        }
    }

    private companion object {
        const val PAGE_SIZE = 100

        /** Leaves room for the chunk's own fields next to its escaped entries. */
        const val CHUNK_BUDGET = MasterdataWireCodec.MAX_ENVELOPE_BYTES - 1024
    }
}
