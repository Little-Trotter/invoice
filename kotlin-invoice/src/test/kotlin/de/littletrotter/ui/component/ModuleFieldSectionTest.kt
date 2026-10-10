package de.littletrotter.ui.component

import com.vaadin.flow.component.HasEnabled
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.spi.form.EnumeratedOption
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FieldSpec
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.spi.form.InvoiceFormModule
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ModuleFieldSection]: with no modules registered no module UI appears; a registered module
 * unconditionally renders its field at its slot.
 */
class ModuleFieldSectionTest {

    private val ctx = FormContext(InvoiceDto(), readOnly = false, Locale.GERMAN)
    private val binder = Binder(Invoice::class.java)

    private val projectNumberSpec = FieldSpec(
        id = "projectNumber",
        i18nKey = "invoiceModule.projectNumber",
        kind = FieldKind.TEXT,
        slot = FormSlot.OVERVIEW_REFERENCES,
        xmlTarget = de.littletrotter.spi.form.XmlTarget(businessTerm = B2bTerm.BT_11, xpath = "/Invoice/ProjectNumber"),
    )

    private class TestModule(val spec: FieldSpec) : InvoiceFormModule {
        override val id: String = "test-module"
        override fun fields(ctx: FormContext): List<FieldSpec> = listOf(spec)
    }

