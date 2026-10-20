package de.littletrotter.application.invoice

import de.littletrotter.application.error.ApplicationValidationException
import java.util.Locale
import java.util.ResourceBundle

/**
 * Shared write-path validation for a receipt's mandatory fields, used by both
 * [ReceiptUploadService.upload] and [ReceiptPoolService.updateDetails] so the two write paths can
 * never word the same rule differently.
 */

/** German-first user-facing refusal texts, read from the shared message bundle so the service and
 *  the UI can never word the same rule differently (German is the display default,
 *  . */
internal fun receiptBundleMessage(key: String): String = ResourceBundle.getBundle(
    "i18n.messages",
    Locale.GERMAN,
    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
).getString(key)

/** The trimmed, non-blank description a write path may persist — refused otherwise. */
internal fun requireReceiptDescription(description: String?): String {
    val trimmed = description?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        throw ApplicationValidationException(receiptBundleMessage("receipt.error.descriptionRequired"))
    }
    return trimmed
}
