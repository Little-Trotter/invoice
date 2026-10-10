package de.littletrotter.application.system

/**
 * Application-layer port for querying runtime system / dependency status.
 * Implementations live in the infrastructure layer (e.g. DockerStatusService).
 */
fun interface SystemStatusService {

    fun listDependencies(): List<ServiceStatus>

    data class ServiceStatus(
        val key: String,
        val instanceName: String,
        val target: String,
        val up: Boolean,
        val version: String = "",
    )
}
