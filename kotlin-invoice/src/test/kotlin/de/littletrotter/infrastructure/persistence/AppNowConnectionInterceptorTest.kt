package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.FIXED_TIME_SOURCE
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import java.sql.Connection
import java.sql.PreparedStatement

class AppNowConnectionInterceptorTest {

    @Test
    fun `every acquired connection carries the time source's UTC reading as app now`() {
        val statement = mock<PreparedStatement>()
        val connection = mock<Connection> { on { prepareStatement(any()) } doReturn statement }

        AppNowConnectionInterceptor(FIXED_TIME_SOURCE).onConnectionAcquire(connection)

        inOrder(connection, statement) {
            verify(connection).prepareStatement("SELECT set_config('app.now', ?, false)")
            verify(statement).setString(1, "2026-06-15T10:00Z")
            verify(statement).execute()
            verify(statement).close()
        }
    }
}
