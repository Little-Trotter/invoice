package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import java.time.LocalDate

/**
 * Input port of the "add/edit an invoice item" use case: [de.littletrotter.ui.view.invoice.BaseItemDialog]
 * with its [de.littletrotter.ui.view.invoice.GoodsItemDialog]/[de.littletrotter.ui.view.invoice.ServiceItemDialog]
 * subclasses, their B2B/article helper functions and [de.littletrotter.ui.view.invoice.InvoiceItemDialogs]
 * read exactly these methods: the selectable tax rates, the item's B2B line fields and values, and
 * the article catalogue.
 *
 * Not marked as a [de.littletrotter.application.command.CommandEntryPoint]: [saveB2bFieldValues]
 * and [copyB2bArticleTemplateToInvoiceItem] are internal writes on the same item-entry
 * read/write boundary, and every method keeps returning exactly what the underlying reference-data
 * facades return, so `CommandBoundaryArchitectureTest`'s entity-leak check does not apply.
 */
interface ItemEntryQueryPort {
    /** See [de.littletrotter.application.reference.port.inbound.TaxRateQueryPort.findAllTaxRates]. */
    fun findAllTaxRates(): List<TaxRateDto>

    /** See [de.littletrotter.application.reference.port.inbound.TaxRateQueryPort.findReferenceTaxRate]. */
    fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto

    /** `FREE` `LINE` fields of [profileId] that render on a position — see
     *  [de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort.findB2bInvoiceItemFields]. */
    fun findB2bInvoiceItemFields(profileId: Long?): List<B2bProfileFieldDto>

    /** Resolves an invoice's own B2B profile id server-side — see
     *  [de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort.findB2bProfileIdForInvoice]. */
    fun findB2bProfileIdForInvoice(invoiceId: Long): Long?

    /** See [de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort.findB2bFieldValues]. */
    fun findB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long): Map<String, String?>

    /** See [de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase.saveB2bFieldValues]. */
    fun saveB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>)

    /** See
     *  [de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase.copyB2bArticleTemplateToInvoiceItem]. */
    fun copyB2bArticleTemplateToInvoiceItem(articleId: Long, invoiceItemId: Long, invoiceId: Long)

    /** See [de.littletrotter.application.reference.port.inbound.ArticleQueryPort.findArticleById]. */
    fun findArticleById(id: Long): ArticleDto?

    /** See [de.littletrotter.application.reference.port.inbound.ArticleQueryPort.countArticlesForSelect]. */
    fun countArticlesForSelect(category: InvoiceItemCategory, filter: String? = null): Int

    /** See [de.littletrotter.application.reference.port.inbound.ArticleQueryPort.findArticlesForSelect]. */
    fun findArticlesForSelect(category: InvoiceItemCategory, filter: String? = null, offset: Int = 0, limit: Int = 60): List<ArticleDto>

    /** See [de.littletrotter.application.reference.port.inbound.ArticleQueryPort.findArticleRecipientRef]. */
    fun findArticleRecipientRef(articleId: Long, recipientId: Long): ArticleRecipientRefDto?
}
