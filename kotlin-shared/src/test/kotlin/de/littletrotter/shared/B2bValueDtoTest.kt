package de.littletrotter.shared

import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.assertj.core.api.Assertions.assertThat

/**
 * Tests for B2bValueDto data class.
 */
class B2bValueDtoTest {

    @Test
    fun `B2bValueDto constructor sets term and value`() {
        val dto = B2bValueDto(B2bTerm.BT_19, "some value")

        assertEquals(B2bTerm.BT_19, dto.term)
        assertEquals("some value", dto.value)
    }

    @Test
    fun `B2bValueDto with empty value`() {
        val dto = B2bValueDto(B2bTerm.BT_19, "")

        assertEquals(B2bTerm.BT_19, dto.term)
        assertEquals("", dto.value)
    }

    @Test
    fun `B2bValueDto data class equality`() {
        val dto1 = B2bValueDto(B2bTerm.BT_19, "value")
        val dto2 = B2bValueDto(B2bTerm.BT_19, "value")
        val dto3 = B2bValueDto(B2bTerm.BT_19, "different")

        assertThat(dto1).isEqualTo(dto2)
        assertThat(dto1).isNotEqualTo(dto3)
    }

    @Test
    fun `B2bValueDto data class equality distinguishes term`() {
        val dto1 = B2bValueDto(B2bTerm.BT_19, "value")
        val dto2 = B2bValueDto(B2bTerm.BT_58, "value")

        assertThat(dto1).isNotEqualTo(dto2)
    }

    @Test
    fun `B2bValueDto hash code consistent with equality`() {
        val dto1 = B2bValueDto(B2bTerm.BT_19, "value")
        val dto2 = B2bValueDto(B2bTerm.BT_19, "value")

        assertEquals(dto1.hashCode(), dto2.hashCode())
    }

    @Test
    fun `B2bValueDto copy function`() {
        val original = B2bValueDto(B2bTerm.BT_19, "value")
        val copied = original.copy()

        assertEquals(original, copied)
        assertThat(original).isNotSameAs(copied)
    }

    @Test
    fun `B2bValueDto copy with modified fields`() {
        val original = B2bValueDto(B2bTerm.BT_19, "original value")
        val modified = original.copy(value = "new value")

        assertEquals(B2bTerm.BT_19, modified.term)
        assertEquals("new value", modified.value)
    }

    @Test
    fun `B2bValueDto copy with modified term`() {
        val original = B2bValueDto(B2bTerm.BT_19, "value")
        val modified = original.copy(term = B2bTerm.BT_58)

        assertEquals(B2bTerm.BT_58, modified.term)
        assertEquals("value", modified.value)
    }

    @Test
    fun `B2bValueDto toString is defined`() {
        val dto = B2bValueDto(B2bTerm.BT_19, "value")
        val str = dto.toString()

        assertThat(str).contains("BT_19").contains("value")
    }

    @Test
    fun `B2bValueDto with special characters in value`() {
        val dto = B2bValueDto(B2bTerm.BT_19, "value with [special] {chars}")

        assertEquals(B2bTerm.BT_19, dto.term)
        assertEquals("value with [special] {chars}", dto.value)
    }

    @Test
    fun `B2bValueDto with multi-line value`() {
        val multiLineValue = "line1\nline2\nline3"
        val dto = B2bValueDto(B2bTerm.BT_19, multiLineValue)

        assertEquals(multiLineValue, dto.value)
    }
}
