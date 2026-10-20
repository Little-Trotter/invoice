package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port boundaries of the hexagon:
 *
 * (a) an interface in `application` that an `infrastructure` class implements is a port: an
 *     outbound port in `..port.outbound..`, or an input port in `..port.inbound..` whose read model
 *     the infrastructure implements by design; a loose interface next to the services is neither;
 * (b) `ui` reaches `application` behaviour only through `..port.inbound..` (the DTOs in `..dto..`
 *     and the plug-in contracts in `spi` are data and extension points, not behaviour). Behaviour
 *     here means a container-managed class, or an interface a container-managed class implements
 *     outside `ui` — a view injecting either bypasses the input port of its use case. An interface
 *     that only `ui` classes implement (the push notification sinks) is a driven port whose adapter
 *     is the view layer itself and stays allowed.
 *
 * Each rule is checked against production (must pass) and a specimen set (the violating specimen
 * must be reported, the look-alike must stay silent).
 */
class PortBoundaryArchitectureTest {

    private val production = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.boundary")

    private class Layers(val application: String, val infrastructure: String, val ui: String)

    private val productionLayers = Layers(
        application = "de.littletrotter.application",
        infrastructure = "de.littletrotter.infrastructure",
        ui = "de.littletrotter.ui",
    )

    private val specimenLayers = Layers(
        application = "de.littletrotter.arch.specimen.boundary.application",
        infrastructure = "de.littletrotter.arch.specimen.boundary.infrastructure",
        ui = "de.littletrotter.arch.specimen.boundary.ui",
    )

    private fun JavaClass.isIn(prefix: String) = packageName == prefix || packageName.startsWith("$prefix.")

    private fun JavaClass.isContainerManaged() = annotations.any { it.rawType.simpleName in SCOPE_ANNOTATIONS }

    private fun outboundPortRule(layers: Layers): ArchRule =
        classes().that(
            DescribedPredicate.describe("interfaces in application implemented by an infrastructure class") { type ->
                type.isInterface && type.isIn(layers.application) &&
                    type.allSubclasses.any { it.isIn(layers.infrastructure) }
            },
        ).should(
            object : ArchCondition<JavaClass>("reside in a port.outbound or port.inbound package") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    if (!item.packageName.contains(".port.outbound") && !item.packageName.contains(".port.inbound")) {
                        events.add(
                            SimpleConditionEvent.violated(
                                item,
                                "${item.name} is implemented by infrastructure but lives in no port package",
                            ),
                        )
                    }
                }
            },
        ).allowEmptyShould(true)

    private fun isApplicationBehaviour(type: JavaClass, layers: Layers): Boolean {
        val owner = type.enclosingClass.orElse(null)
        val exempt = type.packageName.contains(".port.inbound") || type.packageName.contains(".dto")
        val ownBehaviour = type.isIn(layers.application) && !exempt &&
            (type.isContainerManaged() || (type.isInterface && hasManagedImplementation(type, layers)))
        return ownBehaviour || (owner != null && isApplicationBehaviour(owner, layers))
    }

    private fun hasManagedImplementation(type: JavaClass, layers: Layers) =
        type.allSubclasses.any { (it.isIn(layers.application) || it.isIn(layers.infrastructure)) && it.isContainerManaged() }

    private fun inboundOnlyRule(layers: Layers): ArchRule =
        noClasses().that().resideInAPackage("${layers.ui}..")
            .should().dependOnClassesThat(
                DescribedPredicate.describe("application behaviour outside port.inbound and dto") {
                    isApplicationBehaviour(it, layers)
                },
            )
            .because("a view injects the input port of its use case, never an application service or an outbound contract")

    private fun violationDetails(rule: ArchRule, classes: JavaClasses) =
        rule.evaluate(classes).failureReport.details.joinToString("\n")

    @Test
    fun `application interfaces implemented by infrastructure live in a port package`() {
        outboundPortRule(productionLayers).check(production)
        val details = violationDetails(outboundPortRule(specimenLayers), specimens)
        assertTrue(details.contains("SpecimenLooseRepository"), details)
        assertFalse(details.contains("SpecimenOutboundPort"), details)
        assertFalse(details.contains("SpecimenReadModelQueryPort"), details)
        assertFalse(details.contains("SpecimenInternalContract"), details)
    }

    @Test
    fun `ui reaches application only through inbound ports, dtos and plug-in contracts`() {
        inboundOnlyRule(productionLayers).check(production)
        val details = violationDetails(inboundOnlyRule(specimenLayers), specimens)
        assertTrue(details.contains("SpecimenViolatingView") && details.contains("SpecimenAppService"), details)
        assertTrue(details.contains("SpecimenServiceContract"), details)
        assertFalse(details.contains("SpecimenCleanView"), details)
    }

    @Test
    fun `production contains the layers the rules guard, so they are not vacuous`() {
        assertTrue(production.any { it.isInterface && it.isIn(productionLayers.application) && it.packageName.contains(".port.outbound") })
        assertTrue(production.any { it.isIn(productionLayers.ui) && it.isContainerManaged() })
        assertTrue(production.any { it.isInterface && it.packageName.contains(".port.inbound") })
    }

    private companion object {
        val SCOPE_ANNOTATIONS = setOf("ApplicationScoped", "Singleton", "RequestScoped", "SessionScoped", "Dependent")
    }
}
