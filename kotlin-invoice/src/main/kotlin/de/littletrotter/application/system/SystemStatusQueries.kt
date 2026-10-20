package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.SystemStatusQueryPort
import de.littletrotter.application.system.port.outbound.SystemStatusService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.dto.ServiceStatus

/** Implements [SystemStatusQueryPort] on top of the runtime status adapter. */
@ApplicationScoped
class SystemStatusQueries @Inject constructor(
    private val statusService: SystemStatusService,
) : SystemStatusQueryPort {
    override fun listDependencies(): List<ServiceStatus> = statusService.listDependencies()
}
