package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.text.MessageFormat
import java.util.Properties
import kotlin.text.Charsets.UTF_8

/**
 * The bundle's counting messages, rendered rather than parsed. [I18nBundleParityTest] proves every
 * pattern is syntactically valid; this proves the ones that count things actually read correctly
 * at 0, 1 and many — "1 Einträge" is a valid pattern and still wrong.
 *
 * Both bundles are covered: a `choice` clause is per-language (German and English do not even have
 * the same number of forms in the general case), so a translation that drops the singular arm is
 * exactly the regression this catches.
 */
class CountingMessagesTest {

    /** Read as UTF-8 explicitly: `Properties.load(InputStream)` is defined to decode ISO-8859-1,
     *  which turns every umlaut in these bundles into mojibake. The runtime does not have that
     *  problem — `PropertyResourceBundle` reads UTF-8 since Java 9 — so decoding any other way
     *  here would test a string the application never renders. */
    private fun load(resourceName: String): Properties {
        val properties = Properties()
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Bundle resource '$resourceName' not found on the classpath"
        }
        stream.use { InputStreamReader(it, UTF_8).use { reader -> properties.load(reader) } }
        return properties
    }

    private val german by lazy { load("i18n/messages_de.properties") }
    private val english by lazy { load("i18n/messages.properties") }

    private fun render(properties: Properties, key: String, count: Long): String =
        MessageFormat(properties.getProperty(key) ?: error("missing key: $key"), TranslationService.GERMAN)
            .format(arrayOf<Any>(count))

    @Test
    fun `the journal entry count reads correctly at zero, one and many`() {
        assertEquals("Keine Einträge", render(german, "journal.count", 0))
        assertEquals("1 Eintrag", render(german, "journal.count", 1))
        assertEquals("7 Einträge", render(german, "journal.count", 7))

        assertEquals("No entries", render(english, "journal.count", 0))
        assertEquals("1 entry", render(english, "journal.count", 1))
        assertEquals("7 entries", render(english, "journal.count", 7))
    }

    /** The grid summary and the dialog's own header count the same thing; both are rendered
     *  unconditionally, so both need an honest zero arm — a payload can legitimately be empty. */
    @Test
    fun `the journal field counts read correctly at zero, one and many`() {
        listOf("journal.summary.changed", "journal.summary.captured", "journal.dialog.changeCount")
            .forEach { key ->
                listOf(german, english).forEach { bundle ->
                    val zero = render(bundle, key, 0)
                    val one = render(bundle, key, 1)
                    val many = render(bundle, key, 5)
                    assertEquals(false, zero.contains("1"), "$key at 0 must not claim one: '$zero'")
                    assertEquals(true, one.contains("1"), "$key at 1 must name the count: '$one'")
                    assertEquals(true, many.contains("5"), "$key at 5 must name the count: '$many'")
                    assertEquals(false, one == many.replace("5", "1"), "$key does not distinguish singular from plural: '$one' / '$many'")
                }
            }
    }
}
