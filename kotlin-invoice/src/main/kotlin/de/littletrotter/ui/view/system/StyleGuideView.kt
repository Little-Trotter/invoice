package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.StatusChip
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.confirmDelete
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import java.text.NumberFormat
import java.util.Locale

/**
 * Developer-only reference page that shows every shared UI building block next to each other, so a
 * change to a theme token or a component can be judged in light and dark mode on one screen. It is
 * deliberately not annotated with a route: it is registered at startup in development mode only,
 * so it never exists in a production deployment.
 */
class StyleGuideView : Div() {

    private data class MoneyRow(val description: String, val amount: Money)

    init {
        setSizeFull()
        val shell = PageShell(tr("styleguide.title"), tr("styleguide.subtitle"))
            .actions(darkModeToggle())
            .content(
                section("styleguide.section.buttons", buttonRow()),
                section("styleguide.section.actionBar", actionBarDemo()),
                section("styleguide.section.dialogs", dialogRow()),
                section("styleguide.section.chips", chipRow()),
                section("styleguide.section.fields", fieldRow()),
                section("styleguide.section.grid", moneyGrid()),
                section("styleguide.section.notifications", notificationRow()),
            )
        add(shell)
    }

    private fun tr(key: String): String = UiTranslations.tr(key)

    private fun section(titleKey: String, body: Component): Div =
        Div(H3(tr(titleKey)), body)

    private fun row(vararg components: Component): Div =
        Div(*components).apply { addClassName("lt-styleguide__row") }

    /** Lumo's dark attribute on the root element is the single switch the stylesheet reads. */
    private fun darkModeToggle(): Checkbox =
        Checkbox(tr("styleguide.dark")).apply {
            addValueChangeListener { event ->
                UI.getCurrent().page.executeJs(
                    $$"document.documentElement.setAttribute('theme', $0)",
                    if (event.value) DARK_THEME else LIGHT_THEME,
                )
            }
        }

    private fun buttonRow(): Div {
        val noop = {}
        val disabled = listOf(
            AppButtons.primary(tr("styleguide.button.primary"), onClick = noop),
            AppButtons.secondary(tr("styleguide.button.secondary"), onClick = noop),
            AppButtons.tertiary(tr("styleguide.button.tertiary"), onClick = noop),
            AppButtons.danger(tr("styleguide.button.danger"), onClick = noop),
        ).map { it.apply { isEnabled = false; text = tr("styleguide.button.disabled") } }
        return Div(
            row(
                AppButtons.primary(tr("styleguide.button.primary"), onClick = noop),
                AppButtons.secondary(tr("styleguide.button.secondary"), onClick = noop),
                AppButtons.tertiary(tr("styleguide.button.tertiary"), onClick = noop),
                AppButtons.danger(tr("styleguide.button.danger"), onClick = noop),
                AppButtons.cancel(noop),
            ),
            row(*disabled.toTypedArray()),
            row(
                AppButtons.primary(tr("styleguide.button.withIcon"), ActionIcons.ADD, noop),
                AppButtons.icon(ActionIcons.EDIT, tr("styleguide.button.iconTooltip"), noop),
                AppButtons.icon(ActionIcons.DELETE, tr("app.delete"), noop),
                AppButtons.icon(ActionIcons.DOWNLOAD, tr("styleguide.button.iconTooltip"), noop).apply { isEnabled = false },
            ),
        )
    }

    private fun actionBarDemo(): ActionBar =
        ActionBar(
            listOf(Span(tr("styleguide.actionBar.leading"))),
            AppButtons.cancel {},
            AppButtons.primary(tr("app.save"), ActionIcons.CONFIRM) {},
        )

    private fun dialogRow(): Div =
        row(
            dialogOpener("styleguide.dialog.small", AppDialog.Size.S),
            dialogOpener("styleguide.dialog.medium", AppDialog.Size.M),
            dialogOpener("styleguide.dialog.large", AppDialog.Size.L),
            AppButtons.danger(tr("styleguide.confirmDelete.open")) {
                confirmDelete(tr("styleguide.confirmDelete.title"), tr("styleguide.confirmDelete.text")) {}
            },
        )

    private fun dialogOpener(titleKey: String, size: AppDialog.Size) =
        AppButtons.secondary(tr(titleKey)) {
            val dialog = AppDialog(tr(titleKey), size)
            dialog.add(Span(tr("styleguide.dialog.text")))
            dialog.footer(
                AppButtons.cancel { dialog.close() },
                AppButtons.primary(tr("styleguide.dialog.confirm")) { dialog.close() },
            )
            dialog.open()
        }

    private fun chipRow(): Div =
        row(
            *CHIP_TONES.map { (tone, labelKey) ->
                StatusChip(tr(labelKey), tone)
            }.toTypedArray(),
        )

    private fun fieldRow(): Div =
        row(
            TextField(tr("styleguide.field.text")),
            ComboBox<String>(tr("styleguide.field.combo")).apply {
                setItems(tr("styleguide.field.option1"), tr("styleguide.field.option2"))
            },
            DatePicker(tr("styleguide.field.date")),
        )

    private fun moneyGrid(): Grid<MoneyRow> {
        val money = NumberFormat.getCurrencyInstance(Locale.GERMANY)
        return Grid<MoneyRow>().apply {
            addColumn { it.description }.setHeader(tr("styleguide.grid.description")).asTextColumn(TextColumnWidth.MEDIUM)
            addColumn { money.format(it.amount.amount) }.setHeader(tr("styleguide.grid.amount")).asNumericColumn()
            setItems(
                MoneyRow(tr("styleguide.grid.row1"), Money.of("1200.00")),
                MoneyRow(tr("styleguide.grid.row2"), Money.of("86.40")),
                MoneyRow(tr("styleguide.grid.row3"), Money.of("14999.99")),
            )
            height = GRID_HEIGHT
        }
    }

    private fun notificationRow(): Div =
        row(
            AppButtons.secondary(tr("styleguide.notify.success")) {
                UiNotifications.success(tr("styleguide.notify.successText"))
            },
            AppButtons.secondary(tr("styleguide.notify.warning")) {
                UiNotifications.warning(tr("styleguide.notify.warningText"))
            },
            AppButtons.secondary(tr("styleguide.notify.error")) {
                UiNotifications.error(tr("styleguide.notify.errorText"))
            },
            AppButtons.secondary(tr("styleguide.notify.info")) {
                UiNotifications.info(tr("styleguide.notify.infoText"))
            },
        )

    private companion object {
        const val DARK_THEME = "dark"
        const val LIGHT_THEME = ""
        const val GRID_HEIGHT = "11rem"

        /** Chip tone (matches the `--lt-chip-<tone>` token pair) to its label key. */
        val CHIP_TONES = listOf(
            ChipTone.OK to "styleguide.chip.ok",
            ChipTone.WARN to "styleguide.chip.warn",
            ChipTone.ERROR to "styleguide.chip.error",
            ChipTone.REVIEW to "styleguide.chip.review",
            ChipTone.TECH_ERROR to "styleguide.chip.techError",
            ChipTone.STORNO to "styleguide.chip.storno",
            ChipTone.NEUTRAL to "styleguide.chip.neutral",
            ChipTone.ACCENT to "styleguide.chip.accent",
        )
    }
}
