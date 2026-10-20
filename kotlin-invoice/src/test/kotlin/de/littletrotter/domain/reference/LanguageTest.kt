package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguageTest {

    @Test
    fun `Language with same properties should have equal properties`() {
        val lang1 = Language().edit {
            id = 1
            code = "de"
            isActive = true
            isFallback = false
            sortOrder = 1
        }
        val lang2 = Language().edit {
            id = 1
            code = "de"
            isActive = true
            isFallback = false
            sortOrder = 1
        }
        assertEquals(lang1.id, lang2.id)
        assertEquals(lang1.code, lang2.code)
        assertEquals(lang1.isActive, lang2.isActive)
    }

    @Test
    fun `Language with different id should have different id`() {
        val lang1 = Language().edit { id = 1; code = "de" }
        val lang2 = Language().edit { id = 2; code = "de" }
        assertNotEquals(lang1.id, lang2.id)
    }

    @Test
    fun `Language with different code should have different code`() {
        val lang1 = Language().edit { id = 1; code = "de" }
        val lang2 = Language().edit { id = 1; code = "en" }
        assertNotEquals(lang1.code, lang2.code)
    }

    @Test
    fun `Language can be marked as active`() {
        val lang = Language().edit {
            id = 1
            code = "de"
            isActive = true
        }
        assertTrue(lang.isActive)
    }

    @Test
    fun `Language can be marked as inactive`() {
        val lang = Language().edit {
            id = 1
            code = "de"
            isActive = false
        }
        assertFalse(lang.isActive)
    }

    @Test
    fun `Language can be marked as fallback`() {
        val lang = Language().edit {
            id = 1
            code = "en"
            isFallback = true
        }
        assertTrue(lang.isFallback)
    }

    @Test
    fun `Language can have different sort orders`() {
        val lang1 = Language().edit {
            id = 1
            code = "de"
            sortOrder = 1
        }
        val lang2 = Language().edit {
            id = 2
            code = "en"
            sortOrder = 2
        }
        assertNotEquals(lang1.sortOrder, lang2.sortOrder)
    }

    @Test
    fun `Language with default values has correct defaults`() {
        val lang = Language()
        assertEquals(0, lang.id)
        assertEquals("", lang.code)
        assertTrue(lang.isActive)
        assertFalse(lang.isFallback)
        assertEquals(0, lang.sortOrder)
    }

    @Test
    fun `Language code can be set to various lengths`() {
        val lang = Language().edit {
            code = "de-DE"
        }
        assertEquals("de-DE", lang.code)
    }

    @Test
    fun `Language multiple instances with same values have same property values`() {
        val lang1 = Language().edit {
            id = 1
            code = "fr"
            isActive = false
            isFallback = true
            sortOrder = 3
        }
        val lang2 = Language().edit {
            id = 1
            code = "fr"
            isActive = false
            isFallback = true
            sortOrder = 3
        }
        assertEquals(lang1.id, lang2.id)
        assertEquals(lang1.code, lang2.code)
        assertEquals(lang1.isActive, lang2.isActive)
        assertEquals(lang1.isFallback, lang2.isFallback)
        assertEquals(lang1.sortOrder, lang2.sortOrder)
    }
}
