package de.littletrotter.ui.component

import com.vaadin.flow.component.HasValue
import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.data.binder.ValidationResult
import com.vaadin.flow.data.binder.Validator
import de.littletrotter.shared.documents.PdfRenderableText

/**
 * One place for the entry-time "the PDF can show this text" check. Free text printed on the
 * invoice PDF must not contain characters the bundled PDF fonts lack (emoji, CJK, control
 * characters): the PDF engine would refuse the document after the invoice is already issued, so
 * the form rejects the character where it is typed. Binder forms attach [validator]; forms
 * that validate by hand call [check] / [checkAll].
 */
object PdfTextValidation {
    const val MESSAGE_KEY = "validation.pdfText.unrenderable"

    /** The user-facing message for [text], or `null` when every character can be rendered. */
    fun message(tr: (String) -> String, text: String?): String? {
        val bad = PdfRenderableText.firstOffending(text)
        return if (bad.isEmpty()) null else "${tr(MESSAGE_KEY)}: ${bad.joinToString(" ")}"
    }

    /** Binder validator for any text-valued field. */
    fun validator(tr: (String) -> String): Validator<String?> =
        Validator { value, _ ->
            message(tr, value)?.let { ValidationResult.error(it) } ?: ValidationResult.ok()
        }

    /** Hand-validation of one field: marks it invalid with the message, or clears only its own mark. */
    fun <F> check(field: F, tr: (String) -> String): Boolean where F : HasValue<*, String>, F : HasValidation {
        val message = message(tr, field.value)
        if (message != null) {
            field.isInvalid = true
            field.errorMessage = message
            return false
        }
        if (field.isInvalid && field.errorMessage?.startsWith(tr(MESSAGE_KEY)) == true) field.isInvalid = false
        return true
    }

    /** Checks every field (no short-circuit, so each one refreshes its own error state). */
    fun <F> checkAll(tr: (String) -> String, vararg fields: F): Boolean where F : HasValue<*, String>, F : HasValidation =
        fields.map { check(it, tr) }.all { it }
}
