package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.github.mvysny.kaributesting.v10._findAll
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * [ArticleTaxVariantsSection]: the extension==null fork, [ArticleTaxVariantsSection.readFrom]'s
 * rate lookup, the country-change listener resetting the tax-rate field, and
 * [ArticleTaxVariantsSection.submittedRows]' two-stage mapNotNull (no rate chosen, or a chosen
 * rate with no id).
 */
class ArticleTaxVariantsSectionTest {

    @Test
    fun `foreign reference suggestions are deduplicated optional and reset on reopen`() {
        val extension: ArticleTaxVariantsExtension = mock()
        val lu = rate(9L, Country.LU).copy(isReference = true, category = "S")
        val section = ArticleTaxVariantsSection(extension, listOf(lu), tr)
        section.suggestCountries(listOf("DE", "LU", "LU", "FR"))
        val checkboxes = section.children.toList().filterIsInstance<VerticalLayout>()
            .flatMap { it.children.toList() }.filterIsInstance<com.vaadin.flow.component.checkbox.Checkbox>()
        assertEquals(1, checkboxes.size)
        assertTrue(checkboxes.single().value)
        assertEquals(listOf(ArticleTaxVariantDto(Country.LU, 9L)), section.submittedRows())
        checkboxes.single().value = false
        assertEquals(emptyList<ArticleTaxVariantDto>(), section.submittedRows())
        section.readFrom(emptyList())
        assertEquals(emptyList<ArticleTaxVariantDto>(), section.submittedRows())
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val tr: (String) -> String = { it }

    private fun rate(id: Long, country: Country) = TaxRateDto(
        id = id,
        country = country,
        rate = TaxRatePercent(BigDecimal("19.00")),
        name = "Test",
        isActive = true,
        isSelectable = true,
        category = "S",
    )

    private val deRate = rate(1L, Country.DE)
    private val frRate = rate(2L, Country.FR)

    private fun rowsOf(section: ArticleTaxVariantsSection): List<ArticleTaxVariantsSection.Row> = section.rows

    private fun countryCombo(row: ArticleTaxVariantsSection.Row): ComboBox<String> = row.country

    private fun taxRateCombo(row: ArticleTaxVariantsSection.Row): ComboBox<TaxRateDto> = row.taxRate

    private fun countryItems(row: ArticleTaxVariantsSection.Row): List<String> = row.country.dataProvider._findAll()

    private fun referenceRate(id: Long, country: Country) =
        rate(id, country).copy(isReference = true, category = "S")

    @Test
    fun `hasContent is false and nothing renders when there is no extension`() {
        val section = ArticleTaxVariantsSection(extension = null, allTaxRates = emptyList(), tr = tr)
        assertFalse(section.hasContent)
        assertTrue(section.children.toList().isEmpty())
    }

    @Test
    fun `submittedRows is null and readFrom shows nothing when there is no extension`() {
        val section = ArticleTaxVariantsSection(extension = null, allTaxRates = listOf(deRate), tr = tr)
        section.readFrom(listOf(ArticleTaxVariantDto(Country.DE, 1L)))
        assertTrue(rowsOf(section).isEmpty())
        assertNull(section.submittedRows(), "without the section the stored rows stay as they are")
    }

    @Test
    fun `readFrom resolves each row's tax rate by id from the supplied allTaxRates`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate, frRate), tr)
        assertTrue(section.hasContent, "a real extension must report content")

        section.readFrom(listOf(ArticleTaxVariantDto(Country.FR, 2L)))

        val row = rowsOf(section)[0]
        assertEquals("FR", countryCombo(row).value)
        assertEquals(frRate, taxRateCombo(row).value)
    }

    @Test
    fun `readFrom leaves the tax rate unset when the stored id no longer resolves`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate), tr)

        section.readFrom(listOf(ArticleTaxVariantDto(Country.DE, 999L)))

        assertEquals(null, taxRateCombo(rowsOf(section)[0]).value)
    }

    @Test
    fun `changing the country resets the tax rate field and re-scopes its items`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate, frRate), tr)
        section.children.toList().filterIsInstance<Button>().first().click()

        val row = rowsOf(section)[0]
        taxRateCombo(row).value = deRate
        countryCombo(row).value = "FR"

        assertEquals(null, taxRateCombo(row).value, "switching country must clear the now-mismatched tax rate")
    }

    @Test
    fun `submittedRows drops a row with no tax rate chosen at all`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate), tr)
        section.children.toList().filterIsInstance<Button>().first().click() // empty row

        assertEquals(emptyList<ArticleTaxVariantDto>(), section.submittedRows(), "a row with no chosen tax rate must be dropped")
    }

    @Test
    fun `submittedRows drops a row whose chosen rate has no id yet (unsaved)`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE"))
        val unsavedRate = rate(1L, Country.DE).copy(id = null)
        val section = ArticleTaxVariantsSection(extension, listOf(unsavedRate), tr)
        section.children.toList().filterIsInstance<Button>().first().click()
        val row = rowsOf(section)[0]
        taxRateCombo(row).value = unsavedRate

        assertEquals(
            emptyList<ArticleTaxVariantDto>(),
            section.submittedRows(),
            "a chosen rate with a null id must be dropped, not written with a null taxRateId",
        )
    }

    @Test
    fun `only active and selectable rates for the chosen country are offered`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE"))
        val inactive = rate(3L, Country.DE).copy(isActive = false)
        val notSelectable = rate(4L, Country.DE).copy(isSelectable = false)
        val section = ArticleTaxVariantsSection(extension, listOf(deRate, inactive, notSelectable, frRate), tr)
        section.children.toList().filterIsInstance<Button>().first().click()
        val row = rowsOf(section)[0]
        countryCombo(row).value = "DE"

        val items = taxRateCombo(row).dataProvider._findAll()

        assertEquals(listOf(deRate), items, "the inactive rate, the non-selectable rate, and the other country's rate must all be excluded")
    }

    @Test
    fun `submittedRows derives the country from the chosen rate, not the country field`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate, frRate), tr)
        section.children.toList().filterIsInstance<Button>().first().click()
        val row = rowsOf(section)[0]
        taxRateCombo(row).value = frRate

        assertEquals(
            listOf(ArticleTaxVariantDto(Country.FR, 2L)),
            section.submittedRows(),
            "country must come from the chosen TaxRateDto.country, not the (possibly stale) country combo",
        )
    }

    @Test
    fun `clicking remove removes exactly that row`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE"))
        val section = ArticleTaxVariantsSection(extension, listOf(deRate), tr)
        val addButton = section.children.toList().filterIsInstance<Button>().first()
        addButton.click()
        addButton.click()
        assertEquals(2, rowsOf(section).size)

        val removeButton = rowsOf(section)[0].bar.children.toList().filterIsInstance<Button>().first()
        removeButton.click()

        assertEquals(1, rowsOf(section).size)
    }

    @Test
    fun `suggestCountries shows a missing-reference hint for an ineligible rate or an unsaved one`() {
        val extension: ArticleTaxVariantsExtension = mock()
        val ineligibleFr = referenceRate(2L, Country.FR).copy(isActive = false) // country matches, not usable
        val unsavedLu = referenceRate(9L, Country.LU).copy(id = null) // usable, but no id yet
        val section = ArticleTaxVariantsSection(extension, listOf(ineligibleFr, unsavedLu), tr)

        section.suggestCountries(listOf("FR", "LU"))

        val texts = section.children.toList().filterIsInstance<VerticalLayout>()
            .flatMap { it.children.toList() }.filterIsInstance<Span>().map { it.text }
        assertTrue(texts.any { it == "FR: ${tr("serviceCatalog.taxVariant.missingReference")}" })
        assertTrue(texts.any { it == "LU: ${tr("serviceCatalog.taxVariant.missingReference")}" })
        assertTrue(section.children.toList().filterIsInstance<VerticalLayout>()
            .flatMap { it.children.toList() }.filterIsInstance<com.vaadin.flow.component.checkbox.Checkbox>().isEmpty())
    }

    // ── country combobox limited to countries with usable reference VAT data ──

    @Test
    fun `a country without any reference tax rate is absent from the combobox`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR"))
        val section = ArticleTaxVariantsSection(extension, listOf(referenceRate(2L, Country.FR)), tr)
        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals(listOf("FR"), countryItems(rowsOf(section)[0]), "DE has no reference tax rate at all and must be excluded")
    }

    @Test
    fun `adding a usable reference rate for a country later makes it selectable`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR"))
        val before = ArticleTaxVariantsSection(extension, emptyList(), tr)
        before.children.toList().filterIsInstance<Button>().first().click()
        assertTrue(countryItems(rowsOf(before)[0]).isEmpty(), "no reference rate on file yet for either country")

        val after = ArticleTaxVariantsSection(extension, listOf(referenceRate(2L, Country.FR)), tr)
        after.children.toList().filterIsInstance<Button>().first().click()
        assertEquals(listOf("FR"), countryItems(rowsOf(after)[0]))
    }

    @Test
    fun `a reference rate that is inactive or not selectable still excludes its country`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "FR", "LU"))
        val inactiveFr = referenceRate(2L, Country.FR).copy(isActive = false)
        val notSelectableLu = referenceRate(3L, Country.LU).copy(isSelectable = false)
        val section = ArticleTaxVariantsSection(extension, listOf(inactiveFr, notSelectableLu), tr)
        section.children.toList().filterIsInstance<Button>().first().click()

        assertTrue(
            countryItems(rowsOf(section)[0]).isEmpty(),
            "an inactive or non-selectable reference rate must not make its country selectable",
        )
    }

    // ── default seller's additional VAT-ID preselects its country on a new row ──

    @Test
    fun `add button preselects the single unused default-seller candidate country`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU"))
        val section = ArticleTaxVariantsSection(extension, listOf(referenceRate(9L, Country.LU)), tr)
        section.suggestCountries(listOf("LU"))

        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals("LU", countryCombo(rowsOf(section)[0]).value)
    }

    @Test
    fun `add button does not preselect a candidate country that already has a row`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU"))
        val section = ArticleTaxVariantsSection(extension, listOf(referenceRate(9L, Country.LU)), tr)
        section.suggestCountries(listOf("LU"))
        val addButton = section.children.toList().filterIsInstance<Button>().first()
        addButton.click() // first row: LU is the sole unused candidate, gets preselected

        addButton.click() // second row: LU is now already used by the first row

        assertEquals("LU", countryCombo(rowsOf(section)[0]).value)
        assertEquals(null, countryCombo(rowsOf(section)[1]).value, "LU already has a row, falls back to empty")
    }

    @Test
    fun `add button does not preselect and does not throw when there is no default seller`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU"))
        val section = ArticleTaxVariantsSection(extension, listOf(referenceRate(9L, Country.LU)), tr)
        // suggestCountries is never called -- mirrors "no default seller configured"

        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals(null, countryCombo(rowsOf(section)[0]).value)
    }

    @Test
    fun `add button does not preselect when more than one candidate country is unused`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU", "FR"))
        val section = ArticleTaxVariantsSection(
            extension,
            listOf(referenceRate(9L, Country.LU), referenceRate(2L, Country.FR)),
            tr,
        )
        section.suggestCountries(listOf("LU", "FR"))

        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals(null, countryCombo(rowsOf(section)[0]).value, "ambiguous candidates must not preselect either one")
    }

    @Test
    fun `a candidate without a usable reference rate is never preselected even if unused`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU"))
        val section = ArticleTaxVariantsSection(extension, emptyList(), tr) // LU has no reference rate on file
        section.suggestCountries(listOf("LU"))

        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals(null, countryCombo(rowsOf(section)[0]).value)
    }

    @Test
    fun `readFrom clears stale candidate countries from a previous suggestCountries call`() {
        val extension: ArticleTaxVariantsExtension = mock()
        whenever(extension.supportedCountries).thenReturn(listOf("DE", "LU"))
        val section = ArticleTaxVariantsSection(extension, listOf(referenceRate(9L, Country.LU)), tr)
        section.suggestCountries(listOf("LU"))

        section.readFrom(emptyList())
        section.children.toList().filterIsInstance<Button>().first().click()

        assertEquals(
            null,
            countryCombo(rowsOf(section)[0]).value,
            "a stale candidate list from a previous session must not leak into a fresh readFrom",
        )
    }
}
