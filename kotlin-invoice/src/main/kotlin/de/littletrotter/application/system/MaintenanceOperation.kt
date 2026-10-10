package de.littletrotter.application.system

import de.littletrotter.domain.system.DatabaseMaintenanceType

/** Executes the side effect of a claimed maintenance request without changing queue state. */
fun interface MaintenanceOperations {
    fun execute(
        type: DatabaseMaintenanceType,
        fence: MaintenanceExecutionFence,
    ): MaintenanceOperationOutcome
}

/** A typed handler result. Queue state must never be inferred from human-readable details. */
sealed interface MaintenanceOperationOutcome {
    val summary: String
    val details: String

    data class Succeeded(
        override val summary: String,
        override val details: String = summary,
    ) : MaintenanceOperationOutcome

    data class Failed(
        override val summary: String,
        override val details: String = summary,
    ) : MaintenanceOperationOutcome
}

/** Claim-token fence that handlers must validate inside every transaction that changes effects. */
fun interface MaintenanceExecutionFence {
    fun verifyInCurrentTransaction()
}

class MaintenanceExecutionFencedException(message: String) : IllegalStateException(message)
