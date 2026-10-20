package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.spi.party.SellerVatRegistrationRow
import de.littletrotter.spi.party.SellerVatRegistrationsExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * PO decision (2026-08) — new rows on [SellerVatRegistrationsSection] default to Luxembourg, and
 * picking a country prefills the VAT-id field with that country's own two-letter code, unless the
 * user has already typed real content into it. Mirrors [PartyExtensionsSectionTest]'s setup
 * pattern for testing a Vaadin component's behavior directly, without a full view.
 */
class SellerVatRegistrationsSectionTest {

    private class FakeExtension(
        private val rows: List<SellerVatRegistrationRow> = emptyList(),
    ) : SellerVatRegistrationsExtension {
        override val id: String = "fake"
        override val supportedCountries: List<String> = listOf("LU", "FR", "BE", "NL", "DK")
        var written: List<SellerVatRegistrationRow>? = null
        override fun read(senderProfile: Any): List<SellerVatRegistrationRow> = rows
        override fun write(senderProfile: Any, rows: List<SellerVatRegistrationRow>) {
            written = rows
        }
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun addButtonOf(section: SellerVatRegistrationsSection): Button =
        section.children.toList().filterIsInstance<Button>().single()

    /** Each row's country combo and vat-id field, in the same order as rendered — see
     *  [SellerVatRegistrationsSection.addRow]/`Row`. */
    private fun rowFields(section: SellerVatRegistrationsSection): List<Pair<ComboBox<String>, TextField>> =
        section.rows.map { it.country to it.vatId }

    @Test
    fun `clicking add defaults the new row's country to LU and prefills the vat-id field`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        assertEquals(true, section.hasContent, "a real extension must report content")

        addButtonOf(section).click()

        val (country, vatId) = rowFields(section).single()
        assertEquals("LU", country.value)
        assertEquals("LU", vatId.value)
    }

    @Test
    fun `changing the country updates the vat-id field while it still only holds the previous auto-prefix`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()
        assertEquals("LU", vatId.value)

        country._setValue("FR")

