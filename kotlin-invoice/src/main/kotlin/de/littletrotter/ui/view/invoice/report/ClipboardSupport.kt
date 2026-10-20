package de.littletrotter.ui.view.invoice.report

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import de.littletrotter.ui.notification.UiNotifications

/**
 * Copies [markdown] to the browser clipboard via the Clipboard API, run
 * through the current [UI]'s page (`navigator.clipboard.writeText`). There was no existing
 * clipboard idiom in this codebase to reuse, so this is the one both [ValidationReportDialog]'s
 * and [AiReportDialog]'s copy buttons share.
 *
 * A failed write is surfaced as an error toast rather than swallowed — the whole point of the
 * button is a fast, trustworthy copy for email/chat, so a silent failure would be worse than no
 * button at all. A successful write is confirmed on the clicked [button] itself. [errorKey] is an
 * i18n key; the resolved error text is appended to
 * the error toast so a real browser-side failure (e.g. no secure context) is visible, not just
 * "something went wrong".
 */
internal fun copyMarkdownToClipboard(
    markdown: String,
    button: Button,
    tr: (String) -> String,
    errorKey: String = "invoiceDetail.jobs.report.copy.error",
) {
    val ui = UI.getCurrent()
    if (ui == null) {
        UiNotifications.error(tr(errorKey))
        return
    }
    ui.page.executeJs($$"navigator.clipboard.writeText($0)", markdown)
        .then(
            { UiNotifications.confirmOn(button) },
            { err: String -> UiNotifications.error("${tr(errorKey)}: $err") },
        )
}
