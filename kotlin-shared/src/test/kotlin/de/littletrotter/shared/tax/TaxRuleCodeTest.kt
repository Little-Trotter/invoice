package de.littletrotter.shared.tax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TaxRuleCodeTest {

    @Test
    fun `ofOrNull rejects null and blank values`() {
        assertNull(TaxRuleCode.ofOrNull(null))
        assertNull(TaxRuleCode.ofOrNull(""))
        assertNull(TaxRuleCode.ofOrNull("   "))
    }

    @Test
    fun `ofOrNull wraps a non-blank value and round-trips via toString`() {
        val code = TaxRuleCode.ofOrNull("DE_STANDARD_19")!!
        assertEquals("DE_STANDARD_19", code.value)
        assertEquals("DE_STANDARD_19", code.toString())
    }

    @Test
    fun `equality and hash follow the wrapped value`() {
        val a = TaxRuleCode.ofOrNull("A")!!
        assertEquals(a, TaxRuleCode.ofOrNull("A"))
        assertEquals(a.hashCode(), TaxRuleCode.ofOrNull("A").hashCode())
        assertNotEquals(a, TaxRuleCode.ofOrNull("B"))
        assertNotEquals(a as Any, "A" as Any)
    }
}
