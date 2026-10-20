package de.littletrotter.application.dto

import de.littletrotter.domain.shared.SortDirection

/** The grid column key (`name`, `cityCountry` or `invoicePrefix`) the sender-profile list is sorted by, and the direction. */
data class SenderProfileSort(val field: String, val direction: SortDirection)
