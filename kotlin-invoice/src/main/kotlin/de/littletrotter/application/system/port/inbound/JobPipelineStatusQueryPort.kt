package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.dto.JobPipelineStatus

/** Input port for the System screen's document job pipeline row. */
fun interface JobPipelineStatusQueryPort {
    /** Pending, delegated and failed job counts as they stand now. */
    fun currentStatus(): JobPipelineStatus
}
