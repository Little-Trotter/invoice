package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

/**
 * [GoodsItemDialog]/[BaseItemDialog] carries the entire tax-rate-selection and save-validation
 * decision tree shared by every item dialog (goods/service/travel/per-diem). Exercised here
 * through the concrete [GoodsItemDialog] subclass — since [BaseItemDialog] is `abstract`, its
 * branches are only measurable through a concrete subclass, and JaCoCo counts each branch once
 * regardless of how many subclasses exercise it, so this file is the single place that earns
 * [BaseItemDialog]'s branch coverage. [ServiceItemDialogKaribuTest] only covers Service's own
 * additional branches.
 *
 * Finding (not fixed here — out of this task's scope, testing only): the `taxGroup` ComboBox
 * built by [BaseItemDialog.createTaxRateComboBox] is never attached to the dialog's component
 * tree — `BaseItemDialog.open()` adds `formLayout`, the tax-rule/B2B panels, the preview and the
 * advice panel, but never `taxGroup` itself. It is a live object the save/article-selection logic
 * reads and writes, just never rendered, so a real user cannot pick a tax rate through it (only
 * through picking an article whose variant/proposal resolves one). Tests below therefore observe
 * `taxGroup`'s state exclusively through its effect on the saved [InvoiceItemCommand.taxRateId],
 * the same way an end user's only lever on it (article selection, or the item's own persisted
 * value on load) actually works — not through direct component queries, which would find nothing.
 *
 * Covers: blank-description save guard, the sellerCountry==null vs. !=null tax-selection forks,
 * the reverse-charge lock, the E/O exemption-details save guard, create vs. update save paths,
 * loadExistingTaxRate's three-way fallback, and applyArticleSelection's three tax-rate branches.
 */
class GoodsItemDialogKaribuTest {

    private val detailQueryPort: InvoiceDetailQueryPort = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
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

    private fun createView(): InvoiceDetailView {
        val invoiceDetailQueryPort = mock<InvoiceDetailQueryPort>()
        val generationJobQueryPort = mock<GenerationJobQueryPort>()
        return InvoiceDetailView(
            invoiceDetailQueryPort = detailQueryPort,
            generationJobQueryPort = generationJobQueryPort,
            uiEventBus = UiEventBus(),
            invoiceCockpitHero = testCockpitHero(),
            detailTabs = InvoiceDetailTabs(
                overviewPanel = InvoiceOverviewPanel(detailQueryPort, mock()),
                invoiceItemsTab = testItemsTab(
                    taxRateQueries = taxRateQueries,
                    itemEntryQueryPort = articleQueries,
                    b2bFieldDefinitionQueries = b2bFieldDefinitionQueries,
                    b2bFieldValueQueries = b2bFieldValueQueries,
                    recipientQueryPort = recipientQueryPort,
                ),
                invoiceReceiptsTab = InvoiceReceiptsTab(mock(), mock(), ReceiptDialogLauncher(mock(), mock(), invoiceDetailQueryPort, mock())),
                exportPanel = testExportPanel(invoiceDetailQueryPort, generationJobQueryPort),
            ),
            invoiceEntryPane = testEntryPane(recipientQueryPort = recipientQueryPort, invoiceDetailQueryPort = invoiceDetailQueryPort),
        )
    }

    private fun rate(
        code: String,
        category: String,
        percent: String = "19.00",
        country: Country = Country.DE,
    ) = TaxRateDto(
        id = code.hashCode().toLong(),
        code = code,
        category = category,
        rate = TaxRatePercent(BigDecimal(percent)),
        country = country,
        name = code,
        isActive = true,
        isSelectable = true,
        exemptionReasonText = if (category == "E" || category == "O") "Reason" else null,
    )

    private val de19 = rate("DE_STANDARD_19", "S")
    private val de7 = rate("DE_REDUCED_7", "S", "7.00")
    private val rcAe = rate("EU_B2B_RC_SERVICE", "AE", "0.00")
    private val exemptE = rate("DE_EXEMPT_SERVICE", "E", "0.00")
    private val frRate = rate("FR_STANDARD", "S", "20.00", country = Country.FR)

