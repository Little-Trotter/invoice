package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile

/**
 * The level being checked. Rule evaluation is **not** invoice-only: data and format matter at
 * every level — a seller without a VAT id is already wrong at the seller, not first at an
 * invoice that uses it. The invoice is the last stage of this chain, not the only one.
 *
 * Practically, the same rule set with the same `ruleId` and `messageKey` can apply at several
 * levels — a seller's mandatory fields can live in the same rule set as the invoice's.
 */
enum class ComplianceLevel { SELLER, BUYER, ARTICLE, INVOICE }

/**
 * The subject being checked. A [ComplianceRuleSet] receives this instead of a fixed `Invoice`
 * parameter — otherwise every level would need its own evaluation mechanism.
 */
sealed interface ComplianceSubject {
    val level: ComplianceLevel

    data class OfSeller(val profile: SenderProfile) : ComplianceSubject {
        override val level get() = ComplianceLevel.SELLER
    }

    data class OfBuyer(val recipient: Recipient) : ComplianceSubject {
        override val level get() = ComplianceLevel.BUYER
    }

    data class OfArticle(val article: Article) : ComplianceSubject {
        override val level get() = ComplianceLevel.ARTICLE
    }

    /**
     * The invoice with everything attached to it. A rule set at this level also sees the
     * seller, recipient, and line items — but evaluates them in the context of *this document*,
     * not as master data: on the invoice, the frozen state counts; in master data, the current
     * one.
     */
    data class OfInvoice(val invoice: Invoice) : ComplianceSubject {
        override val level get() = ComplianceLevel.INVOICE
    }
}
