package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * A real bundle scan, not a locale-juggled [de.littletrotter.ui.i18n.UiTranslations.tr]
 * call: reads both files as plain [Properties] (see
 * [de.littletrotter.ui.i18n.I18nBundleParityTest]'s own KDoc on why not [java.util.ResourceBundle])
 * so a DE value and an EN value are asserted from the file that actually holds them, never from
 * two different keys resolved under whatever locale the current [Properties] happens to be.
 *
 * kotlin-xpdf's print-label bundles (`invoice-labels*.properties`) are not on kotlin-invoice's test
 * classpath (no such module dependency), so only this module's UI bundles are scanned here.
 */
class TripWordingGuardTest {

    private fun loadProperties(resourceName: String): Properties {
        val properties = Properties()
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Bundle resource '$resourceName' not found on the classpath"
        }
        stream.use { properties.load(it) }
        return properties
    }

    private val english by lazy { loadProperties("i18n/messages.properties") }
    private val german by lazy { loadProperties("i18n/messages_de.properties") }

    private val carCentricSynonym =
        Regex("fahrzeit|fahrtzeit|fahrdauer|driving time|drive time|driving duration", RegexOption.IGNORE_CASE)

    @Test
    fun `no bundle value uses a car-centric synonym for travel time`() {
        val hits = listOf("messages.properties" to english, "messages_de.properties" to german)
            .flatMap { (file, properties) ->
                properties.stringPropertyNames()
                    .filter { key -> carCentricSynonym.containsMatchIn(properties.getProperty(key)) }
                    .map { key -> "$file: $key = ${properties.getProperty(key)}" }
            }

        assertTrue(hits.isEmpty(), "car-centric travel-time synonym found: $hits")
    }

    @Test
    fun `travel-time kind keys read Reisezeit and Travel time under both key forms`() {
        assertEquals("Reisezeit", german.getProperty("invoiceDetail.trip.kind.travelTime"))
        assertEquals("Reisezeit", german.getProperty("trip.kind.travelTime"))
        assertEquals("Travel time", english.getProperty("invoiceDetail.trip.kind.travelTime"))
        assertEquals("Travel time", english.getProperty("trip.kind.travelTime"))
    }
}
