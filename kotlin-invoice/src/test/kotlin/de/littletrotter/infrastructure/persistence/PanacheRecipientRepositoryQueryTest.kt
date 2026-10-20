package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.masterdata.Recipient
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Guard-clause branches [PanacheRecipientRepositoryQueriesIT]/[PanacheRecipientRepositorySearchIT]
 * never exercise: every IT fixture searches with a non-blank `filterText` and a known id. Same
 * mocked-`EntityManager`, JPQL-string level as [PanacheArticleRepositoryQueryTest] — real search
 * behavior against Postgres stays the IT's job.
 */
class PanacheRecipientRepositoryQueryTest {

    private lateinit var em: EntityManager
    private lateinit var repository: PanacheRecipientRepository
    private lateinit var listQuery: TypedQuery<Recipient>
    private lateinit var countQuery: TypedQuery<Long>

    @BeforeEach
    fun setup() {
        em = mock()
        listQuery = mock(defaultAnswer = Mockito.RETURNS_SELF)
        whenever(listQuery.resultList).thenReturn(emptyList())
        countQuery = mock(defaultAnswer = Mockito.RETURNS_SELF)
        whenever(countQuery.singleResult).thenReturn(0L)
        whenever(em.createQuery(any<String>(), eq(Recipient::class.java))).thenReturn(listQuery)
        whenever(em.createQuery(any<String>(), eq(Long::class.java))).thenReturn(countQuery)

        repository = PanacheRecipientRepository()
        val emField = BasePanacheRepository::class.java.getDeclaredField("em")
        emField.isAccessible = true
        emField.set(repository, em)
    }

    private fun capturedJpql(resultClass: Class<*>): String {
        val captor = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(em).createQuery(captor.capture(), eq(resultClass))
        return captor.value
    }

    @Test
    fun `findAll without a filterText has no WHERE clause and binds no token parameter`() {
        repository.findAll(filterText = null)

        val jpql = capturedJpql(Recipient::class.java)
        assertFalse(jpql.contains("WHERE"), jpql)
        Mockito.verify(listQuery, Mockito.never()).setParameter(Mockito.anyString(), any<Any>())
    }

    @Test
    fun `count without a filterText has no WHERE clause and binds no token parameter`() {
        repository.count(filterText = "   ")

        val jpql = capturedJpql(Long::class.java)
        assertEquals("SELECT COUNT(r) FROM Recipient r", jpql)
        Mockito.verify(countQuery, Mockito.never()).setParameter(Mockito.anyString(), any<Any>())
    }

    @Test
    fun `findById returns null for an id with no matching row, without touching addresses or b2bProfile`() {
        whenever(em.find(Recipient::class.java, 999L)).thenReturn(null)

        assertNull(repository.findById(999L))
    }

    @Test
    fun `findNamesByIds with an empty collection returns an empty map without querying at all`() {
        assertTrue(repository.findNamesByIds(emptyList()).isEmpty())
        Mockito.verifyNoInteractions(em)
    }
}
