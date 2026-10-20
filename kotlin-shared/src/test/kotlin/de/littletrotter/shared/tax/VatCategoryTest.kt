package de.littletrotter.shared.tax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VatCategoryTest {

    @Test
    fun `fromCodeOrNull maps every known UNTDID 5305 letter code`() {
        VatCategory.entries.forEach { assertEquals(it, VatCategory.fromCodeOrNull(it.code)) }
    }

    @Test
    fun `fromCodeOrNull returns null for a null or unknown code`() {
        assertNull(VatCategory.fromCodeOrNull(null))
        assertNull(VatCategory.fromCodeOrNull("X"))
    }

    @Test
    fun `fromCode maps every known code and rejects an unknown one`() {
        VatCategory.entries.forEach { assertEquals(it, VatCategory.fromCode(it.code)) }
        assertThrows(IllegalArgumentException::class.java) { VatCategory.fromCode("X") }
    }
}
