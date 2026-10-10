package de.littletrotter.infrastructure.messaging

import io.quarkus.arc.Arc
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * the production request-context factory of [MailSendResultNatsAdapter], which
 * [MailSendResultNatsAdapterTest] replaces by a fake for every `receive()` test: on a pooled
 * consume thread (like the real JetStream `MessageHandler`) it must open a request context for the
 * message and terminate it again afterwards. `mail.enabled=false` in `%test` keeps `@PostConstruct`
 * from touching NATS, so this only exercises the factory itself, not the subscription.
 */
@QuarkusTest
class MailSendResultNatsAdapterIT {

    @Inject
    lateinit var adapter: MailSendResultNatsAdapter

    @Test
    fun `happy path the default factory activates a request context for the message and terminates it`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val states = pool.submit<List<Boolean>> {
                val before = Arc.container().requestContext().isActive
                val during = adapter.requestContextFactory().use { Arc.container().requestContext().isActive }
                val after = Arc.container().requestContext().isActive
                listOf(before, during, after)
            }.get(30, TimeUnit.SECONDS)

            assertEquals(listOf(false, true, false), states)
        } finally {
            pool.shutdownNow()
        }
    }
}
