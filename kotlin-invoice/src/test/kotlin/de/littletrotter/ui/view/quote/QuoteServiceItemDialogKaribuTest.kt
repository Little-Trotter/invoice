package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.VatCategory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class QuoteServiceItemDialogKaribuTest {

    private val articles: QuoteArticleLookupQueryPort = mock()
    private val saved = mutableListOf<QuoteItemInput>()

    private val workshop = QuoteArticleOption(
        "a-9", "A-9", "Workshop", InvoiceItemCategory.SERVICE, InvoiceItemUnit.DAY, Money.of("800.00"), VatCategory.STANDARD, BigDecimal("19.00"),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(articles.articles(any(), any(), any(), any())).thenReturn(listOf(workshop))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun dialog(existing: de.littletrotter.application.quote.port.inbound.QuoteItem? = null) =
        QuoteServiceItemDialog(testHost(), articles, "q-1", existing) { saved += it }.also { it.open() }

    @Test
    fun `the unit is a radio group of hour day flat rate and call out`() {
        val dialog = dialog()

        assertEquals(
            listOf(InvoiceItemUnit.HOUR, InvoiceItemUnit.DAY, InvoiceItemUnit.FLAT_RATE, InvoiceItemUnit.APPROACH),
            dialog.unitGroup.listDataView.items.toList(),
        )
        assertEquals(InvoiceItemUnit.HOUR, dialog.unitGroup.value)
        assertEquals("Stundensatz (€)", dialog.price.label)
        assertEquals("Leistungsbeschreibung / Tätigkeit", dialog.description.label)
    }

    @Test
    fun `the price label follows the unit`() {
        val dialog = dialog()

        dialog.unitGroup.value = InvoiceItemUnit.DAY
        assertEquals("Tagessatz (€)", dialog.price.label)
        dialog.unitGroup.value = InvoiceItemUnit.FLAT_RATE
        assertEquals("Pauschalbetrag (€)", dialog.price.label)
        dialog.unitGroup.value = InvoiceItemUnit.APPROACH
        assertEquals("Preis je Anfahrt (€)", dialog.price.label)
        assertTrue(dialog.quantity.isVisible)
    }

    @Test
    fun `a flat rate hides the quantity and is saved with quantity one`() {
        val dialog = dialog()
        dialog.unitGroup.value = InvoiceItemUnit.FLAT_RATE
        dialog.quantity.value = 5.0
        dialog.description.value = "Installation"
        dialog.price.value = 250.0

        assertFalse(dialog.quantity.isVisible)
        assertTrue(visibleText(dialog.preview).contains("250,00"))
        dialog.saveButton._click()

        val input = saved.single()
        assertEquals(BigDecimal.valueOf(1.0), input.quantity)
        assertEquals(InvoiceItemUnit.FLAT_RATE, input.unit)
        assertEquals(InvoiceItemCategory.SERVICE, input.category)
        assertEquals(Money.of("250.00"), input.unitPrice)
        assertFalse(dialog.dialog.isOpened)
    }

    @Test
    fun `an hourly line is saved with its quantity`() {
        val dialog = dialog()
        dialog.description.value = "Beratung"
        dialog.quantity.value = 2.5
        dialog.price.value = 100.0
        dialog.save()

        assertEquals(BigDecimal.valueOf(2.5), saved.single().quantity)
        assertEquals(InvoiceItemUnit.HOUR, saved.single().unit)
    }

    @Test
    fun `description quantity and price are required`() {
        val dialog = dialog()
        assertFalse(dialog.save())
        assertTrue(dialog.description.isInvalid)

        dialog.description.value = "Beratung"
        dialog.quantity.value = 0.0
        assertFalse(dialog.save())
        assertTrue(dialog.quantity.isInvalid)

        dialog.quantity.value = 1.0
        dialog.price.value = null
        assertFalse(dialog.save())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the article picker offers only services and a pick prefills unit price and tax`() {
        val dialog = dialog()
        (dialog.articleBox.dataProvider as DataProvider<QuoteArticleOption, String>).fetch(Query(0, 20, emptyList(), null, "work")).toList()
        verify(articles).articles("q-1", InvoiceItemCategory.SERVICE, "work", 20)

        dialog.articleBox.value = workshop

        assertEquals(InvoiceItemUnit.DAY, dialog.unitGroup.value)
        assertEquals("Tagessatz (€)", dialog.price.label)
        assertEquals("Workshop", dialog.description.value)
        assertEquals(800.0, dialog.price.value)
        assertEquals(19.0, dialog.vatRate.value)
        assertTrue(visibleText(dialog.preview).contains("952,00"))
    }

    @Test
    fun `an article unit outside the service units is not taken over`() {
        val dialog = dialog()
        dialog.articleBox.value = workshop.copy(unit = InvoiceItemUnit.PIECE)

        assertEquals(InvoiceItemUnit.HOUR, dialog.unitGroup.value)
    }

    @Test
    fun `editing a flat rate line shows it without quantity and keeps id and article link`() {
        val existing = quoteItem(1).copy(unit = InvoiceItemUnit.FLAT_RATE, quantity = BigDecimal.ONE)
        val dialog = dialog(existing)

        assertEquals(InvoiceItemUnit.FLAT_RATE, dialog.unitGroup.value)
        assertFalse(dialog.quantity.isVisible)
        assertEquals("Pauschalbetrag (€)", dialog.price.label)
        dialog.save()

        assertEquals("i-1", saved.single().id)
        assertEquals("a-1", saved.single().articleId)
    }
}
