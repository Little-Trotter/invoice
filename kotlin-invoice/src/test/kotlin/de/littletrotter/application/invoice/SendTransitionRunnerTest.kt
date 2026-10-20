package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import io.quarkus.narayana.jta.QuarkusTransactionException
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** Retry policy of the ISSUED transition; the transaction boundary itself is covered by the IT. */
class SendTransitionRunnerTest {

    private class StaleObjectStateException : RuntimeException("No rows were returned from JDBC query for versioned entity")

    private class Scripted(private val failures: List<RuntimeException>) : SendTransitionRunner(mock()) {
        var attempts = 0

        override fun inNewTransaction(invoiceId: Long) {
            attempts++
            failures.getOrNull(attempts - 1)?.let { throw it }
        }
    }

    @Test
    fun `succeeds without retry when the first attempt works`() {
        val runner = Scripted(emptyList())
        runner.transitionToSent(1L)
        assertEquals(1, runner.attempts)
    }

    @Test
    fun `retries once after an optimistic lock conflict`() {
        val runner = Scripted(listOf(OptimisticLockException("version bumped")))
        runner.transitionToSent(1L)
        assertEquals(2, runner.attempts)
    }

    @Test
    fun `retries once when the stale-state error is wrapped as a cause`() {
        val runner = Scripted(listOf(QuarkusTransactionException("tx failed", StaleObjectStateException())))
        runner.transitionToSent(1L)
        assertEquals(2, runner.attempts)
    }

    @Test
    fun `gives up after one retry and rethrows the second conflict`() {
        val second = OptimisticLockException("again")
        val runner = Scripted(listOf(OptimisticLockException("first"), second))
        val thrown = assertThrows(OptimisticLockException::class.java) { runner.transitionToSent(1L) }
        assertSame(second, thrown)
        assertEquals(2, runner.attempts)
    }

    @Test
    fun `does not retry a legality failure`() {
        val runner = Scripted(listOf(InvoiceStatusTransitionException("VOIDED", "ISSUED")))
        assertThrows(InvoiceStatusTransitionException::class.java) { runner.transitionToSent(1L) }
        assertEquals(1, runner.attempts)
    }
}
