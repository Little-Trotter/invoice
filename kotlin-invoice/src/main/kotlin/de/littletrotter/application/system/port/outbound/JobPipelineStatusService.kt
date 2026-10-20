package de.littletrotter.application.system.port.outbound

import de.littletrotter.application.dto.JobPipelineStatus

/**
 * Application-layer port for the NATS panel's pipeline row. The view reads only this
 * (never NATS or [de.littletrotter.domain.repository.GenerationJobRepository] directly, per
 * [de.littletrotter.ui.view.system.NatsPanel]'s own constraints).
 */
fun interface JobPipelineStatusService {
    fun currentStatus(): JobPipelineStatus
}
