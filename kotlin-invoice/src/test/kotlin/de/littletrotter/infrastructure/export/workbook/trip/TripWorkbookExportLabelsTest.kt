package de.littletrotter.infrastructure.export.workbook.trip

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * The trip workbook's own label-builder parity test, the same discipline
 * [de.littletrotter.infrastructure.export.workbook.InvoiceWorkbookExportLabelBuilderTest] already
 * applies to the invoice workbook's keys: [buildTripWorkbookExportLabels] must produce a
 * label set every sheet writer's own `require(...)` guard accepts, and every new `tripExport.*`/
 * `trip.kind.*` key this builder introduces must resolve to a non-blank value in **both** the real
 * German and English bundles — the identity `tr` function used in the structural assertions below
 * would otherwise mask a key that only exists in one language.
 */
class TripWorkbookExportLabelsTest {

    private val identityTr: (String) -> String = { key -> key }

    @Test
    fun `detail sheet labels carry exactly two column headers, all non-blank`() {
        val labels = buildTripWorkbookExportLabels(identityTr).detailSheet
        assertThat(labels.columnHeaders).hasSize(2).allMatch { it.isNotBlank() }
        assertThat(
            listOf(
                labels.fieldTitle, labels.fieldPurpose, labels.fieldPeriodFrom, labels.fieldPeriodTo,
                labels.fieldInvoiceNumber, labels.fieldInvoiceDate, labels.fieldCustomer, labels.fieldCostCenter,
                labels.fieldTaxTreatment, labels.fieldCalculatedNet, labels.fieldFixedNetPrice, labels.fieldDelta,
                labels.fieldReceiptsCount, labels.taxTreatmentInheritsRatePattern, labels.noValueLabel,
            ),
        ).allMatch { it.isNotBlank() }
    }

    @Test
    fun `expense sheet labels carry exactly COLUMN_COUNT column headers and a label for every trip kind key`() {
        val labels = buildTripWorkbookExportLabels(identityTr).expenseSheet
        assertThat(labels.columnHeaders).hasSize(TripExpenseSheetWriter.COLUMN_COUNT).allMatch { it.isNotBlank() }
        assertThat(labels.kindLabels.keys).containsExactlyInAnyOrderElementsOf(TRIP_EXPENSE_KIND_KEYS)
        assertThat(labels.kindLabels.values).allMatch { it.isNotBlank() }
        assertThat(labels.totalRowLabel).isNotBlank()
    }

    @Test
    fun `per diem sheet mirrors the seven overview columns and description labels`() {
        val labels = buildTripWorkbookExportLabels(identityTr).perDiemSheet
        assertThat(labels.columnHeaders).containsExactly(
            "invoiceDetail.trip.expenses.overview.kind",
            "invoiceDetail.trip.expense.description",
            "invoiceDetail.trip.expenses.overview.date",
            "invoiceDetail.trip.expenses.overview.quantity",
            "invoiceDetail.trip.expenses.overview.unitPrice",
            "invoiceDetail.trip.expenses.overview.total",
            "invoiceDetail.trip.expenses.overview.receipt",
        )
        assertThat(labels.kindLabel).isEqualTo("invoiceDetail.trip.perDiem.overview.kind")
        assertThat(labels.scopeDaysOnlyPattern).isEqualTo("invoiceDetail.trip.perDiem.overview.scope.daysOnly")
        assertThat(labels.scopeNamedPattern).isEqualTo("invoiceDetail.trip.perDiem.overview.scope.named")
        assertThat(labels.scopeCountedPattern).isEqualTo("invoiceDetail.trip.perDiem.overview.scope.counted")
        assertThat(labels.descriptionLabels.arrivalDeparture).isEqualTo("invoiceDetail.trip.perDiem.overview.arrivalDeparture")
        assertThat(labels.descriptionLabels.fullDay).isEqualTo("invoiceDetail.trip.perDiem.overview.fullDay")
    }

    @Test
    fun `export sheet reuses the invoice workbook's own field-value and privacy labels, with a two-note trip-specific block`() {
        val labels = buildTripWorkbookExportLabels(identityTr).exportSheet
        assertThat(labels.notes.notes).hasSize(2).allMatch { it.label.isNotBlank() && it.text.isNotBlank() }
        assertThat(labels.privacy.paragraphs).hasSize(SEVEN_PARAGRAPHS).allMatch { it.article.isNotBlank() && it.text.isNotBlank() }
        assertThat(labels.subtitlePattern).isEqualTo("{0}")
    }

