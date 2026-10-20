package de.littletrotter.test

import java.sql.Connection
import java.time.Instant

/**
 * Stamps the session-level `app.now` GUC that [de.littletrotter.infrastructure.persistence.AppNowConnectionInterceptor]
 * sets on every connection Agroal hands out of the pool. A connection opened directly via
 * `DriverManager` (or any other hand-built `DataSource`) bypasses that pool entirely, so without
 * this call the invoice audit trigger sees no `app.now` and rejects the write outright — the
 * database still never invents a time, it just has nobody to ask, and a raw JDBC test is that
 * nobody unless it says so itself.
 */
fun Connection.withAppNow(now: Instant = FIXED_INSTANT): Connection {
    prepareStatement("SELECT set_config('app.now', ?, false)").use { statement ->
        statement.setString(1, now.toString())
        statement.execute()
    }
    return this
}
