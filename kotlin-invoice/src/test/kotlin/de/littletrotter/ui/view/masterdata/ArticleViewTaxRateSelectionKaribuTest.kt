package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.domain.value.TaxRatePercent
import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.ui.view.referencedata.ArticleFormData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional
import java.math.BigDecimal
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.test.FIXED_TODAY

/**
 * [ArticleView.readFormData]'s tax-rate preselection chain
 * (`defaultTaxRateId -> match` / `id -> stored defaultTaxRateId` / `else -> null`, each falling
 * back through `isDefault` then the hard-coded 19% rate) was entirely untested: every existing
 * [ArticleViewKaribuTest]/[ArticleViewCoverageKaribuTest] fixture stubs `findAllTaxRates()` to
 * `emptyList()`, so every `.find { }` in the chain short-circuited to `null` before its predicate
 * ever ran. These drive the same method with a real, non-empty tax-rate list so every branch of
 * the `when` and both fallback `?:` steps actually executes. Also covers the matching fallback in
 * [ArticleView.persistForm] and the STANDARD-only filter [ArticleView.configureDialog] applies to
 * the combo's own item list, which the same empty-list gap left untested.
 */
class ArticleViewTaxRateSelectionKaribuTest {

    private val articleQueries: ArticleQueryPort = mock()

    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val articleTaxVariantsRegistry: ArticleTaxVariantsExtensionRegistry = mock()
    private val articleRecipientRefsRegistry: ArticleRecipientRefsExtensionRegistry = mock()
    private val sellerProfileService: SellerProfileService = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun taxRate(id: Long, rate: String, isDefault: Boolean = false, category: String = "S"): TaxRateDto =
        TaxRateDto(
            id = id,
            rate = TaxRatePercent(BigDecimal(rate)),
            isDefault = isDefault,
            category = category,
            name = "TR-$id",
        )

