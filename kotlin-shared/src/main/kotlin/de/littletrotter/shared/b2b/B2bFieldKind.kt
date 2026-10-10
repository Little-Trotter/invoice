package de.littletrotter.shared.b2b

/**
 * The input flavor a [B2bTerm] renders as. Deliberately a separate,
 * domain-local enum rather than a reuse of `de.littletrotter.spi.form.FieldKind` (kotlin-invoice):
 * the domain layer must not depend on the `spi.*` package, so `B2bProfileFieldsSection`
 * (kotlin-invoice) is the single place that maps one to the other.
 *
 * `BOOLEAN` (a Checkbox widget) was removed: no catalog-eligible (bound or free) term
 * ever used it, only the two reference-only terms `BT-X-1`/`BT-X-3` (binding == null, never
 * selectable into a live profile field's capture widget) — a dead widget branch behind an
 * unreachable kind. Safe to remove outright: nothing persists this enum by ordinal (a [B2bTerm]'s
 * own official BT number is what `B2bTermConverter` (kotlin-invoice, not on this module's classpath)
 * persists, never this enum's ordinal), so no ordinal-stability shim is needed here.
 */
enum class B2bFieldKind {
    TEXT,
    NUMBER,
    DATE,
    ENUM,
}
