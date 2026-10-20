package de.littletrotter.application.system.port.inbound

import de.littletrotter.application.dto.NatsStatus

/** Input port for the System screen's message broker health and throughput card. */
fun interface NatsStatusQueryPort {
    /** The broker's current reachability, lag and per-subject counts. */
    fun currentStatus(): NatsStatus
}
