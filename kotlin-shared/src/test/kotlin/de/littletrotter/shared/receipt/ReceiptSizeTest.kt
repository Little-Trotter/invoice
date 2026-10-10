package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReceiptSizeTest {

    @Test
    fun `equality is by objectKey and size, both are readable`() {
        val a = ReceiptSize("bucket/key-1", DocumentSize.ofBytes(100L))
        val b = ReceiptSize("bucket/key-1", DocumentSize.ofBytes(100L))
        val differentKey = ReceiptSize("bucket/key-2", DocumentSize.ofBytes(100L))
        val differentSize = ReceiptSize("bucket/key-1", DocumentSize.ofBytes(200L))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == differentKey)
        assertFalse(a == differentSize)

        assertEquals("bucket/key-1", a.objectKey)
        assertEquals(DocumentSize.ofBytes(100L), a.size)
    }

    @Test
    fun `copy and destructuring work like any data class`() {
        val original = ReceiptSize("bucket/key-1", DocumentSize.ofBytes(100L))
        val copy = original.copy(size = DocumentSize.ofBytes(200L))
        assertEquals(ReceiptSize("bucket/key-1", DocumentSize.ofBytes(200L)), copy)

        val (objectKey, size) = original
        assertEquals("bucket/key-1", objectKey)
        assertEquals(DocumentSize.ofBytes(100L), size)
    }
}
