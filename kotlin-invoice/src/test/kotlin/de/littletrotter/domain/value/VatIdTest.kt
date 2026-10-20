package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** the value-class wrapper; format edge cases live in [VatIdValidatorTest]. */
class VatIdTest {

    @Test
    fun `happy path accepts a normalised German VAT id`() {
        val vatId = VatId("DE123456789")
        assertEquals("DE123456789", vatId.value)
        assertEquals("DE123456789", vatId.toString())
    }

    @Test
    fun `boundary - a recognised prefix with the wrong shape is rejected (Violation FORMAT)`() {
        // "NO" is a recognised prefix (Norway, ^NO\d{9}MVA$) — this has the right prefix, wrong
        // digits-and-suffix shape, distinct from an unrecognised prefix below.
        assertThrows(InvalidParameterException::class.java) { VatId("NOTAVALIDVATID") }
    }

    @Test
    fun `boundary - an unrecognised but syntactically complete prefix is accepted (Violation UNKNOWN_PREFIX)`() {
        // Coordinator follow-up — a real third-country identifier (e.g. Japan, Brazil) that
        // VatIdValidator simply has no regex for must not be rejected; only a known prefix with
        // the wrong shape, or a value over the column width, is. Pre-VatId, this field carried
        // only a length bound and no format check at all.
        val vatId = VatId("XX123456789")
        assertEquals("XX123456789", vatId.value)
    }

    @Test
    fun `boundary - fewer than two characters is rejected (Violation NO_PREFIX)`() {
        // Too short to even carry a country prefix — unlike UNKNOWN_PREFIX, no real-world VAT id
        // is ever this shape, so there is nothing plausible this would reject in error.
        assertThrows(InvalidParameterException::class.java) { VatId("D") }
    }

    @Test
    fun `a blank VAT id is rejected`() {
        assertThrows(InvalidParameterException::class.java) { VatId("   ") }
    }

    @Test
    fun `boundary over VatIdValidator's MAX_LENGTH is rejected — sender_profile,recipient,invoice_party_snapshot are VARCHAR(64)`() {
        val over64 = "DE" + "1".repeat(VatIdValidator.MAX_LENGTH)
        assertThrows(InvalidParameterException::class.java) { VatId(over64) }
    }

    @Test
    fun `of normalises whitespace and case before validating`() {
        assertEquals("DE123456789", VatId.of(" de 123456789 ").value)
    }

    @Test
    fun `ofNullable returns null for blank or null input`() {
        assertNull(VatId.ofNullable(null))
        assertNull(VatId.ofNullable("   "))
    }

    @Test
    fun `ofNullable returns a normalised VatId for a non-blank input`() {
        assertEquals(VatId("DE123456789"), VatId.ofNullable(" de 123456789 "))
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = VatIdConverter()
        val vatId = VatId("DE123456789")
        assertEquals("DE123456789", converter.convertToDatabaseColumn(vatId))
        assertEquals(vatId, converter.convertToEntityAttribute("DE123456789"))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = VatIdConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }

    @Test
    fun `converter treats a blank DB value as null`() {
        assertNull(VatIdConverter().convertToEntityAttribute("   "))
    }
}
