package de.littletrotter.domain.shared

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedChildReconciliationTest {
    private class Row(val key: Int, var value: String, val id: Long? = null)

    private fun reconcile(
        current: MutableList<Row>,
        proposed: List<Row>,
        create: (Row) -> Row = { Row(it.key, it.value) },
    ) = reconcileOwnedChildren(
        current = current,
        proposed = proposed,
        strategy = ReconciliationStrategy(
            key = { it.key },
            validate = { rows ->
                if (rows.any { it.value.isBlank() }) throw InvalidParameterException("Blank synthetic value")
            },
            create = create,
            update = { retained, candidate -> retained.value = candidate.value },
        ),
    )

    @Test
    fun `retains managed identity updates fields copies additions and removes only missing keys`() {
        val retained = Row(1, "Demo 2 existing", 11L)
        val omitted = Row(2, "Demo 2 removed", 22L)
        val incoming = Row(3, "Demo 2 added", 999L)
        val current = mutableListOf(retained, omitted)

        reconcile(current, listOf(Row(1, "Demo 2 updated"), incoming))

        assertEquals(listOf(1, 3), current.map { it.key })
        assertSame(retained, current.first())
        assertEquals(11L, retained.id)
        assertEquals("Demo 2 updated", retained.value)
        assertNotSame(incoming, current.last())
        assertNull(current.last().id)
        assertEquals("Demo 2 added", current.last().value)
        assertEquals(999L, incoming.id)
    }

    @Test
    fun `duplicate key is rejected even when aggregate validator omits uniqueness`() {
        val retained = Row(1, "Demo 2 existing", 11L)
        val current = mutableListOf(retained)

        assertThrows(InvalidParameterException::class.java) {
            reconcile(current, listOf(Row(1, "Changed"), Row(1, "Duplicate")))
        }

        assertEquals(listOf(retained), current)
        assertEquals("Demo 2 existing", retained.value)
    }

    @Test
    fun `invalid later input cannot update or remove earlier rows`() {
        val retained = Row(1, "Demo 2 existing", 11L)
        val omitted = Row(2, "Demo 2 preserved", 22L)
        val current = mutableListOf(retained, omitted)

        assertThrows(InvalidParameterException::class.java) {
            reconcile(current, listOf(Row(1, "Changed"), Row(3, "")))
        }

        assertEquals(listOf(retained, omitted), current)
        assertEquals("Demo 2 existing", retained.value)
    }

    @Test
    fun `failing new row creation leaves all managed state untouched`() {
        val retained = Row(1, "Demo 2 existing", 11L)
        val omitted = Row(2, "Demo 2 preserved", 22L)
        val current = mutableListOf(retained, omitted)

        assertThrows(IllegalArgumentException::class.java) {
            reconcile(current, listOf(Row(1, "Changed"), Row(3, "New"))) {
                throw IllegalArgumentException("Demo 2 factory rejection")
            }
        }

        assertEquals(listOf(retained, omitted), current)
        assertEquals("Demo 2 existing", retained.value)
    }

    @Test
    fun `resubmitting own collection and repeating input preserves identity`() {
        val retained = Row(1, "Demo 2 existing", 11L)
        val current = mutableListOf(retained)

        reconcile(current, current)
        reconcile(current, listOf(Row(1, "Demo 2 existing")))

        assertEquals(1, current.size)
        assertSame(retained, current.single())
    }

    @Test
    fun `empty submission removes all rows and accepts already empty collection`() {
        val current = mutableListOf(Row(1, "Demo 2 existing", 11L))

        reconcile(current, emptyList())
        reconcile(current, emptyList())

        assertTrue(current.isEmpty())
    }
}
