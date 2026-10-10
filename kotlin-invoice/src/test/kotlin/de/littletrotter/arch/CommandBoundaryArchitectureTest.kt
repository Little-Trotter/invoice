package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import de.littletrotter.application.command.Command
import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.command.CommandResult
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.MappedSuperclass
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * Implements the commands/DTOs architecture rules from the binding persistence decision's
 * "Architecture rules for commands and DTOs" section. [DddModelArchitectureTest] and
 * [ArchitectureRulesTest] already cover the general onion/layering shape (including the older
 * "UI must never construct Invoice" and "@Transactional only in application" rules); this file
 * adds the boundary the persistence decision named separately: the [Command]/[CommandResult]/
 * [CommandEntryPoint] marker types (`de.littletrotter.application.command`), since commands share
 * a package with plain services and a class-name suffix alone is not a reliable architectural
 * signal (the persistence decision's own warning).
 *
 * Every rule below is checked twice: once against the real production classes (must pass, proving
 * the current code respects the boundary) and once against a small, deliberately violating
 * specimen class under `..arch.specimen..`/`..domain.invoice.specimen..`/`..ui.specimen..` (must
 * fail, proving the rule actually rejects something). Specimens are test sources — [ImportOption
 * .DoNotIncludeTests] on the production scan excludes them from that scan by construction; the
 * negative checks import the specimen packages directly instead. Specimens carry no business
 * data (persistence decision requirement).
 */
class CommandBoundaryArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private fun specimensIn(vararg packages: String) =
        ClassFileImporter().importPackages(*packages)

    // ── commands/DTOs must not depend on JPA/repository/EntityManager/Hibernate/CDI/Vaadin/infrastructure ──

    private val forbiddenCommandDependency: DescribedPredicate<JavaClass> =
        DescribedPredicate.describe(
            "a JPA entity, EntityManager, Hibernate/Panache, CDI-managed service, Vaadin or infrastructure adapter type",
        ) { clazz ->
            clazz.isAnnotatedWith(Entity::class.java) ||
                clazz.isAnnotatedWith(jakarta.enterprise.context.ApplicationScoped::class.java) ||
                clazz.isAssignableTo(EntityManager::class.java) ||
                clazz.packageName.startsWith("org.hibernate") ||
                clazz.packageName.startsWith("io.quarkus.hibernate") ||
                clazz.packageName.startsWith("com.vaadin") ||
                clazz.packageName.startsWith("de.littletrotter.infrastructure") ||
                clazz.packageName.startsWith("de.littletrotter.domain.repository")
        }

    private val commandDependencyRule: ArchRule = noClasses()
        .that().implement(Command::class.java).or().implement(CommandResult::class.java)
        .should().dependOnClassesThat(forbiddenCommandDependency)
        .because(
            "command/DTO contracts do not depend on JPA entities, repositories, EntityManager, " +
                "Hibernate/Panache, CDI services, Vaadin or infrastructure adapters; pure domain value " +
                "types/enums remain allowed",
        )

    @Test
    fun `commands and DTOs stay free of persistence and infrastructure dependencies`() {
        commandDependencyRule.check(productionClasses)
    }

    @Test
    fun `negative specimen depending on EntityManager is rejected`() {
        val result = commandDependencyRule.evaluate(specimensIn("de.littletrotter.arch.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject ViolatingCommandWithEntityManager")
    }

    // ── command entry points accept commands/ids/values and return DTOs; no entity leaks ──

    private fun typeLeaksEntity(type: Type): Boolean = when (type) {
        is Class<*> -> type.isAnnotationPresent(Entity::class.java)
        is ParameterizedType ->
            (type.rawType as? Class<*>)?.isAnnotationPresent(Entity::class.java) == true ||
                type.actualTypeArguments.any { typeLeaksEntity(it) }
        else -> false
    }

    private val noEntityLeakage = object : ArchCondition<JavaMethod>("not leak a JPA entity through its signature") {
        override fun check(method: JavaMethod, events: ConditionEvents) {
            val reflected = method.reflect()
            val leaks = reflected.genericParameterTypes.any { typeLeaksEntity(it) } ||
                typeLeaksEntity(reflected.genericReturnType)
            if (leaks) {
                events.add(SimpleConditionEvent.violated(method, "${method.fullName} leaks a JPA entity through a parameter, return type or nested generic collection"))
            }
        }
    }

    /**
     * Scoped to the entry-point INTERFACE's own declared methods, not every concrete class that
     * happens to implement it: the interface is the contract callers see, while an
     * implementation's other public members (collaborator accessors, unrelated interfaces) are not
     * part of the command boundary and `.implement()` alone would sweep them in. `interface`
     * (not `implement()`, which explicitly excludes interfaces) so a specimen interface counts too.
     */
    private val isCommandEntryPointInterface: DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("a CommandEntryPoint interface") { c -> c.isInterface && c.isAssignableTo(CommandEntryPoint::class.java) }

    private val entryPointEntityRule: ArchRule = methods()
        .that().areDeclaredInClassesThat(isCommandEntryPointInterface)
        .should(noEntityLeakage)
        .because(
            "migrated UI entry points accept commands/ids/value types and return DTOs; entity " +
                "types do not leak through fields, constructors, public parameters, returns or nested " +
                "generic collections",
        )

    @Test
    fun `command entry points never leak an entity through their signature`() {
        entryPointEntityRule.check(productionClasses)
    }

    @Test
    fun `negative specimen leaking Invoice through a return type is rejected`() {
        val result = entryPointEntityRule.evaluate(specimensIn("de.littletrotter.arch.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject ViolatingEntryPoint.leaky(Invoice): Invoice")
    }

    // ── every input port signature carries value types only ──

    private val persistenceAnnotations = listOf(Entity::class.java, Embeddable::class.java, MappedSuperclass::class.java)

    /**
     * A signature type is a value type when it lives in the JDK or Kotlin standard library, is an
     * enum, a DTO in `..application.dto..`, a [Command], a type declared next to the ports in
     * `..port.inbound..` or a type of the entity-free shared module. The check goes by package, not
     * by transitive entity freedom: a domain data class without an entity today may reference one
     * tomorrow, and an exemption for such classes would hide exactly that change. JPA-mapped types
     * are never value types, wherever they live.
     */
    private fun isValueType(type: Type): Boolean = when (type) {
        is Class<*> -> isValueClass(type)
        is ParameterizedType -> isValueType(type.rawType) && type.actualTypeArguments.all { isValueType(it) }
        is WildcardType -> (type.upperBounds + type.lowerBounds).all { isValueType(it) }
        is GenericArrayType -> isValueType(type.genericComponentType)
        is TypeVariable<*> -> type.bounds.all { isValueType(it) }
        else -> false
    }

    private fun isValueClass(clazz: Class<*>): Boolean {
        val packageName = clazz.packageName
        return when {
            persistenceAnnotations.any { clazz.isAnnotationPresent(it) } -> false
            clazz.isArray -> isValueClass(clazz.componentType)
            clazz.isPrimitive -> true
            packageName.startsWith("java.") || packageName.startsWith("kotlin.") || packageName == "kotlin" -> true
            clazz.isEnum -> true
            packageName.contains(".application.dto") -> true
            Command::class.java.isAssignableFrom(clazz) -> true
            packageName.contains(".port.inbound") -> true
            else -> packageName.startsWith("de.littletrotter.shared")
        }
    }

    private val onlyValueTypes = object : ArchCondition<JavaMethod>("take and return value types only") {
        override fun check(method: JavaMethod, events: ConditionEvents) {
            val reflected = method.reflect()
            val offending = (reflected.genericParameterTypes.toList() + reflected.genericReturnType)
                .filterNot { isValueType(it) }
            if (offending.isNotEmpty()) {
                events.add(
                    SimpleConditionEvent.violated(
                        method,
                        "${method.fullName} uses non-value types ${offending.joinToString { it.typeName }}",
                    ),
                )
            }
        }
    }

    private val isInputPortInterface: DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("an input port interface") { c -> c.isInterface && c.packageName.contains(".port.inbound") }

    private val valueTypeRule: ArchRule = methods()
        .that().areDeclaredInClassesThat(isInputPortInterface)
        .should(onlyValueTypes)
        .because(
            "input ports hand DTOs, commands and plain values across the boundary; entities and " +
                "domain data classes stay behind the application layer",
        )

    @Test
    fun `input ports take and return value types only`() {
        valueTypeRule.check(productionClasses)
    }

    @Test
    fun `negative specimen query port returning an entity is rejected`() {
        val report = valueTypeRule.evaluate(specimensIn("de.littletrotter.arch.specimen.port.inbound")).failureReport.details
        assertTrue(report.any { it.contains("SpecimenEntityQueryPort") }, "expected the value-type rule to reject SpecimenEntityQueryPort")
    }

    @Test
    fun `negative specimen query port returning a domain data class is rejected`() {
        val report = valueTypeRule.evaluate(specimensIn("de.littletrotter.arch.specimen.port.inbound")).failureReport.details
        assertTrue(report.any { it.contains("SpecimenDomainDataQueryPort") }, "expected the value-type rule to reject SpecimenDomainDataQueryPort")
    }

    // ── UI never calls a mutating Invoice domain method directly (construct/repository/persistence already covered elsewhere) ──

    private val mutatingInvoiceMethodNames = setOf(
        "addItem", "removeItem", "transitionTo", "applyPartySnapshots", "recalculateTotals",
        "transitionValidationStatusTo", "replaceImportedB2bFreeTextCodes", "applyInvoiceNoteSnapshot",
        "adoptPartiesAndTaxContextFrom",
    )

    private val callsMutatingInvoiceMethod = DescribedPredicate.describe<com.tngtech.archunit.core.domain.JavaAccess<*>>(
        "a call to a mutating Invoice domain method",
    ) { call ->
        call.target.owner.isEquivalentTo(de.littletrotter.domain.invoice.Invoice::class.java) &&
            call.target.name in mutatingInvoiceMethodNames
    }

    private val uiInvoiceMutationRule: ArchRule = noClasses()
        .that().resideInAPackage("..ui..")
        .should().callMethodWhere(callsMutatingInvoiceMethod)
        .because(
            "UI does not construct/mutate migrated entities directly; a mutating Invoice write " +
                "always goes through a command handled by the application layer (repository/persistence " +
                "API/constructor bans already covered by ArchitectureRulesTest and UiLayerBoundaryTest)",
        )

    @Test
    fun `UI never calls a mutating Invoice domain method directly`() {
        uiInvoiceMutationRule.check(productionClasses)
    }

    @Test
    fun `negative specimen calling addItem from a UI-layer class is rejected`() {
        val result = uiInvoiceMutationRule.evaluate(specimensIn("de.littletrotter.ui.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject a ui-package class calling Invoice.addItem")
    }

    // ── commands are immutable data carriers, never transactional handlers ──

    private val commandNotTransactional: ArchRule = noClasses()
        .that().implement(Command::class.java)
        .should().beAnnotatedWith(Transactional::class.java)
        .because("commands are immutable data carriers, not transactional handlers or services")

    private val commandNoSetters: ArchRule = methods()
        .that().areDeclaredInClassesThat().implement(Command::class.java)
        .and().haveNameStartingWith("set")
        .should().notBePublic()
        .because("commands are immutable (val-only) data carriers; a public setter means a mutable field slipped in")

    @Test
    fun `commands are not annotated transactional`() {
        commandNotTransactional.check(productionClasses)
    }

    @Test
    fun `commands expose no public setter`() {
        // allowEmptyShould: today's real commands have zero setters, so the "that" selection
        // (methods named set* declared in a Command class) is legitimately empty - that IS the
        // pass. The negative specimen below proves the rule still fires when one exists.
        commandNoSetters.allowEmptyShould(true).check(productionClasses)
    }

    @Test
    fun `negative specimen with a var property is rejected`() {
        val result = commandNoSetters.evaluate(specimensIn("de.littletrotter.arch.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject ViolatingMutableCommand's public setter")
    }

    @Test
    fun `negative specimen annotated Transactional is rejected`() {
        val result = commandNotTransactional.evaluate(specimensIn("de.littletrotter.arch.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject ViolatingTransactionalCommand")
    }

    // ── domain does not depend on application commands/DTOs ──

    private val domainCommandRule: ArchRule = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat(
            DescribedPredicate.describe("a Command or CommandResult type") { c: JavaClass ->
                c.isAssignableTo(Command::class.java) || c.isAssignableTo(CommandResult::class.java)
            },
        )
        .because(
            "domain does not depend on application commands/DTOs; handlers translate commands " +
                "into domain operations, never the other way round (mirrors the existing onion/cycle rules)",
        )

    @Test
    fun `domain never depends on a Command or CommandResult type`() {
        domainCommandRule.check(productionClasses)
    }

    @Test
    fun `negative specimen in the domain package depending on a Command is rejected`() {
        val result = domainCommandRule.evaluate(specimensIn("de.littletrotter.domain.invoice.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject a domain-package class depending on InvoiceItemCommand")
    }

    // ── owning write entry points live in the application layer; commands/results never carry the transaction boundary ──

    private val entryPointInApplicationLayer: ArchRule = classes()
        .that().areAssignableTo(CommandEntryPoint::class.java)
        .should().resideInAPackage("..application..")
        .because("owning write entry points are explicitly identified in the application layer")

    private val resultNotTransactional: ArchRule = noClasses()
        .that().implement(CommandResult::class.java)
        .should().beAnnotatedWith(Transactional::class.java)
        .because(
            "transaction annotations are checked on both commands and results; a result carrier " +
                "never owns the transaction boundary either (UI/domain placement is already forbidden by " +
                "ArchitectureRulesTest's `transactional annotation only in the application layer`)",
        )

    @Test
    fun `command entry points live in the application layer`() {
        entryPointInApplicationLayer.check(productionClasses)
    }

    @Test
    fun `command results are never annotated transactional`() {
        resultNotTransactional.check(productionClasses)
    }

    @Test
    fun `negative specimen entry point outside the application layer is rejected`() {
        val result = entryPointInApplicationLayer.evaluate(specimensIn("de.littletrotter.arch.specimen"))
        assertTrue(result.hasViolation(), "expected the rule to reject ViolatingEntryPoint living outside de.littletrotter.application..")
    }

    @Test
    fun `sanity - specimen packages are not swept into the production scan`() {
        val leaked = productionClasses.filter {
            it.packageName.endsWith(".specimen") || it.packageName.contains(".specimen.")
        }
        assertFalse(leaked.isNotEmpty(), "test-only specimen classes must never reach the production import: $leaked")
    }
}
