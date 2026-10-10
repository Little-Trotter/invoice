package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import io.quarkus.arc.Arc
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The production request-context factory of [GenerationJobRecoveryStartup], which the
 * unit test replaces by a fake: on a fresh executor thread (like the recovery scan after a
 * restart) it must open a request context for the scan and terminate it again afterwards.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class GenerationJobRecoveryStartupIT {

    @Inject
    lateinit var recovery: GenerationJobRecoveryStartup

    @Test
    fun `happy path the default factory activates a request context for the scan and terminates it`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val states = pool.submit<List<Boolean>> {
                val before = Arc.container().requestContext().isActive
                val during = recovery.requestContextFactory().use { Arc.container().requestContext().isActive }
                val after = Arc.container().requestContext().isActive
                listOf(before, during, after)
            }.get(30, TimeUnit.SECONDS)

            assertEquals(listOf(false, true, false), states)
        } finally {
            pool.shutdownNow()
        }
    }
}
