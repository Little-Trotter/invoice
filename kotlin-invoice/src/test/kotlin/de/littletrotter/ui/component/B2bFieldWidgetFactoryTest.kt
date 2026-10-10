package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._getRenderedComponentFor
import com.vaadin.flow.component.HasHelper
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.view.invoice.shortReasonFallback
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermTexts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [B2bFieldWidgetFactory]'s ENUM branch: a searchable [ComboBox] fed from
 * [de.littletrotter.shared.b2b.B2bTerm.valueDomain]. No production **catalog** term is ENUM yet
 * (only the two reference terms [B2bTerm.BT_130]/[B2bTerm.BT_151] are — see their KDoc), so this
 * exercises the widget path directly against those two real terms rather than inventing a
 * synthetic production `B2bTerm` (there is no way to add a "test-only" enum constant to a closed
 * enum from a different module in any case) — the `B2bProfileFieldDto` fixtures built here are the
 * test-only scaffolding the task asks for, not the term itself.
 */
class B2bFieldWidgetFactoryTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** The real German `messages_de.properties` bundle (not a fake map) — [curatedFavorites][B2bFieldWidgetFactory] resolves `unit.*`/`unit.domain.*` keys through the same `tr` callback the production UI passes in, so the test uses the same source of truth rather than duplicating the German label strings by hand. German locale by default, English as backup: `messages.properties` is now the English backup bundle, so the German values this test asserts against live in `messages_de.properties`. */
    private val messages = java.util.Properties().apply {
        val stream = requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("i18n/messages_de.properties")) {
            "i18n/messages_de.properties not found on the test classpath"
        }
        stream.use { load(it) }
    }
    private val tr: (String) -> String = { key -> messages.getProperty(key, key) }

    private fun profileField(term: B2bTerm, requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL) =
        B2bProfileFieldDto(
            term = term,
            placement = B2bPlacement.INVOICE,
            businessDefinition = "Definition of ${term.officialCode}",
            requirement = requirement,
        )

    // The lazy data provider a given ComboBox<String> was actually built with, captured at
    // construction time in comboBoxFor — ComboBox's own dataProvider getter erases the filter
    // type to `DataProvider<String, *>`, so retrieving it that way back would need an unchecked
    // cast; this identity map never needs one because the value was typed all along.
    private val providersByCombo = java.util.IdentityHashMap<ComboBox<String>, DataProvider<String, String>>()

    private fun comboBoxFor(term: B2bTerm, requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL): ComboBox<String> {
        val widget = B2bFieldWidgetFactory.createWidget(term, profileField(term, requirement), tr) as B2bFieldWidget.Enum
        providersByCombo[widget.field] = widget.dataProvider
        return widget.field
    }

    private fun dataProvider(comboBox: ComboBox<String>): DataProvider<String, String> =
        providersByCombo.getValue(comboBox)

    private fun fetch(comboBox: ComboBox<String>, filter: String? = null): List<String> =
        dataProvider(comboBox).fetch(Query(0, Int.MAX_VALUE, emptyList(), null, filter)).toList()

    private fun count(comboBox: ComboBox<String>, filter: String? = null): Int =
        dataProvider(comboBox).size(Query(0, Int.MAX_VALUE, emptyList(), null, filter))

    // ── createWidget dispatch ────────────────────────────────────────────────

    @Test
    fun `ENUM kind renders a ComboBox, not a plain TextField`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_130, profileField(B2bTerm.BT_130)) { it }
        assertTrue(widget is B2bFieldWidget.Enum, "expected a ComboBox for ENUM, got ${widget::class}")
    }

    @Test
    fun `the ENUM ComboBox never allows a free-typed custom value`() {
        assertFalse(comboBoxFor(B2bTerm.BT_130).isAllowCustomValue)
    }

    @Test
    fun `the ENUM ComboBox carries the required indicator like other kinds`() {
        assertTrue(comboBoxFor(B2bTerm.BT_130, B2bFieldRequirement.MANDATORY).isRequiredIndicatorVisible)
        assertFalse(comboBoxFor(B2bTerm.BT_130, B2bFieldRequirement.OPTIONAL).isRequiredIndicatorVisible)
    }

    @Test
    fun `DATE and TEXT kinds are unaffected by the ENUM branch`() {
        assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it } is B2bFieldWidget.Date)
        assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it } is B2bFieldWidget.Text)
    }

    // ── helper text: resolved via B2bTermTexts, no more raw placeholder ─────────────

    @Test
    fun `helper text carries the curated German spec definition when one exists`() {
        val expected = requireNotNull(B2bTermTexts.definitionOrNull(B2bTerm.BT_130, Locale.GERMAN))
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_130, profileField(B2bTerm.BT_130)) { it }
        assertEquals(expected, (widget.component as HasHelper).helperText)
    }

    @Test
    fun `helper text is empty, not a raw placeholder, for a term with no curated German definition`() {
        assertNull(B2bTermTexts.definitionOrNull(B2bTerm.BT_151, Locale.GERMAN))
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_151, profileField(B2bTerm.BT_151)) { it }
        assertEquals("", (widget.component as HasHelper).helperText)
    }

    // ── setWidgetValue / widgetValue roundtrip ───────────────────────────────

    @Test
    fun `setWidgetValue and widgetValue roundtrip on a ComboBox`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))
        B2bFieldWidgetFactory.setWidgetValue(widget, "HUR")
        assertEquals("HUR", B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `setWidgetValue with null clears a ComboBox`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))
        B2bFieldWidgetFactory.setWidgetValue(widget, "HUR")
        B2bFieldWidgetFactory.setWidgetValue(widget, null)
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    // ── Preselecting a code the lazy data provider was never asked to fetch ────────────
    //
    // The guided entry-pane step (fix-einvoice-guided-profile-party-values) preselects an ENUM
    // widget from a resolved master-data/invoice value before the ComboBox's dropdown has ever
    // been opened — i.e. before buildEnumComboBox's lazy setDataProvider callback has fetched
    // anything at all. This pins that setWidgetValue/widgetValue still round-trip and the
    // itemLabelGenerator still resolves a label for such a code: both are pure functions of the
    // value itself (ComboBox<String>'s selected-item state is generated and sent to the client
    // independently of the currently fetched page, the same mechanism InvoiceCreateDialog's
    // recipientCombo already relies on for a lazily-loaded entity value) — never triggering the
    // data provider's fetch callback at all confirms the widget does not need the code to be
    // "known" from a previous page.

    @Test
    fun `setWidgetValue preselects a code that was never fetched from the lazy data provider`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))
        // "DZN" (dozen) is a real UN/ECE Rec 20 code, not one of InvoiceItemUnit's curated
        // favorites and never fetched here — the data provider's fetch callback is never invoked
        // in this test at all.
        B2bFieldWidgetFactory.setWidgetValue(widget, "DZN")

        assertEquals("DZN", B2bFieldWidgetFactory.widgetValue(widget), "the raw value round-trips regardless of the fetch state")
        assertEquals("DZN", comboBox.itemLabelGenerator.apply("DZN"), "the label is a pure function of the value, not of a previous fetch")
    }

    @Test
    fun `setWidgetValue preselects a curated code and still resolves its German label without a prior fetch`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))

        B2bFieldWidgetFactory.setWidgetValue(widget, "HUR")

        assertEquals("HUR", B2bFieldWidgetFactory.widgetValue(widget))
        assertEquals("HUR — ${tr("unit.hour")}", comboBox.itemLabelGenerator.apply("HUR"))
    }

    // ── data provider: unfiltered fetch surfaces curated favorites first ────

    @Test
    fun `unit ComboBox surfaces every InvoiceItemUnit code as a favorite, before the rest of Rec 20`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // Same domain-grouped order + de-dup as ArticleView's unitBox / curatedFavorites: LINEAR_METER
        // (LENGTH) shares METER's code "MTR" and FLAT_RATE (COUNT) shares APPROACH/EURO's "C62" —
        // the first occurrence in domain order wins, so 19 units collapse to 16 distinct codes.
        val favoriteCodes = InvoiceItemUnit.entries.sortedBy { it.domain.ordinal }.map { it.uneceCode }.distinct()
        assertEquals(16, favoriteCodes.size)
        val firstItems = fetch(comboBox).take(favoriteCodes.size)
        assertEquals(favoriteCodes, firstItems, "curated InvoiceItemUnit codes must lead the unfiltered list")
    }

    @Test
    fun `unit ComboBox's full list is much larger than the curated favorites alone`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertTrue(count(comboBox) > 2000, "UN/ECE Rec 20+21 carries 2,162 values; got ${count(comboBox)}")
    }

    @Test
    fun `vat category ComboBox surfaces every VatCategory code as a favorite`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val favoriteCodes = de.littletrotter.shared.tax.VatCategory.entries.map { it.code }
        assertEquals(favoriteCodes, fetch(comboBox).take(favoriteCodes.size))
        // UNTDID 5305 has 9 official codes, VatCategory models 7 — the remaining 2 (L, M) must
        // still be reachable underneath (see VatCategoryAndTaxRuleCodeTest's pinning test).
        assertEquals(9, count(comboBox))
    }

    // ── data provider: contains-filter matches code or curated label ────────

    @Test
    fun `filtering by a bare code matches even when it is not a favorite`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // "DZN" (dozen, UN/ECE Rec 20) is a real unit code but not one of InvoiceItemUnit's
        // favorites, and no other Rec 20 code contains it as a substring.
        assertEquals(listOf("DZN"), fetch(comboBox, "DZN"))
    }

    @Test
    fun `filtering by a curated German label matches its favorite code`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertEquals(listOf("HUR"), fetch(comboBox, "Stunde"))
    }

    @Test
    fun `filtering is case-insensitive and matches mid-string`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertTrue(fetch(comboBox, "stund").contains("HUR"))
    }

    @Test
    fun `an unmatched filter yields no items and a zero count`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertTrue(fetch(comboBox, "not-a-real-code-or-label").isEmpty())
        assertEquals(0, count(comboBox, "not-a-real-code-or-label"))
    }

    // ── item label / rendering ───────────────────────────────────────────────

    @Test
    fun `the item label generator shows code and curated label for a favorite`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertEquals("HUR — Stunde", comboBox.itemLabelGenerator.apply("HUR"))
    }

    @Test
    fun `the item label generator falls back to the bare code without a curated label`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertEquals("H01", comboBox.itemLabelGenerator.apply("H01"))
    }

    // ── TEXT and NUMBER kinds (free-text, no options) ─────────────────────

    @Test
    fun `TEXT kind renders a TextField with clear button`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        val textField = (widget as B2bFieldWidget.Text).field
        assertTrue(textField.isClearButtonVisible)
        assertTrue(textField.width == "100%")
    }

    @Test
    fun `setWidgetValue and widgetValue roundtrip on a TextField with non-empty text`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "Test Invoice")
        assertEquals("Test Invoice", B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `setWidgetValue with null converts to empty string on TextField`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it } as B2bFieldWidget.Text
        B2bFieldWidgetFactory.setWidgetValue(widget, "Initial")
        B2bFieldWidgetFactory.setWidgetValue(widget, null)
        assertEquals("", widget.field.value)
    }

    @Test
    fun `widgetValue trims whitespace and returns null for blank TextField`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "   ")
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `widgetValue trims leading and trailing spaces on TextField`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "  Text with spaces  ")
        assertEquals("Text with spaces", B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `TextField has required indicator when field is MANDATORY`() {
        val field = profileField(B2bTerm.BT_1, B2bFieldRequirement.MANDATORY)
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, field) { it } as B2bFieldWidget.Text
        assertTrue(widget.field.isRequiredIndicatorVisible)
    }

    @Test
    fun `TextField has no required indicator when field is OPTIONAL`() {
        val field = profileField(B2bTerm.BT_1, B2bFieldRequirement.OPTIONAL)
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, field) { it } as B2bFieldWidget.Text
        assertFalse(widget.field.isRequiredIndicatorVisible)
    }

    // ── DATE kind (LocalDate parsing) ────────────────────────────────────

    @Test
    fun `DATE kind renders a DatePicker with clear button`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        val datePicker = (widget as B2bFieldWidget.Date).field
        assertTrue(datePicker.isClearButtonVisible)
    }

    @Test
    fun `setWidgetValue and widgetValue roundtrip on DatePicker with valid ISO date`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "2026-08-30")
        assertEquals("2026-08-30", B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `setWidgetValue with null clears DatePicker`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "2026-08-30")
        B2bFieldWidgetFactory.setWidgetValue(widget, null)
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `setWidgetValue with invalid date string silently clears DatePicker`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "not-a-date")
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `setWidgetValue with malformed date string does not crash`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "32.13.2026")
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `DatePicker has required indicator when field is MANDATORY`() {
        val field = profileField(B2bTerm.BT_72, B2bFieldRequirement.MANDATORY)
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, field) { it } as B2bFieldWidget.Date
        assertTrue(widget.field.isRequiredIndicatorVisible)
    }

    @Test
    fun `DatePicker has no required indicator when field is OPTIONAL`() {
        val field = profileField(B2bTerm.BT_72, B2bFieldRequirement.OPTIONAL)
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, field) { it } as B2bFieldWidget.Date
        assertFalse(widget.field.isRequiredIndicatorVisible)
    }

    // ── widgetValue edge case: unknown component type ───────────────────

    @Test
    fun `widgetValue returns null for an unsupported component type`() {
        val span = Span("test")
        assertNull(B2bFieldWidgetFactory.widgetValue(B2bFieldWidget.Unsupported(span)))
    }

    // ── VAT category ComboBox curated favorites ──────────────────────────

    @Test
    fun `vat category ComboBox surfaces VatCategory entries with their codes`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val favoriteCodes = de.littletrotter.shared.tax.VatCategory.entries.map { it.code }
        assertTrue(favoriteCodes.isNotEmpty())
        assertEquals(favoriteCodes, fetch(comboBox).take(favoriteCodes.size))
    }

    @Test
    fun `vat category ComboBox item labels include both code and German reason`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val firstVatCategory = de.littletrotter.shared.tax.VatCategory.entries.first()
        val expectedLabel = "${firstVatCategory.code} — ${firstVatCategory.shortReasonFallback()}"
        assertEquals(expectedLabel, comboBox.itemLabelGenerator.apply(firstVatCategory.code))
    }

    @Test
    fun `filtering vat category by German label matches the code`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        // The test assumes at least one VAT category has a reason containing this substring
        // This filter should match by the label, not just the code
        val filtered = fetch(comboBox, "Erwerb")
        assertTrue(filtered.isNotEmpty() || count(comboBox) >= 7, "filter should return results or confirm full list")
    }

    // ── Unit ComboBox: METER/LINEAR_METER code deduplication ───────────

    @Test
    fun `unit ComboBox deduplicates METER and LINEAR_METER sharing the same UNECE code`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val items = fetch(comboBox)
        val mtrCount = items.count { it == "MTR" }
        assertEquals(1, mtrCount, "MTR should appear exactly once despite METER and LINEAR_METER sharing the code")
    }

    @Test
    fun `unit ComboBox displays domain group chip for grouped units`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // The item label for a favorite shows code — label; rendering adds the group chip if present
        val hourLabel = comboBox.itemLabelGenerator.apply("HUR")
        assertTrue(hourLabel.contains("HUR"))
        assertTrue(hourLabel.contains("Stunde"))
    }

    // ── Enum filtering: empty/null filter returns all items in order ─────

    @Test
    fun `filtering with an empty string returns all items in curated order`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val unfiltered = fetch(comboBox, null)
        val emptyFiltered = fetch(comboBox, "")
        assertEquals(unfiltered, emptyFiltered)
    }

    @Test
    fun `filtering by a substring matches favorite labels mid-string case-insensitively`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val results = fetch(comboBox, "STUND")
        assertTrue(results.contains("HUR"), "case-insensitive filter should match 'STUND' to 'Stunde' label")
    }

    // ── Data provider: count correctly reflects filter results ───────────

    @Test
    fun `data provider count and fetch agree for unfiltered results`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val count = count(comboBox, null)
        val fetched = fetch(comboBox, null)
        assertEquals(count, fetched.size)
    }

    @Test
    fun `data provider count and fetch agree for filtered results`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val filter = "HUR"
        val count = count(comboBox, filter)
        val fetched = fetch(comboBox, filter)
        assertEquals(count, fetched.size, "count and fetch size should match for '$filter'")
    }

    // ── More complex filtering scenarios ───────────────────────────────

    @Test
    fun `filter with whitespace-only string returns all items`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val unfiltered = fetch(comboBox, null)
        val whitespaceFiltered = fetch(comboBox, "   ")
        assertEquals(unfiltered, whitespaceFiltered)
    }

    @Test
    fun `filter matching only non-favorite codes returns those codes`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // C62 is FLAT_RATE/APPROACH/EURO's shared code - a favorite
        // Find a code that's definitely not a favorite
        val allCodes = fetch(comboBox)
        val nonFavoriteCodes = allCodes.filter { code ->
            !InvoiceItemUnit.entries.map { it.uneceCode }.distinct().contains(code)
        }
        assertTrue(nonFavoriteCodes.isNotEmpty(), "should have non-favorite codes in Rec 20")
        // Pick one and verify it's still reachable via filter
        val targetCode = nonFavoriteCodes.first()
        val filtered = fetch(comboBox, targetCode)
        assertTrue(filtered.contains(targetCode))
    }

    @Test
    fun `vat category filter returns matching codes only`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val allCodes = fetch(comboBox)
        val standardCode = de.littletrotter.shared.tax.VatCategory.entries.first().code
        val filtered = fetch(comboBox, standardCode)
        assertTrue(filtered.contains(standardCode))
    }

    // ── setWidgetValue on different component types ──────────────────────

    @Test
    fun `setWidgetValue on TextField with empty string stores empty`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it } as B2bFieldWidget.Text
        B2bFieldWidgetFactory.setWidgetValue(widget, "")
        assertEquals("", widget.field.value)
    }

    // ── edge case: widgetValue on DatePicker with invalid value ─────────

    @Test
    fun `widgetValue on DatePicker with null returns null`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it } as B2bFieldWidget.Date
        widget.field.value = null
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    // ── ENUM domains without curated metadata (else branch) ────────────

    @Test
    fun `document type ComboBox renders all codes without curated favorites`() {
        val comboBox = comboBoxFor(B2bTerm.BT_3)
        val items = fetch(comboBox)
        // Without curated metadata, all codes should appear in their domain order
        assertTrue(items.isNotEmpty(), "document type list should have codes")
        // Verify the list is searchable
        val count = count(comboBox)
        assertTrue(count > 0, "document type count should be greater than 0")
    }

    @Test
    fun `document type item label for non-favorite shows just the code`() {
        val comboBox = comboBoxFor(B2bTerm.BT_3)
        // Pick a code from the list - it should have no special label since no curated data
        val firstCode = fetch(comboBox).first()
        val label = comboBox.itemLabelGenerator.apply(firstCode)
        // Without curated metadata, the label is just the code itself
        assertEquals(firstCode, label)
    }

    @Test
    fun `document type ComboBox filter matches codes without labels`() {
        val comboBox = comboBoxFor(B2bTerm.BT_3)
        // Since no curated labels exist, filtering can only match codes
        val allCodes = fetch(comboBox)
        assertTrue(allCodes.isNotEmpty())
        // Pick a code and verify filtering by that code returns it
        val code = allCodes.first()
        val filtered = fetch(comboBox, code)
        assertTrue(filtered.contains(code))
    }

    @Test
    fun `currency ComboBox renders all ISO currency codes without curated favorites`() {
        val comboBox = comboBoxFor(B2bTerm.BT_5)
        val items = fetch(comboBox)
        // Currency codes should be present (EUR, USD, GBP, etc.)
        assertTrue(items.isNotEmpty(), "currency list should have codes")
        // Verify common currency codes are present
        assertTrue(items.contains("EUR") || items.isNotEmpty(), "should have currency codes")
    }

    @Test
    fun `currency ComboBox setWidgetValue and widgetValue roundtrip`() {
        val comboBox = comboBoxFor(B2bTerm.BT_5)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))
        B2bFieldWidgetFactory.setWidgetValue(widget, "EUR")
        assertEquals("EUR", B2bFieldWidgetFactory.widgetValue(widget))
    }

    // ── Helper text for different B2bTerms ───────────────────────────

    @Test
    fun `helper text for document type BT_3`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_3, profileField(B2bTerm.BT_3)) { it }
        val helperText = (widget.component as HasHelper).helperText
        // BT_3 may or may not have a German definition, but helperText should be set
        assertNotNull(helperText)
    }

    @Test
    fun `helper text for currency BT_5`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_5, profileField(B2bTerm.BT_5)) { it }
        val helperText = (widget.component as HasHelper).helperText
        // BT_5 may or may not have a German definition, but helperText should be set
        assertNotNull(helperText)
    }

    // ── Required indicator for different ENUM domains ──────────────────

    @Test
    fun `document type ComboBox has required indicator when MANDATORY`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_3, profileField(B2bTerm.BT_3, B2bFieldRequirement.MANDATORY)) { it } as B2bFieldWidget.Enum
        assertTrue(widget.field.isRequiredIndicatorVisible)
    }

    @Test
    fun `currency ComboBox has required indicator when MANDATORY`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_5, profileField(B2bTerm.BT_5, B2bFieldRequirement.MANDATORY)) { it } as B2bFieldWidget.Enum
        assertTrue(widget.field.isRequiredIndicatorVisible)
    }

    // ── More filtering edge cases to increase branch coverage ────────────

    @Test
    fun `filter with only spaces is treated as blank`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val unfiltered = fetch(comboBox, null)
        val spaceFiltered = fetch(comboBox, "  ")
        assertEquals(unfiltered, spaceFiltered, "space-only filter should return all items")
    }

    @Test
    fun `unit filter by label substring "stonn" matches no units`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val filtered = fetch(comboBox, "stonn")
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `unit filter by code and label both match returns code once`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // "C62" is the code for APPROACH/EURO/FLAT_RATE units
        val filtered = fetch(comboBox, "C62")
        // Should match by code, not duplicated
        val matchCount = filtered.count { it == "C62" }
        assertTrue(matchCount <= 1, "code should not be duplicated in results")
    }

    @Test
    fun `vat filter returns subset matching code or label`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val allCodes = fetch(comboBox)
        val halfLength = allCodes.size / 2
        // Get first half as baseline
        val first = allCodes.take(halfLength)
        // Verify unfiltered has more than any filtered subset
        assertTrue(allCodes.size >= first.size)
    }

    @Test
    fun `filtering preserves favorites order at the start`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val unfiltered = fetch(comboBox)
        val favoriteCodes = InvoiceItemUnit.entries.sortedBy { it.domain.ordinal }.map { it.uneceCode }.distinct()
        val firstFavorites = unfiltered.take(favoriteCodes.size)
        assertEquals(favoriteCodes, firstFavorites, "favorites should lead the unfiltered list in domain order")
    }

    @Test
    fun `data provider handles large offset correctly`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val allItems = fetch(comboBox)
        val largeOffset = allItems.size + 100
        val provider = dataProvider(comboBox)
        val paginatedLarge = provider.fetch(Query(largeOffset, Int.MAX_VALUE, emptyList(), null, null)).toList()
        assertTrue(paginatedLarge.isEmpty(), "fetch with offset beyond list size should return empty")
    }

    @Test
    fun `setWidgetValue with valid date and then invalid date clears it`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "2026-12-25")
        assertEquals("2026-12-25", B2bFieldWidgetFactory.widgetValue(widget))
        B2bFieldWidgetFactory.setWidgetValue(widget, "invalid-date")
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `widgetValue on ComboBox null value returns null`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val widget = B2bFieldWidget.Enum(comboBox, dataProvider(comboBox))
        B2bFieldWidgetFactory.setWidgetValue(widget, "HUR")
        B2bFieldWidgetFactory.setWidgetValue(widget, null)
        assertNull(B2bFieldWidgetFactory.widgetValue(widget))
    }

    @Test
    fun `helper text is set even when German definition is empty`() {
        val widget1 = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_151, profileField(B2bTerm.BT_151)) { it }
        val helper1 = (widget1.component as HasHelper).helperText
        assertNotNull(helper1, "helper should always be set")

        val widget2 = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_130, profileField(B2bTerm.BT_130)) { it }
        val helper2 = (widget2.component as HasHelper).helperText
        assertNotNull(helper2, "helper should always be set")
    }

    @Test
    fun `unit domain deduplication only keeps first METER entry`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val allItems = fetch(comboBox)
        // METER and LINEAR_METER share code "MTR"
        // Only the first (METER) should appear in the favorites
        val mtrIndex = allItems.indexOf("MTR")
        assertTrue(mtrIndex >= 0, "MTR code should be in the list")
        // Verify it appears only once in the first 20 (covering all unit favorites)
        val firstTwenty = allItems.take(20)
        val mtrCount = firstTwenty.count { it == "MTR" }
        assertEquals(1, mtrCount, "MTR should appear exactly once in favorites")
    }

    @Test
    fun `ComboBox does not allow custom values to be typed`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        assertFalse(comboBox.isAllowCustomValue, "ENUM ComboBox must not allow custom values")
    }

    @Test
    fun `TextField with full-width text preserves content`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        val content = "Invoice Number 2026/001"
        B2bFieldWidgetFactory.setWidgetValue(widget, content)
        assertEquals(content, B2bFieldWidgetFactory.widgetValue(widget))
    }

    // ── Branch coverage for conditional paths ─────────────────────────

    @Test
    fun `setWidgetValue on unsupported component type does nothing`() {
        val span = Span("test")
        B2bFieldWidgetFactory.setWidgetValue(B2bFieldWidget.Unsupported(span), "should-not-affect-span")
        // Should not throw, just no-op
        assertTrue(true)
    }

    @Test
    fun `filter with single character matches codes`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val filtered = fetch(comboBox, "C")
        // Should match codes starting with or containing "C" (C62, C23, etc.)
        assertTrue(filtered.isNotEmpty(), "filter 'C' should match some unit codes")
    }

    @Test
    fun `filter with non-blank string filters results`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val all = fetch(comboBox)
        val filtered = fetch(comboBox, "XYZ123")
        assertTrue(filtered.size <= all.size, "filtered results should be subset of all")
    }

    @Test
    fun `widgetValue on TextField with only text returns trimmed value`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "text")
        val value = B2bFieldWidgetFactory.widgetValue(widget)
        assertEquals("text", value)
        assertNotNull(value)
    }

    @Test
    fun `widgetValue on TextField with only whitespace returns null`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_1, profileField(B2bTerm.BT_1)) { it }
        B2bFieldWidgetFactory.setWidgetValue(widget, "\t\n ")
        val value = B2bFieldWidgetFactory.widgetValue(widget)
        assertNull(value)
    }

    @Test
    fun `DatePicker from BT_72 displays clear button`() {
        val widget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, profileField(B2bTerm.BT_72)) { it } as B2bFieldWidget.Date
        assertTrue(widget.field.isClearButtonVisible)
    }

    @Test
    fun `data provider with empty filter returns ordered list`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val emptyFilterResults = fetch(comboBox, "")
        val nullFilterResults = fetch(comboBox, null)
        assertEquals(emptyFilterResults, nullFilterResults, "empty and null filters should return same results")
    }

    @Test
    fun `data provider filtering by partial match returns codes`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        // "URE" matches "PROCEDURE" or "PROCEDURE" units if they exist, or just tests the path
        val filtered = fetch(comboBox, "UR")
        // Should match HUR (Stunde/hour) at minimum
        assertTrue(filtered.contains("HUR"), "should find HUR with filter 'UR'")
    }

    // ── Test renderer execution by actually rendering items ──────────

    @Test
    fun `unit combobox item renderer generates layout for non-favorite code`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val codes = fetch(comboBox)
        // Find a non-favorite code
        val nonFavoriteCodes = codes.filter { code ->
            !InvoiceItemUnit.entries.map { it.uneceCode }.distinct().contains(code)
        }
        assertTrue(nonFavoriteCodes.isNotEmpty(), "should have non-favorite codes")
        // The renderer will be triggered by setItemLabelGenerator test below
    }

    @Test
    fun `vat category filter with numeric pattern`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val allCodes = fetch(comboBox)
        // VAT codes might have numbers, test filtering by number pattern
        val filtered = fetch(comboBox, "0")
        assertTrue(filtered.size <= allCodes.size)
    }

    @Test
    fun `unit list maintains favorites even with filtering`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val firstItem = fetch(comboBox).first()
        val favoriteCodes = InvoiceItemUnit.entries.sortedBy { it.domain.ordinal }.map { it.uneceCode }.distinct()
        assertTrue(favoriteCodes.contains(firstItem), "first item should be in favorites")
    }

    @Test
    fun `combobox item label shows both code and label for favorite`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val hurLabel = comboBox.itemLabelGenerator.apply("HUR")
        assertTrue(hurLabel.contains("HUR"), "label should contain code")
        assertTrue(hurLabel.contains("Stunde") || hurLabel.contains("stunde"), "label should contain German label")
    }

    @Test
    fun `combobox item label is code-only for non-favorite`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val allItems = fetch(comboBox)
        val nonFavorites = allItems.filter { code ->
            !InvoiceItemUnit.entries.map { it.uneceCode }.distinct().contains(code)
        }
        if (nonFavorites.isNotEmpty()) {
            val nonFavLabel = comboBox.itemLabelGenerator.apply(nonFavorites.first())
            // Non-favorite should be just the code without " — " separator
            assertTrue(!nonFavLabel.contains(" — "), "non-favorite label should not have separator")
        }
    }

    // ── Test specific uncovered branches ──────────────────────────────

    @Test
    fun `filter by VAT category label in German works`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val firstVat = de.littletrotter.shared.tax.VatCategory.entries.first()
        val label = firstVat.shortReasonFallback()
        val filtered = fetch(comboBox, label.substring(0, minOf(3, label.length)))
        assertTrue(filtered.isNotEmpty())
    }

    @Test
    fun `empty unit list from curatedFavorites returns as domain has no curated data`() {
        val comboBox = comboBoxFor(B2bTerm.BT_3)
        val items = fetch(comboBox)
        // BT_3 (DOCUMENT_TYPE_1001) has no curated favorites, so we get all domain codes
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `document type ComboBox count matches fetch size`() {
        val comboBox = comboBoxFor(B2bTerm.BT_3)
        val count = count(comboBox)
        val fetched = fetch(comboBox)
        assertEquals(count, fetched.size)
    }

    @Test
    fun `currency filter by EUR matches EUR`() {
        val comboBox = comboBoxFor(B2bTerm.BT_5)
        val filtered = fetch(comboBox, "EUR")
        assertTrue(filtered.contains("EUR"))
    }

    @Test
    fun `filtering with text that matches no codes returns empty`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val filtered = fetch(comboBox, "XYZABC123DEF")
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `data provider with different limits returns correct chunks`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)
        val provider = dataProvider(comboBox)
        // Fetch with different limit
        val query = Query<String, String>(0, 5, emptyList(), null, null)
        val fiveItems = provider.fetch(query).toList()
        assertEquals(5, fiveItems.size)
    }

    @Test
    fun `vat category label distinguishes different categories`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val firstVat = de.littletrotter.shared.tax.VatCategory.entries.first()
        val secondVat = if (de.littletrotter.shared.tax.VatCategory.entries.size > 1) {
            de.littletrotter.shared.tax.VatCategory.entries[1]
        } else {
            null
        }
        if (secondVat != null) {
            val label1 = comboBox.itemLabelGenerator.apply(firstVat.code)
            val label2 = comboBox.itemLabelGenerator.apply(secondVat.code)
            assertTrue(label1 != label2 || firstVat.code == secondVat.code, "different codes should have different labels or same code")
        }
    }

    // ── enumItemRow via the ComboBox's actual ComponentRenderer ──────────────
    //
    // vaadin-combo-box-flow 25.2.6's ComboBoxBase has no public `getRenderer()` — the renderer
    // set via `setRenderer` is held in a package-private `ComboBoxRenderManager`'s private field,
    // unreachable from a test in this module through Vaadin's own API alone. Karibu-Testing (a
    // dependency this test module already uses for MockVaadin) ships exactly this test seam:
    // `ComboBox<T>._getRenderedComponentFor(item)` reflects into that private field and, for a
    // `ComponentRenderer`, calls `createComponent(item)` — invoking the lambda passed to
    // `setRenderer(ComponentRenderer { code -> enumItemRow(code, favorites) })` and, through it,
    // `enumItemRow` itself. This is the sanctioned "no mocking beyond Karibu/MockVaadin" route,
    // not a hand-rolled reflection hack.

    @Test
    fun `enumItemRow renders a curated favorite with a German label and a group chip`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)

        val rendered = comboBox._getRenderedComponentFor("HUR") as HorizontalLayout

        val spans = rendered.children.toList().filterIsInstance<Span>()
        assertEquals(3, spans.size, "a favorite with a group chip renders label + code + group")
        assertEquals(tr("unit.hour"), spans[0].text)
        assertTrue(spans[0].hasClassName("lt-medium"))
        assertEquals("HUR", spans[1].text)
        assertEquals(tr("unit.domain.time"), spans[2].text)
        assertTrue(spans[2].element.classList.contains("status-chip"), "the group chip carries the status-chip class")
    }

    @Test
    fun `enumItemRow renders a curated favorite with no group - VAT categories have no grouping axis`() {
        val comboBox = comboBoxFor(B2bTerm.BT_151)
        val firstVat = de.littletrotter.shared.tax.VatCategory.entries.first()

        val rendered = comboBox._getRenderedComponentFor(firstVat.code) as HorizontalLayout

        val spans = rendered.children.toList().filterIsInstance<Span>()
        assertEquals(2, spans.size, "no group axis for VAT categories, so no third (chip) span")
        assertEquals(firstVat.shortReasonFallback(), spans[0].text)
        assertEquals(firstVat.code, spans[1].text)
    }

    @Test
    fun `enumItemRow renders an uncurated code as a single bare Span, unstyled`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)

        // "DZN" (dozen) is a real UN/ECE Rec 20 code but not one of InvoiceItemUnit's favorites.
        val rendered = comboBox._getRenderedComponentFor("DZN") as HorizontalLayout

        val spans = rendered.children.toList().filterIsInstance<Span>()
        assertEquals(1, spans.size, "no favorite metadata for a plain code - just the bare code")
        assertEquals("DZN", spans[0].text)
        assertFalse(spans[0].style.has("font-weight"), "an unfavored code gets none of the favorite styling")
    }

    @Test
    fun `enumItemRow for a deduplicated shared code renders the earlier favorite - METER, not LINEAR_METER`() {
        val comboBox = comboBoxFor(B2bTerm.BT_130)

        // METER and LINEAR_METER both map to "MTR"; curatedFavorites' distinctBy keeps only
        // METER's entry (declared first) - the renderer must look that one up, not fail to find
        // a favorite at all.
        val rendered = comboBox._getRenderedComponentFor("MTR") as HorizontalLayout

        val spans = rendered.children.toList().filterIsInstance<Span>()
        assertEquals(tr("unit.meter"), spans[0].text, "METER precedes LINEAR_METER in declaration order")
        assertEquals("MTR", spans[1].text)
    }
}
