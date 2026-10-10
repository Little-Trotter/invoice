package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** [PostalCode]'s own bare-constructor invariant (the `VARCHAR(20)` column bound,
 *  ex-`@field:Size(max = 20)`) plus [PostalCode.forCountry]'s reuse of [PostalCodeValidator]. */
class PostalCodeTest {

    // ── bare constructor: the column-length invariant, ex-@field:Size(max = 20) ──

    @Test
    fun `boundary exactly 20 characters is accepted`() {
        val value = "A".repeat(PostalCode.MAX_COLUMN_LENGTH)
        assertEquals(value, PostalCode(value).value)
    }

    @Test
    fun `boundary 21 characters is rejected`() {
        val ex = assertThrows(InvalidParameterException::class.java) { PostalCode("A".repeat(PostalCode.MAX_COLUMN_LENGTH + 1)) }
        assertEquals("PLZ darf höchstens 20 Zeichen lang sein.", ex.message)
    }

    @Test
    fun `blank is accepted - requiredness is Address validate's own concern`() {
        assertEquals("", PostalCode("").value)
    }

    @Test
    fun `toString renders the plain value`() {
        assertEquals("10115", PostalCode("10115").toString())
    }

    // ── forCountry: reuses PostalCodeValidator, the country-specific format rule ──

    @Test
    fun `forCountry accepts a valid German five-digit PLZ`() {
        assertEquals("10115", PostalCode.forCountry("DE", "10115").value)
    }

    @Test
    fun `forCountry rejects a non-five-digit German PLZ`() {
        val ex = assertThrows(InvalidParameterException::class.java) { PostalCode.forCountry("DE", "1234") }
        assertEquals(PostalCodeValidator.Violation.GERMAN_FORMAT.message, ex.message)
    }

    @Test
    fun `forCountry accepts a real foreign postal code up to the lenient cap`() {
        assertEquals("L-1611", PostalCode.forCountry("LU", "L-1611").value)
    }

    @Test
    fun `forCountry rejects a foreign postal code longer than the lenient cap`() {
        val ex = assertThrows(InvalidParameterException::class.java) {
            PostalCode.forCountry("LU", "L-16110000000")
        }
        assertEquals(PostalCodeValidator.Violation.TOO_LONG.message, ex.message)
    }

    @Test
    fun `forCountry accepts blank regardless of country`() {
        assertEquals("", PostalCode.forCountry("DE", "").value)
        assertEquals("", PostalCode.forCountry("LU", "").value)
    }

    // ── PostalCodeConverter: rehydrates an already-persisted row, no country in scope ──

    @Test
    fun `converter maps a non-null PostalCode to its raw column value`() {
        val converter = PostalCodeConverter()
        assertEquals("10115", converter.convertToDatabaseColumn(PostalCode("10115")))
    }

    @Test
    fun `converter maps a null PostalCode attribute to a null column value`() {
        val converter = PostalCodeConverter()
        assertEquals(null, converter.convertToDatabaseColumn(null))
    }

    @Test
    fun `converter maps a non-null column value to a PostalCode`() {
        val converter = PostalCodeConverter()
        assertEquals(PostalCode("10115"), converter.convertToEntityAttribute("10115"))
    }

    @Test
    fun `converter maps a null column value to a null PostalCode`() {
        val converter = PostalCodeConverter()
        assertEquals(null, converter.convertToEntityAttribute(null))
    }
}
