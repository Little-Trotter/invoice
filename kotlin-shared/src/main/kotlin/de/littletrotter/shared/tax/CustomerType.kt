package de.littletrotter.shared.tax

/** Drives which fields are mandatory: CONSUMER doesn't need VAT ID or IBAN. */
enum class CustomerType { BUSINESS, CONSUMER }
