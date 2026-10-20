package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.notification.Notification
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Buttons, dialogs and toasts come from one place each, so every screen looks and behaves alike.
 * Outside the shared component packages (the app's own and the standalone component library) nobody constructs a plain Vaadin `Button` (the app's button
 * factory sets size, variants and accessibility once), nobody constructs or directly extends a
 * plain `Dialog` (the app dialog fixes widths and footer order), and only the notification
 * helper shows toasts (it owns duration, position and severity styling).
 * Each rule is checked against production (must pass) and a violating specimen (must fail).
 */
class UiComponentArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.ui")

    private fun constructs(type: Class<*>) =
        object : DescribedPredicate<JavaConstructorCall>("construct ${type.simpleName}") {
            override fun test(call: JavaConstructorCall) = call.target.owner.isEquivalentTo(type)
        }

    private val noPlainButton: ArchRule = noClasses()
        .that().resideOutsideOfPackages(COMPONENT_PACKAGE, COMPONENT_LIBRARY_PACKAGE)
        .should().callConstructorWhere(constructs(Button::class.java))

    private val noPlainDialogConstruction: ArchRule = noClasses()
        .that().resideOutsideOfPackages(COMPONENT_PACKAGE, COMPONENT_LIBRARY_PACKAGE)
        .should().callConstructorWhere(constructs(Dialog::class.java))

    private val noPlainDialogSubclass: ArchRule = classes()
        .that().resideOutsideOfPackages(COMPONENT_PACKAGE, COMPONENT_LIBRARY_PACKAGE)
        .should(
            object : ArchCondition<JavaClass>("not directly extend Dialog (extend AppDialog instead)") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    if (item.rawSuperclass.map { it.isEquivalentTo(Dialog::class.java) }.orElse(false)) {
                        events.add(SimpleConditionEvent.violated(item, "${item.name} extends Dialog directly"))
                    }
                }
            },
        )

    private val onlyHelperShowsNotifications: ArchRule = noClasses()
        .that(
            DescribedPredicate.describe("are not the notification helper") { type: JavaClass ->
                !type.name.startsWith(NOTIFICATION_HELPER)
            },
        )
        .should().callMethodWhere(
            object : DescribedPredicate<JavaMethodCall>("show a Notification") {
                override fun test(call: JavaMethodCall) =
                    call.target.owner.isEquivalentTo(Notification::class.java) && call.target.name == "show"
            },
        )

    private fun passes(rule: ArchRule, classes: JavaClasses) = !rule.evaluate(classes).hasViolation()

    private fun violationsMention(rule: ArchRule, name: String) =
        rule.evaluate(specimens).failureReport.details.any { it.contains(name) }

    @Test
    fun `only shared components construct a plain Button`() {
        noPlainButton.check(productionClasses)
        assertTrue(violationsMention(noPlainButton, "ViolatingButtonCreator.plain"))
        assertTrue(violationsMention(noPlainButton, "ViolatingButtonCreator.withListener"))
        assertFalse(violationsMention(noPlainButton, "CompliantAppDialogSubclass"))
    }

    @Test
    fun `only shared components construct a plain Dialog`() {
        noPlainDialogConstruction.check(productionClasses)
        assertTrue(violationsMention(noPlainDialogConstruction, "ViolatingDialogCreator"))
        assertFalse(violationsMention(noPlainDialogConstruction, "CompliantAppDialogSubclass"))
    }

    @Test
    fun `dialogs outside the shared components extend AppDialog, not Dialog`() {
        noPlainDialogSubclass.check(productionClasses)
        assertTrue(violationsMention(noPlainDialogSubclass, "ViolatingDialogSubclass"))
        assertFalse(violationsMention(noPlainDialogSubclass, "CompliantAppDialogSubclass"))
    }

    @Test
    fun `only the notification helper shows notifications`() {
        onlyHelperShowsNotifications.check(productionClasses)
        assertTrue(violationsMention(onlyHelperShowsNotifications, "ViolatingNotificationCaller"))
    }

    @Test
    fun `production actually uses the shared components, so the rules are not vacuous`() {
        assertTrue(productionClasses.any { it.name == "de.littletrotter.ui.component.AppDialog" })
        assertTrue(
            productionClasses.any { type ->
                type.name.startsWith(NOTIFICATION_HELPER) &&
                    type.methodCallsFromSelf.any { it.target.owner.isEquivalentTo(Notification::class.java) }
            },
        )
        assertFalse(passes(noPlainButton, specimens))
    }

    private companion object {
        const val COMPONENT_PACKAGE = "de.littletrotter.ui.component.."

        /** Separate library module the app depends on; it cannot reach the app's own button factory. */
        const val COMPONENT_LIBRARY_PACKAGE = "de.littletrotter.ui.components.."
        const val NOTIFICATION_HELPER = "de.littletrotter.ui.notification.UiNotifications"
    }
}
