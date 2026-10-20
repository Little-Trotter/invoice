package de.littletrotter.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.application.invoice.MaintenanceQueueProcessor
import de.littletrotter.application.system.maintenanceBestEffort
import de.littletrotter.application.system.port.outbound.MaintenanceWakeupPublisher
import de.littletrotter.shared.MaintenanceWakeup
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * CORE NATS wake-up hint adapter. Hints are deliberately non-durable: the maintenance table is
 * authoritative and startup/periodic recovery handles a lost notification. The connection is
 * resolved only when the worker scheduler is enabled or a publisher call is made.
 */
@Startup
@ApplicationScoped
class MaintenanceWakeupNatsAdapter @Inject constructor(
    private val maintenanceQueueProcessor: MaintenanceQueueProcessor,
    private val connections: Instance<Connection>,
    @param:ConfigProperty(name = "nats.maintenance.instance", defaultValue = "default")
    instance: String,
    @param:ConfigProperty(name = "quarkus.scheduler.enabled", defaultValue = "false")
    private val schedulerEnabled: Boolean,
) : MaintenanceWakeupPublisher {

    private val log = Logger.getLogger(MaintenanceWakeupNatsAdapter::class.java)
    private val mapper = jacksonObjectMapper()
    private val subject = MaintenanceWakeup.subject(instance)
    private var dispatcher: Dispatcher? = null
    private var subscriptionConnection: Connection? = null

    @PostConstruct
    fun subscribe() {
        if (!schedulerEnabled) return
        maintenanceBestEffort({ ex ->
            log.warn("Maintenance wake-up subscription unavailable; durable recovery remains active", ex)
        }) {
            val connection = connections.get()
            subscriptionConnection = connection
            dispatcher = connection.createDispatcher { message ->
                maintenanceBestEffort({ ex -> log.warn("Ignoring invalid maintenance wake-up hint", ex) }) {
                    val wakeup = mapper.readValue(message.data, MaintenanceWakeup::class.java)
                    if (wakeup.version == MaintenanceWakeup.CURRENT_VERSION) {
                        maintenanceQueueProcessor.requestDrain()
                    }
                }
            }
            dispatcher!!.subscribe(subject)
        }
    }

    override fun publish(requestId: Long) {
        maintenanceBestEffort({ ex -> log.warn("Could not publish maintenance wake-up hint for request $requestId", ex) }) {
            val payload = mapper.writeValueAsBytes(MaintenanceWakeup(MaintenanceWakeup.CURRENT_VERSION, requestId))
            connections.get().publish(subject, payload)
        }
    }

    @PreDestroy
    fun close() {
        val currentDispatcher = dispatcher
        val currentConnection = subscriptionConnection
        if (currentDispatcher != null && currentConnection != null) {
            currentConnection.closeDispatcher(currentDispatcher)
        }
        dispatcher = null
        subscriptionConnection = null
    }
}
