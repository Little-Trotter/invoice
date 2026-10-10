package de.littletrotter.infrastructure.messaging

import jakarta.enterprise.context.ApplicationScoped

/**
 * Hand-over point between the NATS connection and the outbox relay: the relay needs the
 * connection to publish, so the connection cannot depend on the relay. The relay registers its
 * run here and the connection calls it when a lost broker connection is back.
 */
@ApplicationScoped
class NatsReconnectListener {

    @Volatile
    var onReconnected: () -> Unit = {}

    fun reconnected() = onReconnected()
}
