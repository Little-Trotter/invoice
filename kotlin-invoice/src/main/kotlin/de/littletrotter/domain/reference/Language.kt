package de.littletrotter.domain.reference

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size

/**
 * A document language, e.g. `de`/`en` (ISO 639-1, optionally with a BCP-47 region subtag).
 * System-supplied reference data, read-only at runtime: no UI in this package writes it.
 *
 * [id] is a stable, never-reused numeric identity (the schema's own `COMMENT ON TABLE` states the
 * same contract); [code] is what every other table and the resolution function
 * ([de.littletrotter.shared.reference.resolveLocalizedText]) actually compares against.
 */
@Entity
@Table(name = "language")
class Language {

    @Id
    var id: Int = 0

    @field:Size(max = 5)
    @Column(nullable = false, unique = true, length = 5)
    var code: String = ""

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    /**
     * Exactly one row carries `TRUE` (DB partial unique index `uq_language_single_fallback`). A
     * document must never be half-translated: [de.littletrotter.shared.reference
     * .resolveLocalizedText] falls back to this language whenever a text is missing in the
     * document language.
     */
    @Column(name = "is_fallback", nullable = false)
    var isFallback: Boolean = false

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
