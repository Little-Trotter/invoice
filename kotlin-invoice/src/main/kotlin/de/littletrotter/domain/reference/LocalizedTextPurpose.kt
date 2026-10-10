package de.littletrotter.domain.reference

/**
 * What a [LocalizedTextGroup] is used for. Persisted as the ordinal
 * ([de.littletrotter.domain.reference.converters.LocalizedTextPurposeConverter]); the schema's own
 * `chk_localized_text_group_purpose` CHECK mirrors the entry list and must be extended in the same
 * commit as a new constant here.
 *
 * [maxLength] is the slot's own length rule — [localized_text.value][LocalizedTextEntry.value] is
 * `TEXT` in the schema (shared by every purpose), so a per-purpose limit lives here instead of on
 * the column, enforced by [LocalizedTextGroup.validate] and mirrored as a UI `maxLength`.
 */
enum class LocalizedTextPurpose(val maxLength: Int) {
    /** The seller's own free-text note printed on the invoice, per language. The limit
     *  carries over unchanged from the former `sender_profile.footer_note VARCHAR(200)`. */
    SELLER_INVOICE_NOTE(200),

    /** A payment term's display name, per language. The limit carries over unchanged from
     *  `payment_term.name`/`payment_term.english_name VARCHAR(150)`. */
    PAYMENT_TERM_NAME(150),

    /** The seller's notice for private customers on craft-trade invoices, per language. Longer
     *  than the general note because the default wording already needs about 170 characters. */
    SELLER_CONSUMER_NOTE(400),
}
