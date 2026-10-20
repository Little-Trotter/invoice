package de.littletrotter.application.export

import de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Bundle completeness for [InvoiceHeaderColumnKeys]: every key it defines must resolve to
 * a non-blank value in BOTH `messages.properties` (English) and `messages_de.properties` (German).
 * [de.littletrotter.ui.i18n.I18nBundleParityTest] already guards that the two bundles never drift
 * apart key-for-key project-wide; this test additionally pins that these specific, newly-added
 * keys are actually present (not merely that the two files agree with each other) and that the key
 * list itself matches [InvoiceHeaderSheetWriter]'s column count.
 */
class InvoiceHeaderColumnKeysTest {

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

    private val allKeys: List<String> = InvoiceHeaderColumnKeys.ORDERED_KEYS + listOf(
        InvoiceHeaderColumnKeys.PARTY_SOURCE_SNAPSHOT_KEY,
        InvoiceHeaderColumnKeys.PARTY_SOURCE_MASTER_DATA_KEY,
        InvoiceHeaderColumnKeys.VERDICT_OK_KEY,
        InvoiceHeaderColumnKeys.VERDICT_DEVIATION_KEY,
        InvoiceHeaderColumnKeys.INVOICE_NUMBER_MALFORMED_MARKER_KEY,
    )

    @Test
    fun `carries exactly one key per sheet column, with no duplicates`() {
        assertThat(InvoiceHeaderColumnKeys.ORDERED_KEYS).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT)
        assertThat(InvoiceHeaderColumnKeys.ORDERED_KEYS.toSet()).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT)
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
    fun `the malformed-number marker pattern carries exactly one MessageFormat argument in both languages`() {
        assertThat(english.getProperty(InvoiceHeaderColumnKeys.INVOICE_NUMBER_MALFORMED_MARKER_KEY)).contains("{0}")
        assertThat(german.getProperty(InvoiceHeaderColumnKeys.INVOICE_NUMBER_MALFORMED_MARKER_KEY)).contains("{0}")
    }
}
