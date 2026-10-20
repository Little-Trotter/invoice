package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.MaintainAttachmentLibraryUseCase
import de.littletrotter.application.quote.port.inbound.StoredQuoteReduction
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import jakarta.inject.Inject

/**
 * The offer receipt pool: reusable documents (terms, instructions, data sheets) offered to every
 * offer as attachments. Each entry has numbered versions that are never changed; a new file is a
 * new version and the old ones stay readable, so it can be shown which version went out with an
 * offer. Everyone who may view offers sees the pool and can open its files; adding entries and
 * versions needs the library permission, archiving its own one. Archived entries are hidden by
 * default and can no longer be chosen for offers.
 */
@Route(value = QuoteRoutes.RECEIPT_POOL, layout = MainLayout::class)
@PageTitle("Belegpool")
class QuoteReceiptPoolView
@Inject
constructor(
    private val query: AttachmentLibraryQueryPort,
    private val maintain: MaintainAttachmentLibraryUseCase,
    private val readability: ReceiptReadabilityQueryPort,
    availability: QuoteAvailabilityQueryPort,
    roles: AppRoleQueryPort,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val grid = DataTable(AttachmentLibraryEntry::class.java)
    internal val emptyState = Span(tr("quote.receiptPool.empty")).apply { addClassName("text-muted") }
    internal val newEntry = AppButtons.primary(tr("quote.receiptPool.new"), ActionIcons.ADD) { openNewEntry() }
    internal val showArchived = Checkbox(tr("quote.receiptPool.showArchived"))

    private val access = QuoteAccess(availability, roles)
    private val labels = QuoteLabels(this)
    private var entries: List<AttachmentLibraryEntry> = emptyList()

    override fun getComponent(): Component = this

    init {
        isPadding = true
        isSpacing = true
        showArchived.addValueChangeListener { render() }
        configureColumns()
        add(PageShell(tr("nav.quoteReceiptPool")).actions(newEntry).filters(showArchived).content(emptyState, grid))
    }

    private fun configureColumns() {
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, tr("quote.receiptPool.ariaLabel"))
        grid.ellipsizedTextColumn { titleText(it) }.setHeader(tr("quote.receiptPool.column.title")).setKey("title").asTextColumn(TextColumnWidth.LARGE)
        grid.addColumn({ tr("quote.attachments.version", it.currentVersion.versionNo) }).setHeader(tr("quote.receiptPool.column.version")).setKey("version").asCompactColumn()
        grid.addColumn({ labels.date(it.currentVersion.validFrom) }).setHeader(tr("quote.receiptPool.column.validFrom")).setKey("validFrom").asCompactColumn()
        grid.addColumn({ it.usedByQuotes }).setHeader(tr("quote.receiptPool.column.usedBy")).setKey("usedBy").asCompactColumn()
        grid.addComponentColumn { actions(it) }.setKey("actions").asActionColumn()
    }

    private fun titleText(entry: AttachmentLibraryEntry): String =
        if (entry.archived) "${entry.title} · ${tr("quote.receiptPool.archived")}" else entry.title

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!access.admit(event, AppPermission.QUOTE_VIEW)) return
        newEntry.isVisible = access.mayMaintainLibrary()
        reload()
    }

    private fun reload() {
        entries = guarded { query.entries() } ?: emptyList()
        render()
    }

    private fun render() {
        val shown = entries.filter { showArchived.value || !it.archived }
        grid.setItems(shown)
        emptyState.isVisible = shown.isEmpty()
        grid.isVisible = shown.isNotEmpty()
    }

    private fun actions(entry: AttachmentLibraryEntry): Component {
        val current = entry.currentVersion
        val cell = HorizontalLayout(quoteFileActions(this, current.fileName) { query.versionContent(entry.id, current.id) }).apply { isPadding = false }
        cell.add(iconButton(ActionIcons.HISTORY, "quote.receiptPool.action.versions") { QuoteReceiptPoolVersionsDialog(this, query, entry).open() })
        if (access.mayMaintainLibrary() && !entry.archived) {
            cell.add(iconButton(ActionIcons.ADD, "quote.receiptPool.action.newVersion") { openNewVersion(entry) })
        }
        if (access.mayArchiveLibrary() && !entry.archived) {
            cell.add(iconButton(ActionIcons.ARCHIVE, "quote.receiptPool.action.archive") { confirmArchive(entry) })
        }
        return cell
    }

    private fun iconButton(icon: VaadinIcon, labelKey: String, onClick: () -> Unit): Button =
        AppButtons.icon(icon, tr(labelKey), onClick)

    private fun openNewEntry() {
        QuoteReceiptPoolUploadDialog.forNewEntry(this, readability) { entry ->
            val stored = guarded { maintain.create(entry) }
            if (stored != null) {
                reload()
                showUploadResult(entry.file, stored.reduction, tr("quote.receiptPool.created", entry.title))
            }
        }.open()
    }

    private fun openNewVersion(entry: AttachmentLibraryEntry) {
        QuoteReceiptPoolUploadDialog.forNewVersion(this, readability, entry) { file ->
            val stored = guarded { maintain.addVersion(entry.id, file) }
            if (stored != null) {
                reload()
                showUploadResult(file, stored.reduction, tr("quote.receiptPool.versionAdded", entry.title))
            }
        }.open()
    }

    private fun showUploadResult(file: AttachmentLibraryFile, reduction: StoredQuoteReduction?, plain: String) =
        showQuoteUploadResult(this, QuoteUploadResult(file.fileName, file.content.size, reduction), plain)

    private fun confirmArchive(entry: AttachmentLibraryEntry) {
        ConfirmDialog(
            tr("quote.receiptPool.archive.title", entry.title),
            tr("quote.receiptPool.archive.text"),
            tr("quote.receiptPool.archive.confirm"),
        ) { archive(entry) }.also {
            it.setCancelable(true)
            it.setCancelText(tr("app.cancel"))
            it.open()
        }
    }

    internal fun archive(entry: AttachmentLibraryEntry) {
        if (guarded { maintain.archive(entry.id) } != null) {
            UiNotifications.success(tr("quote.receiptPool.archived.done", entry.title))
            reload()
        }
    }
}
