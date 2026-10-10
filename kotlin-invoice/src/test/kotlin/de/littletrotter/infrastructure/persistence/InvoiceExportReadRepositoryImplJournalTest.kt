package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.export.InvoiceExportReadRepository
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.repository.B2bFieldValueRepository
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

/**
 * [InvoiceExportReadRepositoryImpl.findJournalRows]'s own seq-cursor pagination loop,
 * verified against a mocked [InvoiceAuditJournalRepository] rather than a real Postgres: the loop
 * itself never touches [EntityManager] or [B2bFieldValueRepository], so this is a plain unit test,
 * not an IT. [InvoiceExportReadRepositoryImplIT] covers the same method end to end against a real,
 * trigger-populated journal.
 */
class InvoiceExportReadRepositoryImplJournalTest {

    private val journalRepository: InvoiceAuditJournalRepository = mock()
    private val repository = InvoiceExportReadRepositoryImpl(mock<EntityManager>(), mock<B2bFieldValueRepository>(), journalRepository)

    private fun entry(seq: Long, invoiceId: Long = 1L) = InvoiceAuditJournalEntry(
        seq = seq,
        businessTx = seq,
        invoiceId = invoiceId,
        invoiceNumber = "RE-$seq",
        actor = "actor",
        actorName = null,
        occurredAt = OffsetDateTime.now(),
        event = InvoiceAuditEvent.INVOICE_CREATED,
        action = InvoiceAuditAction.UNSPECIFIED,
        detail = "{}",
        documentSha256 = null,
        prevHash = "p$seq",
        entryHash = "h$seq",
    )

    @Test
    fun `returns an empty list when the invoice has no journal entries`() {
        whenever(journalRepository.streamByInvoiceId(eq(1L), eq(0L), any())).thenReturn(emptyList())

        assertThat(repository.findJournalRows(1L)).isEmpty()
        verify(journalRepository, times(1)).streamByInvoiceId(any(), any(), any())
    }

    @Test
    fun `a page shorter than the batch size ends the sweep without a further call`() {
        whenever(journalRepository.streamByInvoiceId(eq(1L), eq(0L), any())).thenReturn(listOf(entry(1L), entry(2L)))

        val rows = repository.findJournalRows(1L)

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.seq }).containsExactly(1L, 2L)
        verify(journalRepository, times(1)).streamByInvoiceId(any(), any(), any())
    }

    @Test
    fun `a full page continues paging with the last seq as the next cursor, until a short page ends it`() {
        val batchSize = InvoiceExportReadRepository.DEFAULT_BATCH_SIZE
        val firstBatch = (1..batchSize).map { entry(it.toLong()) }
        val secondBatch = listOf(entry((batchSize + 1).toLong()))
        whenever(journalRepository.streamByInvoiceId(1L, 0L, batchSize)).thenReturn(firstBatch)
        whenever(journalRepository.streamByInvoiceId(1L, batchSize.toLong(), batchSize)).thenReturn(secondBatch)

        val rows = repository.findJournalRows(1L)

        assertThat(rows).hasSize(batchSize + 1)
        assertThat(rows.map { it.seq }).isSorted()
        assertThat(rows.map { it.seq }.distinct()).hasSize(batchSize + 1)
        verify(journalRepository).streamByInvoiceId(1L, 0L, batchSize)
        verify(journalRepository).streamByInvoiceId(1L, batchSize.toLong(), batchSize)
    }

    @Test
    fun `a page exactly equal to the batch size followed by an empty page ends the sweep cleanly`() {
        val batchSize = InvoiceExportReadRepository.DEFAULT_BATCH_SIZE
        val firstBatch = (1..batchSize).map { entry(it.toLong()) }
        whenever(journalRepository.streamByInvoiceId(1L, 0L, batchSize)).thenReturn(firstBatch)
        whenever(journalRepository.streamByInvoiceId(1L, batchSize.toLong(), batchSize)).thenReturn(emptyList())

        val rows = repository.findJournalRows(1L)

        assertThat(rows).hasSize(batchSize)
        verify(journalRepository, times(2)).streamByInvoiceId(any(), any(), any())
    }
}
