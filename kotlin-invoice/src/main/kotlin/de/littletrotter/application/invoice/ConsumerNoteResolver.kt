package de.littletrotter.application.invoice

import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.domain.invoice.ConsumerNotePolicy
import de.littletrotter.domain.invoice.InvoiceNoteSnapshot
import de.littletrotter.domain.invoice.SellerNoteDefault
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.shared.tax.CustomerType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Works out the private-customer notice an invoice starts with or follows to. The sender's own
 * text wins; without one, the default wording of the document language applies. The notice is
 * only present when [ConsumerNotePolicy] allows it, so a later wording-profile switch never
 * touches existing drafts: nothing here runs unless sender, language or recipient type changes.
 */
@ApplicationScoped
class ConsumerNoteResolver @Inject constructor(
    private val wordingProfile: WordingProfileQueryPort,
) {
    /** The text the sender's notice resolves to in [language]; own text first, then the default. */
    fun textFor(sender: SenderProfile, language: String): String =
        sender.consumerNoteFor(language) ?: defaultText(language)

    /** The notice to copy for this sender/language/recipient combination, `null` when none applies. */
    fun defaultFor(sender: SenderProfile?, language: String, customerType: CustomerType?): String? {
        if (sender == null) return null
        val applies = ConsumerNotePolicy.applies(wordingProfile.currentProfile(), customerType, sender.consumerNoteEnabled)
        return if (applies) textFor(sender, language) else null
    }

    fun sourceFor(sender: SenderProfile?, language: String, customerType: CustomerType?): SellerNoteDefault =
        SellerNoteDefault(
            senderProfileId = sender?.id,
            language = language,
            sellerDefault = defaultFor(sender, language, customerType),
            sourceContext = customerType?.name.orEmpty(),
        )

    fun initialSnapshot(sender: SenderProfile?, language: String, customerType: CustomerType?): InvoiceNoteSnapshot {
        val source = sourceFor(sender, language, customerType)
        return initialInvoiceNoteSnapshot(source.senderProfileId, language, source.sellerDefault, source.sourceContext)
    }

    companion object {
        private const val DEFAULT_KEY = "consumerNote.default"

        /** The language-file default wording, used when a sender maintains no own text. */
        fun defaultText(language: String): String = DocumentTexts.text(DEFAULT_KEY, language)
    }
}
