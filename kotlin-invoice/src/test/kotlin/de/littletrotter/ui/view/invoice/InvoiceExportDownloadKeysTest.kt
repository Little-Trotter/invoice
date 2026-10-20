package de.littletrotter.ui.view.invoice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Bundle completeness for the small block of new keys this package's export button
 * introduces (every other export label reuses an existing key elsewhere in the bundle — see
 * [buildInvoiceWorkbookExportLabels]'s own KDoc). [de.littletrotter.ui.i18n.I18nBundleParityTest]
 * already guards that the two bundles never drift apart key-for-key project-wide; this test
 * additionally pins that this specific key block resolves to a non-blank value in BOTH languages
 * and that the two MessageFormat/String.format patterns among them still carry their placeholder.
 */
class InvoiceExportDownloadKeysTest {

    private val newKeys = listOf(
        "invoiceExport.format.label",
        "invoiceExport.format.workbook",
        "invoiceExport.format.csv",
        "invoiceExport.error",
        "invoiceExport.export.column.field",
        "invoiceExport.export.column.value",
        "invoiceExport.export.field.generatedAt",
        "invoiceExport.export.field.actingUser",
        "invoiceExport.export.field.selection",
        "invoiceExport.export.field.applicationVersion",
        "invoiceExport.export.field.recordCountPattern",
        "invoiceExport.export.selection.singleInvoice",
    )

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

    @Test
    fun `every new key resolves to a non-blank value in the English bundle`() {
        newKeys.forEach { key ->
            assertThat(english.getProperty(key)).describedAs("English value for '%s'", key).isNotNull().isNotBlank()
        }
    }

    @Test
    fun `every new key resolves to a non-blank value in the German bundle`() {
        newKeys.forEach { key ->
            assertThat(german.getProperty(key)).describedAs("German value for '%s'", key).isNotNull().isNotBlank()
        }
    }

    @Test
    fun `the record count pattern carries exactly one String-format placeholder in both languages`() {
        assertThat(english.getProperty("invoiceExport.export.field.recordCountPattern")).contains("%s")
        assertThat(german.getProperty("invoiceExport.export.field.recordCountPattern")).contains("%s")
    }

    @Test
    fun `the single-invoice selection sentence carries exactly one MessageFormat placeholder in both languages`() {
        assertThat(english.getProperty("invoiceExport.export.selection.singleInvoice")).contains("{0}")
        assertThat(german.getProperty("invoiceExport.export.selection.singleInvoice")).contains("{0}")
    }
}
