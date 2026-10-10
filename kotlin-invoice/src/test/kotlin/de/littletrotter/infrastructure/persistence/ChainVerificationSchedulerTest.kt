package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.compliance.ChainVerificationOutcome
import de.littletrotter.application.compliance.ChainVerificationService
import de.littletrotter.application.compliance.InvoiceAuditVerificationResult
import de.littletrotter.application.compliance.NumberJournalVerificationResult
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.compliance.InvoiceAuditChainVerification
import de.littletrotter.domain.compliance.NumberJournalRangeState
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import io.quarkus.scheduler.Scheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [ChainVerificationScheduler.isDue] against a controlled [Clock], proving the
 * fresh-deployment / restart-mid-interval / restart-after-elapsed-interval cases by an
 * invocation-count assertion on [ChainVerificationService.verifyAllChains] rather than by reading
 * the `@Scheduled` annotation.
 */
class ChainVerificationSchedulerTest {

    private val chainVerificationService = mock<ChainVerificationService>()
    private val maintenanceService = mock<DatabaseMaintenanceService>()

    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")

    private fun schedulerAt(instant: Instant) =
        ChainVerificationScheduler(chainVerificationService, maintenanceService, de.littletrotter.shared.time.ClockTimeSource.fixedAt(instant))

    private fun eventAt(instant: Instant) = DatabaseMaintenanceEvent(
        createdAt = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
        type = DatabaseMaintenanceType.CHAIN_VERIFICATION,
        status = DatabaseMaintenanceStatus.SUCCESS,
    )

    private fun cleanOutcome() = ChainVerificationOutcome(
        numberJournal = NumberJournalVerificationResult(checked = 0, breaks = emptyMap()),
        invoiceAudit = InvoiceAuditVerificationResult(checked = 0, verification = InvoiceAuditChainVerification.Valid),
    )

    @Test
    fun `a fresh deployment with no prior event runs immediately`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)).thenReturn(null)
        whenever(chainVerificationService.verifyAllChains()).thenReturn(cleanOutcome())

        schedulerAt(now).verifyChains()

        verify(chainVerificationService).verifyAllChains()
    }

    @Test
    fun `a restart mid-interval skips the run`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.minus(java.time.Duration.ofHours(2))))

        schedulerAt(now).verifyChains()

        verify(chainVerificationService, never()).verifyAllChains()
    }

    @Test
    fun `a restart after the interval has already elapsed runs`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.minus(java.time.Duration.ofHours(25))))
        whenever(chainVerificationService.verifyAllChains()).thenReturn(cleanOutcome())

        schedulerAt(now).verifyChains()

        verify(chainVerificationService).verifyAllChains()
    }

    @Test
    fun `isDue is false exactly at the boundary and true one second past it`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.minus(ChainVerificationScheduler.DUE_INTERVAL).plusSeconds(1)))
        assertFalse(schedulerAt(now).isDue(), "one second short of the interval must not be due yet")

        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.minus(ChainVerificationScheduler.DUE_INTERVAL)))
        assertTrue(schedulerAt(now).isDue(), "exactly the interval since the last run must already be due")
    }

    // ── ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - a due run with an intact chain verifies both journals exactly once`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.minus(Duration.ofDays(2))))
        whenever(chainVerificationService.verifyAllChains()).thenReturn(cleanOutcome())

        schedulerAt(now).verifyChains()

        verify(chainVerificationService, times(1)).verifyAllChains()
    }

    @Test
    fun `boundary - a tampered invoice audit row is reported as a break and the run is not retried in the same tick`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)).thenReturn(null)
        val broken = InvoiceAuditChainVerification.Broken(atSeq = 42, reason = "entry_hash does not match the entry's own content")
        val outcome = cleanOutcome().copy(invoiceAudit = InvoiceAuditVerificationResult(checked = 41, verification = broken))
        whenever(chainVerificationService.verifyAllChains()).thenReturn(outcome)

        schedulerAt(now).verifyChains()

        assertTrue(outcome.hasBreak, "a broken invoice audit chain must surface as a break")
        verify(chainVerificationService, times(1)).verifyAllChains()
    }

    @Test
    fun `boundary - a deleted number journal row alone is enough to report a break`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION)).thenReturn(null)
        val outcome = cleanOutcome().copy(
            numberJournal = NumberJournalVerificationResult(
                checked = 3,
                breaks = mapOf(7L to NumberJournalRangeState.Broken(atSeq = 3, reason = "prevHash does not match the previous entry_hash")),
            ),
        )
        whenever(chainVerificationService.verifyAllChains()).thenReturn(outcome)

        schedulerAt(now).verifyChains()

        assertTrue(outcome.hasBreak, "a number_journal break with an intact audit chain must still surface as a break")
        verify(chainVerificationService, times(1)).verifyAllChains()
    }

    @Test
    fun `boundary - a verification still running is never joined by an overlapping tick`() {
        val scheduled = ChainVerificationScheduler::class.java.getMethod("verifyChains").getAnnotation(Scheduled::class.java)

        assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution)
        assertEquals("24h", scheduled.every)
    }

    @Test
    fun `boundary - a run recorded in the future (clock skew) is not due`() {
        whenever(maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.CHAIN_VERIFICATION))
            .thenReturn(eventAt(now.plus(Duration.ofHours(1))))

        schedulerAt(now).verifyChains()

        verify(chainVerificationService, never()).verifyAllChains()
    }
}
