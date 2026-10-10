package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

/**
 * [ServiceItemDialog]'s own branches beyond what [GoodsItemDialogKaribuTest] already
 * covers for the shared [BaseItemDialog] scaffold: the flat-rate quantity override (both in
 * [ServiceItemDialog.collectItemCommand]/[ServiceItemDialog.collectQuantityAndPrice] and in
 * [ServiceItemDialog.loadExistingItemIntoFields]'s `when` over the persisted unit), the
 * hour/day/flat label switch, and the qty-field visibility toggle on flat-rate selection.
 */
class ServiceItemDialogKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val articleQueries: ItemEntryQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        // The dialogs read tax rates through the item-entry port; route it to the tax-rate mock
        // every test stubs. An explicit stub on the port itself still wins.
        whenever(articleQueries.findAllTaxRates()).thenAnswer { taxRateQueries.findAllTaxRates() }
        whenever(articleQueries.findReferenceTaxRate(any(), any())).thenAnswer {
            taxRateQueries.findReferenceTaxRate(it.getArgument(0), it.getArgument(1))
        }
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val de19 = TaxRateDto(
        id = 1L,
        code = "DE_STANDARD_19",
        category = "S",
        rate = TaxRatePercent(BigDecimal("19.00")),
        name = "Standard",
        isActive = true,
        isSelectable = true,
    )

    private fun invoice() = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun existingServiceItem(unit: InvoiceItemUnit, quantity: BigDecimal = BigDecimal("3")) = ItemDto(
        id = 5L, positionId = "1", description = "Beratung", quantity = quantity,
        unit = unit.name, unitPriceNet = Money(BigDecimal("100.00")),
        taxRate = null, taxRateRefId = de19.id,
        netAmount = null, vatAmount = null, grossAmount = null, category = InvoiceItemCategory.SERVICE.name,
    )

    private fun dialog(): Dialog = _get<Dialog>()

    private fun formLayout(dialog: Dialog): com.vaadin.flow.component.formlayout.FormLayout =
        dialog.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()

    private fun unitGroup(dialog: Dialog): RadioButtonGroup<String> =
        formLayout(dialog).children.toList().filterIsInstance<RadioButtonGroup<String>>().first()

    private fun descField(dialog: Dialog): TextArea =
        formLayout(dialog).children.toList().filterIsInstance<TextArea>()[0]

    private fun qtyField(dialog: Dialog): NumberField =
        formLayout(dialog).children.toList().filterIsInstance<NumberField>()[0]

    private fun priceField(dialog: Dialog): NumberField =
        formLayout(dialog).children.toList().filterIsInstance<NumberField>()[1]

    private fun saveButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("app.save") }

    private fun open(existingItem: ItemDto? = null): Dialog {
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            existingItem = existingItem,
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        return dialog()
    }

    // ── flat-rate quantity override ────────────────────────────────────────────────────────────

    @Test
    fun `selecting the flat unit hides the quantity field and forces quantity to exactly 1`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        val group = unitGroup(dlg)
        group.value = InvoiceUnitLabels.label(null, InvoiceItemUnit.FLAT_RATE)
        assertFalse(qtyField(dlg).isVisible, "qty is meaningless for a flat rate and must be hidden")

        descField(dlg).value = "Pauschale"
        saveButton(dlg).click()
        assertEquals(BigDecimal.ONE, captured?.quantity, "collectItemCommand must force quantity to 1 for a flat rate regardless of the hidden field's stale value")
    }

    @Test
    fun `selecting an hourly unit keeps the quantity field visible and uses its own value`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        assertTrue(qtyField(dlg).isVisible, "hour is the default selection — qty must start visible")
        qtyField(dlg).value = 5.0
        descField(dlg).value = "Beratung"
        saveButton(dlg).click()

        assertEquals(BigDecimal.valueOf(5.0), captured?.quantity)
    }

    @Test
    fun `a filled itemDescription is trimmed and carried into the saved command`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Beratung"
        formLayout(dlg).children.toList().filterIsInstance<TextArea>()[1].value = "  Zusatzinfo  "
        saveButton(dlg).click()

        assertEquals("Zusatzinfo", captured?.itemDescription)
    }

    @Test
    fun `a blank itemDescription is saved as null, not an empty string`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Beratung"
        saveButton(dlg).click()

        assertEquals(null, captured?.itemDescription)
    }

    @Test
    fun `the price field label switches with the selected unit`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open()
        val group = unitGroup(dlg)
        val price = priceField(dlg)
        val hourLabel = UiTranslations.tr("invoiceDetail.service.rateLabel.hour")
        val dayLabel = UiTranslations.tr("invoiceDetail.service.rateLabel.day")

        assertEquals(hourLabel, price.label)
        group.value = InvoiceUnitLabels.label(null, InvoiceItemUnit.DAY)
        assertEquals(dayLabel, price.label)
    }

    // ── loadExistingItemIntoFields' unit `when` (HOUR/DAY/FLAT_RATE/else) ──────────────────────

    @Test
    fun `an existing DAY item preselects the day radio option`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.DAY))
        assertEquals(InvoiceUnitLabels.label(null, InvoiceItemUnit.DAY), unitGroup(dlg).value)
    }

    @Test
    fun `an existing FLAT_RATE item preselects the flat radio option`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.FLAT_RATE))
        assertEquals(InvoiceUnitLabels.label(null, InvoiceItemUnit.FLAT_RATE), unitGroup(dlg).value)
    }

    @Test
    fun `an existing FLAT_RATE item shows the flat price label and hides the quantity`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.FLAT_RATE))
        assertEquals(UiTranslations.tr("invoiceDetail.service.rateLabel.flat"), priceField(dlg).label)
        assertFalse(qtyField(dlg).isVisible)
    }

    @Test
    fun `an existing DAY item shows the day price label and keeps the quantity`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.DAY))
        assertEquals(UiTranslations.tr("invoiceDetail.service.rateLabel.day"), priceField(dlg).label)
        assertTrue(qtyField(dlg).isVisible)
    }

    @Test
    fun `an existing item with an unrelated unit falls back to the hour radio option`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.PIECE))
        assertEquals(InvoiceUnitLabels.label(null, InvoiceItemUnit.HOUR), unitGroup(dlg).value)
    }

    @Test
    fun `an existing item with a zero or negative persisted quantity falls back to 1, not the stale value`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingServiceItem(InvoiceItemUnit.HOUR, quantity = BigDecimal.ZERO))
        assertEquals(1.0, qtyField(dlg).value)
    }

    // ── article selection without a seller VAT registration country (else branch) ──────────────

    @Test
    fun `picking a service article on the plain German path applies effectiveTaxRuleFor`() {
        val de7 = TaxRateDto(id = 2L, code = "DE_REDUCED_7", category = "S", rate = TaxRatePercent(BigDecimal("7.00")), name = "Reduced", isActive = true, isSelectable = true)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, de7))
        val article = ArticleDto(
            id = 1L,
            name = "Beratung",
            category = InvoiceItemCategory.SERVICE,
            defaultUnitPriceNet = Money(BigDecimal("80.00")),
            defaultTaxRateId = de7.id,
        )
        var captured: InvoiceItemCommand? = null
        ServiceItemDialog(
            recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        articleCombo.value = article
        saveButton(dlg).click()

        assertEquals(de7.id, captured?.taxRateId, "no recipient -> resolution null -> articleDefault wins")
    }

    // applyArticleSelection's `if (existingItem == null)` had only ever run its
    // true arm (a brand-new position, via newPositionDescriptionFor): picking a *replacement*
    // article while editing an existing position — which must overwrite the description with the
    // article's own text, not the "new position" phrasing — had never been driven.
    @Test
    fun `picking an article while editing an existing item overwrites the description with the article's own text`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val article = ArticleDto(
            id = 1L,
            name = "Beratung",
            category = InvoiceItemCategory.SERVICE,
            description = "Individuelle Rechtsberatung",
            defaultUnitPriceNet = Money(BigDecimal("80.00")),
        )
        val dlg = open(existingItem = existingServiceItem(InvoiceItemUnit.HOUR))

        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        articleCombo.value = article

        assertEquals("Individuelle Rechtsberatung", descField(dlg).value)
    }

    @Test
    fun `picking an article with no description while editing an existing item falls back to the article's name`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val article = ArticleDto(
            id = 1L,
            name = "Beratung",
            category = InvoiceItemCategory.SERVICE,
            description = null,
            defaultUnitPriceNet = Money(BigDecimal("80.00")),
        )
        val dlg = open(existingItem = existingServiceItem(InvoiceItemUnit.HOUR))

        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        articleCombo.value = article

        assertEquals("Beratung", descField(dlg).value)
    }

    @Test
    fun `picking an article with a non-positive defaultQuantity falls back to 1`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val article = ArticleDto(
            id = 1L,
            name = "Beratung",
            category = InvoiceItemCategory.SERVICE,
            defaultQuantity = BigDecimal.ZERO,
            defaultUnitPriceNet = Money(BigDecimal("80.00")),
        )
        val dlg = open()

        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        articleCombo.value = article

        assertEquals(1.0, qtyField(dlg).value, "a non-positive defaultQuantity must not be taken over as-is")
    }

    // ── loadExistingItemIntoFields — the optional fields ──────────────────────────

    @Test
    fun `an existing item with an optional description and a resolvable article ref pre-loads both`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val article = ArticleDto(id = 3L, name = "Pauschale", category = InvoiceItemCategory.SERVICE)
        whenever(articleQueries.findArticleById(3L)).thenReturn(article)
        val dlg = open(
            existingItem = existingServiceItem(InvoiceItemUnit.HOUR).copy(itemDescription = "Zusatzinfo", articleRefId = 3L),
        )

        val itemDescriptionField = formLayout(dlg).children.toList().filterIsInstance<TextArea>()[1]
        assertEquals("Zusatzinfo", itemDescriptionField.value)
        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        assertEquals(article, articleCombo.value)
    }

    // ── Editing an existing position preselects the article with the "number · name" label ──

    @Test
    fun `an existing item's preselected article renders as number middle-dot name, not the name alone`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val theArticle = ArticleDto(
            id = 3L,
            name = "Pauschale",
            category = InvoiceItemCategory.SERVICE,
            articleNumber = "ART-2026-00036",
        )
        whenever(articleQueries.findArticleById(3L)).thenReturn(theArticle)
        val dlg = open(existingItem = existingServiceItem(InvoiceItemUnit.HOUR).copy(articleRefId = 3L))

        val articleCombo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        assertEquals(theArticle, articleCombo.value)
        assertEquals("ART-2026-00036 · Pauschale", articleCombo.itemLabelGenerator.apply(theArticle))
    }

    @Test
    fun `an existing item with a null unitPriceNet pre-loads the price field as zero`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open(existingItem = existingServiceItem(InvoiceItemUnit.HOUR).copy(unitPriceNet = null))

        assertEquals(0.0, priceField(dlg).value)
    }

    // ── articleSelect is a lazy, SERVICE-category-constrained combo (ArticleQueryPort's own
    // findArticlesForSelect/countArticlesForSelect, reached through itemEntryQueryPort here), not findAllArticles() +
    // a client-side category filter capped at page-size.reference-data.lookup (1000 by default).
    // These drive the combo's own DataProvider with a typed filter, as the regression test proving
    // the old cap's bug is gone. ──────────────────────────────────────────────────────────────

    private fun articleDataProvider(dialog: Dialog): com.vaadin.flow.data.provider.DataProvider<ArticleDto, String> {
        val combo = formLayout(dialog).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        return combo.dataProvider.requireAs()
    }

    private fun articleQuery(offset: Int = 0, limit: Int = 60, filter: String? = null) =
        com.vaadin.flow.data.provider.Query<ArticleDto, String>(offset, limit, emptyList(), null, filter)

    @Test
    fun `articleSelect's lazy data provider finds a SERVICE article beyond the old 1000-row cap via a typed filter, constrained to SERVICE in the query`() {
        val beyondCap = ArticleDto(
            id = 1001L,
            name = "Beratung #1001",
            category = InvoiceItemCategory.SERVICE,
        )
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.SERVICE, "Beratung", 0, 60))
            .thenReturn(listOf(beyondCap))
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.SERVICE, "Beratung")).thenReturn(1)
        val dlg = open()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "Beratung")).toList()
        val size = articleDataProvider(dlg).size(articleQuery(filter = "Beratung"))

        assertEquals(listOf(beyondCap), result)
        assertEquals(1, size)
        verify(articleQueries).findArticlesForSelect(InvoiceItemCategory.SERVICE, "Beratung", 0, 60)
    }

    @Test
    fun `articleSelect's lazy data provider returns nothing for a filter with no match, without falling back to the eager list`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.SERVICE, "Nichts-passt", 0, 60))
            .thenReturn(emptyList())
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.SERVICE, "Nichts-passt")).thenReturn(0)
        val dlg = open()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "Nichts-passt")).toList()

        assertTrue(result.isEmpty())
    }

    // ── One input finds an article by number, name or description; number rendered/ranked ──

    @Test
    fun `articleSelect's placeholder tells the user it now searches by number or name`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open()

        val combo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        assertEquals(UiTranslations.tr("invoiceDetail.service.selectArticle.placeholder"), combo.placeholder)
    }

    @Test
    fun `articleSelect's data provider forwards a numeric-fragment filter to findArticlesForSelect unchanged`() {
        // At the dialog boundary: the combined number-or-name-or-description search and its
        // ranking live in the repository, not here — the dialog just relays the typed text.
        val byNumber = ArticleDto(
            id = 1002L,
            name = "Beratung #1002",
            category = InvoiceItemCategory.SERVICE,
            articleNumber = "ART-2026-00036",
        )
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.SERVICE, "00036", 0, 60))
            .thenReturn(listOf(byNumber))
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.SERVICE, "00036")).thenReturn(1)
        val dlg = open()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "00036")).toList()

        assertEquals(listOf(byNumber), result)
        verify(articleQueries).findArticlesForSelect(InvoiceItemCategory.SERVICE, "00036", 0, 60)
    }

    @Test
    fun `articleSelect's item label generator renders number middle-dot name for a selected article`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val dlg = open()
        val combo = formLayout(dlg).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()
        val numbered = ArticleDto(name = "Wartung", articleNumber = "ART-2026-00036")
        val unnumbered = ArticleDto(name = "Ohne Nummer")

        assertEquals("ART-2026-00036 · Wartung", combo.itemLabelGenerator.apply(numbered))
        assertEquals("Ohne Nummer", combo.itemLabelGenerator.apply(unnumbered))
    }
}