        assertEquals("FR", vatId.value)
    }

    @Test
    fun `a manually typed vat-id is not overwritten when the country changes afterwards`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()

        vatId._setValue("LU12345678")
        country._setValue("FR")

        assertEquals("LU12345678", vatId.value, "real, user-typed content must never be clobbered")
    }

    @Test
    fun `a programmatic (non-client) country change does not touch the vat-id field`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()
        assertEquals("LU", vatId.value)

        country.value = "FR" // plain server-side set — isFromClient = false

        assertEquals("LU", vatId.value, "programmatic changes must not trigger the auto-prefill")
    }

    @Test
    fun `readFrom with a row carrying neither country nor vat-id does not trigger the country-picked prefill`() {
        val extension = FakeExtension(rows = listOf(SellerVatRegistrationRow("", "")))
        val section = SellerVatRegistrationsSection(extension, { it })

        section.readFrom(Any())

        val (country, vatId) = rowFields(section).single()
        assertEquals(null, country.value)
        assertEquals("", vatId.value, "a blank stored row must stay blank, not get the LU-style country prefill")
    }

    @Test
    fun `readFrom shows the persisted country and vat-id, never overridden to the LU default`() {
        val extension = FakeExtension(rows = listOf(SellerVatRegistrationRow("FR", "FR12345678901")))
        val section = SellerVatRegistrationsSection(extension, { it })

        section.readFrom(Any())

        val (country, vatId) = rowFields(section).single()
        assertEquals("FR", country.value)
        assertEquals("FR12345678901", vatId.value)
    }

    // ─── foreign tax number next to the VAT id ───────────────────────────────────────────────

    private fun sectionWithRow(vararg rows: SellerVatRegistrationRow): SellerVatRegistrationsSection {
        val section = SellerVatRegistrationsSection(FakeExtension(rows = rows.toList()), { it })
        section.readFrom(Any())
        return section
    }

    @Test
    fun `each row shows a tax number field with position based ids`() {
        val section = sectionWithRow(
            SellerVatRegistrationRow("LU", "LU12345678", "1990010112345"),
            SellerVatRegistrationRow("FR", "FR12345678901"),
        )

        assertEquals(listOf("seller-vat-country-0", "seller-vat-country-1"), section.rows.map { it.country.id.get() })
        assertEquals(listOf("seller-vat-id-0", "seller-vat-id-1"), section.rows.map { it.vatId.id.get() })
        assertEquals(listOf("seller-vat-tax-number-0", "seller-vat-tax-number-1"), section.rows.map { it.taxNumber.id.get() })
        assertEquals("1990010112345", section.rows[0].taxNumber.value)
        assertEquals("", section.rows[1].taxNumber.value)
    }

    @Test
    fun `removing a row renumbers the remaining ids`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678"), SellerVatRegistrationRow("FR", "FR12345678901"))

        section.rows[0].bar.children.toList().filterIsInstance<Button>().single().click()

        assertEquals("seller-vat-tax-number-0", section.rows.single().taxNumber.id.get())
    }

    @Test
    fun `currentRows normalises the tax number and maps blank to none`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678"))
        val row = section.rows.single()

        row.taxNumber.value = "1990 0101-12345"
        assertEquals(listOf(SellerVatRegistrationRow("LU", "LU12345678", "1990010112345")), section.currentRows())

        row.taxNumber.value = "  "
        assertEquals(null, section.currentRows().single().taxNumber)
    }

    @Test
    fun `a row holding only a tax number is not dropped as blank`() {
        val section = sectionWithRow(SellerVatRegistrationRow("", "", "123"))

        assertEquals(1, section.currentRows().size)
    }

    @Test
    fun `an unrecognised format shows a warning but leaves the field valid`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678"))
        val taxNumber = section.rows.single().taxNumber

        taxNumber._setValue("12345")

        assertEquals("warning.foreignTaxNumber.format", taxNumber.helperText)
        assertEquals(false, taxNumber.isInvalid)
    }

    @Test
    fun `a recognised format and an empty value show no warning`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678"))
        val taxNumber = section.rows.single().taxNumber

        taxNumber._setValue("1990010112345")
        assertEquals("", taxNumber.helperText)

        taxNumber._setValue("")
        assertEquals("", taxNumber.helperText)
    }

    @Test
    fun `changing the country re-evaluates the warning`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678", "1990010112345"))
        val row = section.rows.single()
        assertEquals("", row.taxNumber.helperText)

        row.country._setValue("DK")

        assertEquals("warning.foreignTaxNumber.format", row.taxNumber.helperText)
    }

    @Test
    fun `leaving the field shows the normalised value`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678"))
        val taxNumber = section.rows.single().taxNumber
        taxNumber.value = "1990 0101 12345"

        com.vaadin.flow.component.ComponentUtil.fireEvent(
            taxNumber,
            com.vaadin.flow.component.BlurNotifier.BlurEvent(taxNumber, true),
        )

        assertEquals("1990010112345", taxNumber.value)
    }

    @Test
    fun `validatePairs rejects a tax number next to a blank or prefix-only VAT id and accepts the rest`() {
        val section = sectionWithRow(SellerVatRegistrationRow("LU", "LU12345678", "1990010112345"))
        val row = section.rows.single()
        assertEquals(true, section.validatePairs())
        assertEquals(false, row.taxNumber.isInvalid)

        row.vatId.value = "LU"
        assertEquals(false, section.validatePairs())
        assertEquals(true, row.taxNumber.isInvalid)
        assertEquals("validation.foreignTaxNumber.pairRequired", row.taxNumber.errorMessage)

        row.vatId.value = ""
        assertEquals(false, section.validatePairs())

        row.taxNumber.value = ""
        assertEquals(true, section.validatePairs())
        assertEquals(false, row.taxNumber.isInvalid)
    }

    // ─── extension == null: nothing renders, every mutating call is a no-op ───────────────────

    @Test
    fun `a null extension renders no rows-container or add button and reports no content`() {
        val section = SellerVatRegistrationsSection(null, { it })

        assertEquals(false, section.hasContent)
        assertEquals(0, section.children.count())
    }

    @Test
    fun `a null extension makes readFrom and writeTo safe no-ops`() {
        val section = SellerVatRegistrationsSection(null, { it })

        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            section.readFrom(Any())
            section.writeTo(Any())
        }
    }

    // ─── writeTo: blank rows are dropped, populated rows are kept ─────────────────────────────

    @Test
    fun `writeTo drops a row with neither a chosen country nor a typed vat-id`() {
        val extension = FakeExtension()
        val section = SellerVatRegistrationsSection(extension, { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()
        country._setValue(null)
        vatId._setValue("")

        section.writeTo(Any())

        assertEquals(emptyList<SellerVatRegistrationRow>(), extension.written)
    }

    @Test
    fun `writeTo keeps a row that has a country or a vat-id typed`() {
        val extension = FakeExtension()
        val section = SellerVatRegistrationsSection(extension, { it })
        addButtonOf(section).click() // defaults to LU / "LU"

        section.writeTo(Any())

        assertEquals(listOf(SellerVatRegistrationRow("LU", "LU")), extension.written)
    }

    @Test
    fun `writeTo keeps a row with a typed vat-id but no country chosen, blanking the country to empty`() {
        val extension = FakeExtension()
        val section = SellerVatRegistrationsSection(extension, { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()
        country._setValue(null)
        vatId._setValue("LU12345678")

        section.writeTo(Any())

        assertEquals(listOf(SellerVatRegistrationRow("", "LU12345678")), extension.written, "a chosen vat-id alone must keep the row, with an empty (not null) country")
    }

    // ─── row removal ────────────────────────────────────────────────────────────────────────

    @Test
    fun `clicking a row's remove button deletes that row and notifies listeners`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        addButtonOf(section).click()
        var notified = 0
        section.onChange { notified++ }
        val container = section.children.toList().filterIsInstance<VerticalLayout>().single()
        val bar = container.children.toList().single()
        val removeButton = bar.children.toList().filterIsInstance<Button>().single()

        removeButton.click()

        assertEquals(0, container.children.count(), "the row must be gone from the container")
        assertEquals(1, notified, "removal must fire the change listener")
    }

    // ─── currentCountries ──────────────────────────────────────────────────────────────────────

    @Test
    fun `currentCountries ignores rows with no country chosen and rows with an unrecognized code`() {
        val extension = FakeExtension()
        val section = SellerVatRegistrationsSection(extension, { it })
        addButtonOf(section).click() // LU — a recognized country
        addButtonOf(section).click()
        val secondCountry = rowFields(section)[1].first
        secondCountry._setValue(null) // no country chosen on this row

        val countries = section.currentCountries()

        assertEquals(setOf(de.littletrotter.shared.country.Country.LU), countries)
    }

    // ─── country prefill when the vat-id is already blank, without an old-value match ──────────

    @Test
    fun `a country change prefills the vat-id when it was cleared, even without matching the old value`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        addButtonOf(section).click()
        val (country, vatId) = rowFields(section).single()
        vatId._setValue("")

        country._setValue("FR")

        assertEquals("FR", vatId.value, "a blank vat-id is always prefilled, regardless of the old country value")
    }

    @Test
    fun `readRows loads the given registrations as rows and currentRows reads them back`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        val rows = listOf(SellerVatRegistrationRow("LU", "LU12345678"), SellerVatRegistrationRow("FR", "FR12345678901"))

        section.readRows(rows)

        assertEquals(rows, section.currentRows())
    }

    @Test
    fun `readRows replaces previously loaded rows`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        section.readRows(listOf(SellerVatRegistrationRow("LU", "LU12345678")))

        section.readRows(emptyList())

        assertEquals(emptyList<SellerVatRegistrationRow>(), section.currentRows())
    }

    @Test
    fun `readRows without a registered extension shows nothing`() {
        val section = SellerVatRegistrationsSection(null, { it })

        section.readRows(listOf(SellerVatRegistrationRow("LU", "LU12345678")))

        assertEquals(emptyList<SellerVatRegistrationRow>(), section.currentRows())
    }

    @Test
    fun `currentRows drops a blank add-then-abandon row`() {
        val section = SellerVatRegistrationsSection(FakeExtension(), { it })
        section.readRows(listOf(SellerVatRegistrationRow("LU", "LU12345678")))
        addButtonOf(section).click()
        rowFields(section).last().let { (country, vatId) -> country.clear(); vatId.value = "" }

        assertEquals(listOf(SellerVatRegistrationRow("LU", "LU12345678")), section.currentRows())
    }
}
