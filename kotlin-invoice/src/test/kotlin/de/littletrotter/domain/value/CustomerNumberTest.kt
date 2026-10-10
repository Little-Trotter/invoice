package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** . */
class CustomerNumberTest {

    @Test
    fun `happy path accepts a typical customer number`() {
        val customerNumber = CustomerNumber("KD-0001")
        assertEquals("KD-0001", customerNumber.value)
        assertEquals("KD-0001", customerNumber.toString())
    }

    @Test
    fun `a blank customer number is rejected`() {
        assertThrows(InvalidParameterException::class.java) { CustomerNumber("   ") }
    }

    @Test
    fun `an empty customer number is rejected`() {
        assertThrows(InvalidParameterException::class.java) { CustomerNumber("") }
    }

    @Test
    fun `boundary exactly MAX_LENGTH characters is accepted`() {
        val exact = "K".repeat(CustomerNumber.MAX_LENGTH)
        assertEquals(CustomerNumber.MAX_LENGTH, exact.length)
        assertEquals(exact, CustomerNumber(exact).value)
    }

    @Test
    fun `boundary MAX_LENGTH plus one character is rejected`() {
        val tooLong = "K".repeat(CustomerNumber.MAX_LENGTH + 1)
        assertThrows(InvalidParameterException::class.java) { CustomerNumber(tooLong) }
    }

    @Test
    fun `of trims before validating`() {
        assertEquals("KD-0001", CustomerNumber.of(" KD-0001 ").value)
    }

    @Test
    fun `ofNullable returns null for blank or null input`() {
        assertNull(CustomerNumber.ofNullable(null))
        assertNull(CustomerNumber.ofNullable("   "))
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = CustomerNumberConverter()
        val customerNumber = CustomerNumber("KD-0001")
        assertEquals("KD-0001", converter.convertToDatabaseColumn(customerNumber))
        assertEquals(customerNumber, converter.convertToEntityAttribute("KD-0001"))
    }

    @Test
    fun `converter writes the empty-string sentinel for a null attribute — the column stays NOT NULL`() {
        assertEquals("", CustomerNumberConverter().convertToDatabaseColumn(null))
    }

    @Test
    fun `converter reads an empty-string DB sentinel back as null`() {
        assertNull(CustomerNumberConverter().convertToEntityAttribute(""))
    }

    @Test
    fun `converter reads a null DB value back as null`() {
        assertNull(CustomerNumberConverter().convertToEntityAttribute(null))
    }
}
