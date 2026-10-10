package de.littletrotter.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.SQLException

/**
 * [findConstraintViolation]'s cause-chain walk and [saveEntityFlushingOrThrow]'s "not the
 * constraint this call site knows how to explain" rethrow were previously only exercised
 * incidentally, one hop deep, through concrete repository IT tests. This drives both directly
 * against a mocked [EntityManager], the same style as `MasterDataNumberServiceTest` for the
 * sibling lookup this class's KDoc points at.
 */
class BasePanacheRepositoryTest {

    private class TestRepository(entityManager: EntityManager) : BasePanacheRepository() {
        init {
            em = entityManager
            masterdataChanges = mock()
        }

        fun save(
            entity: Any,
            uniqueConstraintName: String,
            onViolation: (cause: Throwable) -> RuntimeException,
        ): Any = saveEntityFlushingOrThrow(entity, { null }, uniqueConstraintName, onViolation)

        fun violation(ex: Throwable?, name: String) = findConstraintViolation(ex, name)
    }

    @Test
    fun `happy path - findConstraintViolation returns the matching cause several hops down the chain`() {
        val repository = TestRepository(mock())
        val violation = ConstraintViolationException("duplicate key", SQLException("unique violation"), "uq_recipient_number")
        val chain = RuntimeException("outer wrapper", RuntimeException("middle wrapper", violation))

        val found = repository.violation(chain, "uq_recipient_number")

        assertSame(violation, found)
    }

    @Test
    fun `boundary - findConstraintViolation returns null when no cause in the chain matches`() {
        val repository = TestRepository(mock())
        val chain = RuntimeException("outer wrapper", RuntimeException("middle wrapper, no constraint here"))

        assertNull(repository.violation(chain, "uq_recipient_number"))
    }

    @Test
    fun `boundary - findConstraintViolation returns null for a null cause`() {
        val repository = TestRepository(mock())

        assertNull(repository.violation(null, "uq_recipient_number"))
    }

    @Test
    fun `boundary - saveEntityFlushingOrThrow rethrows the original exception unchanged when its cause is a different constraint`() {
        val em: EntityManager = mock()
        val original = ConstraintViolationException("duplicate key", SQLException("unique violation"), "uq_other_table")
        whenever(em.flush()).thenThrow(original)
        val repository = TestRepository(em)
        var violationBuilderCalled = false

        val thrown =
            assertThrows(ConstraintViolationException::class.java) {
                repository.save(Any(), "uq_recipient_number") {
                    violationBuilderCalled = true
                    RuntimeException("must not be built")
                }
            }

        assertSame(original, thrown, "an unmatched constraint must escape unchanged, not be wrapped")
        assertEquals(false, violationBuilderCalled)
        verify(em, never()).merge(org.mockito.kotlin.any<Any>())
    }
}
