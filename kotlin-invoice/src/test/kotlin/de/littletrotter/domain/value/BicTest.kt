package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** . */
class BicTest {

    @Test
    fun `happy path accepts an 8-character BIC`() {
        val bic = Bic("DEUTDEFF")
        assertEquals("DEUTDEFF", bic.value)
        assertEquals("DEUTDEFF", bic.toString())
    }

    @Test
    fun `happy path accepts an 11-character BIC with branch code`() {
        assertEquals("DEUTDEFF500", Bic("DEUTDEFF500").value)
    }

    @Test
    fun `a structurally invalid BIC is rejected`() {
        assertThrows(InvalidParameterException::class.java) { Bic("INVALID") }
    }

    @Test
    fun `a blank BIC is rejected`() {
        assertThrows(InvalidParameterException::class.java) { Bic("   ") }
    }

    @Test
    fun `of trims and upper-cases before validating`() {
        assertEquals("DEUTDEFF", Bic.of(" deutdeff ").value)
    }

    @Test
    fun `ofNullable returns null for blank or null input`() {
        assertNull(Bic.ofNullable(null))
        assertNull(Bic.ofNullable("   "))
    }

    @Test
    fun `ofNullable returns a normalised Bic for a non-blank input`() {
        assertEquals(Bic("DEUTDEFF"), Bic.ofNullable(" deutdeff "))
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = BicConverter()
        val bic = Bic("DEUTDEFF")
        assertEquals("DEUTDEFF", converter.convertToDatabaseColumn(bic))
        assertEquals(bic, converter.convertToEntityAttribute("DEUTDEFF"))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = BicConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }

    @Test
    fun `converter treats a blank DB value as null`() {
        assertNull(BicConverter().convertToEntityAttribute("   "))
    }
}
