package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.tax.VatCategory
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Persists [VatCategory] as its UNTDID 5305 [VatCategory.code] (`S`, `AE`, ...), the value the
 * tax-rate check constraint accepts, so the snapshot column is byte-identical to the former
 * plain-string mapping. A stored code outside the enum fails loudly: silently dropping it would
 * change what the XML and PDF state about the position.
 */
@Converter(autoApply = false)
class VatCategoryConverter : AttributeConverter<VatCategory, String> {
    override fun convertToDatabaseColumn(attribute: VatCategory?): String? = attribute?.code

    override fun convertToEntityAttribute(dbData: String?): VatCategory? = dbData?.let { code ->
        VatCategory.fromCodeOrNull(code) ?: error("Unknown VAT category code '$code' in stored invoice item")
    }
}
