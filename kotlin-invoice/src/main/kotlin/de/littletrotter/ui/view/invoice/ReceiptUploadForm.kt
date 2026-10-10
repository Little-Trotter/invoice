package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.server.streams.UploadHandler
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.LocalDate

/**
 * The "Hochladen" tab of [ReceiptDialog]: naming and size hints, the mandatory description and
 * document date, and the PDF [Upload] control. The control is enabled only while both mandatory
 * fields are filled AND the invoice-wide budget allows another receipt ([setBudgetAllowsUpload],
 * driven by the dialog's limit state). Vaadin Upload fires on file pick, so disabling the control
 * is the ergonomic enforcement; the server check stays the backstop. A picked file is handed to
 * [onFile] together with the entered description and date; the dialog owns the actual upload.
 */
internal class ReceiptUploadForm(
    namingHint: String,
    private val onFile: (fileName: String, bytes: ByteArray, description: String, documentDate: LocalDate?) -> Unit,
) : TranslationProvider {
    private val root = VerticalLayout().apply { isPadding = false; isSpacing = true }
    override fun getComponent(): Component = root

    private val descriptionField = TextField(tr("receipt.description")).apply {
        setWidthFull(); placeholder = tr("receipt.description.placeholder")
        isRequiredIndicatorVisible = true
        valueChangeMode = ValueChangeMode.EAGER
    }

    // Required, but deliberately NOT defaulted to today: a silently-wrong default date on a
    // document is worse than none — the user has to pick the date the document carries.
    private val documentDatePicker = DatePicker(tr("receipt.documentDate")).apply {
        setWidthFull()
        isRequiredIndicatorVisible = true
    }

    private val upload = Upload(
        UploadHandler.inMemory { metadata, bytes ->
            onFile(metadata.fileName, bytes, descriptionField.value, documentDatePicker.value)
        },
    ).apply {
        setAcceptedMimeTypes("application/pdf")
        setAcceptedFileExtensions(".pdf")
        // IR-2 — the client-side cap is the outer bound a file must clear before the
        // reduce/keep decision even runs (offerReceiptUpload); the server check stays
        // authoritative. A tighter cap here would silently block a file that should instead
        // reach the "offer reduction" dialog.
        maxFileSize = ReceiptSizePolicy.MAX_UPLOAD_SIZE_BYTES.toInt()
        isDropAllowed = true
        setWidthFull()
        applyReceiptUploadI18n(root)
    }

    private var budgetAllowsUpload = false

    init {
        // The two field listeners re-evaluate the field side — including the re-disable after a
        // successful upload cleared both fields.
        descriptionField.addValueChangeListener { refreshUploadEnabled() }
        documentDatePicker.addValueChangeListener { refreshUploadEnabled() }
        root.add(
            Span(namingHint).apply { addClassNames(FS_08, TEXT_MUTED) },
            // IR-2 — stated up front, not only after a rejected upload.
            Span(tr("receipt.upload.maxSizeHint", DocumentSizeFormatter.format(ReceiptSizePolicy.MAX_UPLOAD_SIZE)))
                .apply { addClassNames(FS_08, TEXT_MUTED) },
            descriptionField,
            documentDatePicker,
            upload,
        )
        refreshUploadEnabled()
    }

    fun setBudgetAllowsUpload(enabled: Boolean) {
        budgetAllowsUpload = enabled
        refreshUploadEnabled()
    }

    /** Clears both mandatory fields after a successful upload (which disables the control again). */
    fun clear() {
        descriptionField.clear()
        documentDatePicker.clear()
    }

    private fun refreshUploadEnabled() {
        upload.isEnabled = budgetAllowsUpload && descriptionField.value.isNotBlank() && documentDatePicker.value != null
    }

    private companion object {
        const val TEXT_MUTED = "text-muted"
        const val FS_08 = "fs-08"
    }
}
