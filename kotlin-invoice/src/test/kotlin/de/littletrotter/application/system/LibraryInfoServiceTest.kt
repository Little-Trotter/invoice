package de.littletrotter.application.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * [LibraryInfoService.LibraryInfo] is a plain data holder; adapters are covered by
 * their own tests (e.g. `ClasspathLibraryInfoServiceTest`). This exercises the data class
 * contract itself (default `description`, `equals`/`copy`) that no adapter test happens to touch.
 */
class LibraryInfoServiceTest {

    @Test
    fun `happy path - description defaults to blank when not given`() {
        val info = LibraryInfoService.LibraryInfo(name = "ZUGFeRD (Mustang)", version = "2.16.0")

        assertEquals("", info.description)
    }

    @Test
    fun `boundary - equal field values make two LibraryInfo instances equal`() {
        val a = LibraryInfoService.LibraryInfo("KoSIT Validator", "2.1.0", "KoSIT validation scenario")
        val b = LibraryInfoService.LibraryInfo("KoSIT Validator", "2.1.0", "KoSIT validation scenario")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `boundary - a different version makes two LibraryInfo instances unequal`() {
        val a = LibraryInfoService.LibraryInfo("KoSIT Validator", "2.1.0")
        val b = LibraryInfoService.LibraryInfo("KoSIT Validator", "2.2.0")

        assertNotEquals(a, b)
    }

    @Test
    fun `boundary - copy overrides only the requested field`() {
        val original = LibraryInfoService.LibraryInfo("KoSIT Validator", "2.1.0", "KoSIT validation scenario")

        val upgraded = original.copy(version = "2.2.0")

        assertEquals("2.2.0", upgraded.version)
        assertEquals(original.name, upgraded.name)
        assertEquals(original.description, upgraded.description)
    }
}