    private fun newView(allTaxRates: List<TaxRateDto>): ArticleView {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(allTaxRates)
        whenever(articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(articleQueries.countArticles(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0L)
        return ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, sellerProfileService, time = FIXED_TIME_SOURCE)
    }

    private fun readFormData(view: ArticleView, formData: ArticleFormData) {
        val m = ArticleView::class.java.getDeclaredMethod("readFormData", ArticleFormData::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    private fun taxRateCombo(view: ArticleView): ComboBox<TaxRateDto> = view.taxRateCombo

    /** Saves [formData] as a new article through the view and returns the draft it sent. */
    private fun savedDraft(view: ArticleView, formData: ArticleFormData): ArticleDraft {
        val m = ArticleView::class.java.getDeclaredMethod("persistForm", ArticleFormData::class.java, ArticleDto::class.java)
        m.isAccessible = true
        m.invoke(view, formData, null)
        return org.mockito.kotlin.argumentCaptor<ArticleDraft>().also { org.mockito.kotlin.verify(manageArticle).saveArticle(it.capture()) }.firstValue
    }

    // ── when-branch 1: formData.defaultTaxRateId != null ────────────────────────

    @Test
    fun `a matching defaultTaxRateId preselects that exact tax rate`() {
        val wanted = taxRate(id = 3L, rate = "7.00")
        val other = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val view = newView(listOf(other, wanted))

        readFormData(view, ArticleFormData(defaultTaxRateId = 3L))

        assertEquals(wanted, taxRateCombo(view).value)
    }

    @Test
    fun `a defaultTaxRateId with no matching rate falls back to the isDefault rate`() {
        val defaultRate = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val view = newView(listOf(defaultRate))

        readFormData(view, ArticleFormData(defaultTaxRateId = 999L))

        assertEquals(defaultRate, taxRateCombo(view).value)
    }

    // ── when-branch 2: formData.id != null (defaultTaxRateId null) ──────────────

    @Test
    fun `an existing article with no defaultTaxRateId preselects its own entity's tax rate`() {
        val entityRate = taxRate(id = 2L, rate = "7.00")
        val existing = ArticleDto(id = 5L, name = "Bestand", defaultTaxRateId = entityRate.id)
        whenever(articleQueries.findArticleById(5L)).thenReturn(existing)
        val view = newView(listOf(entityRate, taxRate(id = 1L, rate = "19.00", isDefault = true)))

        readFormData(view, ArticleFormData(id = 5L))

        assertEquals(entityRate, taxRateCombo(view).value)
    }

    @Test
    fun `an existing article whose entity has no tax rate falls back to the hard-coded 19 percent rate`() {
        val existing = ArticleDto(id = 5L, name = "Bestand", defaultTaxRateId = null)
        whenever(articleQueries.findArticleById(5L)).thenReturn(existing)
        val nineteen = taxRate(id = 4L, rate = "19", isDefault = false)
        val view = newView(listOf(nineteen))

        readFormData(view, ArticleFormData(id = 5L))

        assertEquals(nineteen, taxRateCombo(view).value)
    }

    // ── when-branch 3: else -> null (new article, no id, no defaultTaxRateId) ──

    @Test
    fun `a brand-new article with no id preselects the isDefault rate`() {
        val defaultRate = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val view = newView(listOf(defaultRate, taxRate(id = 2L, rate = "7.00")))

        readFormData(view, ArticleFormData())

        assertEquals(defaultRate, taxRateCombo(view).value)
    }

    @Test
    fun `with no isDefault rate at all a brand-new article falls back to the plain 19 percent rate`() {
        val nineteen = taxRate(id = 4L, rate = "19", isDefault = false)
        val seven = taxRate(id = 2L, rate = "7.00", isDefault = false)
        val view = newView(listOf(seven, nineteen))

        readFormData(view, ArticleFormData())

        assertEquals(nineteen, taxRateCombo(view).value)
    }

    @Test
    fun `with neither an isDefault nor a 19 percent rate available the combo stays unselected`() {
        val seven = taxRate(id = 2L, rate = "7.00", isDefault = false)
        val view = newView(listOf(seven))

        readFormData(view, ArticleFormData())

        assertNull(taxRateCombo(view).value)
    }

    @Test
    fun `a new article retains DE when the default seller also has LU VAT`() {
        val seller = SenderProfile().apply { isDefault = true }
        seller.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU26375245"))), today = FIXED_TODAY)
        val germanDefault = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val luxembourgReference = taxRate(id = 2L, rate = "17.00").copy(country = Country.LU, isReference = true, isActive = true, isSelectable = true)
        whenever(sellerProfileService.getDefaultSeller()).thenReturn(Optional.of(seller))
        val view = newView(listOf(germanDefault, luxembourgReference))

        readFormData(view, ArticleFormData())

        assertEquals(germanDefault, taxRateCombo(view).value)
    }

    @Test
    fun `a new article keeps the normal default when the additional VAT country has no reference rate`() {
        val seller = SenderProfile().apply { isDefault = true }
        seller.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU26375245"))), today = FIXED_TODAY)
        val germanDefault = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val luxembourgRate = taxRate(id = 2L, rate = "17.00").copy(country = Country.LU)
        whenever(sellerProfileService.getDefaultSeller()).thenReturn(Optional.of(seller))
        val view = newView(listOf(germanDefault, luxembourgRate))

        readFormData(view, ArticleFormData())

        assertEquals(germanDefault, taxRateCombo(view).value)
    }

    @Test
    fun `saving a new article retains the German base rate despite LU registration`() {
        val seller = SenderProfile().apply { isDefault = true }
        seller.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU26375245"))), today = FIXED_TODAY)
        val germanDefault = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val luxembourgReference = taxRate(id = 2L, rate = "17.00").copy(country = Country.LU, isReference = true, isActive = true, isSelectable = true)
        whenever(sellerProfileService.getDefaultSeller()).thenReturn(Optional.of(seller))
        whenever(articleTaxVariantsRegistry.enabledExtension()).thenReturn(
            de.littletrotter.application.masterdata.ArticleTaxVariantsModule(
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
            ),
        )
        val view = newView(listOf(germanDefault, luxembourgReference))

        readFormData(view, ArticleFormData())
        val draft = savedDraft(view, ArticleFormData(name = "Luxembourg service"))

        assertEquals(germanDefault.id, draft.defaultTaxRateId)
        assertEquals(Country.LU, draft.taxVariants!!.single().country)
        assertEquals(luxembourgReference.id, draft.taxVariants!!.single().taxRateId)
    }

    // ── the save's own fallback: only reached when the combo itself is unset ──

    @Test
    fun `saving falls back to the isDefault rate when the combo was left unset`() {
        val defaultRate = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val view = newView(listOf(defaultRate))

        val draft = savedDraft(view, ArticleFormData(name = "Widget"))

        assertEquals(defaultRate.id, draft.defaultTaxRateId)
    }

    @Test
    fun `saving sends the combo's own selection when one was made`() {
        val defaultRate = taxRate(id = 1L, rate = "19.00", isDefault = true)
        val chosen = taxRate(id = 2L, rate = "7.00")
        val view = newView(listOf(defaultRate, chosen))
        taxRateCombo(view).value = chosen

        val draft = savedDraft(view, ArticleFormData(name = "Widget"))

        assertEquals(chosen.id, draft.defaultTaxRateId)
    }

    // ── defaultTaxRateForNewArticle's own fallback chain and candidate filter ───────────────

    @Test
    fun `boundary - an isReference German rate is preferred over isDefault and the plain 19 percent rate`() {
        val reference = taxRate(id = 1L, rate = "16.00").copy(isReference = true)
        val default = taxRate(id = 2L, rate = "19.00", isDefault = true)
        val nineteen = taxRate(id = 3L, rate = "19")
        val view = newView(listOf(default, nineteen, reference))

        readFormData(view, ArticleFormData())

        assertEquals(reference, taxRateCombo(view).value)
    }

    @Test
    fun `boundary - an inactive, non-selectable, hotel or non-standard rate never enters the German candidate pool`() {
        val inactive = taxRate(id = 1L, rate = "19.00", isDefault = true).copy(isActive = false)
        val nonSelectable = taxRate(id = 2L, rate = "19.00", isDefault = true).copy(isSelectable = false)
        val hotel = taxRate(id = 3L, rate = "19.00", isDefault = true).copy(isHotelRate = true)
        val nonStandard = taxRate(id = 4L, rate = "19.00", isDefault = true, category = "AE")
        val valid = taxRate(id = 5L, rate = "19.00", isDefault = true)
        val view = newView(listOf(inactive, nonSelectable, hotel, nonStandard, valid))

        readFormData(view, ArticleFormData())

        assertEquals(valid, taxRateCombo(view).value)
    }

    @Test
    fun `boundary - an existing article's non-DE entity tax rate is rejected and falls back to the default candidate pool`() {
        val luRate = taxRate(id = 1L, rate = "17.00").copy(country = Country.LU)
        val existing = ArticleDto(id = 5L, name = "Bestand", defaultTaxRateId = luRate.id)
        whenever(articleQueries.findArticleById(5L)).thenReturn(existing)
        val germanFallback = taxRate(id = 2L, rate = "19.00", isDefault = true)
        val view = newView(listOf(luRate, germanFallback))

        readFormData(view, ArticleFormData(id = 5L))

        assertEquals(germanFallback, taxRateCombo(view).value, "a non-DE entity rate must not be preselected")
    }

    // ── priceField <-> Money converter's null branches ──────────────────────────────────────

    private fun priceField(view: ArticleView): com.vaadin.flow.component.textfield.BigDecimalField {
        val f = ArticleView::class.java.getDeclaredField("priceField")
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.textfield.BigDecimalField
    }

    @Test
    fun `boundary - clearing the price field saves Money ZERO instead of a null amount`() {
        val view = newView(emptyList())
        readFormData(view, ArticleFormData())
        priceField(view).value = null

        val draft = savedDraft(view, ArticleFormData(name = "Widget", defaultUnitPriceNet = null))

        assertEquals(de.littletrotter.shared.Money.ZERO, draft.defaultUnitPriceNet)
    }

    @Test
    fun `boundary - a form data with a null price shows as BigDecimal ZERO in the price field`() {
        val view = newView(emptyList())

        readFormData(view, ArticleFormData(defaultUnitPriceNet = null))

        assertEquals(BigDecimal.ZERO, priceField(view).value)
    }

    // ── the combo's own item list: STANDARD-only filter (configureDialog, line 320) ────────────

    @Test
    fun `the tax rate combo only offers STANDARD-category rates, never a reverse-charge or exempt one`() {
        val standard = taxRate(id = 1L, rate = "19.00", category = "S")
        val reverseCharge = taxRate(id = 2L, rate = "0.00", category = "AE")
        val exempt = taxRate(id = 3L, rate = "0.00", category = "E")
        val view = newView(listOf(standard, reverseCharge, exempt))

        val items = taxRateCombo(view).listDataView.items.toList()

        assertEquals(listOf(standard), items)
    }

    @Test
    fun `boundary - the tax rate combo excludes a STANDARD-category rate from a non-DE country`() {
        val standard = taxRate(id = 1L, rate = "19.00", category = "S")
        val luStandard = taxRate(id = 2L, rate = "17.00", category = "S").copy(country = Country.LU)
        val view = newView(listOf(standard, luStandard))

        val items = taxRateCombo(view).listDataView.items.toList()

        assertEquals(listOf(standard), items, "a STANDARD-category rate outside Germany must not appear in the combo")
    }
}
