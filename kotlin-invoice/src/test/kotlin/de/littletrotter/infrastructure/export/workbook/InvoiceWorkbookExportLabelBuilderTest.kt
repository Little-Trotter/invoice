package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Properties

/**
 * [buildInvoiceWorkbookExportLabels] must produce a label set every sheet writer's own
 * `require(...)` guard accepts (right column count, an entry for every enum value) and every
 * label must resolve to something other than blank — this pins the label builder against silently
 * reusing a key that doesn't actually exist, using the identity `tr` function so an
 * unresolved/blank lookup would otherwise be invisible.
 *
 * adds the "Export" cover sheet's own new key family (`invoiceExport.cover.*`,
 * `invoiceExport.notes.<n>.*`, `invoiceExport.privacy.<n>.*`) — [every new key resolves in the
 * real DE and EN bundles] is the one test here that does NOT use the identity lookup: it is the
 * only way to catch a key declared in the builder but missing (or only present in one language)
 * from the actual `messages.properties`/`messages_de.properties` files.
 */
class InvoiceWorkbookExportLabelBuilderTest {

    /** Identity lookup: every resolved label IS the key itself, so a missing/blank key would show
     *  up as an empty string rather than being masked by a real i18n value. */
    private val identityTr: (String) -> String = { key -> key }

