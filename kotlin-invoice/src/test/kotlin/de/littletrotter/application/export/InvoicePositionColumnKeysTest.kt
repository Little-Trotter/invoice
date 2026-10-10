package de.littletrotter.application.export

import de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Bundle completeness for [InvoicePositionColumnKeys], mirroring
 * [InvoiceHeaderColumnKeysTest]: every key it defines must resolve to a non-blank value in BOTH
 * `messages.properties` (English) and `messages_de.properties` (German), and the key list itself
 * must match [InvoicePositionSheetWriter]'s column count exactly.
 */
class InvoicePositionColumnKeysTest {

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

    private val allKeys: List<String> = InvoicePositionColumnKeys.ORDERED_KEYS + listOf(
        InvoicePositionColumnKeys.VERDICT_OK_KEY,
        InvoicePositionColumnKeys.VERDICT_DEVIATION_KEY,
        InvoicePositionColumnKeys.VERDICT_TAX_RATE_MISSING_KEY,
    )

    @Test
    fun `carries exactly one key per sheet column, with no duplicates`() {
        assertThat(InvoicePositionColumnKeys.ORDERED_KEYS).hasSize(InvoicePositionSheetWriter.COLUMN_COUNT)
        assertThat(InvoicePositionColumnKeys.ORDERED_KEYS.toSet()).hasSize(InvoicePositionSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `every key resolves to a non-blank value in the English bundle`() {
        allKeys.forEach { key ->
            assertThat(english.getProperty(key)).describedAs("English value for '%s'", key).isNotNull().isNotBlank()
        }
    }

    @Test
    fun `every key resolves to a non-blank value in the German bundle`() {
        allKeys.forEach { key ->
            assertThat(german.getProperty(key)).describedAs("German value for '%s'", key).isNotNull().isNotBlank()
        }
    }

    @Test
    fun `the tax-rate-missing verdict reads exactly Steuersatz fehlt in German, per the spec's own wording`() {
        assertThat(german.getProperty(InvoicePositionColumnKeys.VERDICT_TAX_RATE_MISSING_KEY)).isEqualTo("Steuersatz fehlt")
    }
}
