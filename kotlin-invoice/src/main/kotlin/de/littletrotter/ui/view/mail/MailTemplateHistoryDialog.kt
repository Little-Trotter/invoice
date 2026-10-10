package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateVersion
import de.littletrotter.application.security.AccessDeniedException
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import java.time.ZoneOffset

/**
 * Every stored version of one template. "Anzeigen" hands a version to the editor's preview;
 * "Wiederherstellen" copies it forward as a new current version, so history is only ever appended to
 * and an earlier wording can always be traced.
 */
internal class MailTemplateHistoryDialog(
    private val ports: MailTemplatePorts,
    private val kind: MailTemplateKind,
    private val language: MailTemplateLanguage,
    private val onShow: (MailTemplateVersion) -> Unit,
    private val onRestored: (MailTemplate) -> Unit,
) : AppDialog(
    UiTranslations.tr("mail.templates.history.title", UiTranslations.tr("mail.journal.kind.${kind.name}"), language.name),
    Size.L,
) {

    internal val grid = DataTable(MailTemplateVersion::class.java)

    init {
        grid.removeAllColumns()
        grid.addColumn { version -> versionLabel(version) }.setHeader(tr("mail.templates.column.version")).asCompactColumn()
        grid.addColumn { version -> DateFormatter.format(version.createdAt.atOffset(ZoneOffset.UTC)) }
            .setHeader(tr("mail.templates.column.changedAt")).asCompactColumn()
        grid.addColumn { version -> version.subject }.setHeader(tr("mail.templates.column.subject")).asTextColumn(TextColumnWidth.LARGE)
        grid.addColumn { version -> originLabel(version.seededDefault) }.setHeader(tr("mail.templates.column.origin")).asCompactColumn()
        grid.addColumn(ComponentRenderer { version: MailTemplateVersion -> actions(version) })
            .setKey("actions").asActionColumn().setHeader(tr("mail.templates.column.actions"))
        grid.setWidthFull()
        grid.height = "24rem"
        add(grid)
        footer(AppButtons.tertiary(tr("app.close")) { close() })
        load()
    }

    private fun load() {
        try {
            grid.setItems(ports.query.listVersions(kind, language))
        } catch (e: MailDispatchException) {
            grid.setItems(emptyList())
            UiNotifications.error(tr("mail.templates.loadFailed", e.message.orEmpty()))
        }
    }

    private fun versionLabel(version: MailTemplateVersion): String =
        if (version.current) "${version.version} (${tr("mail.templates.history.current")})" else version.version.toString()

    private fun originLabel(seededDefault: Boolean): String =
        tr(if (seededDefault) "mail.templates.origin.standard" else "mail.templates.origin.custom")

    private fun actions(version: MailTemplateVersion): HorizontalLayout =
        HorizontalLayout().apply {
            isPadding = false
            add(small("mail.templates.history.show") { onShow(version) })
            if (!version.current) add(small("mail.templates.history.restore") { restore(version) })
        }

    private fun small(labelKey: String, onClick: () -> Unit): Button =
        AppButtons.tertiary(tr(labelKey), null, onClick).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
        }

    private fun restore(version: MailTemplateVersion) {
        try {
            val restored = ports.templates.restoreVersion(kind, language, version.version)
            UiNotifications.success(tr("mail.templates.history.restored", version.version, restored.version))
            onRestored(restored)
            close()
        } catch (e: MailDispatchException) {
            UiNotifications.error(tr("mail.templates.actionFailed", e.message.orEmpty()))
        } catch (e: AccessDeniedException) {
            UiNotifications.error(tr("mail.templates.actionFailed", e.message.orEmpty()))
        }
    }

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(key, *params)
}
