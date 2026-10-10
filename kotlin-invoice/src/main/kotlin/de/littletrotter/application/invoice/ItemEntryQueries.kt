package de.littletrotter.application.invoice

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate

/** Implements [ItemEntryQueryPort] on top of [TaxRateQueryPort]'s, [B2bFieldDefinitionQueryPort]'s,
 *  [B2bFieldValueQueryPort]'s and [ArticleQueryPort]'s existing reads/writes. */
@ApplicationScoped
class ItemEntryQueries @Inject constructor(
    private val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort,
    private val b2bFieldValueQueries: B2bFieldValueQueryPort,
    private val manageB2bFieldValue: ManageB2bFieldValueUseCase,
    private val articleQueries: ArticleQueryPort,
    private val taxRateQueries: TaxRateQueryPort,
) : ItemEntryQueryPort {

    override fun findAllTaxRates(): List<TaxRateDto> = taxRateQueries.findAllTaxRates()

    override fun findReferenceTaxRate(country: Country, date: LocalDate): TaxRateDto =
        taxRateQueries.findReferenceTaxRate(country, date)

    override fun findB2bInvoiceItemFields(profileId: Long?): List<B2bProfileFieldDto> =
        b2bFieldDefinitionQueries.findB2bInvoiceItemFields(profileId)

    override fun findB2bProfileIdForInvoice(invoiceId: Long): Long? = b2bFieldValueQueries.findB2bProfileIdForInvoice(invoiceId)

    override fun findB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long): Map<String, String?> =
        b2bFieldValueQueries.findB2bFieldValues(ownerType, ownerId)

    override fun saveB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>) =
        manageB2bFieldValue.saveB2bFieldValues(ownerType, ownerId, values)

    override fun copyB2bArticleTemplateToInvoiceItem(articleId: Long, invoiceItemId: Long, invoiceId: Long) =
        manageB2bFieldValue.copyB2bArticleTemplateToInvoiceItem(articleId, invoiceItemId, invoiceId)

    override fun findArticleById(id: Long): ArticleDto? = articleQueries.findArticleById(id)

    override fun countArticlesForSelect(category: InvoiceItemCategory, filter: String?): Int =
        articleQueries.countArticlesForSelect(category, filter)

    override fun findArticlesForSelect(category: InvoiceItemCategory, filter: String?, offset: Int, limit: Int): List<ArticleDto> =
        articleQueries.findArticlesForSelect(category, filter, offset, limit)

    override fun findArticleRecipientRef(articleId: Long, recipientId: Long): ArticleRecipientRefDto? =
        articleQueries.findArticleRecipientRef(articleId, recipientId)
}
