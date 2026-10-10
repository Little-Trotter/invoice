package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.inMemoryTypedQuery
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Projection of [InvoiceNumberReadRepositoryImpl]: number to id, the id read as any numeric type. */
class InvoiceNumberReadRepositoryImplTest {

    private fun row(number: String, id: Number): Tuple = mock<Tuple>().also {
        whenever(it.get("number")).thenReturn(number)
        whenever(it.get("id")).thenReturn(id)
    }

    @Test
    fun `rows are mapped to a number to id map with one bound parameter`() {
        val query = inMemoryTypedQuery(listOf(row("RE-1", 5L), row("RE-2", 7)))
        val em: EntityManager = mock()
        whenever(em.createNativeQuery(any<String>(), eq(Tuple::class.java))).thenReturn(query)

        val ids = InvoiceNumberReadRepositoryImpl(em).findIdsByNumbers(setOf("RE-1", "RE-2"))

        assertEquals(mapOf("RE-1" to 5L, "RE-2" to 7L), ids)
        verify(query).setParameter("numbers", setOf("RE-1", "RE-2"))
    }
}
