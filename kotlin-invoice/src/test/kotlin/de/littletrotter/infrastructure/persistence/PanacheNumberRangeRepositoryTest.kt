package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.repository.MasterdataChangeListener
import de.littletrotter.fixture.edit
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PanacheNumberRangeRepositoryTest {

    private val em = mock<EntityManager>()
    private val changes = mock<MasterdataChangeListener>()
    private val repository = PanacheNumberRangeRepository(em, changes)

    @Test
    fun `saving a new range persists it and reports the change`() {
        val range = NumberRange()

        repository.save(range)

        inOrder(em, changes) {
            verify(em).persist(range)
            verify(changes).saved(range)
        }
    }

    @Test
    fun `saving an existing range merges it and reports the merged entity`() {
        val detached = NumberRange().edit { id = 4 }
        val merged = NumberRange().edit { id = 4 }
        whenever(em.merge(detached)).thenReturn(merged)

        repository.save(detached)

        verify(changes).saved(merged)
    }

    @Test
    fun `deleting a range reports it before removal`() {
        val range = NumberRange().edit { id = 4 }
        whenever(em.find(NumberRange::class.java, 4L)).thenReturn(range)

        repository.delete(4)

        inOrder(em, changes) {
            verify(changes).deleting(range)
            verify(em).remove(range)
        }
    }

    @Test
    fun `deleting an unknown range reports nothing`() {
        repository.delete(99)

        verifyNoInteractions(changes)
    }
}
