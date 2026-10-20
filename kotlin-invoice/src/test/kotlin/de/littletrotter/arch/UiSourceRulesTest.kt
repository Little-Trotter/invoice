package de.littletrotter.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Colours and spacing live in the theme stylesheet, never in Kotlin. Every Kotlin file under the
 * UI source tree is scanned for colour literals and for inline `style.set` calls: the only inline
 * style a view may set is a custom property (`--…`) that carries a data-driven value such as a bar
 * width. ArchUnit cannot see string arguments, hence a source-text check. The same checker is
 * run against a violating specimen (must be flagged) and a clean one (must pass), so the rule
 * is proven to bite.
 */
class UiSourceRulesTest {

    @Test
    fun `ui sources contain no colour literals and no inline styles besides custom properties`() {
        assertTrue(Files.isDirectory(UI_SOURCES), "UI source tree not found: $UI_SOURCES")
        val violations = Files.walk(UI_SOURCES).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .flatMap { file -> violations(Files.readString(file)).map { "${UI_SOURCES.relativize(file)}: $it" } }
                .sorted()
                .toList()
        }
        assertTrue(violations.isEmpty(), "Inline styles / colour literals in UI sources:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `the checker flags every kind of violation in the specimen`() {
        val found = violations(specimen("StyleViolations.kt.txt"))

        assertEquals(COLOUR_VIOLATIONS, found.count { it.startsWith("colour literal") }, "violations found: $found")
        assertEquals(INLINE_VIOLATIONS, found.count { it.startsWith("inline style") }, "violations found: $found")
        assertTrue(found.any { it.startsWith("colour literal") && "#2196f3" in it })
        assertTrue(found.any { it.startsWith("colour literal") && "rgba(" in it })
        assertTrue(found.any { it.startsWith("colour literal") && "rgb(" in it })
        assertTrue(found.any { it.startsWith("colour literal") && "#fff" in it })
    }

    @Test
    fun `the checker accepts classes and custom properties`() {
        assertEquals(emptyList<String>(), violations(specimen("StyleClean.kt.txt")))
    }

    @Test
    fun `the checker is not fooled by line breaks or constants as property names`() {
        assertFalse(violations("x.style.set(\n    \"color\",\n    \"red\")").isEmpty())
        assertFalse(violations("x.style.set(PROPERTY, \"red\")").isEmpty())
        assertTrue(violations("x.style.set(\"--lt-bar\", \"4%\")").isEmpty())
    }

    private fun specimen(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/specimen/ui-source-rules/$name")) { "missing specimen $name" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }

    private companion object {
        val UI_SOURCES: Path = Path.of("src/main/kotlin/de/littletrotter/ui")

        const val COLOUR_VIOLATIONS = 4
        const val INLINE_VIOLATIONS = 8

        val COLOUR_LITERAL = Regex("""(?<![\w&])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b|\brgba?\(""")

        /** `style.set(` / `getStyle().set(` whose first argument is not a `"--…"` literal. */
        val INLINE_STYLE_CALL = Regex("""(?:\bstyle|getStyle\(\))\.set\((?!\s*"--)""")

        /** A second `.set(` chained onto a `style.set(…)` call on the same line. */
        val CHAINED_STYLE_CALL = Regex("""(?:\bstyle|getStyle\(\))\.set\([^\n]*\)\.set\(""")

        fun violations(source: String): List<String> {
            val colours = COLOUR_LITERAL.findAll(source).map { "colour literal ${it.value}" }
            val calls = INLINE_STYLE_CALL.findAll(source).map { "inline style call at offset ${it.range.first}" }
            val chained = CHAINED_STYLE_CALL.findAll(source).map { "inline style chained call at offset ${it.range.first}" }
            return (colours + calls + chained).toList()
        }
    }
}
