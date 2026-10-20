package de.littletrotter.ui.view.invoice

import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationHintsTest {

    // A maintained rule ID resolves to its German hint text.
    @Test
    fun `resolves the hint for a maintained rule id`() {
        val hint = resolveValidationHint("BR-CO-25")
        assertTrue(hint != null && hint.isNotBlank())
    }

    // An unmaintained rule ID resolves to null, not a placeholder.
    @Test
    fun `returns null for an unmaintained rule id`() {
        val hint = resolveValidationHint("BR-XX-999-DOES-NOT-EXIST")
        assertNull(hint)
        // Explicitly guard against the "!key!" placeholder UiTranslations.tr would produce —
        // that's precisely the failure mode this dedicated resolver exists to avoid.
        assertFalse(hint?.startsWith("!") == true)
    }

    // Hyphens and casing in the rule ID are used verbatim in the lookup key; a
    // near-miss (wrong case) fails cleanly (null), never throws.
    @Test
    fun `hyphenated rule id is looked up verbatim, wrong case fails cleanly`() {
        assertTrue(resolveValidationHint("BR-DE-17") != null)
        assertNull(resolveValidationHint("br-de-17"))
    }

    // A finding without a rule ID (null/blank code) has no hint, no exception.
    @Test
    fun `finding without a rule id has no hint`() {
        assertNull(resolveValidationHint(null))
        assertNull(resolveValidationHint(""))
        assertNull(resolveValidationHint("   "))
    }

    // Every validation.rule.*.hint key actually maintained in the bundle has a
    // non-blank value (catches an accidentally empty catalog entry).
    @Test
    fun `every maintained rule hint key has a non-blank value`() {
        val bundle = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
        val hintKeys = bundle.keys.toList().filter { it.startsWith("validation.rule.") && it.endsWith(".hint") }
        assertTrue(hintKeys.isNotEmpty(), "Expected at least one validation.rule.*.hint key")
        hintKeys.forEach { key ->
            assertFalse(bundle.getString(key).isBlank(), "Empty hint value for $key")
        }
    }

    // loadBundle's own MissingResourceException catch branch: the real basename always
    // resolves on this classpath, so a nonexistent one is required to actually hit it.
    @Test
    fun `happy path - loadBundle returns the bundle when the resource exists`() {
        assertTrue(ValidationHints.loadBundle("i18n.messages", Locale.GERMAN) != null)
    }

    @Test
    fun `boundary - loadBundle returns null when the resource bundle does not exist`() {
        assertNull(ValidationHints.loadBundle("i18n.does-not-exist-for-cov-23", Locale.GERMAN))
    }

    // The bundle == null short-circuit in resolve(ruleId, bundle) can only be driven
    // through the internal (bundle) overload; the cached singleton bundle is never null.
    @Test
    fun `boundary - resolve returns null when no bundle is available, before even looking up the key`() {
        assertNull(ValidationHints.resolve("BR-CO-25", bundle = null))
    }
}
