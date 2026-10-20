package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.ItemEntryQueries
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.dialog
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.form
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.invoice
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.rate
import de.littletrotter.ui.view.invoice.ItemDialogTestFixtures.save
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TODAY

/**
 * [GoodsItemDialog] and the shared [BaseItemDialog] branches the existing suite leaves
 * open: the 19 %-then-first fallback without a dialog default, the seller-country reference rate
 * (also for an invoice without date), cleared fields falling back to safe defaults, and articles
 * without number, description or id.
 */
class GoodsItemDialogBranchKaribuTest {

    private val taxRateQueries: TaxRateQueryPort = mock()
    private val recipientQueryPort: RecipientQueryPort = mock()
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val articleQueries: ArticleQueryPort = mock()
    private val itemEntryQueryPort: ItemEntryQueryPort = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, articleQueries, taxRateQueries)
    private val de19 = rate("DE_STANDARD_19", "S", "19")
    private val de7 = rate("DE_REDUCED_7", "S", "7.00")
    private val frRate = rate("FR_STANDARD", "S", "20.00", Country.FR)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun open(
        rates: List<TaxRateDto>,
        defaultRate: TaxRateDto? = de19,
        sellerCountry: String? = null,
        existing: ItemDto? = null,
        invoiceDate: java.time.LocalDate? = java.time.LocalDate.of(2026, 6, 1),
        onSave: (InvoiceItemCommand) -> Unit,
    ): Dialog {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(rates)
        GoodsItemDialog(
            recipientQueryPort = recipientQueryPort,
            itemEntryQueryPort = itemEntryQueryPort,
            invoice = invoice(sellerVatRegistrationCountry = sellerCountry, invoiceDate = invoiceDate),
            existingItem = existing,
            defaultTaxRate = { defaultRate },
            onSaveNew = { onSave(it); 1L }, onUpdate = onSave, today = FIXED_TODAY,
        ).open()
        return dialog()
    }

    private fun desc(dlg: Dialog) = form(dlg).filterIsInstance<TextArea>()[0]
    private fun qty(dlg: Dialog) = form(dlg).filterIsInstance<NumberField>()[0]
    private fun price(dlg: Dialog) = form(dlg).filterIsInstance<NumberField>()[1]
    private fun textField(dlg: Dialog, key: String) = form(dlg).filterIsInstance<TextField>().single { it.label == UiTranslations.tr(key) }

    private fun unitBox(dlg: Dialog) =
        form(dlg).filterIsInstance<ComboBox<*>>().single { it.label == UiTranslations.tr("invoiceDetail.itemDialog.unit") }
            .requireAs<ComboBox<InvoiceItemUnit>>()

    private fun articleBox(dlg: Dialog) =
        form(dlg).filterIsInstance<ComboBox<*>>().single { it.label == UiTranslations.tr("invoiceDetail.goods.selectArticle") }
            .requireAs<ComboBox<ArticleDto>>()

    private fun article(id: Long? = 1L, number: String? = "A-1", description: String? = "Lieferung") = ArticleDto(
        id = id, name = "Pumpe", category = InvoiceItemCategory.PRODUCT, articleNumber = number, description = description,
    )

    @Test
    fun `happy path - an existing item keeps its buyer item description and saves it trimmed via update`() {
        var saved: InvoiceItemCommand? = null
        val existing = ItemDto(
            id = 5L, positionId = "1", description = "Altposition", quantity = BigDecimal("2"),
            unit = InvoiceItemUnit.PIECE.name, unitPriceNet = Money.of("10.00"), taxRate = null, taxRateRefId = de19.id,
            netAmount = null, vatAmount = null, grossAmount = null, category = InvoiceItemCategory.PRODUCT.name,
            buyerItemDescription = "  Kundenbezeichnung  ",
        )
        val dlg = open(listOf(de19), existing = existing) { saved = it }

        save(dlg)

        assertEquals("Kundenbezeichnung", saved?.buyerItemDescription)
        assertEquals(5L, saved?.itemId)
    }

    @Test
    fun `boundary - without a dialog default the 19 percent rate wins, otherwise the first selectable rate`() {
        var saved: InvoiceItemCommand? = null
        var dlg = open(listOf(de7, de19), defaultRate = null) { saved = it }
        desc(dlg).value = "Pumpe"
        save(dlg)
        assertEquals(de19.id, saved?.taxRateId)

        dlg.close()
        dlg = open(listOf(de7), defaultRate = null) { saved = it }
        desc(dlg).value = "Pumpe"
        save(dlg)
        assertEquals(de7.id, saved?.taxRateId)
    }

    @Test
    fun `boundary - with a seller country an article without variant takes the reference rate, also for an undated invoice`() {
        whenever(taxRateQueries.findReferenceTaxRate(eq(Country.FR), any())).thenReturn(frRate)
        var saved: InvoiceItemCommand? = null
        val dlg = open(listOf(frRate), defaultRate = null, sellerCountry = "FR", invoiceDate = null) { saved = it }

        qty(dlg).value = 3.0
        articleBox(dlg).value = article()
        save(dlg)

        assertEquals(frRate.id, saved?.taxRateId)
    }

    @Test
    fun `boundary - cleared quantity, price, unit and article fall back to one, zero, piece and no article`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open(listOf(de19)) { saved = it }
        articleBox(dlg).value = article()
        articleBox(dlg).value = null

        qty(dlg).value = null
        price(dlg).value = null
        unitBox(dlg).value = null
        desc(dlg).value = "Pumpe"
        save(dlg)

        assertEquals(0, BigDecimal.ONE.compareTo(saved?.quantity))
        assertEquals(Money.ZERO, saved?.unitPriceNet)
        assertEquals(InvoiceItemUnit.PIECE, saved?.unit)
        assertNull(saved?.articleId)
    }

    @Test
    fun `boundary - selecting an article prefills a blank B2B item-number field with the article's own number`() {
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(1L)).thenReturn(5L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(5L)).thenReturn(
            listOf(
                B2bProfileFieldDto(
                    term = B2bTerm.BT_155,
                    placement = B2bPlacement.INVOICE,
                    businessDefinition = "Sellers item identification",
                    requirement = B2bFieldRequirement.OPTIONAL,
                ),
            ),
        )
        whenever(articleQueries.findArticleById(7L)).thenReturn(article(id = 7L, number = "ART-9"))
        val dlg = open(listOf(de19)) { }

        articleBox(dlg).value = article(id = 7L, number = "ART-9")

        val b2bField = _find<TextField>().single { it.label == "Sellers item identification" }
        assertEquals("ART-9", b2bField.value, "the B2B BT-155 widget must pick up the selected article's own number")
    }

    @Test
    fun `boundary - an unsaved article without number or description leaves both fields empty`() {
        var saved: InvoiceItemCommand? = null
        val dlg = open(listOf(de19)) { saved = it }

        articleBox(dlg).value = article(id = null, number = null, description = null)

        assertEquals("", textField(dlg, "article.articleNumber").value)
        assertEquals("", textField(dlg, "invoiceDetail.goods.deliveryRef").value)
        save(dlg)
        assertNull(saved?.deliveryNoteReference)
        assertNull(saved?.articleId)
    }
}
