package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Span
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Regression coverage for every persisted invoice unit and every supported locale path. */
class InvoiceUnitLabelsTest {
    private val german = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)
    private val english = ResourceBundle.getBundle(
        "i18n.messages",
        Locale.ENGLISH,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `every unit resolves its canonical German label`() {
        InvoiceItemUnit.entries.forEach { unit ->
            val resolved = InvoiceUnitLabels.label(unit) { key -> german.getStringOrNull(key) }

            assertEquals(
                expectedLabel(german, unit),
                resolved,
                "missing German invoice unit label for ${unit.labelKey}",
            )
            assertFalse(resolved == unit.name, "raw enum leaked for $unit")
        }
    }

    @Test
    fun `every unit resolves its canonical English label`() {
        InvoiceItemUnit.entries.forEach { unit ->
            val resolved = InvoiceUnitLabels.label(unit) { key -> english.getStringOrNull(key) }

            assertEquals(
                expectedLabel(english, unit),
                resolved,
                "missing English invoice unit label for ${unit.labelKey}",
            )
            assertFalse(resolved == unit.name, "raw enum leaked for $unit")
        }
    }

    @Test
    fun `missing or unsupported locale falls back to German for every unit`() {
        InvoiceItemUnit.entries.forEach { unit ->
            assertEquals(
                unit.germanLabel(plural = false),
                InvoiceUnitLabels.label(unit) { null },
                "unsupported locale must use the German fallback for $unit",
            )
        }
    }

    @Test
    fun `canonical unit key is used when invoice detail key is absent`() {
        InvoiceItemUnit.entries.forEach { unit ->
            val resolved = InvoiceUnitLabels.label(unit) { key ->
                if (key == unit.labelKey) "canonical ${unit.name}" else null
            }
            assertEquals("canonical ${unit.name}", resolved)
        }
    }

    @Test
    fun `unknown persisted unit never becomes user visible enum text`() {
        // No UI locale means the application default, German, is used explicitly.
        MockVaadin.tearDown()
        val resolved = InvoiceUnitLabels.label(null, "NOT_A_UNIT")
        assertEquals("Unbekannte Einheit", resolved)
        assertFalse(resolved.contains("NOT_A_UNIT"))
    }

    @Test
    fun `component route honors DE EN FR and no UI for every unit`() {
        val ui = UI.getCurrent()
        val component = Span().also(ui::add)

        ui.locale = Locale.GERMAN
        InvoiceItemUnit.entries.forEach { unit ->
            assertEquals(expectedLabel(german, unit), InvoiceUnitLabels.label(component, unit))
        }

        ui.locale = Locale.ENGLISH
        InvoiceItemUnit.entries.forEach { unit ->
            assertEquals(expectedLabel(english, unit), InvoiceUnitLabels.label(component, unit))
        }

        // TranslationService supports DE/EN only. A French UI must use the documented German
        // fallback rather than ResourceBundle's base English bundle.
        ui.locale = Locale.FRENCH
        InvoiceItemUnit.entries.forEach { unit ->
            assertEquals(expectedLabel(german, unit), InvoiceUnitLabels.label(component, unit))
        }

        MockVaadin.tearDown()
        InvoiceItemUnit.entries.forEach { unit ->
            assertEquals(expectedLabel(german, unit), InvoiceUnitLabels.label(Span(), unit))
        }
    }

    @Test
    fun `component route localizes unknown persisted values`() {
        val ui = UI.getCurrent()
        val component = Span().also(ui::add)
        ui.locale = Locale.ENGLISH
        assertEquals("Unknown unit", InvoiceUnitLabels.label(component, "NOT_A_UNIT"))
        ui.locale = Locale.FRENCH
        assertEquals("Unbekannte Einheit", InvoiceUnitLabels.label(component, "NOT_A_UNIT"))
    }

    @Test
    fun `unit consistency arguments are translated at render time`() {
        assertEquals(
            "Quadratmeter (m2)",
            InvoiceUnitLabels.validationArgument(null, "compliance.unit.unitCodeMismatch", 2, InvoiceItemUnit.SQUARE_METER.name),
        )
        assertEquals(
            "HOUR",
            InvoiceUnitLabels.validationArgument(null, "compliance.unit.quantityNotPositive", 0, InvoiceItemUnit.HOUR.name),
        )
    }

    private fun expectedLabel(bundle: ResourceBundle, unit: InvoiceItemUnit): String =
        bundle.getStringOrNull("invoiceDetail.${unit.labelKey}")
            ?: bundle.getString(unit.labelKey)

    private fun ResourceBundle.getStringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    // ── boundary tests ──────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary - the plural German fallback differs from the singular for HOUR`() {
        assertEquals("Stunde", InvoiceUnitLabels.label(InvoiceItemUnit.HOUR, plural = false) { null })
        assertEquals("Stunden", InvoiceUnitLabels.label(InvoiceItemUnit.HOUR, plural = true) { null })
    }

    @Test
    fun `boundary - validationArgument translates the description-token-quantity index`() {
        assertEquals(
            expectedLabel(german, InvoiceItemUnit.HOUR),
            InvoiceUnitLabels.validationArgument(
                null,
                "compliance.unit.quantityLikeTokenInDescription",
                1,
                InvoiceItemUnit.HOUR.name,
            ),
        )
    }

    @Test
    fun `boundary - an unrelated finding key never translates its argument`() {
        assertEquals(
            InvoiceItemUnit.HOUR.name,
            InvoiceUnitLabels.validationArgument(null, "compliance.unrelated.finding", 0, InvoiceItemUnit.HOUR.name),
        )
    }

    @Test
    fun `boundary - unitCodeMismatch at a non-unit index is left untranslated`() {
        assertEquals(
            InvoiceItemUnit.HOUR.name,
            InvoiceUnitLabels.validationArgument(null, "compliance.unit.unitCodeMismatch", 0, InvoiceItemUnit.HOUR.name),
        )
    }
}
