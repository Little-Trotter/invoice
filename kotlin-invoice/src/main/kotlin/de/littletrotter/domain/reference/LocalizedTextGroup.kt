package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.converters.LocalizedTextPurposeConverter
import de.littletrotter.shared.reference.resolveLocalizedText
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * One text slot that may exist in more than one language, resolved per document language
 * ([de.littletrotter.shared.reference.resolveLocalizedText]). Owned by exactly one master-data
 * record (e.g. a future `sender_profile.invoice_note_group_id`), never the other way round — no
 * polymorphic owner, no `owner_table` column.
 *
 * [entries] is an owned collection: a group without any text is worthless, so removing an entry
 * from this list (or the whole group) deletes the row, never leaves an orphan.
 */
@Entity
@Table(name = "localized_text_group")
class LocalizedTextGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Convert(converter = LocalizedTextPurposeConverter::class)
    @Column(name = "purpose", nullable = false)
    var purpose: LocalizedTextPurpose = LocalizedTextPurpose.SELLER_INVOICE_NOTE

    @OneToMany(mappedBy = "group", cascade = [CascadeType.ALL], orphanRemoval = true)
    var entries: MutableList<LocalizedTextEntry> = mutableListOf()

    /**
     * Adds (or, if [language] already has an entry, would create the duplicate [validate] rejects)
     * a new entry, keeping both sides of the bidirectional association in sync — `@OneToMany
     * (mappedBy = ...)` never maintains the owning side ([LocalizedTextEntry.group]) on its own, so
     * a caller building entries by hand (rather than through this method) must remember to set it,
     * or `@MapsId` has nothing to derive the composite id from at persist time.
     */
    fun addEntry(language: Language, value: String): LocalizedTextEntry {
        val entry = LocalizedTextEntry().apply {
            this.group = this@LocalizedTextGroup
            this.language = language
            this.value = value
        }
        entries.add(entry)
        return entry
    }

    /**
     * The resolved text for [language] (a document language code, e.g. `"de"`/`"en"`),
     * delegating to [resolveLocalizedText]. No repository lookup for the fallback language:
     * scanning this group's own (already-loaded) [entries] for [Language.isFallback] is
     * behaviourally identical to a real lookup of the global fallback language, and misses cleanly
     * when that entry does not exist. Shared by every owner of a text group (originally duplicated
     * per-owner, e.g. `PaymentTerm.nameFor`/`SenderProfile.invoiceNoteFor`) since the resolution
     * rule itself has nothing owner-specific about it.
     */
    fun resolvedFor(language: String): String? {
        val texts = entries.mapNotNull { entry -> entry.language?.code?.let { it to entry.value } }.toMap()
        val fallbackCode = entries.firstOrNull { it.language?.isFallback == true }?.language?.code ?: language
        return resolveLocalizedText(texts, language, fallbackCode)
    }

    /**
     * The text stored for exactly [language], or `null`. Unlike [resolvedFor] there is no
     * fallback to another language: a notice printed on an English document must never show
     * German text just because only that one is maintained.
     */
    fun exactFor(language: String): String? =
        entries.firstOrNull { it.language?.code == language }?.value

    /**
     * Self-contained invariants — no repository lookup required:
     * - at most one entry per language (the DB primary key already enforces it; mirrored here so
     *   the failure surfaces as a domain exception instead of a raw constraint violation).
     * - every entry's language must be [Language.isActive] — an inactive language is not offered
     *   for new text, but existing rows are never deleted just because a language was
     *   deactivated, so this only rejects newly *added* entries against an inactive language.
     * - every entry's value stays within [purpose]'s own [LocalizedTextPurpose.maxLength] — the
     *   old per-purpose column length (e.g. `sender_profile.footer_note VARCHAR(200)`), now
     *   enforced here because `localized_text.value` itself is `TEXT`, shared by every purpose.
     */
    fun validate() {
        val duplicated = entries.groupBy { it.language?.id }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw InvalidParameterException("Je Sprache ist höchstens ein Text je Textgruppe zulässig.")
        }
        entries.forEach { validateEntry(it) }
    }

    // ThrowsCount — validate()'s four checks split across three functions so each stays
    // under ThrowsCount on its own; behaviour is unchanged.
    private fun validateEntry(entry: LocalizedTextEntry) {
        val language = requireActiveLanguage(entry)
        if (entry.value.length > purpose.maxLength) {
            throw InvalidParameterException(
                "Text für Sprache '${language.code}' überschreitet die Höchstlänge von ${purpose.maxLength} Zeichen.",
            )
        }
    }

    private fun requireActiveLanguage(entry: LocalizedTextEntry): Language {
        val language = entry.language
            ?: throw InvalidParameterException("Ein Text muss einer Sprache zugeordnet sein.")
        if (!language.isActive) {
            throw InvalidParameterException("Sprache '${language.code}' ist nicht aktiv und kann keinen neuen Text tragen.")
        }
        return language
    }
}
