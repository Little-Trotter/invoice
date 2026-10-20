package de.littletrotter.domain.invoice

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.validation.constraints.Size

/**
 * The free-form texts an invoice owns: the header free text, the editable seller note and the
 * private-customer notice (each with the provenance that tells an untouched seller default from a
 * deliberate edit), and the BT codes already copied into the free text.
 *
 * Grouped into one value object so the note rules live next to the data they protect. Every
 * column keeps its exact pre-existing name. The origin and language columns are NOT NULL, so
 * Hibernate never hydrates this composite as `null`, unlike [InvoiceReferences].
 *
 * A plain class with body-level `var`s: Hibernate needs the implicit no-arg constructor that a
 * constructor-parameter `data class` does not produce.
 */
@Embeddable
class InvoiceTexts {

    @Column(name = "free_text", columnDefinition = "TEXT")
    var freeText: String? = null
        private set

    /** The invoice-owned, editable copy of the seller's language-specific note. */
    @Column(name = "invoice_note", columnDefinition = "TEXT")
    var invoiceNote: String? = null
        private set

    /** Explicit provenance makes an intentionally cleared note distinguishable from an untouched
     * empty seller default. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_note_origin", nullable = false, length = 20)
    var invoiceNoteOrigin: InvoiceNoteOrigin = InvoiceNoteOrigin.SELLER_DEFAULT
        private set

    @Column(name = "invoice_note_source_sender_id")
    var invoiceNoteSourceSenderProfileId: Long? = null
        private set

    @field:Size(max = 5)
    @Column(name = "invoice_note_source_language", nullable = false, length = 5)
    var invoiceNoteSourceLanguage: String = "de"
        private set

    @Column(name = "invoice_note_source_default", columnDefinition = "TEXT")
    private var invoiceNoteSourceDefaultValue: String? = null

    /** Invoice-owned copy of the private-customer notice; `null` when none applies. */
    @Column(name = "consumer_note", columnDefinition = "TEXT")
    var consumerNote: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "consumer_note_origin", nullable = false, length = 20)
    var consumerNoteOrigin: InvoiceNoteOrigin = InvoiceNoteOrigin.SELLER_DEFAULT
        private set

    @Column(name = "consumer_note_source_sender_id")
    private var consumerNoteSourceSenderProfileId: Long? = null

    @field:Size(max = 5)
    @Column(name = "consumer_note_source_language", nullable = false, length = 5)
    private var consumerNoteSourceLanguage: String = "de"

    /** Recipient type the default was resolved for. */
    @field:Size(max = 20)
    @Column(name = "consumer_note_source_context", nullable = false, length = 20)
    var consumerNoteSourceContext: String = ""
        private set

    @Column(name = "consumer_note_source_default", columnDefinition = "TEXT")
    private var consumerNoteSourceDefaultValue: String? = null

    /** Comma-separated official BT codes already copied into [freeText]. Codes are stored
     * separately so later manual edits/removal of generated text remain authoritative. */
    @Column(name = "b2b_free_text_imported_codes", columnDefinition = "TEXT")
    private var b2bFreeTextImportedCodes: String? = null

    fun changeFreeText(text: String?) {
        freeText = text
    }

    fun invoiceNoteSnapshot(): InvoiceNoteSnapshot = InvoiceNoteSnapshot(
        invoiceNote,
        invoiceNoteOrigin,
        invoiceNoteSourceSenderProfileId,
        invoiceNoteSourceLanguage,
        invoiceNoteSourceDefaultValue,
    )

    fun applyInvoiceNote(snapshot: InvoiceNoteSnapshot) {
        invoiceNote = snapshot.value
        invoiceNoteOrigin = snapshot.origin
        invoiceNoteSourceSenderProfileId = snapshot.sourceSenderProfileId
        invoiceNoteSourceLanguage = snapshot.sourceLanguage
        invoiceNoteSourceDefaultValue = snapshot.sourceDefaultValue
    }

    /** The invoice-owned private-customer notice with its provenance, for snapshot updates. */
    fun consumerNoteSnapshot(): InvoiceNoteSnapshot = InvoiceNoteSnapshot(
        consumerNote,
        consumerNoteOrigin,
        consumerNoteSourceSenderProfileId,
        consumerNoteSourceLanguage,
        consumerNoteSourceDefaultValue,
        consumerNoteSourceContext,
    )

    /** Writes [snapshot] back onto the consumer note columns. */
    fun applyConsumerNote(snapshot: InvoiceNoteSnapshot) {
        consumerNote = snapshot.value
        consumerNoteOrigin = snapshot.origin
        consumerNoteSourceSenderProfileId = snapshot.sourceSenderProfileId
        consumerNoteSourceLanguage = snapshot.sourceLanguage
        consumerNoteSourceContext = snapshot.sourceContext
        consumerNoteSourceDefaultValue = snapshot.sourceDefaultValue
    }

    fun importedB2bFreeTextCodes(): Set<String> = b2bFreeTextImportedCodes
        ?.split(',')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.toSet()
        .orEmpty()

    fun replaceImportedB2bFreeTextCodes(codes: Set<String>) {
        b2bFreeTextImportedCodes = codes.sorted().joinToString(",").ifEmpty { null }
    }
}
