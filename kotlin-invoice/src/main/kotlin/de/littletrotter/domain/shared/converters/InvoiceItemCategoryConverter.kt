package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.catalogue.InvoiceItemCategory
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for [InvoiceItemCategory], persisted as [InvoiceItemCategory.code], never
 * the enum ordinal: the code sequence has a deliberate gap (`2`-`4`, the deleted TRAVEL_EXPENSE/
 * EXPENSE/PER_DIEM categories) that an ordinal-based [OrdinalEnumConverter] could not
 * express. Reads via [InvoiceItemCategory.fromCodeOrNull] rather than the throwing `fromCode`:
 * a row whose code no longer names a known category must fail loudly at the CHECK constraint
 * (`chk_invoice_item_category`/`chk_article_category`) on write, but a read that somehow meets a
 * stale value should not crash the whole page — same defensive read contract
 * [de.littletrotter.domain.reference.converters.B2bTermConverter] documents for its own
 * explicit-code column.
 */
@Converter(autoApply = false)
class InvoiceItemCategoryConverter : AttributeConverter<InvoiceItemCategory, Int> {
    override fun convertToDatabaseColumn(attribute: InvoiceItemCategory?): Int? = attribute?.code

    override fun convertToEntityAttribute(dbData: Int?): InvoiceItemCategory? = dbData?.let { InvoiceItemCategory.fromCodeOrNull(it) }
}
