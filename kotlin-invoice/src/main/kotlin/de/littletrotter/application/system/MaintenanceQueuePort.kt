package de.littletrotter.application.system

import de.littletrotter.domain.system.DatabaseMaintenanceType

/**
 * Durable ordered queue boundary for database maintenance.
 *
 * The lease is cross-process exclusive and remains held while handlers run. Each lease operation
 * uses its own short database transaction; the lease must not introduce one transaction around a
 * complete sweep.
 */
interface MaintenanceQueuePort {
    fun <T> withExclusiveDrainLease(block: MaintenanceDrainLease.() -> T): LeaseAttempt<T>

    sealed interface LeaseAttempt<out T> {
        data object Busy : LeaseAttempt<Nothing>
        data class Acquired<T>(val result: T) : LeaseAttempt<T>
    }
}

interface MaintenanceDrainLease : MaintenanceExecutionFence {
    /** Verifies that this session still owns the cross-process drain lock. */
    fun verifyOwnership()

    /** Recovers only versioned, retry-safe claims and terminalizes unknown historical RUNNING rows. */
    fun recoverInterruptedClaims(): RecoveryResult

    /** Claims the next safe request in durable order, or reports why no request can run. */
    fun claimNext(): ClaimResult

    /** Atomically writes the handler outcome onto the original claimed queue row. */
    fun complete(claim: ClaimedMaintenanceJob, outcome: MaintenanceOperationOutcome)

    data class RecoveryResult(
        val terminalizedVersionedClaims: Int,
        val terminalizedHistoricalClaims: Int,
    )

    sealed interface ClaimResult {
        data object Empty : ClaimResult
        data class Blocked(val details: String) : ClaimResult
        data class Claimed(val job: ClaimedMaintenanceJob) : ClaimResult
    }

    data class ClaimedMaintenanceJob(
        val eventId: Long,
        val type: DatabaseMaintenanceType,
        val claimToken: Long,
    )
}

class MaintenanceDrainLockLostException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
