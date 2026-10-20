package de.littletrotter.domain.masterdata

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import java.math.BigDecimal

/**
 * The plain editable fields of an [Article]; see [Article.update]. Tax variants and recipient
 * mappings are children with their own reconciliation and are not part of it. Defaults equal the
 * values of a freshly created article.
 */
data class ArticleDetails(
    val name: String,
    val description: String? = null,
    val category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
    val defaultUnit: InvoiceItemUnit = InvoiceItemUnit.HOUR,
    val defaultQuantity: BigDecimal = BigDecimal.ONE,
    val defaultUnitPriceNet: Money = Money.ZERO,
    val defaultTaxRateRef: TaxRate? = null,
    val articleNumber: ArticleNumber? = null,
    val shortHash: String? = null,
)
