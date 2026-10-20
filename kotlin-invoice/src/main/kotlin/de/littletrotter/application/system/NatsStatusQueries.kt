package de.littletrotter.application.system

import de.littletrotter.application.system.port.inbound.NatsStatusQueryPort
import de.littletrotter.application.system.port.outbound.NatsStatusService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.dto.NatsStatus

/** Implements [NatsStatusQueryPort] on top of the broker status adapter. */
@ApplicationScoped
class NatsStatusQueries @Inject constructor(
    private val statusService: NatsStatusService,
) : NatsStatusQueryPort {
    override fun currentStatus(): NatsStatus = statusService.currentStatus()
}
