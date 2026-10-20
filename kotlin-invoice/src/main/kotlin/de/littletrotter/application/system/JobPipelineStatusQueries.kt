package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.JobPipelineStatusQueryPort
import de.littletrotter.application.system.port.outbound.JobPipelineStatusService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.dto.JobPipelineStatus

/** Implements [JobPipelineStatusQueryPort] on top of the pipeline status adapter. */
@ApplicationScoped
class JobPipelineStatusQueries @Inject constructor(
    private val statusService: JobPipelineStatusService,
) : JobPipelineStatusQueryPort {
    override fun currentStatus(): JobPipelineStatus = statusService.currentStatus()
}