    private fun invoice(
        reverseCharge: Boolean = false,
        sellerVatRegistrationCountry: String? = null,
        recipient: PartyDto? = null,
    ) = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = recipient, items = emptyList(), latestGenerationJob = null,
        reverseCharge = reverseCharge, sellerVatRegistrationCountry = sellerVatRegistrationCountry,
    )

    private fun existingGoodsItem(taxRateRefId: Long? = null) = ItemDto(
        id = 5L, positionId = "1", description = "Altposition", quantity = BigDecimal("2"),
        unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money(BigDecimal("10.00")),
        taxRate = null, taxRateRefId = taxRateRefId,
        netAmount = null, vatAmount = null, grossAmount = null, category = InvoiceItemCategory.PRODUCT.name,
    )

    private fun article(id: Long = 1L, name: String = "Pumpe", defaultTaxRateRef: TaxRateDto? = null, variantRate: TaxRateDto? = null) = ArticleDto(
        id = id,
        name = name,
        category = InvoiceItemCategory.PRODUCT,
        defaultUnitPriceNet = Money(BigDecimal("42.00")),
        defaultTaxRateId = defaultTaxRateRef?.id,
        taxVariants = listOfNotNull(variantRate?.id?.let { ArticleTaxVariantDto(variantRate.country, it) }),
    )

    private fun dialog(): Dialog = _get<Dialog>()

    private fun formLayout(dialog: Dialog): com.vaadin.flow.component.formlayout.FormLayout =
        dialog.children.toList().filterIsInstance<com.vaadin.flow.component.formlayout.FormLayout>().first()

    private fun articleCombo(dialog: Dialog): ComboBox<ArticleDto> =
        formLayout(dialog).children.toList().filterIsInstance<ComboBox<ArticleDto>>().first()

    private fun descField(dialog: Dialog): TextArea =
        formLayout(dialog).children.toList().filterIsInstance<TextArea>()[0]

    private fun saveButton(dialog: Dialog): com.vaadin.flow.component.button.Button =
        dialog._get<com.vaadin.flow.component.button.Button> { text = UiTranslations.tr("app.save") }

    // ── Save validation: blank description guard ──────────────────────────────────────────────

    @Test
    fun `save is blocked and dialog stays open when the description is blank`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var saveNewCalled = false
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { saveNewCalled = true; 1L },
            onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "   "
        saveButton(dlg).click()

        assertFalse(saveNewCalled, "blank description must block the save entirely")
        assertTrue(dlg.isOpened, "dialog must stay open when validation fails")
    }

    @Test
    fun `a filled description with sellerCountry null and no exemption requirement saves successfully`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L },
            onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Hydraulikpumpe"
        saveButton(dlg).click()

        assertFalse(dlg.isOpened, "a valid save must close the dialog")
        assertEquals("Hydraulikpumpe", captured?.description)
        assertEquals(de19.id, captured?.taxRateId, "createTaxRateComboBox's sellerCountry==null branch must resolve the fallback rate")
    }

    @Test
    fun `unit price dialog keeps UNIT_PRICE category when saved`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            category = InvoiceItemCategory.UNIT_PRICE,
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L },
            onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Fliesenfläche"
        saveButton(dlg).click()

        assertEquals(InvoiceItemCategory.UNIT_PRICE, captured?.category)
    }

    // ── Save validation: sellerCountry != null requires an explicit tax-rate selection ─────────

    @Test
    fun `with a seller VAT registration country, a new item cannot be saved until an article resolves a tax rate`() {
        val articleWithVariant = article(variantRate = frRate)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(frRate))
        val view = createView()
        var saveNewCalled = false
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(sellerVatRegistrationCountry = "FR"),
            defaultTaxRate = { null },
            onSaveNew = { cmd -> saveNewCalled = true; captured = cmd; 1L },
            onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Hydraulikpumpe"
        // createTaxRateComboBox's `else -> null` branch: sellerCountry != null starts unselected,
        // and (unlike an existing item) nothing pre-loads it — the ComboBox itself is never
        // rendered (see class KDoc), so an article pick is the only lever a new item has.
        saveButton(dlg).click()
        assertFalse(saveNewCalled, "no tax rate resolved yet — save must be blocked")

        articleCombo(dlg).value = articleWithVariant
        saveButton(dlg).click()
        assertTrue(saveNewCalled, "picking the article resolves its country variant, unblocking save")
        assertEquals(frRate.id, captured?.taxRateId)
    }

    // ── Reverse-charge lock ─────────────────────────────────────────────────────────────────────

    @Test
    fun `reverse-charge lock resolves the item to the single locked rate without any exemption requirement`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, rcAe))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(reverseCharge = true),
            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Hydraulikpumpe"
        saveButton(dlg).click()

        assertFalse(dlg.isOpened, "a locked reverse-charge rate needs no further validation to save")
        assertEquals(rcAe.id, captured?.taxRateId, "reverseChargeLocked must force the locked rate, not the fallback")
    }

    // ── E/O exemption-details save guard ────────────────────────────────────────────────────────

    @Test
    fun `an E category rate as the effective default blocks save until reason and legal basis are filled`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, exemptE))
        val view = createView()
        var saveNewCalled = false
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            // No recipient -> resolution is null -> effectiveTaxRuleFor falls through to
            // articleDefault (null, no article picked) ?: fallback -- making exemptE the
            // fallback here is the only way (see class KDoc) to get taxGroup.value = exemptE.
            defaultTaxRate = { exemptE },
            onSaveNew = { cmd -> saveNewCalled = true; captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Heilbehandlung"
        saveButton(dlg).click()
        assertFalse(saveNewCalled, "E category requires reason+legal basis before save")

        // The exemption fields start invisible (only [TaxRuleProposalPanel.onSelectedRateChanged]
        // — never fired for a brand-new item, see class KDoc — flips that), so Karibu's default
        // `_get` (visible components only) will not find them; located by their known position
        // in the panel instead ([TaxRuleProposalPanel]'s own add-order: hint/deviation/rc/reason/legalBasis).
        val panel = dlg.children.toList().filterIsInstance<TaxRuleProposalPanel>().first()
        val panelChildren = panel.children.toList()
        val reasonField = panelChildren[3] as TextField
        val legalField = panelChildren[4] as TextField
        reasonField.value = "Heilbehandlung"
        legalField.value = "§ 4 Nr. 14 UStG"
        saveButton(dlg).click()

        assertTrue(saveNewCalled, "once both exemption fields are filled, save must proceed")
        assertEquals("Heilbehandlung", captured?.exemptionReasonTextOverride)
        assertEquals("§ 4 Nr. 14 UStG", captured?.taxLegalBasisOverride)
    }

    // ── create vs. update save paths ────────────────────────────────────────────────────────────

    @Test
    fun `saving a new item calls onSaveNew, never onUpdate`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var saveNewCalled = false
        var updateCalled = false
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { saveNewCalled = true; 7L },
            onUpdate = { updateCalled = true }, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        descField(dlg).value = "Neue Position"
        saveButton(dlg).click()

        assertTrue(saveNewCalled)
        assertFalse(updateCalled)
    }

    @Test
    fun `saving an existing item calls onUpdate, never onSaveNew, and pre-loads its fields`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var saveNewCalled = false
        var updateCalled = false
        var capturedCommand: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de19.id),
            defaultTaxRate = { de19 },
            onSaveNew = { saveNewCalled = true; 7L },
            onUpdate = { cmd -> updateCalled = true; capturedCommand = cmd }, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        assertEquals("Altposition", descField(dlg).value, "an existing item's fields must be pre-loaded")
        saveButton(dlg).click()

        assertTrue(updateCalled)
        assertFalse(saveNewCalled)
        assertEquals(5L, capturedCommand?.itemId)
    }

    // ── loadExistingTaxRate three-way fallback ──────────────────────────────────────────────────

    @Test
    fun `loadExistingTaxRate preselects the item's own stored tax rate when it still resolves`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, de7))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de7.id),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = { cmd -> captured = cmd }, today = FIXED_TODAY,
        ).open()
        saveButton(dialog()).click()

        assertEquals(de7.id, captured?.taxRateId, "the item's own persisted rate must win over the dialog default")
    }

    @Test
    fun `loadExistingTaxRate falls back to the dialog default when the stored tax rate id no longer resolves`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, de7))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = 999999L),
            defaultTaxRate = { de7 },
            onSaveNew = { 1L }, onUpdate = { cmd -> captured = cmd }, today = FIXED_TODAY,
        ).open()
        saveButton(dialog()).click()

        assertEquals(de7.id, captured?.taxRateId, "an unresolvable stored id must fall back to the dialog default")
    }

    @Test
    fun `loadExistingTaxRate falls back to the first available rate when there is no default either`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, de7))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = null),
            defaultTaxRate = { null },
            onSaveNew = { 1L }, onUpdate = { cmd -> captured = cmd }, today = FIXED_TODAY,
        ).open()
        saveButton(dialog()).click()

        assertEquals(de19.id, captured?.taxRateId, "with no stored id and no default, the first available rate is used")
    }

    @Test
    fun `loadExistingTaxRate keeps the reverse-charge locked rate even for an existing item's own stored tax rate`() {
        // reverseChargeLocked short-circuits loadExistingTaxRate BEFORE the stored
        // taxRateRefId/default/first-available fallback chain even runs — an existing item's own
        // persisted rate (de7 here) must never resurface once RC is locked, mirroring the
        // "picking an article while reverse-charge is locked" case above, but exercised through
        // the load path instead of the article-selection path.
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rcAe, de7))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(reverseCharge = true),
            existingItem = existingGoodsItem(taxRateRefId = de7.id),
            defaultTaxRate = { null },
            onSaveNew = { 1L }, onUpdate = { cmd -> captured = cmd }, today = FIXED_TODAY,
        ).open()
        saveButton(dialog()).click()

        assertEquals(rcAe.id, captured?.taxRateId, "reverse-charge lock overrides even the item's own persisted rate on load")
    }

    // ── applyArticleSelection's three tax-rate branches ─────────────────────────────────────────

    @Test
    fun `picking an article while reverse-charge is locked keeps the locked rate, not the article's own rate`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rcAe))
        val theArticle = article(defaultTaxRateRef = de19)
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(reverseCharge = true),
            defaultTaxRate = { null },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        articleCombo(dlg).value = theArticle
        descField(dlg).value = "Pumpe"
        saveButton(dlg).click()

        assertEquals(rcAe.id, captured?.taxRateId, "reverse-charge lock must survive an article pick")
    }

    @Test
    fun `picking an article with a seller VAT registration country applies the article's own country variant`() {
        val articleWithVariant = article(variantRate = frRate)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(frRate))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(sellerVatRegistrationCountry = "FR"),
            defaultTaxRate = { null },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        articleCombo(dlg).value = articleWithVariant
        descField(dlg).value = "Pumpe"
        saveButton(dlg).click()

        assertEquals(frRate.id, captured?.taxRateId, "sellerCountry != null must apply the article's own country variant")
    }

    @Test
    fun `picking an article on the plain German path applies effectiveTaxRuleFor with the article default`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19, de7))
        val theArticle = article(defaultTaxRateRef = de7)
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        articleCombo(dlg).value = theArticle
        // No resolution (no recipient) -> effectiveTaxRuleFor(resolution=null) -> articleDefault ?: fallback
        saveButton(dlg).click()

        assertEquals(de7.id, captured?.taxRateId)
    }

    // ── article selection listener: recipient-ref prefill branch ───────────────────────────────

    @Test
    fun `article selection prefills the description from the recipient mapping when one exists`() {
        val recipient = PartyDto(id = 3L, name = "Kunde")
        val mapping = ArticleRecipientRefDto(3L, buyerArticleName = "Nekar-Bezeichnung")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val theArticle = article()
        whenever(articleQueries.findArticleRecipientRef(1L, 3L)).thenReturn(mapping)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(recipient = recipient),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        articleCombo(dlg).value = theArticle
        assertEquals("Nekar-Bezeichnung", descField(dlg).value)
    }

    @Test
    fun `article selection falls back to the article name when there is no recipient mapping`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val theArticle = article(name = "Pumpe")
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        articleCombo(dlg).value = theArticle
        assertEquals("Pumpe", descField(dlg).value)
    }

    // ── quantity field reflects a live NumberField, exercising collectQuantityAndPrice ─────────

    @Test
    fun `changing quantity feeds into the saved command's quantity`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var captured: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val dlg = dialog()
        val qty = formLayout(dlg).children.toList().filterIsInstance<NumberField>()[0]
        qty.value = 3.0
        descField(dlg).value = "Pumpe"
        saveButton(dlg).click()

        assertEquals(BigDecimal.valueOf(3.0), captured?.quantity)
    }

    // ── articleSelect is a lazy, PRODUCT-category-constrained combo (ArticleQueryPort's own
    // findArticlesForSelect/countArticlesForSelect, reached through itemEntryQueryPort here), not
    // findAllArticles() + a client-side category filter capped at page-size.reference-data.lookup
    // (1000 by default).
    // These drive the combo's own DataProvider with a typed filter, as the regression test proving
    // the old cap's bug is gone. ──────────────────────────────────────────────────────────────

    private fun articleDataProvider(dialog: Dialog): com.vaadin.flow.data.provider.DataProvider<ArticleDto, String> =
        articleCombo(dialog).dataProvider.requireAs()

    private fun articleQuery(offset: Int = 0, limit: Int = 60, filter: String? = null) =
        com.vaadin.flow.data.provider.Query<ArticleDto, String>(offset, limit, emptyList(), null, filter)

    @Test
    fun `articleSelect's lazy data provider finds a PRODUCT article beyond the old 1000-row cap via a typed filter, constrained to PRODUCT in the query`() {
        // Beyond findAllArticles()'s old single capped page, and — the second half of the bug —
        // the old code loaded everything then filtered PRODUCT client-side; here the category is
        // an argument the fetch callback itself receives.
        val beyondCap = article(id = 1001L, name = "Hydraulikpumpe #1001")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, "Hydraulik", 0, 60))
            .thenReturn(listOf(beyondCap))
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, "Hydraulik")).thenReturn(1)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "Hydraulik")).toList()
        val size = articleDataProvider(dlg).size(articleQuery(filter = "Hydraulik"))

        assertEquals(listOf(beyondCap), result)
        assertEquals(1, size)
        verify(articleQueries).findArticlesForSelect(InvoiceItemCategory.PRODUCT, "Hydraulik", 0, 60)
    }

    @Test
    fun `articleSelect's lazy data provider returns nothing for a filter with no match, without falling back to the eager list`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, "Nichts-passt", 0, 60))
            .thenReturn(emptyList())
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, "Nichts-passt")).thenReturn(0)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "Nichts-passt")).toList()

        assertTrue(result.isEmpty())
    }

    // ── one input finds an article by number, name or description; number rendered/ranked ────────

    @Test
    fun `articleSelect's placeholder tells the user it now searches by number or name`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        assertEquals(UiTranslations.tr(view, "invoiceDetail.goods.selectArticle.placeholder"), articleCombo(dlg).placeholder)
    }

    @Test
    fun `articleSelect's data provider forwards a numeric-fragment filter to findArticlesForSelect unchanged`() {
        // At the dialog boundary: the dialog does not interpret the filter text itself, it
        // simply relays whatever the user typed (a number fragment here) to the facade — the
        // combined number-or-name-or-description search and its ranking live in the repository
        // (PanacheArticleRepositoryQueryTest/PanacheArticleRepositorySelectIT), not here.
        val byNumber = article(id = 1002L, name = "Hydraulikpumpe #1002").copy(articleNumber = "ART-2026-00036")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, "00036", 0, 60))
            .thenReturn(listOf(byNumber))
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, "00036")).thenReturn(1)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val result = articleDataProvider(dlg).fetch(articleQuery(filter = "00036")).toList()

        assertEquals(listOf(byNumber), result)
        verify(articleQueries).findArticlesForSelect(InvoiceItemCategory.PRODUCT, "00036", 0, 60)
    }

    @Test
    fun `articleSelect's item label generator renders number middle-dot name for a selected article`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()
        val numbered = article(name = "Kabelkanal").copy(articleNumber = "ART-2026-00036")
        val unnumbered = article(name = "Ohne Nummer")

        assertEquals("ART-2026-00036 · Kabelkanal", articleCombo(dlg).itemLabelGenerator.apply(numbered))
        assertEquals("Ohne Nummer", articleCombo(dlg).itemLabelGenerator.apply(unnumbered))
    }

    // ── Article pickup takes the price over; a 0-€ position warns but saves ──────────────

    private fun priceField(dlg: Dialog): NumberField =
        formLayout(dlg).children.toList().filterIsInstance<NumberField>()[1]

    @Test
    fun `picking an article takes over its unit price, quantity and unit`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        articleCombo(dlg).value = article()

        assertEquals(42.0, priceField(dlg).value, "the article's defaultUnitPriceNet must land in the price field")
        assertEquals(1.0, formLayout(dlg).children.toList().filterIsInstance<NumberField>()[0].value)
    }

    @Test
    fun `picking an article with a non-positive defaultQuantity falls back to 1`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val zeroQtyArticle = article().copy(defaultQuantity = BigDecimal.ZERO)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        articleCombo(dlg).value = zeroQtyArticle

        assertEquals(1.0, formLayout(dlg).children.toList().filterIsInstance<NumberField>()[0].value, "a non-positive defaultQuantity must not be taken over as-is")
    }

    @Test
    fun `switching the article on an existing item takes the article's own name, not the recipient mapping description`() {
        // newPositionDescriptionFor is deliberately only used for a brand-new position
        // (existingItem == null) — switching the article on an already-persisted item always
        // takes the plain article.name, per applyArticleSelection's own existingItem branch.
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val theArticle = article(name = "Neue Pumpe")
        whenever(articleQueries.findArticleRecipientRef(org.mockito.kotlin.eq(theArticle.id!!), org.mockito.kotlin.any())).thenReturn(
            ArticleRecipientRefDto(3L, buyerArticleName = "Sollte ignoriert werden"),
        )
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(recipient = PartyDto(id = 3L, name = "Kunde")),            existingItem = existingGoodsItem(),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        articleCombo(dlg).value = theArticle

        assertEquals("Neue Pumpe", descField(dlg).value, "ein bestehender Posten uebernimmt den reinen Artikelnamen, nicht die Kunden-Zuordnung")
    }

    @Test
    fun `a zero-price goods position saves with a warning instead of blocking`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var savedCommand: InvoiceItemCommand? = null
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> savedCommand = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()
        descField(dlg).value = "Gratisposition"
        priceField(dlg).value = 0.0
        saveButton(dlg).click()

        assertNotNull(savedCommand, "a 0-€ price warns, it never blocks the save (PO default)")
        assertTrue(savedCommand!!.unitPriceNet.isZero())
        val warning = getNotifications().singleOrNull()
        assertNotNull(warning, "exactly the zero-price warning notification must be shown")
    }

    // ── loadExistingItemIntoFields — the optional fields ─────────────────────────────────────────
    // The "pre-loads its fields" test above only exercises existingGoodsItem()'s defaults, which
    // leave itemDescription/deliveryNoteReference/articleRefId null and unitPriceNet non-null —
    // only ever the one direction of each `?:`/`.orEmpty()`/`?.let` in loadExistingItemIntoFields.

    @Test
    fun `an existing item with an optional description, delivery reference and resolvable article pre-loads all three`() {
        val article = article(id = 3L, name = "Pumpe X")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticleById(3L)).thenReturn(article)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de19.id).copy(
                itemDescription = "Zusatzinfo", deliveryNoteReference = "LS-99", articleRefId = 3L,
            ),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val itemDescriptionField = formLayout(dlg).children.toList().filterIsInstance<TextArea>()[1]
        assertEquals("Zusatzinfo", itemDescriptionField.value)
        val refField = formLayout(dlg).children.toList().filterIsInstance<TextField>()
            .first { it.label == UiTranslations.tr(view, "invoiceDetail.goods.deliveryRef") }
        assertEquals("LS-99", refField.value)
        assertEquals(article, articleCombo(dlg).value)
    }

    // ── editing an existing position preselects the article with the "number · name" label ────────

    @Test
    fun `an existing item's preselected article renders as number middle-dot name, not the name alone`() {
        val theArticle = article(id = 3L, name = "Pumpe X").copy(articleNumber = "ART-2026-00036")
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticleById(3L)).thenReturn(theArticle)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de19.id).copy(articleRefId = 3L),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val combo = articleCombo(dlg)
        assertEquals(theArticle, combo.value)
        assertEquals("ART-2026-00036 · Pumpe X", combo.itemLabelGenerator.apply(theArticle))
    }

    @Test
    fun `a filled delivery reference and itemDescription are trimmed and carried into the saved command`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        descField(dlg).value = "Pumpe"
        formLayout(dlg).children.toList().filterIsInstance<TextArea>()[1].value = "  Zusatzinfo  "
        formLayout(dlg).children.toList().filterIsInstance<TextField>()
            .first { it.label == UiTranslations.tr(view, "invoiceDetail.goods.deliveryRef") }.value = "  LS-42  "
        saveButton(dlg).click()

        assertEquals("Zusatzinfo", captured?.itemDescription)
        assertEquals("LS-42", captured?.deliveryNoteReference)
    }

    @Test
    fun `a blank delivery reference and itemDescription are saved as null`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        var captured: InvoiceItemCommand? = null
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { cmd -> captured = cmd; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        descField(dlg).value = "Pumpe"
        saveButton(dlg).click()

        assertEquals(null, captured?.itemDescription)
        assertEquals(null, captured?.deliveryNoteReference)
    }

    @Test
    fun `a priced goods position saves without any warning notification`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        var saved = false
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            defaultTaxRate = { de19 },
            onSaveNew = { saved = true; 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()
        descField(dlg).value = "Bezahlte Position"
        priceField(dlg).value = 12.5
        saveButton(dlg).click()

        assertTrue(saved)
        assertTrue(getNotifications().isEmpty(), "a normal priced save shows no notification from the dialog")
    }

    @Test
    fun `an existing item with a null article ref and an unresolvable article ref both leave articleSelect empty`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        whenever(articleQueries.findArticleById(99L)).thenReturn(null)
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de19.id).copy(articleRefId = 99L),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        assertEquals(null, articleCombo(dialog()).value)
    }

    @Test
    fun `an existing item with a null unitPriceNet pre-loads the price field as zero`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            existingItem = existingGoodsItem(taxRateRefId = de19.id).copy(unitPriceNet = null),
            defaultTaxRate = { de19 },
            onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()
        val dlg = dialog()

        val priceField = formLayout(dlg).children.toList().filterIsInstance<NumberField>()
            .first { it.label == UiTranslations.tr(view, "invoiceDetail.itemDialog.unitPriceNet") }
        assertEquals(0.0, priceField.value)
    }

    @Test
    fun `unit price editor labels its category units instead of enum names`() {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(de19))
        val view = createView()
        GoodsItemDialog(
recipientQueryPort = recipientQueryPort, itemEntryQueryPort = articleQueries, invoice = invoice(),            category = InvoiceItemCategory.UNIT_PRICE,
            defaultTaxRate = { de19 }, onSaveNew = { 1L }, onUpdate = {}, today = FIXED_TODAY,
        ).open()

        val unitBox = formLayout(dialog()).children.toList()
            .filterIsInstance<ComboBox<*>>()
            .first { it.label == UiTranslations.tr(view, "invoiceDetail.itemDialog.unit") }
        val labels = unitBox.requireAs<ComboBox<InvoiceItemUnit>>()
        assertEquals("Quadratmeter (m2)", labels.itemLabelGenerator.apply(InvoiceItemUnit.SQUARE_METER))
        assertEquals("Pauschale", labels.itemLabelGenerator.apply(InvoiceItemUnit.FLAT_RATE))
    }
}
