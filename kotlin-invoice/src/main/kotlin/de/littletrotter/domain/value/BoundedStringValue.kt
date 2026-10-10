package de.littletrotter.domain.value

/**
 * Common contract every String-based value class in this package
 * implements via its companion object: the max length the wrapped string may have, i.e. the
 * VARCHAR/CHAR column width the class's own `init` block already enforces at construction
 * (directly, or transitively via a validator it delegates to, e.g. [VatIdValidator.MAX_LENGTH]).
 *
 * [de.littletrotter.infrastructure.persistence.EntityColumnSizeConsistencyIT] uses this contract —
 * via Kotlin reflection on the entity's *declared* property type, since a nullable value class
 * over a reference-typed underlying value (`String`) erases to `java.lang.String` at the JVM field
 * level (javap-verified: `SellerVatRegistration.vatId`'s field descriptor is `java.lang.String`,
 * not `VatId`) — to recognise a value-class-typed field as already-bounded instead of demanding a
 * redundant `@Size` annotation, while still failing on any class-vs-column width mismatch.
 */
interface BoundedStringValue {
    val maxLength: Int
}
