package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import java.time.LocalDate

/**
 * Typed replacement for [B2bFieldWidgetFactory]'s former generic `Component` dispatch.
 * Each variant carries its own genuinely-typed Vaadin field, set once at construction time and
 * never re-obtained through a cast, so [setValue]/[value] need no unchecked generic cast anywhere
 * — unlike a raw `ComboBox<*>`, [Enum.field] is `ComboBox<String>` from the moment
 * [B2bFieldWidgetFactory.createWidget] builds it.
 *
 * [Unsupported] is the explicit typed case for a [Component] this factory never builds itself
 * (the ComboBox/DatePicker/TextField union is exhaustive for every [de.littletrotter.shared.b2b
 * .B2bFieldKind]) — [B2bFieldWidgetFactoryTest] exercises it directly against an arbitrary
 * component (e.g. a plain `Span`), a tested, documented no-op/`null` contract, not merely an
 * implementation detail of the two production callers ([B2bProfileFieldsSection],
 * [ScopedB2bProfileFieldsSection]).
 */
sealed class B2bFieldWidget {

    /** The underlying Vaadin component, for layout placement and the
     *  [com.vaadin.flow.component.HasHelper]/[com.vaadin.flow.component.HasValue] casts the two
     *  production callers already apply uniformly across every widget kind. */
    abstract val component: Component

    abstract fun setValue(value: String?)

    abstract fun value(): String?

    class Text(val field: TextField) : B2bFieldWidget() {
        override val component: Component = field
        override fun setValue(value: String?) { field.value = value.orEmpty() }
        override fun value(): String? = field.value?.trim()?.ifBlank { null }
    }

    class Date(val field: DatePicker) : B2bFieldWidget() {
        override val component: Component = field
        override fun setValue(value: String?) {
            field.value = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
        override fun value(): String? = field.value?.toString()
    }

    /** [dataProvider] is the exact instance [field]'s lazy items were built from — captured at
     *  construction time, before [ComboBox]'s own erased `getDataProvider(): DataProvider<T, ?>`
     *  ever has a chance to lose its filter type, so a caller that needs to drive fetch/count
     *  directly (its own tests) never has to cast a wildcard filter type back to [String]. */
    class Enum(val field: ComboBox<String>, val dataProvider: DataProvider<String, String>) : B2bFieldWidget() {
        override val component: Component = field
        override fun setValue(value: String?) { field.value = value }
        override fun value(): String? = field.value
    }

    class Unsupported(override val component: Component) : B2bFieldWidget() {
        override fun setValue(value: String?) = Unit
        override fun value(): String? = null
    }
}
