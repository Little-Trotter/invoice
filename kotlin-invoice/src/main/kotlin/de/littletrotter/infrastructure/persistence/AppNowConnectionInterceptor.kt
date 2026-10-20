package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.time.TimeSource
import io.agroal.api.AgroalPoolInterceptor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection

/**
 * Hands the application's notion of "now" to the database on every connection taken from the
 * pool, as the session setting `app.now`.
 *
 * The database never reads its own clock: the invoice audit trigger stamps `occurred_at` from
 * `app.now` and rejects a write that arrives without it. A connection is taken once per
 * transaction and once per non-transactional use, so the value is always the time source's
 * reading at the start of the unit of work — and a test that fixes or moves the time source moves
 * the database's timestamps with it.
 *
 * Session level rather than transaction local: the pool may hand the connection out before the
 * transaction begins, and a transaction-local value would vanish with the implicit one around this
 * statement. The next acquisition overwrites it, so no stale value outlives a unit of work.
 */
@ApplicationScoped
class AppNowConnectionInterceptor @Inject constructor(
    private val time: TimeSource,
) : AgroalPoolInterceptor {

    override fun onConnectionAcquire(connection: Connection) {
        connection.prepareStatement(SET_APP_NOW).use { statement ->
            statement.setString(1, time.nowUtc().toString())
            statement.execute()
        }
    }

    private companion object {
        const val SET_APP_NOW = "SELECT set_config('app.now', ?, false)"
    }
}
