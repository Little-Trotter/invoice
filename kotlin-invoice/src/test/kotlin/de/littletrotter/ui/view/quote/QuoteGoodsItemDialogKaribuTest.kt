package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.LeistungsverzeichnisRules
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class QuoteGoodsItemDialogKaribuTest {

    private val articles: QuoteArticleLookupQueryPort = mock()
    private val saved = mutableListOf<QuoteItemInput>()

    private val material = QuoteArticleOption(
        "a-5", "M-5", "Hydraulikpumpe", InvoiceItemCategory.PRODUCT, InvoiceItemUnit.KILOGRAM, Money.of("12.50"), VatCategory.STANDARD, BigDecimal("7.00"),
        customerReference = "K-5", customerArticleName = "Pumpe beim Kunden",
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(articles.articles(any(), any(), any(), any())).thenReturn(listOf(material))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun dialog(category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT, existing: de.littletrotter.application.quote.port.inbound.QuoteItem? = null) =
        QuoteGoodsItemDialog(testHost(), articles, "q-1", category, existing) { saved += it }.also { it.open() }

    private fun fill(dialog: QuoteGoodsItemDialog, text: String = "Pumpe", qty: Double = 2.0, price: Double = 10.0) {
        dialog.description.value = text
        dialog.quantity.value = qty
        dialog.price.value = price
    }

    @Test
    fun `the dialog shows no business terms and no delivery note field`() {
        val dialog = dialog()

        assertTrue(dialog.dialog.isOpened)
        val form = dialog.description.parent.get()
        val labels = descendants(form).mapNotNull { (it as? com.vaadin.flow.component.HasLabel)?.label }
        val known = listOf("Menge", "Einheit", "Einzelpreis netto (EUR)", "USt.-Kategorie", "USt.-Satz")
        assertEquals(known, labels.filter { it in known })
        assertFalse(labels.any { it.contains("Lieferschein") || it.contains("Empfänger") })
        assertEquals(listOf(dialog.cancelButton, dialog.saveButton), footerComponents(dialog.dialog).filterIsInstance<com.vaadin.flow.component.button.Button>())
    }

    @Test
    fun `a material offers the units of its kind and starts with a piece`() {
        val dialog = dialog()

        assertEquals(
            LeistungsverzeichnisRules.allowedUnits(InvoiceItemCategory.PRODUCT),
            (dialog.unitBox.dataProvider as ListDataProvider<InvoiceItemUnit>).items.toSet(),
        )
        assertEquals(InvoiceItemUnit.PIECE, dialog.unitBox.value)
        assertFalse(InvoiceItemUnit.EURO in (dialog.unitBox.dataProvider as ListDataProvider<InvoiceItemUnit>).items)
    }

    @Test
    fun `a unit price offers the units of its kind and starts with square metres`() {
        val dialog = dialog(InvoiceItemCategory.UNIT_PRICE)

        assertEquals(InvoiceItemUnit.SQUARE_METER, dialog.unitBox.value)
        assertEquals(
            LeistungsverzeichnisRules.allowedUnits(InvoiceItemCategory.UNIT_PRICE),
            (dialog.unitBox.dataProvider as ListDataProvider<InvoiceItemUnit>).items.toSet(),
        )
    }

    @Test
    fun `a missing description is refused and nothing is saved`() {
        val dialog = dialog()
        fill(dialog, text = "  ")

        assertFalse(dialog.save())
        assertTrue(saved.isEmpty())
        assertTrue(dialog.description.isInvalid)
        assertTrue(dialog.dialog.isOpened)
    }

    @Test
    fun `quantity zero price below zero and a missing rate are refused`() {
        val dialog = dialog()

        fill(dialog, qty = 0.0)
        assertFalse(dialog.save())
        assertTrue(dialog.quantity.isInvalid)

        fill(dialog, price = -1.0)
        assertFalse(dialog.save())
        assertTrue(dialog.price.isInvalid)

        fill(dialog)
        dialog.vatRate.value = null
        assertFalse(dialog.save())
        assertTrue(dialog.vatRate.isInvalid)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a text the pdf font cannot print is refused`() {
        val dialog = dialog()
        fill(dialog, text = "Pumpe 😀")

        assertFalse(dialog.save())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a price of zero is allowed`() {
        val dialog = dialog()
        fill(dialog, price = 0.0)

        assertTrue(dialog.save())
        assertEquals(Money.of("0.00"), saved.single().unitPrice)
    }

    @Test
    fun `the article picker only asks for articles of the dialogs kind`() {
        val dialog = dialog(InvoiceItemCategory.UNIT_PRICE)

        (dialog.articleBox.dataProvider as DataProvider<QuoteArticleOption, String>).fetch(Query(0, 20, emptyList(), null, "pu")).toList()

        verify(articles).articles("q-1", InvoiceItemCategory.UNIT_PRICE, "pu", 20)
        assertEquals("M-5 · Hydraulikpumpe", dialog.articleBox.itemLabelGenerator.apply(material))
    }

    @Test
    fun `picking an article prefills the fields and the preview follows`() {
        val dialog = dialog()
        dialog.articleBox.value = material

        assertEquals("M-5", dialog.articleNumber.value)
        assertEquals("Hydraulikpumpe", dialog.description.value)
        assertEquals(InvoiceItemUnit.KILOGRAM, dialog.unitBox.value)
        assertEquals(12.5, dialog.price.value)
        assertEquals(7.0, dialog.vatRate.value)
        assertEquals(VatCategory.STANDARD, dialog.vatCategory.value)
        val preview = visibleText(dialog.preview)
        assertTrue(preview.contains("12,50"), preview)
        assertTrue(preview.contains("0,88"), preview)
        assertTrue(preview.contains("13,38"), preview)
    }

    @Test
    fun `an article unit the kind does not allow is not taken over`() {
        val dialog = dialog()
        dialog.articleBox.value = material.copy(unit = InvoiceItemUnit.HOUR)

        assertEquals(InvoiceItemUnit.PIECE, dialog.unitBox.value)
    }

    @Test
    fun `the preview calculates net vat and gross from quantity price and rate`() {
        val dialog = dialog()
        fill(dialog, qty = 3.0, price = 10.0)
        dialog.vatRate.value = 19.0

        val preview = visibleText(dialog.preview)
        assertTrue(preview.contains("30,00"), preview)
        assertTrue(preview.contains("5,70"), preview)
        assertTrue(preview.contains("35,70"), preview)
        assertTrue(preview.contains("19 %"), preview)
    }

    @Test
    fun `saving hands the line with its article link to the callback and closes`() {
        val dialog = dialog()
        dialog.articleBox.value = material
        dialog.description.value = "  Pumpe Typ X "
        dialog.quantity.value = 4.0
        dialog.unitBox.value = InvoiceItemUnit.PIECE
        dialog.vatCategory.value = VatCategory.EXEMPT
        dialog.saveButton._click()

        val input = saved.single()
        assertEquals("Pumpe Typ X", input.description)
        assertEquals(BigDecimal.valueOf(4.0), input.quantity)
        assertEquals(InvoiceItemCategory.PRODUCT, input.category)
        assertEquals(InvoiceItemUnit.PIECE, input.unit)
        assertEquals(Money.of("12.50"), input.unitPrice)
        assertEquals(VatCategory.EXEMPT, input.vatCategory)
        assertEquals(BigDecimal.valueOf(7.0), input.vatRate)
        assertNull(input.id)
        assertEquals("a-5", input.articleId)
        assertEquals("M-5", input.articleNumber)
        assertEquals("K-5", input.customerReference)
        assertEquals("Pumpe beim Kunden", input.customerArticleName)
        assertFalse(dialog.dialog.isOpened)
    }

    @Test
    fun `a free line carries no article link`() {
        val dialog = dialog(InvoiceItemCategory.UNIT_PRICE)
        fill(dialog, text = "Estrich")
        dialog.save()

        val input = saved.single()
        assertEquals(InvoiceItemCategory.UNIT_PRICE, input.category)
        assertEquals(InvoiceItemUnit.SQUARE_METER, input.unit)
        assertNull(input.articleId)
    }

    @Test
    fun `cancel closes without saving`() {
        val dialog = dialog()
        fill(dialog)
        dialog.cancelButton._click()

        assertTrue(saved.isEmpty())
        assertFalse(dialog.dialog.isOpened)
    }

    @Test
    fun `editing loads the line and keeps its id and article link unless another article is picked`() {
        val existing = quoteItem(2).copy(category = InvoiceItemCategory.PRODUCT, unit = InvoiceItemUnit.METER)
        val dialog = dialog(InvoiceItemCategory.PRODUCT, existing)

        assertEquals("Beratung 2", dialog.description.value)
        assertEquals(2.5, dialog.quantity.value)
        assertEquals(InvoiceItemUnit.METER, dialog.unitBox.value)
        assertEquals(100.0, dialog.price.value)
        assertEquals("A-1", dialog.articleNumber.value)
        dialog.save()

        val kept = saved.single()
        assertEquals("i-2", kept.id)
        assertEquals("a-1", kept.articleId)
        assertEquals("K-77", kept.customerReference)

        val replaced = dialog(InvoiceItemCategory.PRODUCT, existing).also { it.articleBox.value = material.copy(customerReference = null, customerArticleName = null) }
        replaced.save()
        val other = saved.last()
        assertEquals("i-2", other.id)
        assertEquals("a-5", other.articleId)
        assertNull(other.customerReference)
    }
}
