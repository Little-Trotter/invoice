package de.littletrotter.application.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InvoiceIdByNumberQueriesTest {

    private val repository: InvoiceNumberReadRepository = mock()
    private val queries = InvoiceIdByNumberQueries(repository)

    @Test
    fun `numbers are resolved with one repository call`() {
        whenever(repository.findIdsByNumbers(setOf("RE-1", "RE-2"))).thenReturn(mapOf("RE-1" to 5L))

        assertEquals(mapOf("RE-1" to 5L), queries.findIdsByNumbers(setOf("RE-1", "RE-2")))
    }

    @Test
    fun `an empty input does not query`() {
        assertTrue(queries.findIdsByNumbers(emptySet()).isEmpty())

        verify(repository, never()).findIdsByNumbers(org.mockito.kotlin.any())
    }

    @Test
    fun `blank numbers are never looked up`() {
        assertTrue(queries.findIdsByNumbers(setOf("", "  ")).isEmpty())
        whenever(repository.findIdsByNumbers(setOf("RE-1"))).thenReturn(mapOf("RE-1" to 5L))

        assertEquals(mapOf("RE-1" to 5L), queries.findIdsByNumbers(setOf("", "RE-1")))
    }
}
