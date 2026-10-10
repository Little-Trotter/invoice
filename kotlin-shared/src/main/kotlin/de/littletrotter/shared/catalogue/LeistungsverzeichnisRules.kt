package de.littletrotter.shared.catalogue

/** Category-specific unit rules for the Leistungsverzeichnis. */
object LeistungsverzeichnisRules {
    fun allowedUnits(category: InvoiceItemCategory): Set<InvoiceItemUnit> = when (category) {
        // Material is deliberately limited to the physical/countable units used by the
        // catalogue.  Keep this list explicit: adding a new unit to InvoiceItemUnit must not
        // silently make it valid for material positions.
        InvoiceItemCategory.PRODUCT -> setOf(
            InvoiceItemUnit.PIECE,
            InvoiceItemUnit.METER,
            InvoiceItemUnit.SQUARE_METER,
            InvoiceItemUnit.CUBIC_METER,
            InvoiceItemUnit.KILOMETER,
            InvoiceItemUnit.LITER,
            InvoiceItemUnit.KILOGRAM,
            InvoiceItemUnit.APPROACH,
            InvoiceItemUnit.GRAM,
            InvoiceItemUnit.LINEAR_METER,
        )
        InvoiceItemCategory.UNIT_PRICE -> InvoiceItemUnit.entries.filter {
            (it.domain in setOf(InvoiceItemUnitDomain.AREA, InvoiceItemUnitDomain.LENGTH, InvoiceItemUnitDomain.VOLUME) &&
                it != InvoiceItemUnit.LITER) ||
                it == InvoiceItemUnit.FLAT_RATE
        }.toSet()
        InvoiceItemCategory.SERVICE -> setOf(InvoiceItemUnit.HOUR, InvoiceItemUnit.FLAT_RATE, InvoiceItemUnit.DAY, InvoiceItemUnit.APPROACH)
        InvoiceItemCategory.TRIP -> setOf(InvoiceItemUnit.FLAT_RATE)
    }

    fun isAllowed(category: InvoiceItemCategory, unit: InvoiceItemUnit): Boolean = unit in allowedUnits(category)

    /** Single validation contract shared by catalogue maintenance and invoice position commands. */
    fun requireAllowed(category: InvoiceItemCategory, unit: InvoiceItemUnit) {
        require(isAllowed(category, unit)) { "Invalid unit for catalogue category." }
    }
}
