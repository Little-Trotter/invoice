package de.littletrotter.application.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OutputIntentSettingGateTest {

    private fun gate(initial: Map<String, String> = emptyMap(), configure: InMemorySystemSettingRepository.() -> Unit = {}) =
        OutputIntentSettingGate(InMemorySystemSettingRepository(initial).apply(configure))

    @Test
    fun `stored token is returned trimmed`() {
        assertEquals("abc.def", gate(mapOf(OutputIntentSettingGate.KEY to "  abc.def\n")).currentToken())
    }

    @Test
    fun `missing row yields no token`() {
        assertNull(gate().currentToken())
    }

    @Test
    fun `blank value yields no token`() {
        assertNull(gate(mapOf(OutputIntentSettingGate.KEY to "   ")).currentToken())
    }

    @Test
    fun `tokens of further sellers in suffixed rows join the first, unrelated keys stay out`() {
        val rows = mapOf(
            "${OutputIntentSettingGate.KEY}.2" to " t2 ",
            OutputIntentSettingGate.KEY to "t1",
            "${OutputIntentSettingGate.KEY}.3" to "  ",
            "${OutputIntentSettingGate.KEY}-other" to "x",
            "wording.profile" to "y",
        )

        assertEquals("t1 t2", gate(rows).currentToken())
    }

    @Test
    fun `suffixed rows alone still count`() {
        assertEquals("t2", gate(mapOf("${OutputIntentSettingGate.KEY}.2" to "t2")).currentToken())
    }

    @Test
    fun `unreadable repository yields no token`() {
        assertNull(gate(mapOf(OutputIntentSettingGate.KEY to "abc")) { failReads = true }.currentToken())
    }
}
