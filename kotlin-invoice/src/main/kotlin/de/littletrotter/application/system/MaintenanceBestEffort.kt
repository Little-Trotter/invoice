package de.littletrotter.application.system

import java.io.IOException
import java.util.concurrent.RejectedExecutionException

/**
 * Runs a best-effort maintenance side effect (a post-commit wake-up hint, a recovery scan
 * hand-off, a NATS hint subscription/publication): the durable `database_maintenance_event`
 * table remains authoritative, so a failure here must never fail a committed business
 * transaction, block application startup, or stop a periodic scheduler tick.
 *
 * Catches exactly the narrow, concrete exception types the guarded executor hand-off and Core
 * NATS/Jackson calls in this subsystem actually throw ([IllegalStateException] from the NATS
 * client when the connection is closed/draining, [RejectedExecutionException] from a
 * shutting-down [org.eclipse.microprofile.context.ManagedExecutor], [IOException] from Jackson
 * (de)serialization of [MaintenanceWakeup][de.littletrotter.shared.MaintenanceWakeup]) and
 * reports the failure through [onFailure]. Any other exception is a programming error and is
 * intentionally allowed to escape rather than being silently discarded.
 */
internal inline fun <T> maintenanceBestEffort(onFailure: (Exception) -> Unit, block: () -> T): T? =
    try {
        block()
    } catch (ex: IllegalStateException) {
        onFailure(ex)
        null
    } catch (ex: RejectedExecutionException) {
        onFailure(ex)
        null
    } catch (ex: IOException) {
        onFailure(ex)
        null
    }
