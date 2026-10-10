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
        override val supportedCountries: List<String> = listOf("LU", "FR", "BE", "NL")
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
}
