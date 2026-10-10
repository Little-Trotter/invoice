package de.littletrotter.infrastructure.persistence

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.value.BoundedStringValue
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberProperties

/**
 * ST-B3 — every `String` field backed by a bounded database column (`VARCHAR(n)`/`CHAR(n)`) must
 * carry a matching `@Size(max = n)`, so an over-long value is rejected by bean validation before
 * it ever reaches Postgres as a raw "value too long for type character varying(n)" SQL error (the
 * `SenderProfile.bic`/`legalRegistrationSchemeId` finding this package fixes).
 *
 * Discovers every JPA `@Entity` under `de.littletrotter.domain` via [ClassFileImporter] (bytecode
 * scan, same technique as [de.littletrotter.arch.MoneyArchitectureTest] — no hand-maintained
 * class list to keep in sync), then reads each bounded column's actual width from
 * `information_schema.columns` against a real Postgres (Testcontainers, [PostgresTestResource])
 * instead of a second hand-maintained length table — a later DDL width change is caught here
 * automatically instead of silently drifting out of sync with the entity, per the package spec's
 * "pick the IT, it cannot drift".
 *
 * Deliberately excludes `@Embeddable` fields (`PartyIdentifier`/`ElectronicAddress`/
 * `LegalRegistrationIdentifier`): a `String` field here always means a field declared directly on
 * an `@Entity` (or a `@MappedSuperclass` it extends, e.g. `AuditableEntity.createdBy`), never one
 * reached through an `@Embedded`/`@AttributeOverrides` object graph — those three embeddables
 * carry their own fixed `@Size` bounds instead (see their KDoc), verified by hand against every
 * column they are mapped onto because the same embeddable is reused with different column names
 * per usage site, which this generic field→column lookup cannot resolve.
 *
 * ST-B3 follow-up ('s `VatId`/`ArticleNumber`/`TaxNumber`/`Bic`/`CustomerNumber`/`PostalCode`)
 * — a nullable `@JvmInline value class` wrapping a reference-typed underlying value (`String`)
 * erases to `java.lang.String` at the JVM field level (javap-verified: e.g.
 * `SellerVatRegistration.vatId`'s field descriptor is `java.lang.String`, not `VatId`), so
 * [java.lang.reflect.Field] alone cannot tell such a field apart from a genuinely raw `String` one
 * that still needs a Bean Validation `@Size`. [boundedValueClassMaxLength] resolves the *Kotlin*-
 * declared property type via reflection (reading the class's `@Metadata`, unaffected by the JVM
 * erasure) and, when that type's companion implements [BoundedStringValue], accepts its
 * [BoundedStringValue.maxLength] in place of `@Size(max = …)` — but only when it equals the actual
 * column width; a class whose own invariant disagrees with its column (the
 * `seller_vat_registration.vat_id VARCHAR(32)` vs. `VatId`'s 64-character contract this task
 * fixed) still fails loudly instead of being waved through.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class EntityColumnSizeConsistencyIT {

    @Inject
    lateinit var em: EntityManager

    /** `null` when the column does not exist in [table] at all, or is unbounded (`TEXT`/`JSONB`,
     *  where `character_maximum_length` is itself `NULL`) — both cases are out of this test's
     *  scope, so callers skip on `null` rather than treat it as a violation. */
    private fun boundedColumnLength(table: String, column: String): Int? {
        val rows = em.createNativeQuery(
            "SELECT character_maximum_length FROM information_schema.columns WHERE table_name = :t AND column_name = :c",
        ).setParameter("t", table).setParameter("c", column).resultList
        if (rows.isEmpty()) return null
        return (rows[0] as Number?)?.toInt()
    }

    private fun snakeCase(name: String): String = SNAKE_BOUNDARY.replace(name) { "_${it.value}" }.lowercase()

    private fun columnNameOf(field: Field): String {
        val explicit = field.getAnnotation(Column::class.java)?.name
        return explicit?.takeIf { it.isNotBlank() } ?: snakeCase(field.name)
    }

    /** Resolves [field]'s *Kotlin*-declared property type on [entityClass] (or an ancestor) and,
     *  when its companion implements [BoundedStringValue], returns [BoundedStringValue.maxLength]
     *  — the class's own already-enforced max length — instead of `null`, which callers treat the
     *  same as "no `@Size`" (see this class's KDoc for why [Field.getType] alone cannot see past
     *  the JVM erasure of a nullable `String`-backed value class). */
    private fun boundedValueClassMaxLength(entityClass: Class<*>, field: Field): Int? {
        val property = entityClass.kotlin.memberProperties.find { it.name == field.name } ?: return null
        val propertyType = property.returnType.classifier as? KClass<*> ?: return null
        return (propertyType.companionObjectInstance as? BoundedStringValue)?.maxLength
    }

    /** Walks [entityClass] and every ancestor still annotated `@MappedSuperclass`, collecting
     *  declared `String` instance fields that are not `@Transient` — mirrors how Hibernate maps an
     *  entity's own fields *and* its mapped-superclass fields onto the one table [entityClass]
     *  owns (e.g. every `AuditableEntity` subclass's `created_by`/`updated_by`). */
    private fun mappedStringFields(entityClass: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = entityClass
        while (current != null && current != Any::class.java) {
            if (current == entityClass || current.isAnnotationPresent(MappedSuperclass::class.java)) {
                fields += current.declaredFields.filter { field ->
                    field.type == String::class.java &&
                        !Modifier.isStatic(field.modifiers) &&
                        !field.isAnnotationPresent(Transient::class.java)
                }
            }
            current = current.superclass
        }
        return fields
    }

    @Test
    fun `every bounded String column carries a matching @Size`() {
        val entityClasses: List<JavaClass> = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("de.littletrotter.domain")
            .filter { it.isAnnotatedWith(Entity::class.java) }

        val violations = mutableListOf<String>()
        for (javaClass in entityClasses) {
            val clazz = Class.forName(javaClass.fullName)
            val table = clazz.getAnnotation(Table::class.java)?.name ?: continue
            for (field in mappedStringFields(clazz)) {
                val column = columnNameOf(field)
                val dbLength = boundedColumnLength(table, column) ?: continue
                val size = field.getAnnotation(Size::class.java)
                val valueClassMaxLength = boundedValueClassMaxLength(clazz, field)
                val declaredBound = size?.max ?: valueClassMaxLength
                if (declaredBound == null || declaredBound != dbLength) {
                    violations += "${clazz.simpleName}.${field.name} -> $table.$column is bounded to " +
                        "$dbLength but carries @Size(max=${size?.max ?: "none"}) and its value class " +
                        "max length is ${valueClassMaxLength ?: "n/a"}"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Missing/mismatched @Size for a bounded column (see this class's KDoc):\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    companion object {
        private val SNAKE_BOUNDARY = Regex("(?<!^)[A-Z]")
    }
}
