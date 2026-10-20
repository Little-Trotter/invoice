package de.littletrotter.domain.reference

import de.littletrotter.shared.b2b.B2bTermLevel

/**
 * How strictly a [B2bProfileField] must be filled.
 *
 * Persisted by **name**, not ordinal: [B2bProfileField.requirement] carries `@Enumerated(EnumType
 * .STRING)`, matching the `chk_b2b_profile_field_requirement` CHECK constraint (`ddl/V001__baseline
 * .sql`) that lists the allowed literals. Declaration order below is therefore cosmetic — unlike
 * [de.littletrotter.shared.catalogue.InvoiceItemUnit] (ordinal-persisted via a converter, where
 * append-only order is load-bearing), a new constant here is free to go anywhere in the file. Adding
 * [CONDITIONAL_IF_EMPTY] still needed a migration (`ddl/V916`) because the CHECK
 * constraint enumerates the literals explicitly and the column was `VARCHAR(15)`, too short for the
 * 21-character name.
 */
enum class B2bFieldRequirement {
    OPTIONAL,
    MANDATORY,

    /** Mandatory exactly when [B2bProfileField.conditionTerm] has a value on the owning record. */
    CONDITIONAL,

    /**
     * Mandatory exactly when [B2bProfileField.conditionTerm] has **no** value on the
     * owning record (or, when that term is [B2bTermLevel.LINE]-level and this field itself is not,
     * absent on **at least one** line — see [de.littletrotter.domain.compliance.B2bProfileRules]'s
     * KDoc for the exact predicate). The XOR/either-or counterpart to [CONDITIONAL]: expresses
     * "field A is required unless field B is filled" (Nekar's BT-72 vs. BT-134/135 line periods),
     * which the pre-existing "always" ([MANDATORY]) / "required when filled" ([CONDITIONAL]) pair
     * could only over-approximate with a plain [MANDATORY].
     */
    CONDITIONAL_IF_EMPTY,
}
