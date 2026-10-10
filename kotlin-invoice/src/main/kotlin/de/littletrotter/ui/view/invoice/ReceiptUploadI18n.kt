package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.component.upload.UploadI18N
import de.littletrotter.ui.i18n.UiTranslations

/**
 * Vaadin ships English-only upload texts; the receipt uploads must speak the user's language
 * (drop zone, add button, rejection and transfer errors, size units). Shared by every receipt
 * upload so the wording stays identical.
 */
internal fun Upload.applyReceiptUploadI18n(owner: Component = this) {
    fun t(key: String) = UiTranslations.tr(owner, key)
    i18n = UploadI18N()
        .setDropFiles(UploadI18N.DropFiles().setOne(t("upload.dropFiles.one")).setMany(t("upload.dropFiles.many")))
        .setAddFiles(UploadI18N.AddFiles().setOne(t("upload.addFiles.one")).setMany(t("upload.addFiles.many")))
        .setError(
            UploadI18N.Error()
                .setTooManyFiles(t("upload.error.tooManyFiles"))
                .setFileIsTooBig(t("upload.error.fileIsTooBig"))
                .setIncorrectFileType(t("upload.error.incorrectFileType")),
        )
        .setUploading(
            UploadI18N.Uploading()
                .setStatus(
                    UploadI18N.Uploading.Status()
                        .setConnecting(t("upload.status.connecting"))
                        .setStalled(t("upload.status.stalled"))
                        .setProcessing(t("upload.status.processing"))
                        .setHeld(t("upload.status.held")),
                )
                .setRemainingTime(
                    UploadI18N.Uploading.RemainingTime()
                        .setPrefix(t("upload.remainingTime.prefix"))
                        .setUnknown(t("upload.remainingTime.unknown")),
                )
                .setError(
                    UploadI18N.Uploading.Error()
                        .setServerUnavailable(t("upload.uploadError.serverUnavailable"))
                        .setUnexpectedServerError(t("upload.uploadError.unexpectedServerError"))
                        .setForbidden(t("upload.uploadError.forbidden"))
                        .setFileTooLarge(t("upload.uploadError.fileTooLarge")),
                ),
        )
        .setUnits(listOf("B", "kB", "MB", "GB"))
}
