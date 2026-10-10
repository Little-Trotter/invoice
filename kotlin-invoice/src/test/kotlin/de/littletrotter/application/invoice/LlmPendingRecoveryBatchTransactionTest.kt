package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [LlmPendingRecoveryBatchTransaction] exists purely to give [GenerationJobRecoveryService
 * .resolveStaleLlmPendingForIds]'s per-batch write a genuinely separate `@Transactional` scope
 * (see that class's own KDoc); [resolveBatch] itself is a one-line delegation. These tests pin
 * that the exact id list is forwarded unmodified, the collaborator's own result is returned
 * unchanged, and a failure from it is never swallowed here.
 *
 * The fake now directly subclasses [GenerationJobRecoveryService] (CDI-allopen like the
 * dissolved `GenerationJobService` facade it replaces); the other constructor collaborators are
 * irrelevant once [resolveStaleLlmPendingForIds] itself is overridden.
 */
class LlmPendingRecoveryBatchTransactionTest {

    private fun stubRepo() = object : GenerationJobRepository {
        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = emptyList()
        override fun findPending(): List<GenerationJob> = emptyList()
        override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        override fun findById(id: Long): GenerationJob? = null
        override fun save(job: GenerationJob): GenerationJob = job
        override fun tryMarkDelegated(id: Long): Boolean = true
    }

    private fun recoveryService(resolve: (List<Long>) -> Int) = object : GenerationJobRecoveryService(
        stubRepo(),
        mock<InvoiceRepository>(),
        mock<GenerationJobEventDispatcher>(),
        mock<GenerationJobLlmResultHandler>(), time = FIXED_TIME_SOURCE,
    ) {
        override fun resolveStaleLlmPendingForIds(ids: List<Long>): Int = resolve(ids)
    }

    @Test
    fun `happy path - resolveBatch delegates the exact id list to the collaborator and returns its result`() {
        var receivedIds: List<Long>? = null
        val ops = recoveryService { ids -> receivedIds = ids; 2 }

        val result = LlmPendingRecoveryBatchTransaction(ops).resolveBatch(listOf(10L, 11L))

        assertEquals(2, result)
        assertEquals(listOf(10L, 11L), receivedIds)
    }

    @Test
    fun `boundary - resolveBatch with an empty id list still delegates and returns zero`() {
        val ops = recoveryService { ids -> ids.size }

        val result = LlmPendingRecoveryBatchTransaction(ops).resolveBatch(emptyList())

        assertEquals(0, result)
    }

    @Test
    fun `boundary - resolveBatch propagates an exception raised by the collaborator instead of swallowing it`() {
        val ops = recoveryService { throw IllegalStateException("db down") }

        assertThrows(IllegalStateException::class.java) { LlmPendingRecoveryBatchTransaction(ops).resolveBatch(listOf(1L)) }
    }

    @Test
    fun `boundary - resolveBatch returns the collaborator's own count unchanged for a single id`() {
        val ops = recoveryService { 1 }

        val result = LlmPendingRecoveryBatchTransaction(ops).resolveBatch(listOf(99L))

        assertEquals(1, result)
    }
}
