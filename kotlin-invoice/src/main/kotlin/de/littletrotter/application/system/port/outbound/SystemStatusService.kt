package de.littletrotter.application.system.port.outbound

import de.littletrotter.application.dto.ServiceStatus

/**
 * Application-layer port for querying runtime system / dependency status.
 * Implementations live in the infrastructure layer (e.g. DockerStatusService).
 */
fun interface SystemStatusService {
    fun listDependencies(): List<ServiceStatus>
}
