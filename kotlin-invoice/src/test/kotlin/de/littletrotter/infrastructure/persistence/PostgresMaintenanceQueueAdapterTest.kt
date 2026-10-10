package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.FIXED_TIME_SOURCE
import io.agroal.api.AgroalDataSource
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.InvocationTargetException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * [PostgresMaintenanceQueueAdapter.releaseLease]'s own `connection.abort { ... }` failure
 * is folded (`addSuppressed`) onto the release failure that triggered it in the first place
 * (line 59 of the production class). `PostgresMaintenanceQueueAdapterIT` reaches the release
 * failure itself with a real, genuinely-never-acquired connection (same technique as its
 * `verifyOwnership` tests), but a real, otherwise-healthy pooled connection's own `abort()` never
 * fails — only a broken connection does, which is exactly what a mocked [Connection] is for here:
 * this is exception-composition bookkeeping, not database interaction, so it needs no real Postgres.
 */
class PostgresMaintenanceQueueAdapterTest {

    private fun adapter() = PostgresMaintenanceQueueAdapter(dataSource = mock<AgroalDataSource>(), em = mock<EntityManager>(), time = FIXED_TIME_SOURCE)

    @Test
    fun `boundary - a connection abort failure during release is suppressed onto the release failure itself, not thrown separately`() {
        val connection = mock<Connection>()
        whenever(connection.prepareStatement(any<String>())).thenThrow(SQLException("connection already broken"))
        whenever(connection.abort(any())).thenThrow(SQLException("abort also failed"))
        val releaseLease = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("releaseLease", Connection::class.java, Throwable::class.java)
            .apply { isAccessible = true }

        val invocationFailure = assertThrows<InvocationTargetException> {
            releaseLease.invoke(adapter(), connection, null)
        }

        val cause = assertInstanceOf(SQLException::class.java, invocationFailure.targetException)
        assertEquals("connection already broken", cause.message)
        assertEquals(1, cause.suppressed.size, "the abort failure must be recorded as suppressed on the release failure")
        assertInstanceOf(SQLException::class.java, cause.suppressed[0])
        assertEquals("abort also failed", cause.suppressed[0].message)
    }

    /** `pg_try_advisory_lock`/`pg_advisory_unlock` always return exactly one row against
     *  a real Postgres, so `result.next() == false` never happens through the IT's genuine
     *  connections; a mocked [ResultSet] is the only way to reach this defensive branch. */
    private fun connectionReturning(noRow: Boolean): Connection {
        val resultSet = mock<ResultSet>()
        whenever(resultSet.next()).thenReturn(!noRow)
        whenever(resultSet.getBoolean(1)).thenReturn(true)
        val statement = mock<PreparedStatement>()
        whenever(statement.executeQuery()).thenReturn(resultSet)
        val connection = mock<Connection>()
        whenever(connection.prepareStatement(any<String>())).thenReturn(statement)
        return connection
    }

    @Test
    fun `boundary - tryAcquire is false when the advisory-lock query unexpectedly returns no row`() {
        val method = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("tryAcquire", Connection::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(adapter(), connectionReturning(noRow = true)) as Boolean

        assertEquals(false, result)
    }

    @Test
    fun `boundary - release is false when the advisory-unlock query unexpectedly returns no row`() {
        val method = PostgresMaintenanceQueueAdapter::class.java
            .getDeclaredMethod("release", Connection::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(adapter(), connectionReturning(noRow = true)) as Boolean

        assertEquals(false, result)
    }
}
