package de.littletrotter.application.invoice

import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import java.time.LocalDate

/** unit coverage for [ItemEntryQueries], the role interface the item-entry
 *  dialogs (BaseItemDialog/GoodsItemDialog/ServiceItemDialog) inject instead of the four
 *  underlying facades directly. */
class ItemEntryQueriesTest {

    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
    private val b2bFieldValueQueries: B2bFieldValueQueryPort = mock()
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase = mock()
    private val articleQueries: ArticleQueryPort = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val queries = ItemEntryQueries(b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue, articleQueries, taxRateQueries)

    @Test
    fun `findAllTaxRates delegates to the tax-rate facade`() {
        val rate = TaxRateDto(id = 3L)
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(rate))

        assertEquals(listOf(rate), queries.findAllTaxRates())
    }

    @Test
    fun `findReferenceTaxRate delegates to the tax-rate facade`() {
        val rate = TaxRateDto(id = 4L)
        val date = LocalDate.of(2026, 6, 1)
        whenever(taxRateQueries.findReferenceTaxRate(Country.LU, date)).thenReturn(rate)

        assertEquals(rate, queries.findReferenceTaxRate(Country.LU, date))
    }

    @Test
    fun `happy path - findB2bInvoiceItemFields delegates to the field-definition facade`() {
        val field = B2bProfileFieldDto(id = 1L)
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(5L)).thenReturn(listOf(field))

        assertEquals(listOf(field), queries.findB2bInvoiceItemFields(5L))
    }

    @Test
    fun `happy path - saveB2bFieldValues delegates to the field-value facade`() {
        val values = mapOf("BT-156" to "K-1")

        queries.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 7L, values)

        verify(manageB2bFieldValue).saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 7L, values)
    }

    @Test
    fun `happy path - findArticleById delegates to the article facade`() {
        val article = ArticleDto(id = 3L)
        whenever(articleQueries.findArticleById(3L)).thenReturn(article)

        assertEquals(article, queries.findArticleById(3L))
    }

    @Test
    fun `boundary - findB2bInvoiceItemFields for a null profile returns an empty list, not null`() {
        whenever(b2bFieldDefinitionQueries.findB2bInvoiceItemFields(null)).thenReturn(emptyList())

        assertTrue(queries.findB2bInvoiceItemFields(null).isEmpty())
    }

    @Test
    fun `boundary - findArticleById for an unknown id returns null`() {
        whenever(articleQueries.findArticleById(99L)).thenReturn(null)

        assertNull(queries.findArticleById(99L))
    }

    @Test
    fun `boundary - findArticlesForSelect and countArticlesForSelect pass a null filter through unchanged`() {
        whenever(articleQueries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, null, 0, 60)).thenReturn(emptyList())
        whenever(articleQueries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, null)).thenReturn(0)

        assertTrue(queries.findArticlesForSelect(InvoiceItemCategory.PRODUCT, null, 0, 60).isEmpty())
        assertEquals(0, queries.countArticlesForSelect(InvoiceItemCategory.PRODUCT, null))
    }

    @Test
    fun `boundary - findArticleRecipientRef delegates to the article facade`() {
        val ref = ArticleRecipientRefDto(2L, "K-1", null)
        whenever(articleQueries.findArticleRecipientRef(1L, 2L)).thenReturn(ref)

        assertEquals(ref, queries.findArticleRecipientRef(1L, 2L))
    }

    @Test
    fun `boundary - copyB2bArticleTemplateToInvoiceItem delegates to the field-value facade`() {
        queries.copyB2bArticleTemplateToInvoiceItem(1L, 2L, 3L)

        verify(manageB2bFieldValue).copyB2bArticleTemplateToInvoiceItem(1L, 2L, 3L)
    }

    @Test
    fun `boundary - findB2bProfileIdForInvoice returns null when the invoice has no profile`() {
        whenever(b2bFieldValueQueries.findB2bProfileIdForInvoice(4L)).thenReturn(null)

        assertNull(queries.findB2bProfileIdForInvoice(4L))
    }
}
