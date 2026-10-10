package de.littletrotter.domain.exception

import de.littletrotter.ui.notification.UiNotifications
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * [UiNotifications] resolves an [InvoiceValidationException] to human-readable text by turning
 * [InvoiceValidationError.name] into a `domain.validation.<camelCase>` i18n key
 * (`UiNotifications.errorCodeToCamelCase`). Nothing enforced that every enum value actually has
 * a matching bundle entry — add a code without one and the UI silently shows "!{...}!" to a user
 * instead of failing a build. This test closes that gap: every [InvoiceValidationError] must
 * resolve to a real bundle entry.
 *
 * `errorCodeToCamelCase` is `private` in `UiNotifications` (not `internal`), so its conversion is
 * reproduced here rather than changing production visibility for a test.
 */
class InvoiceValidationErrorI18nTest {

    /** SCREAMING_SNAKE_CASE to camelCase — mirrors UiNotifications.errorCodeToCamelCase. */
    private fun errorCodeToCamelCase(name: String): String =
        name.split("_")
            .mapIndexed { i, word -> if (i == 0) word.lowercase() else word.lowercase().replaceFirstChar(Char::uppercase) }
            .joinToString("")

    @Test
    fun `every InvoiceValidationError has a domain-validation i18n bundle entry`() {
        InvoiceValidationError.values().forEach { error ->
            val key = "domain.validation." + errorCodeToCamelCase(error.name)
            val resolved = UiNotifications.tr(key)
            assertFalse(
                resolved == "!{$key}!",
                "InvoiceValidationError.${error.name} has no i18n entry for key '$key' in messages.properties",
            )
        }
    }
}
