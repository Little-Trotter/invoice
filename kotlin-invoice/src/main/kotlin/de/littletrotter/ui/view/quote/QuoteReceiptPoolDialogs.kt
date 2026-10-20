package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.server.streams.UploadHandler
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryVersion
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider

private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
private const val FALLBACK_MIME_TYPE = "application/octet-stream"

/**
 * Collects a file for the receipt pool: for a new entry also its title and description, for a new
 * version only the file and the date from which it applies. The file stays in memory until the
 * user confirms, so nothing reaches the pool before the explicit save.
 *
 * [onSave] receives the file; for a new entry [onCreate] receives the entry with it instead.
 */
internal class QuoteReceiptPoolUploadDialog private constructor(
    private val host: TranslationProvider,
    private val readability: ReceiptReadabilityQueryPort,
    header: String,
    private val asNewEntry: Boolean,
    private val onCreate: (NewAttachmentLibraryEntry) -> Unit,
    private val onSave: (AttachmentLibraryFile) -> Unit,
) : AppDialog(header, Size.M) {

    internal val title = TextField(host.tr("quote.receiptPool.field.title"))
    internal val description = TextField(host.tr("quote.receiptPool.field.description"))
    internal val validFrom = DatePicker(host.tr("quote.receiptPool.field.validFrom"))
    internal val upload = Upload(UploadHandler.inMemory { metadata, bytes -> onFile(metadata.fileName(), metadata.contentType(), bytes) })
    internal val fileName = Span()
    internal val save = AppButtons.primary(host.tr("app.save")) { save() }

    private var picked: AttachmentLibraryFile? = null

    init {
        title.isVisible = asNewEntry
        title.isRequired = true
        title.setWidthFull()
        description.isVisible = asNewEntry
        description.isRequired = true
        description.setWidthFull()
        validFrom.setWidthFull()
        validFrom.helperText = host.tr("quote.receiptPool.field.validFrom.hint")
        upload.maxFileSize = MAX_UPLOAD_BYTES
        upload.maxFiles = 1
        upload.setWidthFull()
        save.isEnabled = false
        val cancel = AppButtons.tertiary(host.tr("app.cancel")) { close() }
        add(VerticalLayout(title, description, validFrom, upload, fileName).apply { isPadding = false })
        footer(cancel, save)
    }

    internal fun onFile(name: String, mimeType: String?, bytes: ByteArray) {
        picked = AttachmentLibraryFile(name, mimeType ?: FALLBACK_MIME_TYPE, bytes, validFrom.value)
        fileName.text = name
        save.isEnabled = true
    }

    internal fun save() {
        val file = picked ?: return
        if (asNewEntry && (title.value.isBlank() || description.value.isBlank())) {
            title.isInvalid = title.value.isBlank()
            description.isInvalid = description.value.isBlank()
            return
        }
        val entryTitle = title.value.trim()
        val entryDescription = description.value.trim()
        val date = validFrom.value
        // The question may open on top of this dialog, so it closes only once the choice is made.
        decideQuoteUpload(host.getComponent(), readability, PickedFile(file.mimeType, file.content)) { reduce ->
            val decided = AttachmentLibraryFile(file.fileName, file.mimeType, file.content, date, reduce)
            close()
            if (asNewEntry) onCreate(NewAttachmentLibraryEntry(entryTitle, entryDescription, decided)) else onSave(decided)
        }
    }

    companion object {
        /** A dialog that creates a new entry with its first file. */
        fun forNewEntry(host: TranslationProvider, readability: ReceiptReadabilityQueryPort, onCreate: (NewAttachmentLibraryEntry) -> Unit): QuoteReceiptPoolUploadDialog =
            QuoteReceiptPoolUploadDialog(host, readability, host.tr("quote.receiptPool.new.title"), true, onCreate) { }

        /** A dialog that adds a file as the next version of an existing entry. */
        fun forNewVersion(host: TranslationProvider, readability: ReceiptReadabilityQueryPort, entry: AttachmentLibraryEntry, onSave: (AttachmentLibraryFile) -> Unit): QuoteReceiptPoolUploadDialog =
            QuoteReceiptPoolUploadDialog(host, readability, host.tr("quote.receiptPool.version.title", entry.title), false, { }, onSave)
    }
}

/** Every version of one entry, newest first, each viewable and downloadable; read-only because a version is never edited. */
internal class QuoteReceiptPoolVersionsDialog(
    host: TranslationProvider,
    library: AttachmentLibraryQueryPort,
    entry: AttachmentLibraryEntry,
) : AppDialog(host.tr("quote.receiptPool.versions.title", entry.title), Size.L) {

    internal val grid = DataTable(AttachmentLibraryVersion::class.java)

    init {
        val labels = QuoteLabels(host)
        grid.isAllRowsVisible = true
        grid.addColumn({ host.tr("quote.attachments.version", it.versionNo) }).setHeader(host.tr("quote.receiptPool.column.version")).setKey("version").asCompactColumn()
        grid.addColumn({ it.fileName }).setHeader(host.tr("quote.attachments.name")).setKey("name").asTextColumn(TextColumnWidth.LARGE)
        grid.addColumn({ attachmentSizeText(it.sizeBytes) }).setHeader(host.tr("quote.attachments.size")).setKey("size").asCompactColumn()
        grid.addColumn({ labels.date(it.validFrom) }).setHeader(host.tr("quote.receiptPool.column.validFrom")).setKey("validFrom").asCompactColumn()
        grid.addColumn({ "${labels.date(GermanTime.dayOf(it.uploadedAt))} · ${it.uploadedBy}" })
            .setHeader(host.tr("quote.attachments.uploadedAt")).setKey("uploadedAt").asTextColumn(TextColumnWidth.SMALL)
        grid.addComponentColumn { version ->
            quoteFileActions(host, version.fileName) { library.versionContent(entry.id, version.id) }
        }.setKey("actions").asActionColumn()
        grid.setItems(entry.versions)
        add(grid)
        footer(AppButtons.tertiary(host.tr("app.close")) { close() })
    }
}
