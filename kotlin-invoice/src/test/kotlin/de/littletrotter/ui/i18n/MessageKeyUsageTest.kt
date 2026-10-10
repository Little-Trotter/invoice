package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.streams.asSequence

/**
 * Every message key the view code refers to must exist in both bundles, otherwise the user sees a
 * `!{key}!` placeholder at runtime. Key parity between the two bundles is asserted by
 * [I18nBundleParityTest]; together the two tests give "used keys exist in English and German".
 *
 * What counts as a use, found by scanning the Kotlin sources of the UI layer textually:
 *  - a string literal as first argument (after an optional component argument) of the lookup
 *    helpers `tr`, `t`, `translate`, `trWithArg`, `getTranslation`, `trOrNull`, `trOrNullForLocale`;
 *  - the value of a `const val` / `val` whose name ends in `_KEY`.
 *  - a file that declares its own `tr` helper which prepends a fixed prefix
 *    (`fun tr(key: String) = ...("quote.statistics.${'$'}key")`) has that prefix applied to its literals.
 *
 * A literal with a template (`"invoiceDetail.jobs.status.${'$'}{status}"`) is a dynamic key: only the
 * text before the first `${'$'}` is known, so at least one bundle key must start with that prefix.
 * A literal followed by `+` is treated the same way. Keys assembled entirely at runtime (`"${'$'}{prefix}.value"`) have no static part and cannot be
 * checked; the enum or spec that supplies them has to be covered by its own test.
 */
class MessageKeyUsageTest {

    private data class KeyUse(val file: String, val key: String, val dynamic: Boolean)

    private fun load(resource: String): Set<String> {
        val props = Properties()
        requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "$resource missing" }
            .use { props.load(it) }
        return props.stringPropertyNames()
    }

    private val english by lazy { load("i18n/messages.properties") }
    private val german by lazy { load("i18n/messages_de.properties") }

    private fun scan(file: String, source: String): List<KeyUse> {
        val local = LOCAL_HELPER.find(source)
        val localName = local?.groups?.get("name")?.value
        val localPrefix = local?.groups?.get("prefix")?.value.orEmpty()
        val calls = CALL.findAll(source)
            .filterNot { localName != null && it.groups["key"]?.value == localPrefix }
            .map { match ->
                val ownHelper = localName != null && match.groups["name"]?.value == localName && match.groups["dot"] == null
                toUse(file, match, if (ownHelper) localPrefix else "")
            }
        val constants = CONSTANT.findAll(source).map { toUse(file, it, "") }
        return (calls + constants).toList()
    }

    private fun toUse(file: String, match: MatchResult, prefix: String): KeyUse {
        val dynamic = match.groups["end"]?.value == "$" || match.groups["plus"] != null
        return KeyUse(file, prefix + match.groups["key"]?.value, dynamic)
    }

    private fun missing(uses: List<KeyUse>, bundle: Set<String>): List<String> =
        uses.filter { use ->
            if (use.dynamic) bundle.none { it.startsWith(use.key) } else use.key !in bundle
        }.map { "${it.file}: ${it.key}${if (it.dynamic) "\${…}" else ""}" }.distinct().sorted()

    @Test
    fun `every message key used by the UI code exists in both bundles`() {
        val root = Path.of("src/main/kotlin/de/littletrotter/ui")
        val uses = Files.walk(root).use { paths ->
            paths.asSequence().filter { it.toString().endsWith(".kt") }.flatMap { path ->
                scan(root.relativize(path).toString(), Files.readString(path)).asSequence()
            }.toList()
        }

        assertTrue(uses.size > 1000, "scanner found only ${uses.size} key uses, the patterns no longer match the sources")
        val missingEnglish = missing(uses, english)
        val missingGerman = missing(uses, german)
        assertTrue(missingEnglish.isEmpty(), "Keys used in Kotlin but missing from messages.properties:\n${missingEnglish.joinToString("\n")}")
        assertTrue(missingGerman.isEmpty(), "Keys used in Kotlin but missing from messages_de.properties:\n${missingGerman.joinToString("\n")}")
    }

    @Test
    fun `scanner flags unknown literal, dynamic and constant keys in a specimen`() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("i18n-specimen/UnknownKeySpecimen.txt"))
            .use { String(it.readAllBytes(), Charsets.UTF_8) }

        val uses = scan("UnknownKeySpecimen", source)

        assertEquals(
            listOf(
                "UnknownKeySpecimen: specimen.constant.that.does.not.exist",
                "UnknownKeySpecimen: specimen.dynamic.\${…}",
                "UnknownKeySpecimen: specimen.key.that.does.not.exist",
            ),
            missing(uses, english),
        )
        assertEquals(missing(uses, english), missing(uses, german))
        assertTrue(uses.any { it.key == "app.save" }, "the known key is still collected")
    }

    private companion object {
        private const val KEY_TEXT = """(?<key>[a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_-]+)+\.?)(?<end>["$])(?<plus>\s*\+)?"""
        private const val HELPERS = "tr|t|translate|trWithArg|getTranslation|trOrNull|trOrNullForLocale"
        val LOCAL_HELPER = Regex("""fun\s+(?<name>$HELPERS)\([^)]*\)[^=\n]*=\s*[\w.]+\(\s*(?:[\w@.]+\s*,\s*)?"(?<prefix>[^"$]*)\${'$'}key"""")
        val CALL = Regex("""(?<dot>\.)?\b(?<name>$HELPERS)\(\s*(?:[\w@.]+\s*,\s*)?"$KEY_TEXT""")
        val CONSTANT = Regex("""\b(?:const\s+)?val\s+\w*_KEY(?:\s*:\s*String)?\s*=\s*"$KEY_TEXT""")
    }
}
