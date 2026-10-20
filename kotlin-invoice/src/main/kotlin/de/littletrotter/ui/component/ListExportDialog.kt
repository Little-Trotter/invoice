package de.littletrotter.ui.component

import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.server.streams.DownloadHandler

/** The theme variant `styles.css` paints as a two-segment switch; the control stays a plain radio group. */
private const val SEGMENTED_THEME_NAME = "lt-segmented"

/** Already translated texts of one list export dialog. */
data class ListExportDialogTexts(
    val title: String,
    val scopeLabel: String,
    val scope: String,
    val count: String,
    val formatLabel: String,
    val confirm: String,
    val cancel: String,
)

/** Element ids of the controls, so each list keeps its own addressable dialog. */
data class ListExportDialogIds(val format: String, val confirm: String, val cancel: String)

/** What the dialog does per chosen format: the download it hands out and the privacy hint it shows. */
data class ListExportFormatBehavior<F : Any>(
    val downloadFor: (F) -> DownloadHandler,
    val privacyHintFor: (F) -> String,
)

/**
 * The export dialog shared by the invoice and the offer list: scope and match count of the filter as it
 * stood at the click, a two-segment format choice (the first of [formats], each paired with its label, is preselected) and one
 * download anchor whose handler follows the chosen format. A privacy hint above the actions follows
 * the chosen format via [privacyHintFor]: the file holds personal data and a CSV has no cover sheet
 * to say so, hence the dialog is the notice both formats share. The filter is captured by [downloadFor],
 * so an edit made while the dialog is open never changes what the download builds.
 */
fun <F : Any> buildListExportDialog(
    texts: ListExportDialogTexts,
    ids: ListExportDialogIds,
    formats: List<Pair<F, String>>,
    behavior: ListExportFormatBehavior<F>,
): Dialog {
    val (downloadFor, privacyHintFor) = behavior
    val dialog = AppDialog(texts.title, AppDialog.Size.S)
    dialog.isCloseOnEsc = true
    dialog.isCloseOnOutsideClick = true

    val formatControl = RadioButtonGroup<F>().apply {
        setId(ids.format)
        label = texts.formatLabel
        val labels = formats.toMap()
        setItems(labels.keys)
        setItemLabelGenerator { format -> labels.getValue(format) }
        value = formats.first().first
        addThemeName(SEGMENTED_THEME_NAME)
        // The segments explain themselves; the group's label stays in the accessibility tree via aria-label.
        element.setAttribute("aria-label", texts.formatLabel)
    }

    val confirmButton = AppButtons.primary(texts.confirm, ActionIcons.DOWNLOAD) { dialog.close() }
        .apply { setId(ids.confirm) }
    val anchor = Anchor(downloadFor(formatControl.value), "").apply {
        element.setAttribute("download", true)
        add(confirmButton)
    }
    val privacyHint = Span(privacyHintFor(formats.first().first)).apply {
        setId("${ids.format}-privacy-hint")
        addClassName("lt-hint-text")
    }
    formatControl.addValueChangeListener { event ->
        privacyHint.text = privacyHintFor(event.value ?: formats.first().first)
        anchor.setHref(downloadFor(event.value ?: formats.first().first))
        anchor.element.setAttribute("download", true)
    }

    val cancelButton = AppButtons.tertiary(texts.cancel) { dialog.close() }.apply { setId(ids.cancel) }
    // Buttons sit in the body, not the footer slot: footer children are virtual and invisible to Dialog.getChildren().
    val actionsRow = ActionBar(cancelButton, anchor).apply {
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
    }
    dialog.add(
        VerticalLayout(Span(texts.scopeLabel), Span(texts.scope), Span(texts.count), formatControl, privacyHint, actionsRow)
            .apply { isPadding = false; isSpacing = true },
    )
    return dialog
}
