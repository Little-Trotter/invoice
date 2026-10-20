package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.LlmCorrectionSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Pins the content-based equality of the pipeline's ByteArray-carrying result types
 * (ArrayInDataClass): two results holding equal bytes in distinct arrays must be
 * equal — the generated data-class equals would compare the arrays by identity instead.
 */
class ByteArrayPayloadEqualityTest {

    private fun bytes() = byteArrayOf(1, 2, 3)

    @Test
    fun `ParsedResponse compares the corrected XML by content, including the null cases`() {
        val a = LlmCorrectionSupport.ParsedResponse("report", bytes())
        val b = LlmCorrectionSupport.ParsedResponse("report", bytes())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        assertNotEquals(a, LlmCorrectionSupport.ParsedResponse("other", bytes()))
        assertNotEquals(a, LlmCorrectionSupport.ParsedResponse("report", byteArrayOf(9)))
        assertNotEquals(a, LlmCorrectionSupport.ParsedResponse("report", null))
        assertNotEquals(LlmCorrectionSupport.ParsedResponse("report", null), a)
        assertEquals(
            LlmCorrectionSupport.ParsedResponse("report", null),
            LlmCorrectionSupport.ParsedResponse("report", null),
        )
        assertFalse(a.equals("report"))
    }
}