    @Test
    fun `the real bundles' subtitle pattern takes two arguments - the trip title and the invoice number`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        assertThat(de.getProperty("tripExport.cover.subtitlePattern")).contains("{0}").contains("{1}")
        assertThat(en.getProperty("tripExport.cover.subtitlePattern")).contains("{0}").contains("{1}")
    }

    // ── The real bundles — every new trip-export key must resolve in both languages ────────────

    private fun bundle(name: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream("i18n/$name") ?: error("Missing bundle i18n/$name")
        return Properties().also { props -> stream.use { props.load(it.reader(Charsets.UTF_8)) } }
    }

    private fun newTripExportKeys(): List<String> = listOf(
        "tripExport.cover.subtitlePattern",
        "tripExport.notes.1.label", "tripExport.notes.1.text",
        "tripExport.notes.2.label", "tripExport.notes.2.text",
        "tripExport.detail.field.title", "tripExport.detail.field.purpose",
        "tripExport.detail.field.periodFrom", "tripExport.detail.field.periodTo",
        "tripExport.detail.field.invoiceNumber", "tripExport.detail.field.invoiceDate",
        "tripExport.detail.field.customer", "tripExport.detail.field.costCenter",
        "tripExport.detail.field.taxTreatment", "tripExport.detail.field.calculatedNet",
        "tripExport.detail.field.fixedNetPrice", "tripExport.detail.field.delta",
        "tripExport.detail.field.receiptsCount", "tripExport.detail.taxTreatment.inheritsRatePattern",
        "tripExport.detail.noValue",
        "tripExport.posten.column.number", "tripExport.posten.column.kind",
        "tripExport.posten.column.description", "tripExport.posten.column.date",
        "tripExport.posten.column.quantity", "tripExport.posten.column.unit",
        "tripExport.posten.column.unitPriceNet", "tripExport.posten.column.netAmount",
        "tripExport.posten.column.arrivalDeparture", "tripExport.posten.column.breakfastDeduction",
        "tripExport.posten.column.lunchDeduction", "tripExport.posten.column.dinnerDeduction",
        "tripExport.posten.column.controlNet", "tripExport.posten.column.deltaNet",
        "tripExport.posten.totalRowLabel",
        "invoiceDetail.trip.expenses.overview.kind", "invoiceDetail.trip.expense.description",
        "invoiceDetail.trip.expenses.overview.date", "invoiceDetail.trip.expenses.overview.quantity",
        "invoiceDetail.trip.expenses.overview.unitPrice", "invoiceDetail.trip.expenses.overview.total",
        "invoiceDetail.trip.expenses.overview.receipt", "invoiceDetail.trip.perDiem.overview.kind",
        "invoiceDetail.trip.perDiem.overview.arrivalDeparture", "invoiceDetail.trip.perDiem.overview.fullDay",
        "invoiceDetail.trip.perDiem.overview.deduction.breakfast", "invoiceDetail.trip.perDiem.overview.deduction.lunch",
        "invoiceDetail.trip.perDiem.overview.deduction.dinner", "invoiceDetail.trip.perDiem.overview.footer.total",
        "invoiceDetail.trip.perDiem.overview.scope.daysOnly", "invoiceDetail.trip.perDiem.overview.scope.named",
        "invoiceDetail.trip.perDiem.overview.scope.counted",
    ) + TRIP_EXPENSE_KIND_KEYS

    @Test
    fun `every new RK-5 key resolves to a non-blank value in both the German and the English bundle`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        val keys = newTripExportKeys()

        val missingInDe = keys.filterNot { de.getProperty(it)?.isNotBlank() == true }
        val missingInEn = keys.filterNot { en.getProperty(it)?.isNotBlank() == true }

        assertThat(missingInDe).describedAs("keys missing (or blank) in messages_de.properties").isEmpty()
        assertThat(missingInEn).describedAs("keys missing (or blank) in messages.properties").isEmpty()
    }

    @Test
    fun `the tax-treatment pattern and the no-value label carry the spec's exact binding wording`() {
        val de = bundle("messages_de.properties")
        val en = bundle("messages.properties")
        assertThat(de.getProperty("tripExport.detail.taxTreatment.inheritsRatePattern")).contains("Landesreferenzsatz")
        assertThat(en.getProperty("tripExport.detail.taxTreatment.inheritsRatePattern")).contains("country reference rate")
        assertThat(de.getProperty("tripExport.detail.noValue")).isEqualTo("—")
        assertThat(en.getProperty("tripExport.detail.noValue")).isEqualTo("—")
    }

    @Test
    fun `boundary - PerDiemSheetLabels' German-default constructor values are never used by the real builder, but must stand on their own`() {
        val defaults = PerDiemSheetLabels()

        assertThat(defaults.columnHeaders).isEmpty()
        assertThat(defaults.title).isEqualTo("Verpflegungspauschalen")
        assertThat(defaults.totalRowLabel).isEqualTo("Summe")
        assertThat(defaults.kindLabel).isEqualTo("Verpflegungspauschale")
        assertThat(defaults.scopeDaysOnlyPattern).contains("{0,choice")
        assertThat(defaults.scopeNamedPattern).contains("{1}")
        assertThat(defaults.scopeCountedPattern).contains("Teilnehmer")
        assertThat(defaults.descriptionLabels.arrivalDeparture).isEqualTo("An-/Abreise")
        assertThat(defaults.descriptionLabels.fullDay).isEqualTo("24 Std.")
        assertThat(defaults.descriptionLabels.breakfastDeduction).isEqualTo("− Früh.")
        assertThat(defaults.descriptionLabels.lunchDeduction).isEqualTo("− Mittag")
        assertThat(defaults.descriptionLabels.dinnerDeduction).isEqualTo("− Abend")
    }

    private companion object {
        const val SEVEN_PARAGRAPHS = 7
    }
}
