package de.littletrotter.application.error

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException
import de.littletrotter.domain.exception.ReceiptNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationExceptionTranslatorTest {

    @Test
    fun `happy path - translator maps a not-found domain exception to application not-found, carrying message and cause`() {
        val cause = InvoiceNotFoundException(7L)

        val translated = ApplicationExceptionTranslator.from(cause)

        assertTrue(translated is ApplicationNotFoundException)
        assertSame(cause, translated.cause)
        assertTrue(translated.message!!.contains("7"))
    }

    // The third arm of the InvoiceStatusTransitionException/InvoiceImmutableException/
    // ReceiptLockedByIssuedInvoiceException `is` chain had no test of its own; without it that
    // branch's own bytecode target stayed unexercised even though the other two members of the
    // same `when` arm were covered.
    @Test
    fun `boundary - translator maps a receipt locked by an issued invoice to application conflict`() {
        val translated = ApplicationExceptionTranslator.from(ReceiptLockedByIssuedInvoiceException(listOf("RE-2026-01-00001")))

        assertTrue(translated is ApplicationConflictException)
        assertTrue(translated.message!!.contains("RE-2026-01-00001"))
    }

    // ApplicationNotFoundException/ApplicationConflictException each carry their own
    // `cause: Throwable? = null` default; the translator always forwards an explicit cause, so
    // only a *direct* construction (e.g. from a REST boundary that has no wrapped exception at
    // all) ever omits it -- covered here since no production call site does.
    @Test
    fun `boundary - ApplicationNotFoundException constructed directly without a cause has no cause`() {
        val exception = ApplicationNotFoundException("Rechnung nicht gefunden")

        assertEquals("Rechnung nicht gefunden", exception.message)
        assertEquals(null, exception.cause)
    }

    @Test
    fun `boundary - ApplicationConflictException constructed directly without a cause has no cause`() {
        val exception = ApplicationConflictException("Ungueltiger Statuswechsel")

        assertEquals("Ungueltiger Statuswechsel", exception.message)
        assertEquals(null, exception.cause)
    }

    @Test
    fun `translator maps validation-style domain exceptions to application validation`() {
        val translated = ApplicationExceptionTranslator.from(InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING, message = "bad invoice"))

        assertTrue(translated is ApplicationValidationException)
        assertEquals("bad invoice", translated.message)
    }

    @Test
    fun `translator maps invalid parameter to application validation`() {
        val translated = ApplicationExceptionTranslator.from(InvalidParameterException("bad parameter"))

        assertTrue(translated is ApplicationValidationException)
        assertEquals("bad parameter", translated.message)
    }

    @Test
    fun `translator maps not found to application not found`() {
        val translated = ApplicationExceptionTranslator.from(ReceiptNotFoundException(42L))

        assertTrue(translated is ApplicationNotFoundException)
        assertTrue(translated.message!!.contains("42"))
    }

    @Test
    fun `translator maps invoice not found to application not found, not validation`() {
        val translated = ApplicationExceptionTranslator.from(InvoiceNotFoundException(7L))

        assertTrue(translated is ApplicationNotFoundException, "a missing invoice must be reported as not-found, not as a validation error")
        assertTrue(translated.message!!.contains("7"))
    }

    @Test
    fun `translator maps an invalid status transition to application conflict`() {
        val translated = ApplicationExceptionTranslator.from(InvoiceStatusTransitionException("SENT", "DRAFT"))

        assertTrue(translated is ApplicationConflictException)
    }

    @Test
    fun `translator maps editing a finalized invoice to application conflict`() {
        val translated = ApplicationExceptionTranslator.from(InvoiceImmutableException("INV-2026-0001"))

        assertTrue(translated is ApplicationConflictException)
    }

    @Test
    fun `translator returns application exceptions unchanged`() {
        val original = ApplicationValidationException("already translated")

        val translated = ApplicationExceptionTranslator.from(original)

        assertSame(original, translated)
    }

    @Test
    fun `boundary - IllegalArgumentException carrying its own message wins over the fallback`() {
        val translated = ApplicationExceptionTranslator.from(IllegalArgumentException("explicit message"), "friendly")

        assertTrue(translated is ApplicationValidationException)
        assertEquals("explicit message", translated.message)
    }

    @Test
    fun `translator uses fallback for unknown exceptions`() {
        val translated = ApplicationExceptionTranslator.from(RuntimeException("boom"), "friendly")

        assertTrue(translated is ApplicationValidationException)
        assertEquals("friendly", translated.message)
    }

    // Message precedence rules
    //
    // Tests above verify which application error maps to which domain exception. The actual
    // message matters—it reaches the user. UI builds text from error code, not message, so
    // mismatches can hide the true error (e.g., all master-data checks reported "invoice
    // number missing" when showing the code instead of the message).

    @Test
    fun `bei bekannten Ausnahmen gewinnt deren eigene Meldung, nicht der Ersatztext`() {
        val translated = ApplicationExceptionTranslator.from(
            InvalidParameterException("Steuernummer ist ungültig"),
            fallbackMessage = "Etwas ist schiefgelaufen",
        )

        assertEquals(
            "Steuernummer ist ungültig", translated.message,
            "specific message must not be overridden by fallback text",
        )
    }

    @Test
    fun `ohne eigene Meldung greift der Ersatztext`() {
        // IllegalArgumentException() carries null message, unlike domain exceptions.
        val translated = ApplicationExceptionTranslator.from(IllegalArgumentException(), "Eingabe unvollständig")

        assertTrue(translated is ApplicationValidationException)
        assertEquals("Eingabe unvollständig", translated.message)
    }

    @Test
    fun `ohne Meldung und ohne Ersatztext bleibt eine verstaendliche Konstante`() {
        val translated = ApplicationExceptionTranslator.from(IllegalArgumentException())

        assertEquals("Invalid input", translated.message, "never return empty or null message")
    }

    /**
     * Known exception types use exception.message ?: fallbackMessage; unknown types reverse
     * to fallbackMessage ?: exception.message. This makes sense: unknown exception messages are
     * typically technical (stack trace, driver error) and useless to the user, while the caller
     * knows what was attempted and can provide context.
     */
    @Test
    fun `bei unbekannten Ausnahmen gewinnt umgekehrt der Ersatztext ueber die technische Meldung`() {
        val mitErsatztext = ApplicationExceptionTranslator.from(
            RuntimeException("java.sql.SQLException: connection reset"),
            fallbackMessage = "Speichern fehlgeschlagen",
        )
        assertEquals("Speichern fehlgeschlagen", mitErsatztext.message)

        // Without fallback, technical message is used as last resort.
        val ohneErsatztext = ApplicationExceptionTranslator.from(RuntimeException("connection reset"))
        assertEquals("connection reset", ohneErsatztext.message)

        // When both are missing, return sensible default.
        val nichts = ApplicationExceptionTranslator.from(RuntimeException())
        assertEquals("Unknown error", nichts.message)
    }

    @Test
    fun `die urspruengliche Ausnahme bleibt als Ursache erhalten`() {
        // Without cause, stack trace from original error is lost.
        val ursache = InvalidParameterException("kaputt")

        val translated = ApplicationExceptionTranslator.from(ursache)

        assertSame(ursache, translated.cause)
    }

    @Test
    fun `validation baut einen Anwendungsfehler mit Ursache`() {
        val ursache = RuntimeException("tiefer liegend")

        val translated = ApplicationExceptionTranslator.validation("Bitte Eingaben pruefen", ursache)

        assertEquals("Bitte Eingaben pruefen", translated.message)
        assertSame(ursache, translated.cause)
    }

    @Test
    fun `validation ohne Ursache-Argument erzeugt einen Anwendungsfehler ohne cause`() {
        val translated = ApplicationExceptionTranslator.validation("Bitte Eingaben pruefen")

        assertEquals("Bitte Eingaben pruefen", translated.message)
        assertEquals(null, translated.cause)
    }
}
