package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for MustangVersion object.
 * Verifies that version extraction from POM properties works.
 */
class MustangVersionTest {

    @Test
    fun `MustangVersion value property returns a non-empty string`() {
        val version = MustangVersion.value
        assertNotNull(version, "Version should not be null")
        assertTrue(version.isNotEmpty(), "Version should not be empty")
    }

    @Test
    fun `MustangVersion value property handles lazy initialization`() {
        val version1 = MustangVersion.value
        val version2 = MustangVersion.value
        assertEquals(version1, version2, "Version should be stable across multiple accesses")
    }

    @Test
    fun `MustangVersion value property returns fallback on missing resource`() {
        val version = MustangVersion.value
        // Should return either a real version or "unbekannt" (unknown)
        assertTrue(
            version != null && (version.contains(".") || version == "unbekannt"),
            "Version should be either a version string or 'unbekannt'"
        )
    }

    // ── resolve(loader) — the test seam around the memoized `value` above, since a Kotlin
    // `object`'s `by lazy` only ever evaluates once per JVM. ───────────────────────────────────

    @Test
    fun `resolve returns unbekannt when the classloader has no such resource at all`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream? = null
        }

        assertEquals("unbekannt", MustangVersion.resolve(loader))
    }

    @Test
    fun `resolve returns unbekannt when reading the resource throws`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream =
                throw java.io.IOException("boom")
        }

        assertEquals("unbekannt", MustangVersion.resolve(loader))
    }

    @Test
    fun `resolve returns unbekannt when the pom properties carry no version key`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream =
                "groupId=org.mustangproject".byteInputStream()
        }

        assertEquals("unbekannt", MustangVersion.resolve(loader))
    }

    @Test
    fun `resolve reads the version property when the resource is present and parsable`() {
        val loader = object : ClassLoader() {
            override fun getResourceAsStream(name: String?): java.io.InputStream =
                "version=9.9.9-test".byteInputStream()
        }

        assertEquals("9.9.9-test", MustangVersion.resolve(loader))
    }
}
