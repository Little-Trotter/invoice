package de.littletrotter.domain.reference

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import java.io.Serializable

/**
 * Composite primary key `(group_id, language_id)`, mirroring `localized_text`'s own PK: at
 * most one entry per language per group.
 */
@Embeddable
data class LocalizedTextEntryId(
    @Column(name = "group_id") val groupId: Long = 0,
    @Column(name = "language_id") val languageId: Int = 0,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * One language's text within a [LocalizedTextGroup]. Never blank (the schema's own
 * `chk_localized_text_value_not_blank` CHECK) — a missing translation is the absence of a row,
 * never an empty string.
 */
@Entity
@Table(name = "localized_text")
class LocalizedTextEntry {

    @EmbeddedId
    var id: LocalizedTextEntryId = LocalizedTextEntryId()

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("groupId")
    @JoinColumn(name = "group_id", nullable = false)
    var group: LocalizedTextGroup? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("languageId")
    @JoinColumn(name = "language_id", nullable = false)
    var language: Language? = null

    @Column(name = "value", columnDefinition = "TEXT", nullable = false)
    var value: String = ""
}
