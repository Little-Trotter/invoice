package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.mail.port.inbound.MailAvailabilityQueryPort
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplate
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.mail.port.inbound.MailTemplateQueryPort
import de.littletrotter.application.mail.port.inbound.MailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.ResetMailTemplateUseCase
import de.littletrotter.application.mail.port.inbound.SendMailTemplateBccTestUseCase
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.InvoiceListView
import jakarta.inject.Inject
import java.time.ZoneOffset

/**
 * The mail templates: two kinds in two languages, so the list is small and sorts and filters in the
 * grid itself, with the same kind filter and "only edited" switch the journal uses.
 *
 * A double click or "Bearbeiten" opens [MailTemplateEditorDialog]; "Verlauf" opens the version list
 * of that row. Only reachable while the component is part of the instance, like the journal.
 */
@Route(value = "mail/templates", layout = MainLayout::class)
@PageTitle("E-Mail-Vorlagen")
class MailTemplatesView
@Inject
constructor(
    templateQuery: MailTemplateQueryPort,
    catalogQuery: MailPlaceholderCatalogQueryPort,
    previewQuery: MailRenderPreviewQueryPort,
    templateUseCase: MailTemplateUseCase,
    resetUseCase: ResetMailTemplateUseCase,
    bccTestUseCase: SendMailTemplateBccTestUseCase,
    private val mailAvailability: MailAvailabilityQueryPort,
) : VerticalLayout(), BeforeEnterObserver {

    private val ports = MailTemplatePorts(templateQuery, catalogQuery, previewQuery, templateUseCase, resetUseCase, bccTestUseCase)
    private val dataProvider = ListDataProvider<MailTemplate>(mutableListOf())

    internal val grid = DataTable(MailTemplate::class.java)
    internal val kindFilter = ComboBox<MailTemplateKind>()
    internal val languageFilter = ComboBox<MailTemplateLanguage>()
    internal val customOnlyToggle = Checkbox(tr("mail.templates.filter.customOnly"))

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureFilters()
        configureColumns()
        grid.setItems(dataProvider)
        grid.addItemDoubleClickListener { event -> event.item?.let { openEditor(it) } }
        val wrapper = GridWrapper(grid).apply { setSizeFull() }
        add(PageShell(tr("mail.templates.title")).filters(filterRow()).content(wrapper))
        expand(wrapper)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!mailAvailability.enabled) {
            event.forwardTo(InvoiceListView::class.java)
            return
        }
        reload()
    }

    internal fun reload() {
        try {
            dataProvider.items.clear()
            dataProvider.items.addAll(ports.query.listTemplates())
        } catch (e: MailDispatchException) {
            // Stale rows shown as current would be worse than none.
            dataProvider.items.clear()
            UiNotifications.error(tr("mail.templates.loadFailed", e.message.orEmpty()))
        }
        dataProvider.refreshAll()
    }

    private fun configureFilters() {
        kindFilter.placeholder = tr("mail.templates.filter.kind")
        kindFilter.setItems(*MailTemplateKind.entries.toTypedArray())
        kindFilter.setItemLabelGenerator { tr("mail.journal.kind.${it.name}") }
        languageFilter.placeholder = tr("mail.templates.filter.language")
        languageFilter.setItems(*MailTemplateLanguage.entries.toTypedArray())
        languageFilter.setItemLabelGenerator { it.name }
        listOf(kindFilter, languageFilter).forEach {
            it.isClearButtonVisible = true
            it.width = WIDE_COLUMN
            it.addValueChangeListener { applyFilter() }
        }
        customOnlyToggle.addValueChangeListener { applyFilter() }
    }

    private fun filterRow(): HorizontalLayout =
        HorizontalLayout(kindFilter, languageFilter, customOnlyToggle).apply {
            defaultVerticalComponentAlignment = Alignment.BASELINE
            isPadding = false
        }

    internal fun applyFilter() {
        val kind = kindFilter.value
        val language = languageFilter.value
        val customOnly = customOnlyToggle.value
        dataProvider.setFilter { template ->
            (kind == null || template.kind == kind) &&
                (language == null || template.language == language) &&
                (!customOnly || !template.seededDefault)
        }
    }

    private fun configureColumns() {
        grid.removeAllColumns()
        column("kind", "mail.templates.column.kind", { tr("mail.journal.kind.${it.kind.name}") }) { asTextColumn(TextColumnWidth.SMALL) }
        column("language", "mail.templates.column.language", { it.language.name }) { asCompactColumn() }
        grid.addColumn { it.version }.setHeader(tr("mail.templates.column.version")).setKey("version")
            .setSortable(true).setComparator(Comparator.comparingInt { it.version }).asCompactColumn()
        column("subject", "mail.templates.column.subject", { it.subject }) { asTextColumn(TextColumnWidth.LARGE) }
        grid.addColumn { DateFormatter.format(it.createdAt.atOffset(ZoneOffset.UTC)) }
            .setHeader(tr("mail.templates.column.changedAt")).setKey("changedAt")
            .setSortable(true).setComparator(Comparator.comparing { it.createdAt }).asCompactColumn()
        column("origin", "mail.templates.column.origin", { originLabel(it) }) { asCompactColumn() }
        column("bcc", "mail.templates.column.bcc", { it.bccAddress ?: "–" }) { asTextColumn(TextColumnWidth.MEDIUM) }
        grid.addColumn(ComponentRenderer { template: MailTemplate -> actions(template) })
            .setKey("actions").asActionColumn().setHeader(tr("mail.templates.column.actions"))
        grid.element.setAttribute("aria-label", tr("mail.templates.table.ariaLabel"))
    }

    private fun column(
        key: String,
        headerKey: String,
        text: (MailTemplate) -> String,
        shape: Grid.Column<MailTemplate>.() -> Unit,
    ) {
        grid.addColumn { text(it) }
            .setHeader(tr(headerKey)).setKey(key)
            .setSortable(true).setComparator(Comparator.comparing { text(it) })
            .shape()
    }

    private fun originLabel(template: MailTemplate): String =
        tr(if (template.seededDefault) "mail.templates.origin.standard" else "mail.templates.origin.custom")

    private fun actions(template: MailTemplate): Component =
        HorizontalLayout(
            AppButtons.icon(ActionIcons.EDIT, tr("mail.templates.action.edit")) { openEditor(template) },
            AppButtons.icon(ActionIcons.HISTORY, tr("mail.templates.action.history")) { openHistory(template) },
        ).apply {
            isPadding = false
            isSpacing = false
        }

    private fun openEditor(template: MailTemplate): MailTemplateEditorDialog =
        MailTemplateEditorDialog(ports, template) { reload() }.also { it.open() }

    private fun openHistory(template: MailTemplate) {
        MailTemplateHistoryDialog(
            ports,
            template.kind,
            template.language,
            onShow = { version -> openEditor(template).previewVersion(version) },
            onRestored = { reload() },
        ).open()
    }

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(key, *params)

    private companion object {
        const val WIDE_COLUMN = "14rem"
    }
}
