package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.masterdata.CustomerDeleted
import de.littletrotter.shared.masterdata.MasterdataEntityTypes
import de.littletrotter.shared.masterdata.MasterdataSubjects
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.SnapshotChunk
import de.littletrotter.shared.masterdata.SnapshotRequested
import de.littletrotter.shared.masterdata.TaxRateDeleted
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MasterdataSnapshotServiceTest {

    private val source = mock<MasterdataEventSource>()
    private val jetStream = mock<JetStream>()
    private val service = MasterdataSnapshotService(source, jetStream)

    private fun stubEmpty() {
        MasterdataAggregate.entries.forEach { whenever(source.page(eq(it), any(), any())).thenReturn(emptyList()) }
    }

    private fun published(count: Int): List<SnapshotChunk> {
        val payloads = argumentCaptor<ByteArray>()
        verify(jetStream, times(count)).publish(eq(MasterdataSubjects.SNAPSHOT_CHUNK), payloads.capture(), any<PublishOptions>())
        return payloads.allValues.map { MasterdataWireCodec.decode(String(it, Charsets.UTF_8), SnapshotChunk::class.java) }
    }

    @Test
    fun `an empty inventory is answered with one empty final chunk`() {
        stubEmpty()

        assertEquals(1, service.answer(SnapshotRequested("r")))

        val chunk = published(1).single()
        assertTrue(chunk.last)
        assertEquals(0, chunk.sequence)
        assertTrue(chunk.entries.isEmpty())
    }

    @Test
    fun `entries are grouped by type in delivery order and only the final chunk is marked`() {
        stubEmpty()
        whenever(source.page(eq(MasterdataAggregate.CUSTOMER), eq(0L), any())).thenReturn(listOf(1L to CustomerDeleted(1)))
        whenever(source.page(eq(MasterdataAggregate.TAX_RATE), eq(0L), any())).thenReturn(listOf(2L to TaxRateDeleted(2)))

        assertEquals(2, service.answer(SnapshotRequested("r")))

        val chunks = published(2)
        assertEquals(listOf(MasterdataEntityTypes.TAX_RATE, MasterdataEntityTypes.CUSTOMER), chunks.map { it.entityType })
        assertEquals(listOf(0, 1), chunks.map { it.sequence })
        assertEquals(listOf(false, true), chunks.map { it.last })
    }

    @Test
    fun `a request for one type skips the others`() {
        stubEmpty()
        whenever(source.page(eq(MasterdataAggregate.CUSTOMER), eq(0L), any())).thenReturn(listOf(1L to CustomerDeleted(1)))

        service.answer(SnapshotRequested("r", listOf(MasterdataEntityTypes.CUSTOMER)))

        verify(source, times(0)).page(eq(MasterdataAggregate.TAX_RATE), any(), any())
        assertEquals(1, published(1).single().entries.size)
    }

    @Test
    fun `full pages are followed until a short page and chunks split below the budget`() {
        stubEmpty()
        val fat = mapOf("text" to "x".repeat(5_000))
        val first = (1L..100L).map { it to fat }
        val second = listOf(101L to fat)
        whenever(source.page(eq(MasterdataAggregate.ARTICLE), eq(0L), any())).thenReturn(first)
        whenever(source.page(eq(MasterdataAggregate.ARTICLE), eq(100L), any())).thenReturn(second)

        val count = service.answer(SnapshotRequested("r", listOf(MasterdataEntityTypes.ARTICLE)))

        val chunks = published(count)
        assertEquals(101, chunks.sumOf { it.entries.size })
        assertTrue(chunks.all { MasterdataWireCodec.sizeInBytes(MasterdataWireCodec.encode(it)) < MasterdataWireCodec.MAX_ENVELOPE_BYTES })
        assertEquals((0 until count).toList(), chunks.map { it.sequence })
        assertEquals(listOf(true), chunks.map { it.last }.filter { it })
    }

    @Test
    fun `an entry that cannot fit a chunk fails the answer`() {
        stubEmpty()
        whenever(source.page(eq(MasterdataAggregate.CUSTOMER), eq(0L), any()))
            .thenReturn(listOf(1L to mapOf("text" to "x".repeat(40_000))))

        assertThrows(IllegalStateException::class.java) { service.answer(SnapshotRequested("r")) }
    }
}
