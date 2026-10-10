package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.shared.masterdata.MasterdataSubjects

/**
 * The master data aggregates a replica mirrors. [type] is the outbox aggregate type and the
 * entity type of the snapshot; the two subjects carry the event of a changed and of a removed
 * aggregate.
 */
enum class MasterdataAggregate(
    val type: String,
    val upsertedSubject: String,
    val deletedSubject: String,
) {
    CUSTOMER("customer", MasterdataSubjects.CUSTOMER_UPSERTED, MasterdataSubjects.CUSTOMER_DELETED),
    SELLER("seller", MasterdataSubjects.SELLER_UPSERTED, MasterdataSubjects.SELLER_DELETED),
    ARTICLE("article", MasterdataSubjects.ARTICLE_UPSERTED, MasterdataSubjects.ARTICLE_DELETED),
    TAX_RATE("taxrate", MasterdataSubjects.TAX_RATE_UPSERTED, MasterdataSubjects.TAX_RATE_DELETED),
    PAYMENT_TERM("paymentterm", MasterdataSubjects.PAYMENT_TERM_UPSERTED, MasterdataSubjects.PAYMENT_TERM_DELETED),
    B2B_PROFILE("b2bprofile", MasterdataSubjects.B2B_PROFILE_UPSERTED, MasterdataSubjects.B2B_PROFILE_DELETED),
    NUMBER_RANGE("numberrange", MasterdataSubjects.NUMBER_RANGE_UPSERTED, MasterdataSubjects.NUMBER_RANGE_DELETED),
}
