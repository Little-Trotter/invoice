package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.infrastructure.export.workbook.InvoiceJournalSheetWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Bundle completeness for [InvoiceJournalColumnKeys]: every key it defines, plus every
 * [InvoiceAuditEvent] event-label key the `Ereignis` column resolves through, must resolve to a
 * non-blank value in BOTH `messages.properties` (English) and `messages_de.properties` (German).
 * [de.littletrotter.ui.i18n.I18nBundleParityTest] already guards that the two bundles never drift
 * apart key-for-key project-wide; this test additionally pins that every one of the nine event
 * values has a label in both languages — a missing key must fail this test, not render `!key!`.
 */
class InvoiceJournalColumnKeysTest {

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

    private val allKeys: List<String> = InvoiceJournalColumnKeys.ORDERED_KEYS +
        InvoiceJournalColumnKeys.EVENT_LABEL_KEYS +
        InvoiceJournalColumnKeys.ACTION_LABEL_KEYS +
        listOf(InvoiceJournalColumnKeys.NO_ENTRIES_NOTE_KEY)

    @Test
    fun `carries exactly one key per sheet column, with no duplicates`() {
        assertThat(InvoiceJournalColumnKeys.ORDERED_KEYS).hasSize(InvoiceJournalSheetWriter.COLUMN_COUNT)
        assertThat(InvoiceJournalColumnKeys.ORDERED_KEYS.toSet()).hasSize(InvoiceJournalSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `carries exactly one event-label key per InvoiceAuditEvent value, with no duplicates`() {
        assertThat(InvoiceJournalColumnKeys.EVENT_LABEL_KEYS).hasSize(InvoiceAuditEvent.entries.size)
        assertThat(InvoiceJournalColumnKeys.EVENT_LABEL_KEYS.toSet()).hasSize(InvoiceAuditEvent.entries.size)
    }

    @Test
    fun `carries exactly one action-label key per InvoiceAuditAction value, with no duplicates`() {
        assertThat(InvoiceJournalColumnKeys.ACTION_LABEL_KEYS).hasSize(InvoiceAuditAction.entries.size)
        assertThat(InvoiceJournalColumnKeys.ACTION_LABEL_KEYS.toSet()).hasSize(InvoiceAuditAction.entries.size)
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
}
