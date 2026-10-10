package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit-tests [I18nBundleMerger]'s override/fallback logic against a small, disposable set of
 * classpath fixtures — not the real `i18n/messages*.properties` files, which
 * [I18nBundleParityTest] already pins to carry an identical key set, so a genuine
 * "key missing in the override" case can never occur there. An isolated [URLClassLoader] rooted
 * at a temp directory (parent-less, so it only ever sees the fixture files) makes that case
 * reproducible on demand.
 */
class I18nBundleMergerTest {

    @TempDir
    lateinit var tmp: Path

    private fun fixtureClassLoader(vararg files: Pair<String, String>): URLClassLoader {
        val root = Files.createTempDirectory(tmp, "i18n-bundle-merger-test")
        files.forEach { (relativePath, content) ->
            val target: Path = root.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.write(target, content.toByteArray(StandardCharsets.UTF_8))
        }
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }

    private val baseAndOverride by lazy {
        fixtureClassLoader(
            "i18n/messages.properties" to "greeting=Hello\nfarewell=Goodbye\n",
            "i18n/messages_de.properties" to "greeting=Hallo\n",
        )
    }

    @Test
    fun `known locale overrides the base value but falls back to it for keys missing in the override`() {
        val resolved = I18nBundleMerger.resolve("de", baseAndOverride)

        assertEquals("Hallo", resolved["greeting"], "the German override must win where it exists")
        assertEquals("Goodbye", resolved["farewell"], "a key absent from the override must fall back to the base bundle")
    }

    @Test
    fun `unknown but validly-shaped locale resolves to the base bundle only`() {
        val resolved = I18nBundleMerger.resolve("fr", baseAndOverride)

        assertEquals(mapOf("greeting" to "Hello", "farewell" to "Goodbye"), resolved)
    }

    @Test
    fun `a locale failing the two-lowercase-letter pattern resolves to the base bundle only`() {
        val resolved = I18nBundleMerger.resolve("de-DE", baseAndOverride)

        assertEquals(mapOf("greeting" to "Hello", "farewell" to "Goodbye"), resolved)
    }

    @Test
    fun `a missing default bundle fails loudly instead of returning an empty map`() {
        val emptyClassLoader = fixtureClassLoader()

        val exception = assertThrows(IllegalStateException::class.java) {
            I18nBundleMerger.resolve("de", emptyClassLoader)
        }
        assertEquals(
            "Required i18n bundle 'i18n/messages.properties' not found on the classpath",
            exception.message,
        )
    }

    @Test
    fun `UTF-8 values in either bundle survive the merge`() {
        val umlautClassLoader = fixtureClassLoader(
            "i18n/messages.properties" to "label=Straße\n",
            "i18n/messages_de.properties" to "label=Straße (DE)\n",
        )

        assertEquals("Straße (DE)", I18nBundleMerger.resolve("de", umlautClassLoader)["label"])
        assertEquals("Straße", I18nBundleMerger.resolve("fr", umlautClassLoader)["label"])
    }

    @Test
    fun `resolving against the real application bundles returns known keys with the correct locale value`() {
        assertEquals("Fehler", I18nBundleMerger.resolve("de")["common.error"])
        assertEquals("Error", I18nBundleMerger.resolve("en")["common.error"])
        assertEquals("Error", I18nBundleMerger.resolve("fr")["common.error"], "unknown locale falls back to the English default")
    }
}