    @Test
    fun `header sheet labels carry exactly COLUMN_COUNT column headers, all non-blank and unique`() {
        val labels = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.SINGLE_INVOICE).headerSheet
        assertThat(labels.columnHeaders).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT)
        assertThat(labels.columnHeaders).allMatch { it.isNotBlank() }
        assertThat(labels.columnHeaders.toSet()).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `header sheet enum labels cover every enum value`() {
        val enumLabels = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.SINGLE_INVOICE).headerSheet.enumLabels
        assertThat(enumLabels.typeCode.keys).containsExactlyInAnyOrderElementsOf(InvoiceTypeCode.entries)
        assertThat(enumLabels.status.keys).containsExactlyInAnyOrderElementsOf(InvoiceStatus.entries)
        assertThat(enumLabels.validationStatus.keys).containsExactlyInAnyOrderElementsOf(ValidationStatus.entries)
        assertThat(enumLabels.invoiceFormat.keys).containsExactlyInAnyOrderElementsOf(InvoiceFormat.entries)
        assertThat(enumLabels.typeCode.values).allMatch { it.isNotBlank() }
        assertThat(enumLabels.partySource.keys).hasSize(2)
    }

    @Test
    fun `position sheet labels carry exactly COLUMN_COUNT column headers and cover every enum value`() {
        val labels = buildInvoicePositionSheetLabels(identityTr)
        assertThat(labels.columnHeaders).hasSize(InvoicePositionSheetWriter.COLUMN_COUNT)
        assertThat(labels.enumLabels.category.keys).containsExactlyInAnyOrderElementsOf(InvoiceItemCategory.entries)
        assertThat(labels.enumLabels.vatCategory.keys).containsExactlyInAnyOrderElementsOf(VatCategory.entries)
        assertThat(labels.enumLabels.category.values).allMatch { it.isNotBlank() }
        assertThat(labels.enumLabels.vatCategory.values).allMatch { it.isNotBlank() }
        assertThat(labels.enumLabels.tripExpenseKind.keys).containsExactlyInAnyOrderElementsOf(TripExpenseKind.entries)
        assertThat(labels.enumLabels.tripExpenseKind.values).allMatch { it.isNotBlank() }
        assertThat(labels.perDiemKindLabel).isNotBlank()
    }

    @Test
    fun `journal sheet labels carry exactly COLUMN_COUNT column headers and a label per audit event`() {
        val labels = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.SINGLE_INVOICE).journalSheet
        assertThat(labels.columnHeaders).hasSize(InvoiceJournalSheetWriter.COLUMN_COUNT)
        assertThat(labels.eventLabels.keys).containsExactlyInAnyOrderElementsOf(InvoiceAuditEvent.entries)
        // the "Vorgang" column's own label map.
        assertThat(labels.actionLabels.keys).containsExactlyInAnyOrderElementsOf(de.littletrotter.domain.compliance.InvoiceAuditAction.entries)
        assertThat(labels.noEntriesNoteLabel).isNotBlank()
    }

    @Test
    fun `export sheet labels are all non-blank, mode-independent`() {
        val labels = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.SINGLE_INVOICE).exportSheet
        assertThat(
            listOf(
                labels.subtitlePattern, labels.exportSectionLabel,
                labels.keyValue.fieldColumnHeader, labels.keyValue.valueColumnHeader,
                labels.keyValue.generatedAtLabel, labels.keyValue.actingUserLabel,
                labels.keyValue.selectionLabel, labels.keyValue.applicationVersionLabel,
                labels.keyValue.recordCountLabelPattern, labels.notes.sectionLabel,
                labels.privacy.sectionLabel, labels.closingLabel,
            ),
        ).allMatch { it.isNotBlank() }
        assertThat(labels.notes.notes).hasSize(SIX_NOTES).allMatch { it.label.isNotBlank() && it.text.isNotBlank() }
        assertThat(labels.privacy.paragraphs).hasSize(SEVEN_PARAGRAPHS)
            .allMatch { it.article.isNotBlank() && it.text.isNotBlank() }
    }

    @Test
    fun `mode SINGLE_INVOICE resolves the four-sheet note text, mode FILTERED_LIST the three-sheet one`() {
        val singleInvoiceNotes = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.SINGLE_INVOICE).exportSheet.notes.notes
        val filteredListNotes = buildInvoiceWorkbookExportLabels(identityTr, ExportSheetMode.FILTERED_LIST).exportSheet.notes.notes

        assertThat(singleInvoiceNotes.first().text).isEqualTo("invoiceExport.notes.1.text")
        assertThat(filteredListNotes.first().text).isEqualTo("invoiceExport.notes.1.text.modeB")
        // Every other note and the label of note 1 itself are mode-independent.
        assertThat(singleInvoiceNotes.first().label).isEqualTo(filteredListNotes.first().label)
        assertThat(singleInvoiceNotes.drop(1)).isEqualTo(filteredListNotes.drop(1))
    }

    // ── The real bundles — every new key must resolve in both languages ─────────────────

    private fun bundle(name: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream("i18n/$name") ?: error("Missing bundle i18n/$name")
        return Properties().also { props -> stream.use { props.load(it.reader(Charsets.UTF_8)) } }
    }

    private fun newExportSheetKeys(): List<String> {
        val coverKeys = listOf(
            "invoiceExport.cover.subtitlePattern",
            "invoiceExport.cover.section.export",
            "invoiceExport.cover.section.notes",
            "invoiceExport.cover.section.privacy",
            "invoiceExport.cover.closing",
        )
        val noteKeys = (1..SIX_NOTES).flatMap { n -> listOf("invoiceExport.notes.$n.label", "invoiceExport.notes.$n.text") } +
            listOf("invoiceExport.notes.1.text.modeB")
        val privacyKeys = (1..SEVEN_PARAGRAPHS).flatMap { n -> listOf("invoiceExport.privacy.$n.article", "invoiceExport.privacy.$n.text") }
        return coverKeys + noteKeys + privacyKeys
    }

    @Test
    fun `every new XLS-10 key resolves to a non-blank value in both the German and the English bundle`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        val keys = newExportSheetKeys()

        val missingInDe = keys.filterNot { de.getProperty(it)?.isNotBlank() == true }
        val missingInEn = keys.filterNot { en.getProperty(it)?.isNotBlank() == true }

        assertThat(missingInDe).describedAs("keys missing (or blank) in messages_de.properties").isEmpty()
        assertThat(missingInEn).describedAs("keys missing (or blank) in messages.properties").isEmpty()
    }

    @Test
    fun `the German GDPR paragraphs cite DSGVO, the English ones GDPR`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        (1..SEVEN_PARAGRAPHS).forEach { n ->
            assertThat(de.getProperty("invoiceExport.privacy.$n.article")).contains("DSGVO")
            assertThat(en.getProperty("invoiceExport.privacy.$n.article")).contains("GDPR")
        }
    }

    @Test
    fun `the closing line and the Art 6 paragraph resolve through the real bundles with the spec's exact binding wording`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        assertThat(de.getProperty("invoiceExport.privacy.4.article")).contains("Art. 6 Abs. 1")
        assertThat(en.getProperty("invoiceExport.privacy.4.article")).contains("Art. 6(1)")
        assertThat(de.getProperty("invoiceExport.cover.closing"))
            .contains("keine Rechtsberatung")
            .contains("Datenschutzbeauftragte")
        assertThat(en.getProperty("invoiceExport.cover.closing"))
            .contains("not legal advice")
            .contains("data protection officer")
    }

    // ── The export label lambda carries the resolved (term-substituted) text through
    //    unchanged, so its own profile-dependent behaviour is exercised here against the REAL
    //    bundles + the real TermResolver, not the identityTr fixture used everywhere else in this
    //    file (buildInvoiceWorkbookExportLabels itself has no wording logic of its own — see its
    //    KDoc — it only ever renders whatever `tr` resolves). ──

    private fun realTr(profile: WordingProfile, locale: Locale): (String) -> String {
        val bundleName = if (locale == Locale.GERMAN) "messages_de.properties" else "messages.properties"
        val properties = bundle(bundleName)
        return { key ->
            val raw = properties.getProperty(key) ?: key
            TermSubstitution.apply(raw) { term -> TermResolver.resolve(profile, locale, term) }
        }
    }

    @Test
    fun `the product-position category label follows the active wording profile, both locales`() {
        val handwerkDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDWERK, Locale.GERMAN)).enumLabels.category
        val handelDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDEL, Locale.GERMAN)).enumLabels.category
        val beratungDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.BERATUNG, Locale.GERMAN)).enumLabels.category
        assertThat(handwerkDe.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Material")
        assertThat(handelDe.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Artikel")
        assertThat(beratungDe.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Produkt")

        val handwerkEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDWERK, Locale.ENGLISH)).enumLabels.category
        val handelEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDEL, Locale.ENGLISH)).enumLabels.category
        val beratungEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.BERATUNG, Locale.ENGLISH)).enumLabels.category
        assertThat(handwerkEn.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Material")
        assertThat(handelEn.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Item")
        assertThat(beratungEn.getValue(InvoiceItemCategory.PRODUCT)).isEqualTo("Product")
    }

    @Test
    fun `the trip-position category label is Dienstreise-Business trip regardless of the active wording profile`() {
        val handwerkDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDWERK, Locale.GERMAN)).enumLabels.category
        val handelDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDEL, Locale.GERMAN)).enumLabels.category
        val beratungDe = buildInvoicePositionSheetLabels(realTr(WordingProfile.BERATUNG, Locale.GERMAN)).enumLabels.category
        assertThat(handwerkDe.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Dienstreise")
        assertThat(handelDe.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Dienstreise")
        assertThat(beratungDe.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Dienstreise")

        val handwerkEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDWERK, Locale.ENGLISH)).enumLabels.category
        val handelEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.HANDEL, Locale.ENGLISH)).enumLabels.category
        val beratungEn = buildInvoicePositionSheetLabels(realTr(WordingProfile.BERATUNG, Locale.ENGLISH)).enumLabels.category
        assertThat(handwerkEn.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Business trip")
        assertThat(handelEn.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Business trip")
        assertThat(beratungEn.getValue(InvoiceItemCategory.TRIP)).isEqualTo("Business trip")
    }

    private companion object {
        const val SIX_NOTES = 6
        const val SEVEN_PARAGRAPHS = 7
    }
}
