package de.littletrotter.domain.reference.converters

import de.littletrotter.shared.b2b.B2bTerm
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for [B2bTerm], persisted as the SMALLINT [B2bTerm.code] (the official EN
 * 16931 BT number, e.g. `19` for `BT-19`), never the enum ordinal — unlike
 * [de.littletrotter.domain.shared.converters.OrdinalEnumConverter] elsewhere in this package,
 * [B2bTerm]'s declaration order does not match its BT numbering. A
 * future ZUGFeRD extension term (`BT-X-nn`) would reserve the code block `10000 + nn`
 * (BTE-Konventionen); not allocated by this converter, just documented here
 * so a future addition knows where to start.
 *
 * Reads via [B2bTerm.fromCodeOrNull], not the throwing [B2bTerm.fromCode]: a row whose code no
 * longer names a known term (e.g. left over from a since-removed catalog term) must load as `null`
 * and be dropped by the null-safe call sites that read it, never fail the whole read — the same
 * defensive contract [de.littletrotter.application.reference.B2bProfileFieldDefinitionService
 * .missingMandatorySellerFields] already documents for a stale field.
 */
@Converter(autoApply = false)
class B2bTermConverter : AttributeConverter<B2bTerm, Int> {
    override fun convertToDatabaseColumn(attribute: B2bTerm?): Int? = attribute?.code

    override fun convertToEntityAttribute(dbData: Int?): B2bTerm? = dbData?.let { B2bTerm.fromCodeOrNull(it) }
}
