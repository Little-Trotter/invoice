package de.littletrotter.xpdf.infrastructure.messaging

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.ReceiptDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JobWeightBudgetTest {

    private fun invoice(receiptCount: Int) = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-1"),
        items = emptyList(),
        receipts = List(receiptCount) { ReceiptDto("r$it.pdf", "key/$it") },
    )

    @Test
    fun `weight is the distinct receipt count times the target size in KiB`() {
        assertEquals(0, JobWeightBudget.weightKib(invoice(0)))
        assertEquals(3 * 440, JobWeightBudget.weightKib(invoice(3)))
    }

    @Test
    fun `a job without weight never waits, even when the budget is taken`() {
        val budget = JobWeightBudget(10)
        val inner = budget.admit(10) { budget.admit(0) { "ran" } }

        assertEquals("ran", inner)
    }

    @Test
    fun `a second job waits until the first has given its weight back`() {
        val budget = JobWeightBudget(100)
        val pool = Executors.newFixedThreadPool(2)
        val firstHolds = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        try {
            pool.submit { budget.admit(80) { firstHolds.countDown(); releaseFirst.await() } }
            assertTrue(firstHolds.await(5, TimeUnit.SECONDS))
            val second = pool.submit { budget.admit(80) { secondRan.set(true) } }

            assertThrows(java.util.concurrent.TimeoutException::class.java) { second.get(200, TimeUnit.MILLISECONDS) }
            assertFalse(secondRan.get())

            releaseFirst.countDown()
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondRan.get())
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a job heavier than the whole budget is admitted alone instead of waiting forever`() {
        assertEquals("ran", JobWeightBudget(100).admit(5_000) { "ran" })
    }

    @Test
    fun `the weight is given back when the work fails`() {
        val budget = JobWeightBudget(100)

        assertThrows(IllegalStateException::class.java) { budget.admit(100) { error("boom") } }

        assertEquals("ran", budget.admit(100) { "ran" })
    }

    @Test
    fun `a job heavier than the budget runs alone and a second job waits for it`() {
        val budget = JobWeightBudget(100)
        val heavyRunning = CountDownLatch(1)
        val releaseHeavy = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit { budget.admit(5_000) { heavyRunning.countDown(); releaseHeavy.await(5, TimeUnit.SECONDS) } }
            assertTrue(heavyRunning.await(5, TimeUnit.SECONDS))
            val second = pool.submit { budget.admit(1) { secondRan.set(true) } }

            Thread.sleep(200)
            assertFalse(secondRan.get(), "the clamped heavy job holds the whole budget")
            releaseHeavy.countDown()
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondRan.get())
        } finally {
            releaseHeavy.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the producer prefers the configured budget over the derived one`() {
        assertEquals(1234, JobWeightBudgetProducer().jobWeightBudget(java.util.Optional.of(1234)).budgetKib)
        assertEquals(
            RenderSizing.resolve().weightBudgetKib,
            JobWeightBudgetProducer().jobWeightBudget(java.util.Optional.empty()).budgetKib,
        )
    }
}
