package de.littletrotter.ui.view.referencedata

import de.littletrotter.ui.view.invoice.componentFor
import de.littletrotter.ui.view.invoice.requireAs
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._setValue
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.reference.ValidationSetting
import de.littletrotter.domain.repository.ValidationSettingRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TIME_SOURCE

/** The grid's checkbox listener: client-event guard, success and failure paths. */
class ValidationSettingsTabTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    // ── the actual grid checkbox listener (toggleFor) — isFromClient guard, success and failure
    //    paths, both notification wordings ─────────────────────────────────────────────────────

    private val someCapability = ValidationCapabilityId.OMLX_LLM_CHECK

    private fun grid(tab: ValidationSettingsTab): Grid<ValidationCapabilityId> {
        val f = ValidationSettingsTab::class.java.getDeclaredField("grid")
        f.isAccessible = true
        return f.get(tab).requireAs<Grid<ValidationCapabilityId>>()
    }

    private fun toggleCheckbox(tab: ValidationSettingsTab, capability: ValidationCapabilityId): Checkbox {
        val column = grid(tab).columns.first { it.headerText == tab.tr("referenceData.validations.enabled") }
        return column.componentFor(capability) as Checkbox
    }

    private class ThrowingValidationSettingRepository : ValidationSettingRepository {
        override fun findAll(): List<ValidationSetting> = emptyList()
        override fun findById(capability: ValidationCapabilityId): ValidationSetting? = null
        override fun save(setting: ValidationSetting): ValidationSetting = error("boom")
    }

    private class MessagelessThrowingValidationSettingRepository : ValidationSettingRepository {
        // check()/error() always require a message, so they cannot produce the null-message
        // exception this test needs; built as a value first (not `throw IllegalStateException()`
        // at the call site) so IllegalStateException stays the caught type without re-triggering
        // UseCheckOrError, which only matches a constructor call right at the throw site.
        private val messageless: Throwable = IllegalStateException()

        override fun findAll(): List<ValidationSetting> = emptyList()
        override fun findById(capability: ValidationCapabilityId): ValidationSetting? = null
        override fun save(setting: ValidationSetting): ValidationSetting = throw messageless
    }

    // Same idiom as ReceiptDialogActionsKaribuTest.allComponents/assertNotificationShown — a plain
    // Notification.show(...) (UiNotifications.success) sets its text as a "text" element property
    // with no child Span, while UiNotifications.error wraps its message in an actual Span.
    private fun allComponents(): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(element: com.vaadin.flow.dom.Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(com.vaadin.flow.component.UI.getCurrent().element)
        return found
    }

    private fun assertNotificationShown(expectedText: String) {
        MockVaadin.clientRoundtrip()
        val all = allComponents()
        val plainTextMatch = all.filterIsInstance<com.vaadin.flow.component.notification.Notification>()
            .any { de.littletrotter.ui.toastText(it) == expectedText }
        val spanMatch = all.filterIsInstance<com.vaadin.flow.component.html.Span>().any { it.text == expectedText }
        assertTrue(plainTextMatch || spanMatch, "expected an open notification with text: $expectedText")
    }

    @Test
    fun `a programmatic checkbox change never calls gate setEnabled - not from a real client`() {
        val gate = validationSettingsGate()
        val tab = ValidationSettingsTab(gate)
        val checkbox = toggleCheckbox(tab, someCapability)

        checkbox.value = !checkbox.value // plain server-side set, isFromClient = false

        assertEquals(true, gate.allSettings()[someCapability])
    }

    @Test
    fun `checking a switch off as a real client action persists it without a toast`() {
        val gate = validationSettingsGate()
        val tab = ValidationSettingsTab(gate)
        val checkbox = toggleCheckbox(tab, someCapability)

        checkbox._setValue(false)

        assertFalse(gate.allSettings().getValue(someCapability))
        assertTrue(getNotifications().isEmpty(), "the checkbox itself is the status")
    }

    @Test
    fun `checking a switch on as a real client action persists it without a toast`() {
        val gate = validationSettingsGate(someCapability) // starts disabled
        val tab = ValidationSettingsTab(gate)
        val checkbox = toggleCheckbox(tab, someCapability)

        checkbox._setValue(true)

        assertTrue(gate.allSettings().getValue(someCapability))
        assertTrue(getNotifications().isEmpty(), "the checkbox itself is the status")
    }

    @Test
    fun `a failing gate setEnabled reverts the checkbox and shows an error notification`() {
        val gate = ValidationSettingsGate(ThrowingValidationSettingRepository(), time = FIXED_TIME_SOURCE)
        val tab = ValidationSettingsTab(gate)
        val checkbox = toggleCheckbox(tab, someCapability)

        checkbox._setValue(false)

        assertTrue(checkbox.value, "the failed change must be reverted back to true")
        assertNotificationShown("boom")
    }

    @Test
    fun `a failing gate setEnabled with no exception message falls back to the generic error text`() {
        val gate = ValidationSettingsGate(MessagelessThrowingValidationSettingRepository(), time = FIXED_TIME_SOURCE)
        val tab = ValidationSettingsTab(gate)
        val checkbox = toggleCheckbox(tab, someCapability)

        checkbox._setValue(false)

        assertTrue(checkbox.value, "the failed change must be reverted back to true")
        assertNotificationShown(tab.tr("common.error"))
    }
}
