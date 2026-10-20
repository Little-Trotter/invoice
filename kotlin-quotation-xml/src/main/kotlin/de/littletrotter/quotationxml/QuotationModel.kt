package de.littletrotter.quotationxml

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.VatCategory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Syntax-neutral snapshot of an offer as it is handed to a [QuotationWriter]. It carries the
 * values the issued offer states, not recomputed ones: totals are stated by the caller and the
 * business rules (see [QuotationSchematronValidator]) check that they are consistent.
 *
 * The model is deliberately free of any XML vocabulary so a second serialization (for example the
 * UN/CEFACT CrossIndustryOrder with type code 310) can be added as another [QuotationWriter]
 * without touching callers.
 */
data class QuotationModel(
    val number: String,
    val issueDate: LocalDate,
    /** Last day the offer binds the seller. */
    val validUntil: LocalDate,
    val currency: Currency,
    val seller: QuotationParty,
    val buyer: QuotationParty,
    val lines: List<QuotationLine>,
    val taxSubtotals: List<QuotationTaxSubtotal>,
    val totals: QuotationTotals,
    /** The non-binding clause ("freibleibend") stating that the offer creates no obligation to deliver. */
    val disclaimer: String,
    val notes: List<String> = emptyList(),
    /** Files that accompany the offer outside the XML, listed by name and checksum, never embedded. */
    val attachedDocuments: List<QuotationDocumentReference> = emptyList(),
) {
    init {
        require(number.isNotBlank()) { "Quotation number must not be blank" }
        require(lines.isNotEmpty()) { "A quotation needs at least one line" }
    }
}

/** A trading party; [vatId] is the seller's mandatory VAT identification number, optional for a buyer. */
data class QuotationParty(
    val name: String,
    val street: String,
    val postalCode: String,
    val city: String,
    val country: Country,
    val vatId: String? = null,
    val email: String? = null,
)

data class QuotationLine(
    val position: Int,
    val name: String,
    val quantity: BigDecimal,
    /** UN/ECE Recommendation 20 unit code, for example `C62` for pieces or `HUR` for hours. */
    val unitCode: String,
    val unitPrice: Money,
    val lineTotal: Money,
    val vatCategory: VatCategory,
    /** VAT rate in percent, for example `19`. */
    val vatRate: BigDecimal,
    val description: String? = null,
) {
    init {
        require(position > 0) { "Line position must be positive" }
        require(unitCode.isNotBlank()) { "Unit code must not be blank" }
    }
}

data class QuotationTaxSubtotal(
    val category: VatCategory,
    val rate: BigDecimal,
    val taxableAmount: Money,
    val taxAmount: Money,
)

data class QuotationTotals(
    val net: Money,
    val tax: Money,
    val gross: Money,
)

/**
 * A file that travels with the offer (for example as a mail attachment) and is referenced from the
 * XML by name and SHA-256 instead of being embedded, so the XML stays small and the file exists
 * exactly once. [id] identifies the reference, [fileName] names the accompanying file.
 */
data class QuotationDocumentReference(
    val id: String,
    val fileName: String,
    /** Lowercase hex SHA-256 of the file's bytes. */
    val sha256: String,
    val mimeCode: String,
    val description: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Document reference id must not be blank" }
        require(fileName.isNotBlank()) { "Document reference file name must not be blank" }
        require(SHA256_HEX.matches(sha256)) { "Document reference needs a lowercase hex SHA-256" }
        require(mimeCode.isNotBlank()) { "Document reference mime code must not be blank" }
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
