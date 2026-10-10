package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import de.littletrotter.application.invoice.ItemEntryQueries
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * BT-156 (Artikelnummer des Käufers) moved out of the
 * profile-gated B2B section into a plain, always-visible item field (PO decision 2026-08-25).
 * Covers the two halves of that split: [buildB2bItemSection] must never render BT-156 even when a
 * profile lists it, and the new [buildBuyerItemNumberField]/[persistBuyerItemNumber] pair must
 * read/write it independently of any profile.
 *
 * PO 2026-08-25 (product-position B2B fields) — both dialog helpers now take the pre-resolved
 * [findB2bItemFieldsFor] list instead of each re-resolving the profile itself; the tests below
 * pin that BT-156 still surfaces exactly once (in [buildBuyerItemNumberField], never in
 * [buildB2bItemSection]) and that a MANDATORY profile requirement carries through as the
 * field's required-indicator.
 *
 * [buildB2bItemSection] reads each field's [B2bProfileFieldDto.term] directly; no more
 * [B2bFieldDefinitionQueryPort.findB2bCatalog] stubbing needed.
 */
class ItemDialogB2bSupportTest {

    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    // A real delegating port over the facade mocks above, for the section/tax-rate helpers.
    private val b2bItemPort: ItemEntryQueryPort = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, mock(), taxRateQueries)
    private val itemEntryQueryPort: ItemEntryQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun profileField(term: B2bTerm, requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL) = B2bProfileFieldDto(
        term = term,
        placement = B2bPlacement.INVOICE,
        businessDefinition = "Definition of ${term.officialCode}",
        requirement = requirement,
    )

    @Test
    fun `findB2bItemFieldsFor resolves the invoice's profile and its LINE fields in one shared lookup`() {
        whenever(itemEntryQueryPort.findB2bProfileIdForInvoice(42L)).thenReturn(1L)
        whenever(itemEntryQueryPort.findB2bInvoiceItemFields(1L)).thenReturn(listOf(profileField(B2bTerm.BT_155), profileField(B2bTerm.BT_156)))

        val fields = findB2bItemFieldsFor(itemEntryQueryPort, 42L)

        assertEquals(setOf(B2bTerm.BT_155, B2bTerm.BT_156), fields.map { it.term }.toSet())
    }

    @Test
    fun `findB2bItemFieldsFor is empty when the invoice carries no B2B profile`() {
        whenever(itemEntryQueryPort.findB2bProfileIdForInvoice(42L)).thenReturn(null)
        whenever(itemEntryQueryPort.findB2bInvoiceItemFields(null)).thenReturn(emptyList())

        val fields = findB2bItemFieldsFor(itemEntryQueryPort, 42L)

        assertTrue(fields.isEmpty())
    }

    @Test
    fun `buildB2bItemSection never renders BT-156, even when the invoice's profile lists it`() {
        val fields = listOf(profileField(B2bTerm.BT_155), profileField(B2bTerm.BT_156))
        whenever(b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(emptyMap())

        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)

        assertNull(section?.currentValues()?.get("BT-156"), "BT-156 must not be a widget in the profile-gated section")
        assertEquals(setOf("BT-155"), section?.currentValues()?.keys)
    }

    @Test
    fun `buildB2bItemSection returns null when the resolved field list is empty, same as before`() {
        val section = b2bItemPort.buildB2bItemSection({ it }, emptyList(), null)

        assertNull(section, "no profile fields -> no gated section, unchanged from before the BT-156 split")
    }

    @Test
    fun `buildBuyerItemNumberField loads the existing BT-156 value regardless of any B2B profile`() {
        whenever(itemEntryQueryPort.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(mapOf("BT-156" to "BUYER-0815"))

        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), 99L)

        assertEquals("BUYER-0815", field.value)
    }

    @Test
    fun `buildBuyerItemNumberField starts empty for a new item`() {
        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), null)

        assertEquals("", field.value)
    }

    @Test
    fun `buildBuyerItemNumberField shows the required indicator when the profile marks BT-156 MANDATORY`() {
        val fields = listOf(profileField(B2bTerm.BT_156, B2bFieldRequirement.MANDATORY))

        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, fields, null)

        assertTrue(field.isRequiredIndicatorVisible)
    }

    @Test
    fun `buildBuyerItemNumberField has no required indicator when the profile leaves BT-156 optional`() {
        val fields = listOf(profileField(B2bTerm.BT_156, B2bFieldRequirement.OPTIONAL))

        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, fields, null)

        assertFalse(field.isRequiredIndicatorVisible)
    }

    @Test
    fun `buildBuyerItemNumberField has no required indicator when no profile defines BT-156 at all`() {
        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), null)

        assertFalse(field.isRequiredIndicatorVisible)
    }

    @Test
    fun `BT-156 surfaces exactly once across the section and the buyer item number field for the same profile fields`() {
        // Dedup pin: the profile-gated section and the always-visible field are built from the
        // exact same resolved list (as GoodsItemDialog/ServiceItemDialog now do) — BT-156 must
        // end up in exactly one of the two, never both.
        val fields = listOf(profileField(B2bTerm.BT_155), profileField(B2bTerm.BT_156, B2bFieldRequirement.MANDATORY))
        whenever(b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(mapOf("BT-156" to "BUYER-0815"))
        whenever(itemEntryQueryPort.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(mapOf("BT-156" to "BUYER-0815"))

        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)
        val buyerItemNumberField = buildBuyerItemNumberField(itemEntryQueryPort, { it }, fields, 99L)

        assertFalse(section?.currentValues()?.containsKey("BT-156") ?: false, "section must not carry BT-156")
        assertEquals("BUYER-0815", buyerItemNumberField.value, "the plain field is the sole BT-156 input")
        assertTrue(buyerItemNumberField.isRequiredIndicatorVisible, "MANDATORY still surfaces via the plain field's required indicator")
    }

    @Test
    fun `persistBuyerItemNumber writes the trimmed value under BT-156`() {
        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), null)
        field.value = "  BUYER-42  "

        persistBuyerItemNumber(itemEntryQueryPort, 99L, field)

        verify(itemEntryQueryPort).saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L, mapOf("BT-156" to "BUYER-42"))
    }

    @Test
    fun `persistBuyerItemNumber sends null for a blank value, so saveFieldValues deletes the row`() {
        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), null)
        field.value = "   "

        persistBuyerItemNumber(itemEntryQueryPort, 99L, field)

        verify(itemEntryQueryPort).saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L, mapOf("BT-156" to null))
    }

    @Test
    fun `persistBuyerItemNumber is a no-op before the item has an id`() {
        val field = buildBuyerItemNumberField(itemEntryQueryPort, { it }, emptyList(), null)
        field.value = "BUYER-42"

        persistBuyerItemNumber(itemEntryQueryPort, null, field)

        verify(itemEntryQueryPort, never()).saveB2bFieldValues(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    // ── taxRatesForSellerCountry ────────────────────────────────────────────────────────────

    private fun taxRate(country: Country, active: Boolean = true, selectable: Boolean = true) = TaxRateDto(
        country = country,
        isActive = active,
        isSelectable = selectable,
    )

    @Test
    fun `taxRatesForSellerCountry keeps only active, selectable rates for the given country`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(
            listOf(
                taxRate(Country.LU),
                taxRate(Country.DE),
                taxRate(Country.LU, active = false),
                taxRate(Country.LU, selectable = false),
            ),
        )

        val rates = taxRatesForSellerCountry(b2bItemPort, Country.LU)

        assertEquals(1, rates.size, "nur die aktive, waehlbare LU-Rate bleibt uebrig")
        assertEquals(Country.LU, rates.single().country)
    }

    @Test
    fun `taxRatesForSellerCountry is empty when nothing matches the country`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(taxRate(Country.DE)))

        assertTrue(taxRatesForSellerCountry(b2bItemPort, Country.LU).isEmpty())
    }

    // ── copyB2bTemplateFor ───────────────────────────────────────────────────────────────────

    @Test
    fun `copyB2bTemplateFor copies the template when both article and item are set`() {
        val article = ArticleDto(id = 5L)

        copyB2bTemplateFor(itemEntryQueryPort, invoiceId = 10L, article = article, itemId = 20L)

        verify(itemEntryQueryPort).copyB2bArticleTemplateToInvoiceItem(5L, 20L, 10L)
    }

    @Test
    fun `copyB2bTemplateFor is a no-op for a free-text position with no article`() {
        copyB2bTemplateFor(itemEntryQueryPort, invoiceId = 10L, article = null, itemId = 20L)

        verify(itemEntryQueryPort, never()).copyB2bArticleTemplateToInvoiceItem(any(), any(), any())
    }

    @Test
    fun `copyB2bTemplateFor is a no-op before the item has an id`() {
        val article = ArticleDto(id = 5L)

        copyB2bTemplateFor(itemEntryQueryPort, invoiceId = 10L, article = article, itemId = null)

        verify(itemEntryQueryPort, never()).copyB2bArticleTemplateToInvoiceItem(any(), any(), any())
    }

    // ── prefillIfBlank (plain TextField overload) ───────────────────────────────────────────

    @Test
    fun `prefillIfBlank sets a blank field to the given value`() {
        val field = com.vaadin.flow.component.textfield.TextField()

        prefillIfBlank(field, "prefilled")

        assertEquals("prefilled", field.value)
    }

    @Test
    fun `prefillIfBlank never overwrites a field that already carries a value`() {
        val field = com.vaadin.flow.component.textfield.TextField().apply { value = "typed-by-user" }

        prefillIfBlank(field, "prefilled")

        assertEquals("typed-by-user", field.value)
    }

    @Test
    fun `prefillIfBlank does nothing when the candidate value itself is blank`() {
        val field = com.vaadin.flow.component.textfield.TextField()

        prefillIfBlank(field, "   ")

        assertEquals("", field.value)
    }

    @Test
    fun `prefillIfBlank does nothing when the candidate value is null`() {
        val field = com.vaadin.flow.component.textfield.TextField()

        prefillIfBlank(field, null)

        assertEquals("", field.value)
    }

    // ── persistB2bItemValues ─────────────────────────────────────────────────────────────────

    private fun lineField(term: B2bTerm) = B2bProfileFieldDto(
        term = term,
        placement = B2bPlacement.INVOICE,
        businessDefinition = "Definition of ${term.officialCode}",
    )

    @Test
    fun `persistB2bItemValues is a no-op before the item has an id`() {
        val fields = listOf(lineField(B2bTerm.BT_155))
        whenever(b2bFieldValueQueries.findB2bFieldValues(eq(B2bFieldOwnerType.INVOICE_ITEM), any())).thenReturn(mapOf("BT-155" to "S-1"))
        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)

        persistB2bItemValues(itemEntryQueryPort, itemId = null, section = section, onlyNonBlank = false)

        verify(itemEntryQueryPort, never()).saveB2bFieldValues(any(), any(), any())
    }

    @Test
    fun `persistB2bItemValues is a no-op when the section itself is null`() {
        persistB2bItemValues(itemEntryQueryPort, itemId = 99L, section = null, onlyNonBlank = false)

        verify(itemEntryQueryPort, never()).saveB2bFieldValues(any(), any(), any())
    }

    @Test
    fun `persistB2bItemValues writes a blank value too when onlyNonBlank is false`() {
        // BT-155/BT-156 are the catalog's only two LINE-level FREE terms, and buildB2bItemSection
        // always excludes BT-156 (moved to buildBuyerItemNumberField) — BT-155 alone still
        // exercises the "no filtering at all" branch: a blank widget (no stored value) still ends
        // up in the saved map, as null (B2bFieldWidgetFactory.widgetValue normalizes blank -> null).
        val fields = listOf(lineField(B2bTerm.BT_155))
        whenever(b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(emptyMap())
        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)

        persistB2bItemValues(itemEntryQueryPort, itemId = 99L, section = section, onlyNonBlank = false)

        verify(itemEntryQueryPort).saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L, mapOf("BT-155" to null))
    }

    @Test
    fun `persistB2bItemValues writes a non-blank value through when onlyNonBlank is true`() {
        val fields = listOf(lineField(B2bTerm.BT_155))
        whenever(b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(mapOf("BT-155" to "S-1"))
        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)

        persistB2bItemValues(itemEntryQueryPort, itemId = 99L, section = section, onlyNonBlank = true)

        verify(itemEntryQueryPort).saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L, mapOf("BT-155" to "S-1"))
    }

    @Test
    fun `persistB2bItemValues never calls save when there is nothing to write at all`() {
        val fields = listOf(lineField(B2bTerm.BT_155))
        whenever(b2bFieldValueQueries.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 99L)).thenReturn(emptyMap())
        val section = b2bItemPort.buildB2bItemSection({ it }, fields, 99L)

        persistB2bItemValues(itemEntryQueryPort, itemId = 99L, section = section, onlyNonBlank = true)

        verify(itemEntryQueryPort, never()).saveB2bFieldValues(any(), any(), any())
    }

    // ── buildBuyerItemDescriptionField ───────────────────────────────────────

    @Test
    fun `buildBuyerItemDescriptionField creates a text field with neutral wording`() {
        val field = buildBuyerItemDescriptionField({ it })

        assertEquals("invoiceDetail.itemDialog.buyerItemDescription", field.label)
        assertEquals("invoiceDetail.itemDialog.buyerItemDescription.helper", field.helperText)
    }

    @Test
    fun `buildBuyerItemDescriptionField starts empty for a new item`() {
        val field = buildBuyerItemDescriptionField({ it })

        assertEquals("", field.value)
    }

    @Test
    fun `buildBuyerItemDescriptionField enforces 50-char maxLength like buyerItemNumberField`() {
        val field = buildBuyerItemDescriptionField({ it })

        assertEquals(50, field.maxLength)
    }

    @Test
    fun `prefillIfBlank sets a blank description field to the given value`() {
        val field = com.vaadin.flow.component.textfield.TextField()

        prefillIfBlank(field, "prefilled-description")

        assertEquals("prefilled-description", field.value)
    }

    @Test
    fun `prefillIfBlank never overwrites a description field that already carries a value`() {
        val field = com.vaadin.flow.component.textfield.TextField().apply { value = "user-entered-value" }

        prefillIfBlank(field, "prefilled-description")

        assertEquals("user-entered-value", field.value)
    }
}
