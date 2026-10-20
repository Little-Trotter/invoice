package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.dto.ServiceStatus

/** Input port for the System screen's list of runtime dependencies and whether each is up. */
fun interface SystemStatusQueryPort {
    /** Every dependency the application reports on, with its current state. */
    fun listDependencies(): List<ServiceStatus>
}
