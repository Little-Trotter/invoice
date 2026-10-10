package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import de.littletrotter.application.command.CommandEntryPoint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Input ports (role interfaces per use case) in `..port.inbound..`:
 * no port extends another port (no composite interfaces), at most [MAX_PORT_METHODS] methods,
 * `*QueryPort` is never a [CommandEntryPoint], `*UseCase` always is, and nothing else lives there.
 * Beyond the port packages, no interface anywhere in the application combines two or more of the
 * application's own interfaces: a composite that merely bundles roles hides which use case a
 * caller really needs, so callers inject the narrow port instead.
 * Each rule is checked against production (must pass) and a violating specimen (must fail).
 */
class InputPortArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.port.inbound")

    private fun JavaClass.isInboundPort() = packageName.endsWith(".port.inbound") && isInterface && !isNestedClass

    private fun JavaClass.isProjectInterface() = isInterface && packageName.startsWith("de.littletrotter")

    private fun portRule(description: String, check: (JavaClass) -> String?): ArchRule =
        interfaceRule("input ports", { it.isInboundPort() }, description, check)

    private fun interfaceRule(
        scope: String,
        applies: (JavaClass) -> Boolean,
        description: String,
        check: (JavaClass) -> String?,
    ): ArchRule =
        classes().that(DescribedPredicate.describe(scope) { applies(it) })
            .should(
                object : ArchCondition<JavaClass>(description) {
                    override fun check(item: JavaClass, events: ConditionEvents) {
                        check(item)?.let { events.add(SimpleConditionEvent.violated(item, "${item.name}: $it")) }
                    }
                },
            )
            .allowEmptyShould(true)

    private val noCompositePort = portRule("not extend another input port") { port ->
        port.rawInterfaces.filter { it.isInboundPort() }.takeIf { it.isNotEmpty() }
            ?.let { "extends input port(s) ${it.joinToString { p -> p.simpleName }}" }
    }

    private val noCompositeInterface = interfaceRule(
        "application interfaces",
        { it.isProjectInterface() },
        "combine at most one other application interface",
    ) { type ->
        type.rawInterfaces.filter { it.isProjectInterface() }.takeIf { it.size > 1 }
            ?.let { "combines ${it.joinToString { p -> p.simpleName }}" }
    }

    private val portSize = portRule("declare at most $MAX_PORT_METHODS methods") { port ->
        val count = port.methods.count { !it.modifiers.contains(JavaModifier.SYNTHETIC) }
        if (count > MAX_PORT_METHODS) "declares $count methods" else null
    }

    private val queryCommandSeparation = portRule("separate queries from commands by name and marker") { port ->
        val isCommand = port.isAssignableTo(CommandEntryPoint::class.java)
        when {
            port.simpleName.endsWith("QueryPort") && isCommand -> "a QueryPort must not be a CommandEntryPoint"
            port.simpleName.endsWith("UseCase") && !isCommand -> "a UseCase must be a CommandEntryPoint"
            !port.simpleName.endsWith("QueryPort") && !port.simpleName.endsWith("UseCase") ->
                "name must end with QueryPort or UseCase"
            else -> null
        }
    }

    private fun passes(rule: ArchRule, classes: JavaClasses) = !rule.evaluate(classes).hasViolation()

    private fun violationsMention(rule: ArchRule, name: String) =
        rule.evaluate(specimens).failureReport.details.any { it.contains(name) }

    @Test
    fun `no input port extends another input port`() {
        noCompositePort.check(productionClasses)
        assertTrue(violationsMention(noCompositePort, "SpecimenCompositeQueryPort"))
        assertFalse(violationsMention(noCompositePort, "SpecimenQueryPort:"))
    }

    @Test
    fun `no interface combines two or more application interfaces`() {
        noCompositeInterface.check(productionClasses)
        assertTrue(violationsMention(noCompositeInterface, "SpecimenCombinedQueryPort"))
        assertFalse(violationsMention(noCompositeInterface, "SpecimenCompositeQueryPort:"))
    }

    @Test
    fun `input ports declare at most 12 methods`() {
        portSize.check(productionClasses)
        assertTrue(violationsMention(portSize, "SpecimenOversizedQueryPort"))
        assertFalse(violationsMention(portSize, "SpecimenQueryPort:"))
    }

    @Test
    fun `query ports are never command entry points and use cases always are`() {
        queryCommandSeparation.check(productionClasses)
        assertTrue(violationsMention(queryCommandSeparation, "SpecimenCommandMarkedQueryPort"))
        assertTrue(violationsMention(queryCommandSeparation, "SpecimenUnmarkedUseCase"))
        assertTrue(violationsMention(queryCommandSeparation, "SpecimenInvoiceFacade"))
    }

    @Test
    fun `production actually contains input ports, so the rules are not vacuous`() {
        assertTrue(productionClasses.count { it.isInboundPort() } >= MIN_EXPECTED_PORTS)
        assertFalse(passes(noCompositePort, specimens))
    }

    private companion object {
        const val MAX_PORT_METHODS = 12
        const val MIN_EXPECTED_PORTS = 10
    }
}
