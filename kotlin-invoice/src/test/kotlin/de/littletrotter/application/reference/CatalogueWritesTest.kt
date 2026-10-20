package de.littletrotter.application.reference

import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A catalogue save writes into a fresh row or the stored one, and only while that row is unchanged. */
class CatalogueWritesTest {

    private class Row(val id: Long, val version: Long?)

    private val stored = Row(1L, 4L)

    private val writes = CatalogueWrites({ if (it == stored.id) stored else null }, Row::version) { Row(0L, null) }

    private fun load(id: Long?, version: Long?) = writes.rowFor(id, version)

    @Test
    fun `a missing id creates a fresh row`() {
        assertEquals(0L, load(null, null).id)
    }

    @Test
    fun `the stored row is returned while its version still matches`() {
        assertSame(stored, load(1L, 4L))
    }

    @Test
    fun `a null version skips the revision check`() {
        assertSame(stored, load(1L, null))
    }

    @Test
    fun `a stale version is rejected as a concurrent edit`() {
        assertThrows<OptimisticLockException> { load(1L, 3L) }
    }

    @Test
    fun `a row deleted in the meantime is rejected as a concurrent edit`() {
        assertThrows<OptimisticLockException> { load(2L, 1L) }
    }
}
