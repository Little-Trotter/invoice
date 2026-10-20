package de.littletrotter.application.reference

import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.ResolvedB2bFieldValue
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.documents.PdfRenderableText
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Owns [B2bFieldValue]/[RecipientB2bFieldValue] rows (per-owner data), the resolution
 * chain (invoice override > party value > profile default) and the two copy paths that populate
 * values from a template. Depends on [B2bProfileFieldDefinitionService] for "which fields does
 * this profile define" — never the reverse.
 */
@ApplicationScoped
class B2bFieldValueService @Inject constructor(
    private val fieldValueStore: B2bFieldValueStore,
    // The per-customer field-value store for RECIPIENT-placed fields.
    private val recipientRepository: RecipientRepository,
    private val recipientFieldValues: RecipientFieldValueReadRepository,
    private val invoiceRepository: InvoiceRepository,
    private val fieldDefinitionService: B2bProfileFieldDefinitionService,
    private val partyFieldValues: B2bPartyFieldValues,
    private val auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
) {
    /** Current [B2bFieldValue]s of one owner, keyed by [B2bTerm.officialCode]. */
    fun findFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long): Map<String, String?> =
        fieldValueStore.findByOwner(ownerType, ownerId)
            .mapNotNull { value -> value.term?.officialCode?.let { it to value.value } }
            .toMap()

    /** Batched sibling of [findFieldValues] for a page of owners at once (e.g. the invoice
     *  positions grid, rendering BT-156 per row) — one query for the whole page instead of one
     *  per row. Owners with no stored values are simply absent from the result map. */
    fun findFieldValuesByOwners(ownerType: B2bFieldOwnerType, ownerIds: List<Long>): Map<Long, Map<String, String?>> =
        fieldValueStore.findByOwners(ownerType, ownerIds)
            .groupBy { it.ownerId }
            .mapValues { (_, values) ->
                values.mapNotNull { value -> value.term?.officialCode?.let { it to value.value } }.toMap()
            }

    /**
     * Replaces the given [values] (keyed by [B2bTerm.officialCode]) for
     * [ownerType]/[ownerId]: a blank/`null` value deletes the row (nothing to store), a non-blank
     * one upserts it. Terms not present in [values] are left untouched — this is a partial update
     * over the *submitted* fields, not a full replace of every value the owner ever had. A key
     * that does not parse to a known [B2bTerm] is silently ignored.
     */
    @Transactional
    fun saveFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>) {
        values.forEach { (btCode, rawValue) ->
            val term = B2bTerm.fromOfficialCodeOrNull(btCode) ?: return@forEach
            // Leading/trailing whitespace is not itself content: trimmed once here,
            // before the blank check below and before it is ever persisted.
            val value = rawValue?.trim()
            // Printed on the PDF whose fonts lack e.g. emoji: reject at entry so XML and PDF agree.
            PdfRenderableText.firstOffending(value).takeIf { it.isNotEmpty() }?.let { bad ->
                throw InvalidParameterException(
                    "Das B2B-Feld ${term.officialCode} enthält Zeichen, die im PDF nicht darstellbar sind: ${bad.joinToString(" ")}",
                )
            }
            val existing = fieldValueStore.findByOwnerAndBtCode(ownerType, ownerId, term)
            if (value.isNullOrBlank()) {
                existing?.id?.let { fieldValueStore.delete(it) }
            } else {
                val target = existing ?: B2bFieldValue.forOwner(ownerType, ownerId, term)
                target.changeValue(value)
                target.validate()
                fieldValueStore.save(target)
            }
        }
    }

    /**
     * [recipientId]'s current per-customer field values, keyed by [B2bTerm.officialCode]
     * (the same shape [findFieldValues] returns for the generic `b2b_field_value` owners) —
     * resolved against [profileId]'s own fields, since a `recipient_b2b_field_value` row
     * references a [B2bProfileField] by row id, not by term.
     */
    fun findRecipientFieldValues(recipientId: Long, profileId: Long): Map<String, String?> {
        val btCodeByFieldId = fieldDefinitionService.findFields(profileId).mapNotNull { field ->
            val id = field.id ?: return@mapNotNull null
            val code = field.term?.officialCode ?: return@mapNotNull null
            id to code
        }.toMap()
        return recipientFieldValues.findValues(recipientId)
            .mapNotNull { (fieldId, value) -> btCodeByFieldId[fieldId]?.let { bt -> bt to value } }
            .toMap()
    }

    /**
     * Replaces [recipientId]'s per-customer field [values] (keyed by [B2bTerm
     * .officialCode]), scoped to [profileId]'s own fields: same partial-update semantics as
     * [saveFieldValues]. A code with no matching field on [profileId] is silently ignored — a
     * stale submission from before a profile switch must not create an orphaned row.
     */
    @Transactional
    fun saveRecipientFieldValues(recipientId: Long, profileId: Long, values: Map<String, String?>) {
        val fieldByBtCode = fieldDefinitionService.findFields(profileId).mapNotNull { field ->
            field.term?.officialCode?.let { it to field }
        }.toMap()
        val recipient = recipientRepository.findById(recipientId)
            ?: throw InvalidParameterException("Kunde mit ID $recipientId existiert nicht.")
        val before = recipient.b2bFieldValueList.map { it.profileFieldId to it.value }
        values.forEach { (btCode, value) ->
            val field = fieldByBtCode[btCode] ?: return@forEach
            val fieldId = field.id ?: return@forEach
            recipient.recordB2bFieldValue(fieldId, value)?.stampForPersist(auditActorProvider.currentActor())
        }
        // Reported at the persistence boundary like every other master data write, so the
        // customer event carries the new values.
        if (recipient.b2bFieldValueList.map { it.profileFieldId to it.value } != before) recipientRepository.save(recipient)
    }

    /**
     * The effective wire value of every RECIPIENT-placed FREE HEADER field of [invoice]'s
     * own B2B profile, keyed by [B2bTerm.officialCode]: an actually-stored invoice-level value
     * wins, then the recipient's own stored value, then the field's own
     * [B2bProfileField.defaultValue]. Consumed by `InvoiceDtoMapper` so a RECIPIENT-placed term
     * still reaches the e-invoice export once it resolves to a value.
     */
    fun resolvedRecipientFieldValues(invoice: Invoice): Map<String, String?> {
        val profileId = invoice.b2bProfile?.id ?: return emptyMap()
        val invoiceId = invoice.id ?: return emptyMap()
        return fieldDefinitionService.findRecipientFields(profileId).mapNotNull { field ->
            val term = field.term ?: return@mapNotNull null
            term.officialCode to resolveB2bFieldChain(invoice, invoiceId, field, term).resolved
        }.toMap()
    }

    /**
     * The one resolution chain [resolvedRecipientFieldValues] (export) and
     * [resolvedGuidedFieldValues] (guided entry-pane step 02) both build on: an invoice-level
     * override ([B2bFieldOwnerType.INVOICE]) wins over the field's own party value (resolved via
     * [B2bPartyFieldValues]), which wins over [B2bProfileField.defaultValue].
     */
    private fun resolveB2bFieldChain(
        invoice: Invoice,
        invoiceId: Long,
        field: B2bProfileField,
        term: B2bTerm,
    ): B2bFieldChain {
        val invoiceValue = fieldValueStore.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, invoiceId, term)?.value
        val partyValue = partyFieldValues.partyValue(invoice, field, term)
        return B2bFieldChain(invoiceValue, partyValue, field.defaultValue)
    }

    private data class B2bFieldChain(val invoiceValue: String?, val partyValue: String?, val defaultValue: String?) {
        val resolved: String?
            get() = invoiceValue?.takeIf { it.isNotBlank() } ?: partyValue?.takeIf { it.isNotBlank() } ?: defaultValue
        val origin: B2bFieldValueOrigin
            get() = when {
                !invoiceValue.isNullOrBlank() -> B2bFieldValueOrigin.INVOICE
                !partyValue.isNullOrBlank() -> B2bFieldValueOrigin.PARTY
                else -> B2bFieldValueOrigin.DEFAULT
            }
        /** The value [resolved] would carry without any invoice-level override — the write-back
         *  rule's comparison target. */
        val inherited: String?
            get() = partyValue?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    /**
     * The guided entry-pane step 02's single source of truth: every `FREE` field of
     * [invoiceId]'s own B2B profile that is placed [B2bPlacement.INVOICE], [B2bPlacement.SELLER]
     * at `HEADER`/`SELLER_PARTY` term level, or [B2bPlacement.RECIPIENT] — resolved through
     * [resolveB2bFieldChain], keyed by [B2bTerm.officialCode]. `null`/no profile resolves to an
     * empty map.
     */
    fun resolvedGuidedFieldValues(invoiceId: Long): Map<String, ResolvedB2bFieldValue> {
        val invoice = invoiceRepository.findById(invoiceId) ?: return emptyMap()
        val profileId = invoice.b2bProfile?.id ?: return emptyMap()
        val fields = fieldDefinitionService.findInvoiceHeaderFields(profileId) +
            fieldDefinitionService.findSellerFields(profileId).filter { field ->
                field.term?.level == B2bTermLevel.HEADER || field.term?.level == B2bTermLevel.SELLER_PARTY
            } +
            fieldDefinitionService.findRecipientFields(profileId)
        return fields.mapNotNull { field ->
            val term = field.term ?: return@mapNotNull null
            if (term.binding != B2bTermBinding.FREE) return@mapNotNull null
            val chain = resolveB2bFieldChain(invoice, invoiceId, field, term)
            term.officialCode to ResolvedB2bFieldValue(chain.resolved, chain.origin, chain.inherited)
        }.toMap()
    }

    /**
     * The B2B profile id of [invoiceId] itself, resolved
     * server-side. The UI's reconstructed `Invoice` is not a reliable carrier of the lazy
     * `b2bProfile` relation, so every capture path reads it through this method instead of
     * trusting the caller. `null` when the invoice is absent or carries no B2B profile.
     */
    fun findProfileIdForInvoice(invoiceId: Long): Long? =
        invoiceRepository.findById(invoiceId)?.b2bProfile?.id

    /**
     * Copies an article's `LINE`-level template values
     * (`owner_type = ARTICLE`) onto a newly created/updated invoice item (`owner_type =
     * INVOICE_ITEM`), restricted to the terms that actually appear in the field list of
     * [invoiceId]'s own B2B profile.
     */
    @Transactional
    fun copyLineTemplateValuesToInvoiceItem(articleId: Long, invoiceItemId: Long, invoiceId: Long) {
        val invoiceProfileId = invoiceRepository.findById(invoiceId)?.b2bProfile?.id ?: return
        val allowedTerms = fieldDefinitionService.findFields(invoiceProfileId)
            .filter { it.term?.level == B2bTermLevel.LINE }
            .mapNotNull { it.term?.officialCode }
            .toSet()
        if (allowedTerms.isEmpty()) return
        val templateValues = findFieldValues(B2bFieldOwnerType.ARTICLE, articleId).filterKeys { it in allowedTerms }
        if (templateValues.isEmpty()) return
        saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, invoiceItemId, templateValues)
    }
}
