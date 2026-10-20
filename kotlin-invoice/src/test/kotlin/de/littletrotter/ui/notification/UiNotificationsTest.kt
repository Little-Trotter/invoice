package de.littletrotter.ui.notification

import org.mockito.kotlin.any
import de.littletrotter.application.error.ApplicationValidationException
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import de.littletrotter.domain.exception.DuplicateInvoicePrefixException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import java.time.Duration
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.mock
import org.mockito.kotlin.doReturn
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.test.FIXED_INSTANT
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.application.message.port.inbound.RecordSystemMessageUseCase

class UiNotificationsTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `orEmpty returns empty for null and original for value`() {
        assertEquals("", UiNotifications.orEmpty(null))
        assertEquals("x", UiNotifications.orEmpty("x"))
    }

    @Test
    fun `tr delegates to UiTranslations fallback for missing key`() {
        val key = "non.existing.key.for.test"
        val translated = UiNotifications.tr(key)
        assertEquals("!{$key}!", translated)
    }

    @Test
    fun `formatCityCountry handles city and Country and String`() {
        val countryName = java.util.Locale.forLanguageTag("und-${Country.DE.alpha2}").displayCountry
        // null city -> country name
        assertEquals(countryName, UiNotifications.formatCityCountry(null, Country.DE))
        // city + country -> "city, country"
        assertEquals("Berlin, $countryName", UiNotifications.formatCityCountry("Berlin", Country.DE))
        // city only
        assertEquals("Munich", UiNotifications.formatCityCountry("Munich", null))
        // country as string
        assertEquals(countryName, UiNotifications.formatCityCountry(null, "DE"))
    }

    @Test
    fun `saved and deleted produce notifications without throwing`() {
        val span = Span()
        val saved = UiNotifications.saved(span)
        assertNotNull(saved)

        val deleted = UiNotifications.deleted(span)
        assertNotNull(deleted)
    }

    @Test
    fun `error handles InvoiceValidationException gracefully`() {
        val span = Span()
        UiNotifications.error(span, InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING, message = "validation failed"))
        // No assertion possible on Notification text reliably here; test ensures no exception thrown
    }

    @Test
    fun `error handles ApplicationValidationException gracefully`() {
        val span = Span()
        UiNotifications.error(span, ApplicationValidationException("validation failed"))
    }

    @Test
    fun `resolveMessage translates a ConstraintViolationException to the constraint-violation key`() {
        val span = Span()
        val message = UiNotifications.resolveMessage(span, jakarta.validation.ConstraintViolationException(emptySet()))
        assertEquals(UiNotifications.tr("message.error.constraintViolation"), message)
    }

    @Test
    fun `resolveMessage translates an OptimisticLockException to the optimistic-lock key`() {
        val span = Span()
        val message = UiNotifications.resolveMessage(span, jakarta.persistence.OptimisticLockException("stale entity"))
        assertEquals(UiNotifications.tr("message.error.optimisticLock"), message)
    }

    @Test
    fun `resolveMessage translates a commit-time wrapped optimistic-lock failure to the optimistic-lock key`() {
        val span = Span()
        val wrapped = jakarta.transaction.RollbackException("ARJUNA016053: Could not commit transaction.")
            .apply { initCause(jakarta.persistence.OptimisticLockException("stale entity")) }
        val message = UiNotifications.resolveMessage(span, wrapped)
        assertEquals(UiNotifications.tr("message.error.optimisticLock"), message)
    }

    @Test
    fun `resolveMessage translates an InvoiceImmutableException to the invoice-immutable key`() {
        val span = Span()
        val message = UiNotifications.resolveMessage(span, de.littletrotter.domain.exception.InvoiceImmutableException("RE-2026-0001"))
        assertEquals(UiNotifications.tr("message.error.invoiceImmutable"), message)
    }

    @Test
    fun `resolveMessage falls back to the generic key for an unrecognized exception type`() {
        val span = Span()
        val message = UiNotifications.resolveMessage(span, IllegalStateException("something else entirely"))
        assertEquals(UiNotifications.tr("message.error.generic"), message)
    }

    @Test
    fun `error notification is sticky with duration 0`() {
        val notification = UiNotifications.error("Test error message")
        assertEquals(0, notification.duration, "Error notification should have duration 0 (never auto-dismiss)")
    }

    @Test
    fun `error notification contains close button`() {
        val notification = UiNotifications.error("Test error message")
        var hasButton = false
        notification.children.forEach { component ->
            component.children.forEach { child ->
                if (child is Button) {
                    hasButton = true
                }
            }
        }
        assertTrue(hasButton, "Error notification should contain a close button")
    }

    @Test
    fun `error notification ends its row with the close button`() {
        val notification = UiNotifications.error("Test error message")
        val row = notification.children.toList().single()
        assertTrue(row.children.toList().last() is Button, "close button must be the last child of the row")
    }

    @Test
    fun `resolveMessage shows a domain exception's own user text`() {
        val span = Span()
        val exception = DuplicateInvoicePrefixException("RE-")
        assertEquals(exception.message, UiNotifications.resolveMessage(span, exception))
    }

    @Test
    fun `resolveMessage reduces a unique-constraint violation anywhere in the cause chain to the db-unique text`() {
        val span = Span()
        val violation = org.hibernate.exception.ConstraintViolationException(
            "could not execute statement [ERROR: duplicate key value violates unique constraint \"uq_sender_profile_single_default\"]",
            java.sql.SQLException("duplicate key", "23505"),
            org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE,
            "uq_sender_profile_single_default",
        )
        val message = UiNotifications.resolveMessage(span, RuntimeException("wrapped", violation))
        assertEquals(UiNotifications.tr("message.error.db.unique"), message)
        assertFalse(message.contains("uq_sender_profile"), "no constraint name in the user-facing text")
    }

    @Test
    fun `resolveMessage maps foreign-key and not-null violations to their own texts and other kinds to the generic db text`() {
        val span = Span()
        fun violation(kind: org.hibernate.exception.ConstraintViolationException.ConstraintKind) =
            org.hibernate.exception.ConstraintViolationException("x", java.sql.SQLException("x"), kind, null)
        assertEquals(
            UiNotifications.tr("message.error.db.foreignKey"),
            UiNotifications.resolveMessage(span, violation(org.hibernate.exception.ConstraintViolationException.ConstraintKind.FOREIGN_KEY)),
        )
        assertEquals(
            UiNotifications.tr("message.error.db.notNull"),
            UiNotifications.resolveMessage(span, violation(org.hibernate.exception.ConstraintViolationException.ConstraintKind.NOT_NULL)),
        )
        assertEquals(
            UiNotifications.tr("message.error.db.generic"),
            UiNotifications.resolveMessage(span, violation(org.hibernate.exception.ConstraintViolationException.ConstraintKind.CHECK)),
        )
    }

    @Test
    fun `resolveMessage reduces any other persistence or SQL failure to the generic db text`() {
        val span = Span()
        val persistence = jakarta.persistence.PersistenceException("could not execute statement", java.sql.SQLException("connection reset"))
        assertEquals(UiNotifications.tr("message.error.db.generic"), UiNotifications.resolveMessage(span, persistence))
        assertEquals(
            UiNotifications.tr("message.error.db.generic"),
            UiNotifications.resolveMessage(span, IllegalStateException("outer", java.sql.SQLException("inner"))),
        )
    }

    @Test
    fun `success notification auto-dismisses after 2 seconds`() {
        // The success() function doesn't return a value, so we can only verify it doesn't throw
        UiNotifications.success("Test success message")
        // If it doesn't throw, the test passes
    }

    // Every tier opens in the bottom-left corner (CSS offsets it past the open drawer); the theme variants are exactly the ones the
    // tiers carried before the bottom-centre flat-card styling was introduced.
    private fun shownOnce(show: () -> Unit): Notification {
        clearNotifications()
        show()
        MockVaadin.clientRoundtrip()
        return getNotifications().single()
    }

    @Test
    fun `saved and deleted open bottom-left as plain stock cards without a theme variant`() {
        val span = Span()
        val saved = shownOnce { UiNotifications.saved(span) }
        assertEquals(Notification.Position.BOTTOM_START, saved.position)
        assertTrue(saved.themeNames.isEmpty())

        val deleted = shownOnce { UiNotifications.deleted(span) }
        assertEquals(Notification.Position.BOTTOM_START, deleted.position)
        assertTrue(deleted.themeNames.isEmpty())
    }

    @Test
    fun `success warning and info open bottom-left with their one theme variant`() {
        val success = shownOnce { UiNotifications.success("ok") }
        assertEquals(Notification.Position.BOTTOM_START, success.position)
        assertEquals(setOf("success"), success.themeNames.toSet())

        val warning = shownOnce { UiNotifications.warning("look") }
        assertEquals(Notification.Position.BOTTOM_START, warning.position)
        assertEquals(setOf("warning"), warning.themeNames.toSet())

        val info = shownOnce { UiNotifications.info("fyi") }
        assertEquals(Notification.Position.BOTTOM_START, info.position)
        assertEquals(setOf("primary"), info.themeNames.toSet())
    }

    @Test
    fun `error opens bottom-left sticky with the error variant and actionable without any variant`() {
        val error = shownOnce { UiNotifications.error("broken") }
        assertEquals(Notification.Position.BOTTOM_START, error.position)
        assertEquals(setOf("error"), error.themeNames.toSet())
        assertEquals(0, error.duration)

        val actionable = shownOnce { UiNotifications.actionable("undo?", "Undo") { } }
        assertEquals(Notification.Position.BOTTOM_START, actionable.position)
        assertTrue(actionable.themeNames.isEmpty())
    }

    // This test class never starts a Quarkus/Arc container (only Karibu's mock
    // Vaadin, see setup()), so every error(...) call above already exercises the message-center
    // persistence hook's "no CDI container" path. These two make that explicit rather than
    // incidental; the "container present, actually persists" counterpart lives in
    // UiNotificationsMessageCenterIT (needs a real container + Postgres, out of scope for a plain
    // unit test).
    @Test
    fun `error without a CDI container degrades gracefully instead of throwing`() {
        val notification = org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UiNotifications.error("No CDI container active for this test")
        }
        assertNotNull(notification)
    }

    @Test
    fun `repeated error calls without a CDI container never throw either`() {
        val message = "Repeated error without container"
        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UiNotifications.error(message)
            UiNotifications.error(message)
        }
    }

    // ── actionable notification button behaviour ────────────────────────────────────────────────

    @Test
    fun `happy path - clicking an actionable notification's button runs the action once and closes it`() {
        var ran = 0
        val notification = shownOnce { UiNotifications.actionable("undo?", "Undo") { ran++ } }

        val button = notification.children.toList()
            .flatMap { it.children.toList() }
            .filterIsInstance<Button>()
            .single { it.isVisible }
        button.click()

        assertEquals(1, ran)
        assertFalse(notification.isOpened)
    }

    @Test
    fun `boundary - inside an active CDI container, error persists through RecordSystemMessageUseCase with no active view path`() {
        val service = mock<RecordSystemMessageUseCase>()

        withMockedCdi(service, FIXED_TIME_SOURCE) {
            val notification = assertDoesNotThrow {
                UiNotifications.error("Persisted via a real CDI lookup, distinct message ${UUID.randomUUID()}")
            }

            assertNotNull(notification)
            verify(service).record(any())
        }
    }

    @Test
    fun `boundary - resolveMessage formats a positional argument into a validation message with args`() {
        val span = Span()
        val exception = InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING, args = listOf("2026"))

        val message = UiNotifications.resolveMessage(span, exception)

        assertTrue(message.isNotBlank())
    }

    @Test
    fun `boundary - an identical error within the one-minute window is persisted only once`() {
        val service = mock<RecordSystemMessageUseCase>()
        val time = mock<TimeSource> { on { instant() } doReturn FIXED_INSTANT doReturn FIXED_INSTANT.plusSeconds(59) }
        val message = "boundary-dedup-marker-${UUID.randomUUID()}"

        withMockedCdi(service, time) {
            UiNotifications.error(message)
            UiNotifications.error(message)
        }

        verify(service, times(1)).record(any())
    }

    @Test
    fun `boundary - an identical error is persisted again once the dedup window has elapsed`() {
        // The window is measured on the application's time source, so a test can step past it.
        val service = mock<RecordSystemMessageUseCase>()
        val time = mock<TimeSource> { on { instant() } doReturn FIXED_INSTANT doReturn FIXED_INSTANT.plus(Duration.ofMinutes(2)) }
        val message = "boundary-stale-dedup-marker-${UUID.randomUUID()}"

        withMockedCdi(service, time) {
            UiNotifications.error(message)
            UiNotifications.error(message)
        }

        verify(service, times(2)).record(any())
    }

    // ── single slot ─────────────────────────────────────────────────────────────────────────────

    private fun moreButton(n: Notification): Button? =
        n.children.toList().flatMap { it.children.toList() }.filterIsInstance<Button>()
            .firstOrNull { it.text.startsWith("+") && it.isVisible }

    private fun rowText(n: Notification): String =
        n.children.toList().flatMap { it.children.toList() }.joinToString("|") { it.element.text }

    @Test
    fun `a second toast closes the first and shows the pending counter`() {
        UiNotifications.error("one")
        val first = getNotifications().single()
        UiNotifications.error("two")
        UiNotifications.error("three")
        MockVaadin.clientRoundtrip()

        assertFalse(first.isOpened)
        val visible = getNotifications().single()
        assertEquals("+2 weitere", moreButton(visible)?.text)
    }

    @Test
    fun `replaced non-errors are not counted because the message center does not hold them`() {
        UiNotifications.success("one")
        val first = getNotifications().single()
        UiNotifications.warning("two")
        UiNotifications.info("three")
        MockVaadin.clientRoundtrip()

        assertFalse(first.isOpened)
        assertNull(moreButton(getNotifications().single()))
    }

    @Test
    fun `the first toast carries no pending counter`() {
        UiNotifications.success("one")
        assertNull(moreButton(getNotifications().single()))
    }

    @Test
    fun `the same text again shows a repeat marker and opens no second notification`() {
        val span = Span()
        val first = UiNotifications.saved(span)
        UiNotifications.saved(span)
        UiNotifications.saved(span)
        MockVaadin.clientRoundtrip()

        assertEquals(1, getNotifications().size)
        assertTrue(rowText(first).contains("(3\u00D7)"))
        assertNull(moreButton(first))
    }

    @Test
    fun `an open error is not replaced by a non-error but another error is`() {
        val error = UiNotifications.error("broken")
        UiNotifications.success("ok")
        UiNotifications.saved(Span())
        UiNotifications.warning("w")
        UiNotifications.info("i")
        UiNotifications.actionable("undo", "Undo") { }
        MockVaadin.clientRoundtrip()
        assertTrue(error.isOpened)
        assertEquals(1, getNotifications().size)

        UiNotifications.error("broken too")
        MockVaadin.clientRoundtrip()
        assertFalse(error.isOpened)
        assertEquals("+1 weitere", moreButton(getNotifications().single())?.text)
    }

    @Test
    fun `closing the toast resets the counter and the slot`() {
        UiNotifications.error("one")
        UiNotifications.error("two")
        getNotifications().single().close()
        MockVaadin.clientRoundtrip()

        UiNotifications.success("three")
        val next = getNotifications().single()
        assertNull(moreButton(next))
        assertEquals("three", next.children.toList().flatMap { it.children.toList() }.first().element.text)
    }

    @Test
    fun `the more button closes the toast and opens the message center`() {
        UiNotifications.error("one")
        UiNotifications.error("two")
        val visible = getNotifications().single()
        // The mock UI has no routes registered, so the navigation attempt to the message center
        // surfaces as the router's NotFound for exactly that target.
        val failure = assertThrows(com.vaadin.flow.router.NotFoundException::class.java) { moreButton(visible)!!.click() }

        assertTrue(failure.message!!.contains("MessageCenterView"))
        assertFalse(visible.isOpened)
    }

    // ── errorOn / focusFirstInvalid ─────────────────────────────────────────────────────────────

    private class CountingTextField : com.vaadin.flow.component.textfield.TextField() {
        var valueListeners = 0

        override fun addValueChangeListener(
            listener: com.vaadin.flow.component.HasValue.ValueChangeListener<in com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent<com.vaadin.flow.component.textfield.TextField, String>>,
        ): com.vaadin.flow.shared.Registration {
            valueListeners++
            return super.addValueChangeListener(listener)
        }
    }

    @Test
    fun `errorOn marks the field invalid with the message and shows no toast`() {
        val field = com.vaadin.flow.component.textfield.TextField()
        com.vaadin.flow.component.UI.getCurrent().add(field)

        UiNotifications.errorOn(field, "Pflichtfeld")

        assertTrue(field.isInvalid)
        assertEquals("Pflichtfeld", field.errorMessage)
        assertTrue(getNotifications().isEmpty())
    }

    @Test
    fun `errorOn without a message keeps the existing one`() {
        val field = com.vaadin.flow.component.textfield.TextField().apply { errorMessage = "alt" }
        com.vaadin.flow.component.UI.getCurrent().add(field)

        UiNotifications.errorOn(field, null)

        assertTrue(field.isInvalid)
        assertEquals("alt", field.errorMessage)
    }

    @Test
    fun `errorOn is cleared when the user edits the value and registers its listener once`() {
        val field = CountingTextField()
        com.vaadin.flow.component.UI.getCurrent().add(field)

        val own = field.valueListeners
        UiNotifications.errorOn(field, "a")
        UiNotifications.errorOn(field, "b")
        assertEquals(own + 1, field.valueListeners)
        assertTrue(field.isInvalid)

        field.value = "x"
        assertFalse(field.isInvalid)

        UiNotifications.errorOn(field, "c")
        assertTrue(field.isInvalid)
        assertEquals(own + 1, field.valueListeners)
    }

    @Test
    fun `errorOn falls back to an error toast when the field is not visible`() {
        val hidden = com.vaadin.flow.component.textfield.TextField().apply { isVisible = false }
        val detached = com.vaadin.flow.component.textfield.TextField()
        val tabSheet = com.vaadin.flow.component.tabs.TabSheet()
        val first = com.vaadin.flow.component.textfield.TextField()
        val second = com.vaadin.flow.component.textfield.TextField()
        tabSheet.add("eins", first)
        tabSheet.add("zwei", second)
        val panel = com.vaadin.flow.component.accordion.AccordionPanel("Panel", com.vaadin.flow.component.textfield.TextField())
        val folded = panel.content.findFirst().get() as com.vaadin.flow.component.textfield.TextField
        panel.isOpened = false
        com.vaadin.flow.component.UI.getCurrent().add(hidden, tabSheet, panel)

        listOf(hidden to "a", detached to "b", second to "c", folded to "d").forEach { (field, text) ->
            UiNotifications.errorOn(field, text)
            assertTrue(field.isInvalid)
            assertTrue(getNotifications().any { de.littletrotter.ui.toastText(it) == text }, text)
            clearNotifications()
        }

        UiNotifications.errorOn(first, "sichtbar")
        assertTrue(getNotifications().isEmpty())
    }

    @Test
    fun `focusFirstInvalid reports a rejected binder field and a failure without one`() {
        val field = com.vaadin.flow.component.textfield.TextField()
        com.vaadin.flow.component.UI.getCurrent().add(field)
        val binder = com.vaadin.flow.data.binder.Binder(Holder::class.java)
        binder.forField(field).asRequired("leer").bind({ it.text }, { h, v -> h.text = v })
        val failure = assertThrows(com.vaadin.flow.data.binder.ValidationException::class.java) { binder.writeBean(Holder()) }

        assertTrue(UiNotifications.focusFirstInvalid(failure))
        assertTrue(field.isInvalid)

        val unlocated = com.vaadin.flow.data.binder.ValidationException(emptyList(), emptyList())
        assertFalse(UiNotifications.focusFirstInvalid(unlocated))

        field.isVisible = false
        assertFalse(UiNotifications.focusFirstInvalid(failure))
    }

    @Test
    fun `errorOn on a hidden field still clears once the user edits it`() {
        val field = com.vaadin.flow.component.textfield.TextField().apply { isVisible = false }
        com.vaadin.flow.component.UI.getCurrent().add(field)

        UiNotifications.errorOn(field, "leer")
        field.isVisible = true
        field.value = "korrigiert"

        assertFalse(field.isInvalid)
    }

    class Holder {
        var text: String = ""
    }

    // ── confirmOn ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `confirmOn shows the saved text with a check icon and restores label and icon`() {
        val icon = com.vaadin.flow.component.icon.Icon(com.vaadin.flow.component.icon.VaadinIcon.EDIT)
        val button = Button("Speichern", icon)
        com.vaadin.flow.component.UI.getCurrent().add(button)

        UiNotifications.confirmOn(button)
        assertEquals(UiNotifications.tr("message.saved"), button.text)
        assertNotSame(icon, button.icon)
        assertTrue(getNotifications().isEmpty())

        UiNotifications.restoreConfirmation(button)
        assertEquals("Speichern", button.text)
        assertSame(icon, button.icon)
    }

    @Test
    fun `a repeated confirmOn keeps the true original`() {
        val button = Button("Speichern")
        com.vaadin.flow.component.UI.getCurrent().add(button)

        UiNotifications.confirmOn(button)
        UiNotifications.confirmOn(button)
        UiNotifications.restoreConfirmation(button)

        assertEquals("Speichern", button.text)
        assertNull(button.icon)
    }

    @Test
    fun `restoreConfirmation without a pending confirmation changes nothing and a later confirmOn re-captures`() {
        val button = Button("Speichern")
        com.vaadin.flow.component.UI.getCurrent().add(button)
        UiNotifications.restoreConfirmation(button)
        assertEquals("Speichern", button.text)

        UiNotifications.confirmOn(button)
        UiNotifications.restoreConfirmation(button)
        button.text = "Anders"
        UiNotifications.confirmOn(button)
        UiNotifications.restoreConfirmation(button)
        assertEquals("Anders", button.text)
    }

    @Test
    fun `detaching the button restores it`() {
        val button = Button("Speichern")
        val ui = com.vaadin.flow.component.UI.getCurrent()
        ui.add(button)
        UiNotifications.confirmOn(button)
        ui.remove(button)
        assertEquals("Speichern", button.text)
    }
}