    @Test
    fun `no modules registered contributes neither fields nor layout`() {
        val target = FormLayout()
        val added = ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES, emptyList(), ctx, binder,
        ).attachTo(target)
        assertFalse(added)
        assertEquals(0, target.children.count())
    }

    @Test
    fun `a field for a different slot than the one being attached is filtered out`() {
        val otherSlotSpec = FieldSpec(
            id = "otherSlot",
            i18nKey = "invoiceModule.projectNumber",
            kind = FieldKind.TEXT,
            slot = FormSlot.PARTY_SELLER_LEGAL,
        )
        val module = object : InvoiceFormModule {
            override val id: String = "mixed-slot-module"
            override fun fields(ctx: FormContext): List<FieldSpec> = listOf(projectNumberSpec, otherSlotSpec)
        }
        val target = FormLayout()

        val added = ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(module), ctx, binder).attachTo(target)

        assertTrue(added)
        assertEquals(1, target.children.toList().filterIsInstance<TextField>().size, "only the matching-slot field must render")
    }

    @Test
    fun `a registered module renders a bound text field and counts as added`() {
        val module = TestModule(projectNumberSpec)
        val target = FormLayout()
        val added = ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES, listOf(module), ctx, binder,
        ).attachTo(target)
        assertTrue(added)
        assertEquals(1, target.children.toList().count { it is TextField })
    }

    @Test
    fun `read-only context makes the module field read-only`() {
        val module = TestModule(projectNumberSpec)
        val readOnlyCtx = FormContext(InvoiceDto(), readOnly = true, Locale.GERMAN)
        val target = FormLayout()
        val added = ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES, listOf(module), readOnlyCtx, binder,
        ).attachTo(target)
        assertTrue(added)
        val field = target.children.toList().mapNotNull { it as? TextField }.single()
        assertTrue(field.isReadOnly)
    }

    /** PO-Bugreport 2026-08-30 (/invoice/627): a spec naming a property the bound bean does NOT
     *  expose used to leave a dangling forField builder inside Binder — the next readBean then
     *  crashed the WHOLE form with IllegalStateException. Since the lambda-based reflective
     *  binding, the field renders unbound and readBean stays safe. */
    @Test
    fun `a spec without a matching bean property never breaks readBean`() {
        val module = TestModule(projectNumberSpec)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(module), ctx, binder).attachTo(target)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow { binder.readBean(Invoice()) }
    }

    @Test
    fun `a spec with a matching bean property round-trips through readBean and writeBean`() {
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val module = TestModule(projectNumberSpec)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(module), ctx, formBinder).attachTo(target)
        val field = target.children.toList().filterIsInstance<TextField>().single()

        val bean = de.littletrotter.application.invoice.InvoiceHeaderFormState(projectNumber = "PROJ-42")
        formBinder.readBean(bean)
        assertEquals("PROJ-42", field.value)

        field.value = "PROJ-43"
        val out = de.littletrotter.application.invoice.InvoiceHeaderFormState()
        formBinder.writeBean(out)
        assertEquals("PROJ-43", out.projectNumber)
    }

    // ─── createField: widget kind, colspan, disabled, helper text, required ──────────────────

    private fun specOf(
        kind: FieldKind,
        id: String = "field-$kind",
        options: List<EnumeratedOption> = emptyList(),
        defaultLabelColspan: Int = 1,
        required: Boolean = false,
        helperText: String? = null,
        helperTextKey: String? = null,
        enabled: Boolean = true,
        getter: ((Any) -> Any?)? = null,
        setter: ((Any, Any?) -> Unit)? = null,
    ) = FieldSpec(
        id = id,
        i18nKey = "invoiceModule.projectNumber",
        kind = kind,
        slot = FormSlot.OVERVIEW_REFERENCES,
        options = options,
        defaultLabelColspan = defaultLabelColspan,
        required = required,
        helperText = helperText,
        helperTextKey = helperTextKey,
        enabled = enabled,
        getter = getter,
        setter = setter,
    )

    @Test
    fun `DATE BOOLEAN and NUMBER kinds render a DatePicker, a Checkbox, and a numeric-patterned TextField`() {
        val dateModule = TestModule(specOf(FieldKind.DATE, id = "dateField"))
        val boolModule = TestModule(specOf(FieldKind.BOOLEAN, id = "boolField"))
        val numberModule = TestModule(specOf(FieldKind.NUMBER, id = "numberField"))
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(dateModule, boolModule, numberModule), ctx, binder).attachTo(target)

        val children = target.children.toList()
        assertTrue(children.any { it is DatePicker })
        assertTrue(children.any { it is Checkbox })
        val numberField = children.filterIsInstance<TextField>().single()
        assertEquals("\\d+([.,]\\d+)?", numberField.pattern)
    }

    @Test
    fun `ENUM kind renders a ComboBox whose item label falls back to the raw value for an unknown option`() {
        val spec = specOf(
            FieldKind.ENUM,
            id = "enumField",
            options = listOf(EnumeratedOption(value = "A", i18nKey = "invoiceModule.projectNumber")),
        )
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, binder).attachTo(target)

        assertTrue(target.children.toList().filterIsInstance<ComboBox<*>>().size == 1, "expected exactly one rendered ComboBox")
        assertEquals(
            de.littletrotter.ui.i18n.UiTranslations.tr("invoiceModule.projectNumber"),
            enumItemLabel("A", spec.options),
            "a known option value resolves its i18n label",
        )
        assertEquals(
            "unknown-value",
            enumItemLabel("unknown-value", spec.options),
            "an unrecognized value falls back to itself",
        )
    }

    @Test
    fun `a disabled but not read-only field is rendered non-interactive`() {
        val spec = specOf(FieldKind.TEXT, enabled = false)
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, binder).attachTo(target)

        val field = target.children.toList().single() as HasEnabled
        assertFalse(field.isEnabled)
    }

    @Test
    fun `a colspan greater than one is propagated to the FormLayout`() {
        val spec = specOf(FieldKind.TEXT, defaultLabelColspan = 2)
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, binder).attachTo(target)

        val field = target.children.toList().single()
        assertEquals(2, target.getColspan(field))
    }

    @Test
    fun `helperTextKey takes precedence over a plain helperText, and either is optional`() {
        val withKey = specOf(FieldKind.TEXT, id = "a", helperText = "raw text", helperTextKey = "app.ok")
        val withPlainOnly = specOf(FieldKind.TEXT, id = "b", helperText = "plain helper")
        val withNeither = specOf(FieldKind.TEXT, id = "c")
        val target = FormLayout()

        ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES,
            listOf(TestModule(withKey), TestModule(withPlainOnly), TestModule(withNeither)),
            ctx, binder,
        ).attachTo(target)

        val fields = target.children.toList().filterIsInstance<TextField>()
        assertEquals(de.littletrotter.ui.i18n.UiTranslations.tr("app.ok"), fields[0].helperText)
        assertEquals("plain helper", fields[1].helperText)
        assertTrue(fields[2].helperText.isNullOrEmpty())
    }

    @Test
    fun `required marks aria-required and the indicator on TEXT DATE and ENUM but not BOOLEAN`() {
        val textSpec = specOf(FieldKind.TEXT, id = "text", required = true)
        val dateSpec = specOf(FieldKind.DATE, id = "date", required = true)
        val enumSpec = specOf(FieldKind.ENUM, id = "enum", required = true)
        val boolSpec = specOf(FieldKind.BOOLEAN, id = "bool", required = true)
        val target = FormLayout()

        ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES,
            listOf(TestModule(textSpec), TestModule(dateSpec), TestModule(enumSpec), TestModule(boolSpec)),
            ctx, binder,
        ).attachTo(target)

        val children = target.children.toList()
        val textField = children.filterIsInstance<TextField>().single()
        val datePicker = children.filterIsInstance<DatePicker>().single()
        val comboBox = children.filterIsInstance<ComboBox<*>>().single()
        val checkbox = children.filterIsInstance<Checkbox>().single()

        assertTrue(textField.isRequiredIndicatorVisible)
        assertTrue(datePicker.isRequiredIndicatorVisible)
        assertTrue(comboBox.isRequiredIndicatorVisible)
        assertEquals("true", textField.element.getAttribute("aria-required"))
        assertEquals("true", checkbox.element.getAttribute("aria-required"))
    }

    @Test
    fun `read-only context makes a DatePicker Checkbox and ComboBox read-only too`() {
        val readOnlyCtx = FormContext(InvoiceDto(), readOnly = true, Locale.GERMAN)
        val dateSpec = specOf(FieldKind.DATE, id = "date")
        val boolSpec = specOf(FieldKind.BOOLEAN, id = "bool")
        val enumSpec = specOf(FieldKind.ENUM, id = "enum")
        val target = FormLayout()

        ModuleFieldSection(
            FormSlot.OVERVIEW_REFERENCES,
            listOf(TestModule(dateSpec), TestModule(boolSpec), TestModule(enumSpec)),
            readOnlyCtx, binder,
        ).attachTo(target)

        val children = target.children.toList()
        assertTrue(children.filterIsInstance<DatePicker>().single().isReadOnly)
        assertTrue(children.filterIsInstance<Checkbox>().single().isReadOnly)
        assertTrue(children.filterIsInstance<ComboBox<*>>().single().isReadOnly)
    }

    @Test
    fun `bindField's runCatching absorbs an exception a getter throws while initializing from an already-set bean`() {
        // Binder.bind(field, getter, setter) invokes the getter immediately, synchronously, when
        // the binder already has a bean attached (Binding.initFieldValue) — a throwing getter must
        // not break the whole form, same guarantee as the missing-property reflective path.
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        formBinder.bean = de.littletrotter.application.invoice.InvoiceHeaderFormState()
        val spec = specOf(
            FieldKind.TEXT,
            id = "boom",
            getter = { _ -> throw IllegalStateException("boom") },
            setter = { _, _ -> },
        )
        val target = FormLayout()

        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)
        }
    }

    @Test
    fun `a spec with only a getter and no setter falls back to reflective binding instead of using the lambda`() {
        var getterCalled = false
        val spec = specOf(FieldKind.TEXT, id = "customTotal", getter = { _ -> getterCalled = true; "ignored" })
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)
        formBinder.readBean(de.littletrotter.application.invoice.InvoiceHeaderFormState())

        assertFalse(getterCalled, "getter-only (no setter) must not take the lambda path — customTotal has no bean property either, so reflection leaves it unbound")
    }

    @Test
    fun `writeBean for a reflectively-bound spec without a matching property silently drops the value`() {
        val spec = specOf(FieldKind.TEXT, id = "nonExistentProp")
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)
        val field = target.children.toList().filterIsInstance<TextField>().single()
        field.value = "typed value"

        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            formBinder.writeBean(de.littletrotter.application.invoice.InvoiceHeaderFormState())
        }
    }

    @Test
    fun `a spec with custom getter and setter binds through the lambdas instead of reflection`() {
        // InvoiceHeaderFormState has no "customTotal" property at all — the reflective path
        // would leave the field unbound; the custom getter/setter must still round-trip it.
        var stored = "initial"
        val spec = specOf(
            FieldKind.TEXT,
            id = "customTotal",
            getter = { _ -> stored },
            setter = { _, value -> stored = value as? String ?: "" },
        )
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)
        val field = target.children.toList().filterIsInstance<TextField>().single()

        formBinder.readBean(de.littletrotter.application.invoice.InvoiceHeaderFormState())
        assertEquals("initial", field.value)

        field.value = "changed"
        formBinder.writeBean(de.littletrotter.application.invoice.InvoiceHeaderFormState())
        assertEquals("changed", stored)
    }

    @Test
    fun `boundary - componentFor returns the already-built widget for a rendered field, null otherwise`() {
        val module = TestModule(projectNumberSpec)
        val section = ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(module), ctx, binder)
        section.attachTo(FormLayout())

        assertTrue(section.componentFor("projectNumber") is TextField)
        assertEquals(null, section.componentFor("no-such-field"))
    }

    @Test
    fun `boundary - a BOOLEAN field's helper text is silently skipped because Checkbox has none`() {
        // applyHelperTextAndRequired's `field is HasHelper` check has a false branch only Checkbox
        // reaches — TextField/DatePicker/ComboBox all implement HasHelper.
        val spec = specOf(FieldKind.BOOLEAN, id = "boolWithHelper", helperText = "ignored for a checkbox")
        val target = FormLayout()

        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, binder).attachTo(target)
        }
        assertTrue(target.children.toList().single() is Checkbox)
    }

    private class FlagBean {
        var flag: Boolean = false
    }

    @Test
    fun `boundary - reflective binding matches a primitive-boolean setter via the isPrimitive fallback`() {
        // accessor's parameter-type check accepts either an assignable reference type or a
        // primitive target — `setFlag(boolean)` only matches through the isPrimitive branch since
        // a boxed java.lang.Boolean value is never assignable to the primitive boolean Class.
        val spec = specOf(FieldKind.BOOLEAN, id = "flag")
        val flagBinder = Binder(FlagBean::class.java)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, flagBinder).attachTo(target)
        val checkbox = target.children.toList().filterIsInstance<Checkbox>().single()

        flagBinder.readBean(FlagBean())
        checkbox.value = true
        val out = FlagBean()
        flagBinder.writeBean(out)

        assertTrue(out.flag)
    }

    // accessor()'s `property.replaceFirstChar { it.uppercaseChar() }` (ModuleFieldSection.kt:122)
    // is only ever exercised with a non-empty property name by every other test (every real
    // FieldSpec.id in this codebase names an actual JavaBean property); FieldSpec.id itself carries
    // no non-blank validation, so a blank id is a genuinely reachable, if degenerate, input to the
    // class's own public API — not a synthetic reflection hack.
    @Test
    fun `boundary - a blank FieldSpec id degrades to a reflective lookup for a literal "get"-or-"set" method`() {
        val spec = specOf(FieldKind.TEXT, id = "")
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val target = FormLayout()

        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { formBinder.readBean(de.littletrotter.application.invoice.InvoiceHeaderFormState()) }
    }

    // accessor()'s `it.parameterCount == 1` check (ModuleFieldSection.kt:124) only ever
    // sees real, correctly-shaped setters in every other test; a bean whose only "setXxx"-named
    // method for a property has the wrong arity forces firstOrNull to evaluate — and reject — that
    // mismatch, deterministically (no other same-named method exists to short-circuit the search
    // first, so JVM reflection's unspecified method order cannot make this flaky).
    private class WrongArityBean {
        fun getFlag(): String = "unused"
        // Wrong arity on purpose — never matched, see accessor()'s parameterCount check. Both
        // parameters are referenced (not just declared) so this stays a realistic 2-arg setter
        // shape rather than a synthetic no-op.
        fun setFlag(a: String, b: String) {
            check(a.isNotEmpty() || b.isNotEmpty())
        }
    }

    @Test
    fun `boundary - a property whose only same-named setter has the wrong arity is rendered but unbound`() {
        val spec = specOf(FieldKind.TEXT, id = "flag")
        val weirdBinder = Binder(WrongArityBean::class.java)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, weirdBinder).attachTo(target)
        val field = target.children.toList().filterIsInstance<TextField>().single()
        field.value = "typed value"

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { weirdBinder.writeBean(WrongArityBean()) }
    }

    @Test
    fun `boundary - writeBean invokes the reflective setter with a null value for an unpopulated DATE field`() {
        // DatePicker's empty value is null, not a sentinel — the reflective setter lambda's
        // `value?.javaClass` safe call must handle that null without throwing.
        val spec = specOf(FieldKind.DATE, id = "dueDate")
        val formBinder = Binder(de.littletrotter.application.invoice.InvoiceHeaderFormState::class.java)
        val target = FormLayout()
        ModuleFieldSection(FormSlot.OVERVIEW_REFERENCES, listOf(TestModule(spec)), ctx, formBinder).attachTo(target)
        val bean = de.littletrotter.application.invoice.InvoiceHeaderFormState(
            dueDate = java.time.LocalDate.of(2026, 1, 1),
        )

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { formBinder.writeBean(bean) }

        assertEquals(null, bean.dueDate)
    }
}
