package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.assertj.core.api.Assertions.assertThat

/**
 * Tests for ReceiptDto data class with optional fileName and objectKey.
 */
class ReceiptDtoTest {

    @Test
    fun `ReceiptDto with both fileName and objectKey`() {
        val dto = ReceiptDto("receipt.pdf", "s3://bucket/receipt.pdf")

        assertEquals("receipt.pdf", dto.fileName)
        assertEquals("s3://bucket/receipt.pdf", dto.objectKey)
    }

    @Test
    fun `ReceiptDto with only fileName`() {
        val dto = ReceiptDto(fileName = "receipt.pdf", objectKey = null)

        assertEquals("receipt.pdf", dto.fileName)
        assertNull(dto.objectKey)
    }

    @Test
    fun `ReceiptDto with only objectKey`() {
        val dto = ReceiptDto(fileName = null, objectKey = "s3://bucket/receipt.pdf")

        assertNull(dto.fileName)
        assertEquals("s3://bucket/receipt.pdf", dto.objectKey)
    }

    @Test
    fun `ReceiptDto with neither fileName nor objectKey`() {
        val dto = ReceiptDto()

        assertNull(dto.fileName)
        assertNull(dto.objectKey)
    }

    @Test
    fun `ReceiptDto with default parameters`() {
        val dto = ReceiptDto(null, null)

        assertNull(dto.fileName)
        assertNull(dto.objectKey)
    }

    @Test
    fun `ReceiptDto data class equality`() {
        val dto1 = ReceiptDto("receipt.pdf", "key")
        val dto2 = ReceiptDto("receipt.pdf", "key")
        val dto3 = ReceiptDto("receipt.pdf", "different-key")

        assertThat(dto1).isEqualTo(dto2)
        assertThat(dto1).isNotEqualTo(dto3)
    }

    @Test
    fun `ReceiptDto null handling in equality`() {
        val dto1 = ReceiptDto(null, "key")
        val dto2 = ReceiptDto(null, "key")
        val dto3 = ReceiptDto("name", "key")

        assertEquals(dto1, dto2)
        assertThat(dto1).isNotEqualTo(dto3)
    }

    @Test
    fun `ReceiptDto hash code consistent with equality`() {
        val dto1 = ReceiptDto("receipt.pdf", "key")
        val dto2 = ReceiptDto("receipt.pdf", "key")

        assertEquals(dto1.hashCode(), dto2.hashCode())
    }

    @Test
    fun `ReceiptDto copy function preserves all fields`() {
        val original = ReceiptDto("receipt.pdf", "s3://bucket/key")
        val copied = original.copy()

        assertEquals(original, copied)
        assertThat(original).isNotSameAs(copied)
    }

    @Test
    fun `ReceiptDto copy with partial field modification`() {
        val original = ReceiptDto("receipt.pdf", "s3://bucket/key")
        val modified = original.copy(fileName = "modified.pdf")

        assertEquals("modified.pdf", modified.fileName)
        assertEquals("s3://bucket/key", modified.objectKey)
    }

    @Test
    fun `ReceiptDto toString is defined`() {
        val dto = ReceiptDto("receipt.pdf", "key")
        val str = dto.toString()

        assertThat(str).contains("receipt.pdf").contains("key")
    }

    @Test
    fun `ReceiptDto with empty string fileName`() {
        val dto = ReceiptDto("", "key")

        assertEquals("", dto.fileName)
        assertEquals("key", dto.objectKey)
    }

    @Test
    fun `ReceiptDto with empty string objectKey`() {
        val dto = ReceiptDto("receipt.pdf", "")

        assertEquals("receipt.pdf", dto.fileName)
        assertEquals("", dto.objectKey)
    }
}
