package de.littletrotter.shared.mail

/**
 * Which placeholder keys [MailComposeRequest.placeholderValues] may legally carry for a given
 * [MailDocumentKind], and what [MailPlaceholderValueType] each one must be sent as.
 *
 * Direction: neither — read by both sides. The host uses it to build a request its own tests can
 * check against before it ever reaches the wire ([validate]); the component's `Placeholder`
 * whitelist ([de.littletrotter.email.core]) is expected to know the same keys for the kinds it
 * renders templates for, kept in step by a test at the component's own adapter boundary rather than
 * by a shared type (that boundary is exactly where each side is allowed to know the other's shape).
 *
 * Owner: this contract.
 *
 * **Why this belongs beside the wire codec and is not "domain logic".** The package KDoc rules out
 * entities and service interfaces, not shape checks: [MailWireJson]'s `requiredText`/`requiredEnum`
 * already reject a structurally wrong payload before either side's domain ever sees it, and
 * [validate] is the same kind of check one level up — "is this key known for this kind, and is its
 * type right" rather than any business rule about invoices or quotes. Neither side needs to know
 * the *other's* whitelist to reach that answer; each needs only this table and its own request.
 *
 * **Additive only, like everything else in a frozen contract.** A new [MailDocumentKind] constant
 * and its [entries] is how a third dispatch kind is added later — never a change to an existing
 * kind's already-shipped keys or types, which a deployed host may already be sending.
 */
object MailPlaceholderCatalogue {

    /** One legal placeholder for a [MailDocumentKind]. */
    data class Entry(
        /** The literal name inside `{{ }}` — must match the wire key in [MailComposeRequest.placeholderValues]. */
        val key: String,
        /** The wire type [key]'s value must carry. */
        val type: MailPlaceholderValueType,
        /** Whether a compose request for this kind must include [key] at all. */
        val required: Boolean,
    )

    private val INVOICE_DISPATCH_ENTRIES = listOf(
        Entry("invoiceNumber", MailPlaceholderValueType.STRING, required = true),
        Entry("invoiceDate", MailPlaceholderValueType.DATE, required = true),
        Entry("dueDate", MailPlaceholderValueType.DATE, required = false),
        Entry("grossTotal", MailPlaceholderValueType.MONEY, required = true),
        Entry("recipientName", MailPlaceholderValueType.STRING, required = true),
        Entry("senderName", MailPlaceholderValueType.STRING, required = false),
        Entry("senderPhone", MailPlaceholderValueType.STRING, required = false),
        Entry("senderEmail", MailPlaceholderValueType.STRING, required = false),
        Entry("senderWebsite", MailPlaceholderValueType.STRING, required = false),
        Entry("iban", MailPlaceholderValueType.STRING, required = false),
    )

    private val QUOTE_DISPATCH_ENTRIES = listOf(
        Entry("recipientName", MailPlaceholderValueType.STRING, required = true),
        Entry("quoteNumber", MailPlaceholderValueType.STRING, required = true),
        Entry("quoteDate", MailPlaceholderValueType.DATE, required = true),
        Entry("validUntil", MailPlaceholderValueType.DATE, required = true),
        Entry("totalGross", MailPlaceholderValueType.MONEY, required = true),
        Entry("senderName", MailPlaceholderValueType.STRING, required = true),
        Entry("senderPhone", MailPlaceholderValueType.STRING, required = false),
        Entry("senderEmail", MailPlaceholderValueType.STRING, required = false),
        Entry("senderWebsite", MailPlaceholderValueType.STRING, required = false),
    )

    private val BY_KIND: Map<MailDocumentKind, List<Entry>> = mapOf(
        MailDocumentKind.INVOICE_DISPATCH to INVOICE_DISPATCH_ENTRIES,
        MailDocumentKind.QUOTE_DISPATCH to QUOTE_DISPATCH_ENTRIES,
    )

    /** Every legal placeholder for [kind], in the order a template of that kind is expected to use them. */
    fun entries(kind: MailDocumentKind): List<Entry> = BY_KIND.getValue(kind)

    /**
     * Human-readable violations of [values] against [kind]'s entries — empty means [values] is a
     * legal placeholder map for [kind]. Never throws: a caller building a request piece by piece
     * (or a test asserting on a mapper's output) wants the full list of what is wrong, not the
     * first one, the same reason [PlaceholderRenderer][de.littletrotter.email.core.PlaceholderRenderer]'s
     * own exceptions on the component side list every offending name rather than just one.
     */
    fun validate(kind: MailDocumentKind, values: Map<String, MailPlaceholderValue>): List<String> {
        val byKey = entries(kind).associateBy { it.key }
        val violations = mutableListOf<String>()
        values.forEach { (key, value) ->
            val entry = byKey[key]
            when {
                entry == null -> violations += "unknown placeholder key '$key' for $kind"
                entry.type != value.type ->
                    violations += "placeholder '$key' is ${value.type} but $kind expects ${entry.type}"
            }
        }
        byKey.values.filter { it.required && it.key !in values }
            .forEach { violations += "missing required placeholder '${it.key}' for $kind" }
        return violations
    }
}
