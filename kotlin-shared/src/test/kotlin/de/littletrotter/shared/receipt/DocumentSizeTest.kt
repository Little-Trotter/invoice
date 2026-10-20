package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentSizeTest {

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun `ofBytes and ZERO`() {
        assertEquals(0L, DocumentSize.ZERO.bytes)
        assertEquals(42L, DocumentSize.ofBytes(42L).bytes)
        assertEquals(DocumentSize.ZERO, DocumentSize.ofBytes(0L))
    }

    @Test
    fun `ofKibiBytes and ofMebiBytes convert using 1024`() {
        assertEquals(1024L, DocumentSize.ofKibiBytes(1L).bytes)
        assertEquals(2048L, DocumentSize.ofKibiBytes(2L).bytes)
        assertEquals(1024L * 1024L, DocumentSize.ofMebiBytes(1L).bytes)
        assertEquals(20L * 1024 * 1024, DocumentSize.ofMebiBytes(20L).bytes)
    }

    @Test
    fun `negative sizes are rejected at construction regardless of factory`() {
        assertThrows(IllegalArgumentException::class.java) { DocumentSize.ofBytes(-1L) }
        assertThrows(IllegalArgumentException::class.java) { DocumentSize.ofKibiBytes(-1L) }
        assertThrows(IllegalArgumentException::class.java) { DocumentSize.ofMebiBytes(-1L) }
        assertThrows(IllegalArgumentException::class.java) { DocumentSize(-1L) }
    }

    // ── Read access ──────────────────────────────────────────────────────────

    @Test
    fun `mebiBytes exposes a fractional value`() {
        assertEquals(1.0, DocumentSize.ofMebiBytes(1L).mebiBytes)
        assertEquals(0.5, DocumentSize.ofKibiBytes(512L).mebiBytes)
        assertEquals(0.0, DocumentSize.ZERO.mebiBytes)
    }

    // ── Arithmetic ───────────────────────────────────────────────────────────

    @Test
    fun `plus adds bytes`() {
        assertEquals(DocumentSize.ofBytes(3L), DocumentSize.ofBytes(1L) + DocumentSize.ofBytes(2L))
        assertEquals(DocumentSize.ofBytes(5L), DocumentSize.ZERO + DocumentSize.ofBytes(5L))
    }

    @Test
    fun `minus is strict and throws instead of going negative`() {
        assertEquals(DocumentSize.ofBytes(3L), DocumentSize.ofBytes(5L) - DocumentSize.ofBytes(2L))
        assertEquals(DocumentSize.ZERO, DocumentSize.ofBytes(5L) - DocumentSize.ofBytes(5L))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            DocumentSize.ofBytes(1L) - DocumentSize.ofBytes(2L)
        }
        assertTrue(ex.message!!.contains("negative"))
    }

    @Test
    fun `remainingAfter clamps at zero instead of throwing`() {
        assertEquals(DocumentSize.ofBytes(3L), DocumentSize.ofBytes(5L).remainingAfter(DocumentSize.ofBytes(2L)))
        assertEquals(DocumentSize.ZERO, DocumentSize.ofBytes(5L).remainingAfter(DocumentSize.ofBytes(5L)))
        assertEquals(DocumentSize.ZERO, DocumentSize.ofBytes(5L).remainingAfter(DocumentSize.ofBytes(9L)))
        assertEquals(DocumentSize.ZERO, DocumentSize.ZERO.remainingAfter(DocumentSize.ofBytes(1L)))
    }

    @Test
    fun `times multiplies by a non-negative count`() {
        assertEquals(DocumentSize.ofBytes(0L), DocumentSize.ofBytes(7L) * 0)
        assertEquals(DocumentSize.ofBytes(14L), DocumentSize.ofBytes(7L) * 2)
        assertEquals(
            DocumentSize.ofBytes(19L * 1024 * 1024),
            DocumentSize.ofMebiBytes(1L) * 19,
        )
    }

    @Test
    fun `times rejects a negative count`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { DocumentSize.ofBytes(7L) * -1 }
        assertTrue(ex.message!!.contains("negative"))
    }

    @Test
    fun `base64Encoded rounds up to 4-byte blocks`() {
        assertEquals(DocumentSize.ZERO, DocumentSize.ZERO.base64Encoded())
        assertEquals(DocumentSize.ofBytes(4L), DocumentSize.ofBytes(1L).base64Encoded())
        assertEquals(DocumentSize.ofBytes(4L), DocumentSize.ofBytes(3L).base64Encoded())
        assertEquals(DocumentSize.ofBytes(8L), DocumentSize.ofBytes(4L).base64Encoded())
    }

    @Test
    fun `base64Encoded of 768 KiB is exactly 1 MiB`() {
        assertEquals(DocumentSize.ofMebiBytes(1L), DocumentSize.ofKibiBytes(768L).base64Encoded())
    }

    // ── Comparison ───────────────────────────────────────────────────────────

    @Test
    fun `compareTo and sorting`() {
        assertTrue(DocumentSize.ofBytes(1L) < DocumentSize.ofBytes(2L))
        assertTrue(DocumentSize.ofBytes(2L) > DocumentSize.ofBytes(1L))
        assertEquals(0, DocumentSize.ofBytes(1L).compareTo(DocumentSize.ofBytes(1L)))

        val sorted = listOf(DocumentSize.ofBytes(3L), DocumentSize.ZERO, DocumentSize.ofBytes(1L)).sorted()
        assertEquals(listOf(DocumentSize.ZERO, DocumentSize.ofBytes(1L), DocumentSize.ofBytes(3L)), sorted)
    }

    // ── Equality, toString, data-class members ──────────────────────────────

    @Test
    fun `equality and hashCode are value-based`() {
        assertEquals(DocumentSize.ofBytes(5L), DocumentSize.ofBytes(5L))
        assertEquals(DocumentSize.ofBytes(5L).hashCode(), DocumentSize.ofBytes(5L).hashCode())
        assertFalse(DocumentSize.ofBytes(5L) == DocumentSize.ofBytes(6L))
        assertFalse(DocumentSize.ofBytes(5L).equals("5"))
    }

    @Test
    fun `toString reports bytes`() {
        assertEquals("0B", DocumentSize.ZERO.toString())
        assertEquals("5B", DocumentSize.ofBytes(5L).toString())
    }

    @Test
    fun `copy and destructuring work like any data class`() {
        val original = DocumentSize.ofBytes(5L)
        val copy = original.copy(bytes = 9L)
        assertEquals(DocumentSize.ofBytes(9L), copy)
        val (bytes) = original
        assertEquals(5L, bytes)
    }

    // ── sum ──────────────────────────────────────────────────────────────────

    @Test
    fun `sum of empty collection is zero`() {
        assertEquals(DocumentSize.ZERO, emptyList<DocumentSize>().sum())
        assertEquals(DocumentSize.ZERO, DocumentSize.sum(emptyList()))
    }

    @Test
    fun `sum adds every element`() {
        val sizes = listOf(DocumentSize.ofBytes(1L), DocumentSize.ofBytes(2L), DocumentSize.ofBytes(3L))
        assertEquals(DocumentSize.ofBytes(6L), sizes.sum())
        assertEquals(DocumentSize.ofBytes(6L), DocumentSize.sum(sizes))
    }
}
