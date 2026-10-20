package de.littletrotter.ui.view.invoice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Bundle completeness for the mode-B (filtered-list export) key block, the same
 * "pin this specific block, project-wide parity is [de.littletrotter.ui.i18n.I18nBundleParityTest]'s
 * job" idiom [InvoiceExportDownloadKeysTest] (mode A) already uses.
 */
class InvoiceListExportKeysTest {

    private val newKeys = listOf(
        "invoiceListExport.dialog.title",
        "invoiceListExport.dialog.scope.label",
        "invoiceListExport.dialog.count",
        "invoiceListExport.dialog.confirm",
        "app.cancel",
        "export.privacyHint.coverSheet",
        "export.privacyHint.plain",
        "invoiceListExport.scope.all",
        "invoiceListExport.scope.text",
        "invoiceListExport.scope.status",
        "invoiceListExport.scope.validation",
        "invoiceListExport.scope.reverseCharge",
        "invoiceListExport.scope.recipient",
        "invoiceListExport.scope.sender",
        "invoiceListExport.scope.dateRange",
        "invoiceListExport.scope.dateFrom",
        "invoiceListExport.scope.dateTo",
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
    fun `every one-argument scope fragment carries its MessageFormat placeholder in both languages`() {
        listOf(
            "invoiceListExport.dialog.count",
            "invoiceListExport.scope.text",
            "invoiceListExport.scope.status",
            "invoiceListExport.scope.validation",
            "invoiceListExport.scope.reverseCharge",
            "invoiceListExport.scope.recipient",
            "invoiceListExport.scope.sender",
            "invoiceListExport.scope.dateFrom",
            "invoiceListExport.scope.dateTo",
        ).forEach { key ->
            assertThat(english.getProperty(key)).describedAs("English '%s'", key).contains("{0}")
            assertThat(german.getProperty(key)).describedAs("German '%s'", key).contains("{0}")
        }
    }

    @Test
    fun `the date range fragment carries both MessageFormat placeholders in both languages`() {
        assertThat(english.getProperty("invoiceListExport.scope.dateRange")).contains("{0}").contains("{1}")
        assertThat(german.getProperty("invoiceListExport.scope.dateRange")).contains("{0}").contains("{1}")
    }
}
