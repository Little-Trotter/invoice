package de.littletrotter.shared.wording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordingProfileTest {

    @Test
    fun `fromCode resolves all three known codes`() {
        assertEquals(WordingProfile.HANDWERK, WordingProfile.fromCode(0))
        assertEquals(WordingProfile.BERATUNG, WordingProfile.fromCode(1))
        assertEquals(WordingProfile.HANDEL, WordingProfile.fromCode(2))
    }

    @Test
    fun `fromCode falls back to DEFAULT for an unknown code`() {
        assertEquals(WordingProfile.DEFAULT, WordingProfile.fromCode(99))
    }

    @Test
    fun `fromCodeOrNull resolves all three known codes and null otherwise`() {
        assertEquals(WordingProfile.HANDWERK, WordingProfile.fromCodeOrNull(0))
        assertEquals(WordingProfile.BERATUNG, WordingProfile.fromCodeOrNull(1))
        assertEquals(WordingProfile.HANDEL, WordingProfile.fromCodeOrNull(2))
        assertNull(WordingProfile.fromCodeOrNull(99))
    }

    @Test
    fun `DEFAULT is HANDWERK`() {
        assertEquals(WordingProfile.HANDWERK, WordingProfile.DEFAULT)
    }
}
