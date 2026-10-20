package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.shared.catalogue.InvoiceItemCategory

/**
 * Input port for reading number ranges: the catalogue tab's grid with each range's current
 * counter value, and the non-binding number proposals (a `peek`, nothing is allocated) the
 * article and seller forms show before the first save.
 */
interface NumberRangeQueryPort {
    fun findNumberRanges(pageIndex: Int = 0, pageSize: Int = 60, filterText: String? = null): List<NumberRangeDto>

    fun countNumberRanges(filterText: String? = null): Long

    /** The header counter's "N inaktiv" half. */
    fun countInactiveNumberRanges(filterText: String? = null): Long

    fun findNumberRangeById(id: Long): NumberRangeDto?

    /** `true` once at least one number was drawn from the range, which blocks hard deletion. */
    fun isNumberRangeInUse(id: Long): Boolean

    /** The last number allocated for [subject], or `null` before the first allocation. */
    fun numberRangeCurrentValue(subject: NumberRangeSubject): Long?

    /** The article range's prefix may derive from the article's [category]. */
    fun proposeArticleNumber(category: InvoiceItemCategory): String?

    /** The seller range's prefix may derive from the seller's [sellerName] initials. */
    fun proposeSellerNumber(sellerName: String): String?
}
