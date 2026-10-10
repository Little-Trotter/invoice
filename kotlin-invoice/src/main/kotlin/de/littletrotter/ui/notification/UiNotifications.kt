package de.littletrotter.ui.notification

import de.littletrotter.domain.message.SystemMessageContent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.Focusable
import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.component.HasValue
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.accordion.AccordionPanel
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.data.binder.ValidationException
import com.vaadin.flow.component.notification.NotificationVariant

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.view.message.MessageCenterView
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceItemNotFoundException
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceDateBeforeServiceEndException
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.shared.country.Country
import jakarta.enterprise.inject.spi.CDI
import jakarta.persistence.OptimisticLockException
import de.littletrotter.application.error.isOptimisticLockFailure
import jakarta.validation.ConstraintViolationException
import org.hibernate.exception.ConstraintViolationException.ConstraintKind
import org.jboss.logging.Logger
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Locale
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.application.error.ApplicationUserException

object UiNotifications {

    private const val SUCCESS_MS = 2000

    /** Longer than [SUCCESS_MS]: a warning carries content worth actually reading. */
    private const val WARNING_MS = 5000
    private val POSITION = Notification.Position.BOTTOM_START
    private val log = Logger.getLogger(UiNotifications::class.java)

    // A dedup guard: the same error text fired repeatedly within one
    // minute (e.g. a render loop hitting the same failing code path on every repaint) must not
    // spam the message center with one row per occurrence. Deliberately simple: a single
    // last-message/last-time pair on this singleton object, not a per-message-text cache — a
    // *different* error text right after a suppressed repeat is never suppressed, and a genuinely
    // new occurrence of the *same* text after the window elapses is recorded again. Known
    // limitation: shared across the whole application (this object has no per-user/per-session
    // state), so two different users hitting the same error within the window only get one row —
    // acceptable per the task doc ("simple ... is enough"), revisit if that turns out to matter.
    private val dedupWindow: Duration = Duration.ofMinutes(1)
    private var lastPersistedMessage: String? = null
    private var lastPersistedAt: Instant = Instant.MIN

    /**
     * Persists a shown **error** toast to the message center (`source = UI`,
     * `message_key = "ui.error.raw"`, `resolved_text = message` — there is no stable i18n key at
     * this call site, callers already pass resolved text). Success/saved/deleted notifications
     * are never persisted (deliberate — see the task doc, noise).
     *
     * [UiNotifications] is a plain Kotlin `object`, outside CDI — it has no injection point of its
     * own, so the [MessageCenterService] is looked up on demand via
     * [jakarta.enterprise.inject.spi.CDI.current]. In a plain unit test (no Quarkus/Arc container
     * running) that lookup itself throws — caught and skipped here, so every existing caller of
     * [error] keeps working unchanged, exactly as before this hook existed. Any other failure
     * (bean not resolvable, a DB error inside [MessageCenterService.record]) is caught the same
     * way: this must never be the reason a shown error toast doesn't appear.
     */
    private fun persistError(message: String) {
        try {
            // A plain object has no injection point, so the time source is looked up like the
            // service below; the dedup window then follows a test's simulated clock too.
            val cdi = CDI.current()
            val now = cdi.select(TimeSource::class.java).get().instant()
            if (message == lastPersistedMessage && Duration.between(lastPersistedAt, now) < dedupWindow) {
                return
            }
            lastPersistedMessage = message
            lastPersistedAt = now

            val service = cdi.select(MessageCenterService::class.java).get()
            // Best-effort — see the task doc: "verify the exact accessor, else omit; do not
            // overengineer". UI.getCurrent() is null outside a Vaadin request (e.g. a background
            // job calling error() indirectly), in which case the view path is simply omitted.
            val viewPath = UI.getCurrent()?.internals?.activeViewLocation?.path.blankToNull()
            service.record(
                SystemMessageContent(
                    severity = SystemMessageSeverity.ERROR,
                    source = SystemMessageSource.UI,
                    messageKey = "ui.error.raw",
                    args = listOfNotNull(message, viewPath),
                    resolvedText = message,
                ),
            )
        } catch (ex: Exception) {
            // AP-EXC-2 — reviewed and kept broad on purpose: this is infrastructure called from
            // every error() call in the app, so an escaping exception here would turn a routine
            // error toast into an unrelated crash. Already logged (with the caught exception) at
            // debug rather than warn/error because the dominant case is expected, not a bug: every
            // plain unit test hits this path (no CDI/Arc container running), so a louder level
            // would flood every test run's log for a non-failure.
            log.debugf(ex, "Message-center persistence skipped for a UI error toast (no CDI container, or the lookup/record call failed)")
        }
    }

    /**
     * What is currently on screen for one [UI]. Kept as component data of the UI, never on this
     * shared object, so two users' toasts cannot close each other.
     */
    private class ToastSlot(
        val notification: Notification,
        val message: String,
        val isError: Boolean,
        var pending: Int,
    ) {
        var repeats = 1
        var closed = false
        val marker = Span().apply { addClassName("lt-notification-marker") }
        val more = AppButtons.tertiary("") {}.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }

        fun refresh() {
            marker.text = if (repeats > 1) " ($repeats\u00D7)" else ""
            more.isVisible = pending > 0
            more.text = UiTranslations.tr("message.toast.more", pending)
        }
    }

    /** The toast still visible on [ui], or `null`; a toast closed by timeout, by the user or by a
     *  test clearing the UI no longer counts. */
    private fun liveSlot(ui: UI): ToastSlot? =
        ComponentUtil.getData(ui, ToastSlot::class.java)
            ?.takeIf { !it.closed && it.notification.isOpened }

    /**
     * Single entry point of every toast: at most one is visible per UI. A newer toast replaces the
     * visible one and replaced errors are counted ("+n more", linking to the message center),
     * the same text again only bumps a repeat marker, and an open error is never pushed out by a
     * non-error — errors stay until dismissed and are already persisted to the message center.
     */
    private fun present(
        message: String,
        durationMs: Int,
        variant: NotificationVariant?,
        isError: Boolean = false,
        actionFor: ((Notification) -> Button)? = null,
    ): Notification {
        val ui = UI.getCurrent()
        val current = ui?.let { liveSlot(it) }
        if (current != null) {
            if (current.message == message && current.isError == isError) {
                current.repeats++
                current.refresh()
                return current.notification
            }
            if (current.isError && !isError) return current.notification
        }

        val notification = Notification().apply {
            duration = durationMs
            position = POSITION
            variant?.let { addThemeVariants(it) }
        }
        // Only replaced errors are counted: they are the ones the message center holds, so the
        // "+n more" link always leads to what was pushed off screen.
        val replacedErrors = (current?.pending ?: 0) + if (current?.isError == true) 1 else 0
        val slot = ToastSlot(notification, message, isError, replacedErrors)
        slot.more.addClickListener {
            notification.close()
            ui?.navigate(MessageCenterView::class.java)
        }
        slot.refresh()

        val text = Span(message).apply { addClassName("lt-flex-1") }
        val parts = listOfNotNull<Component>(text, slot.marker, slot.more, actionFor?.invoke(notification))
        notification.add(Div(*parts.toTypedArray()).apply { addClassName("lt-notification-row") })
        notification.addOpenedChangeListener { if (!it.isOpened) slot.closed = true }

        current?.notification?.close()
        ui?.let { ComponentUtil.setData(it, ToastSlot::class.java, slot) }
        notification.open()
        return notification
    }

    fun saved(source: Component): Notification =
        present(UiTranslations.tr(source, "message.saved"), SUCCESS_MS, null)

    fun deleted(source: Component): Notification =
        present(UiTranslations.tr(source, "message.deleted"), SUCCESS_MS, null)

    fun success(message: String) {
        present(message, SUCCESS_MS, NotificationVariant.LUMO_SUCCESS)
    }

    /** L5 — advisory tier between [success] and [error]: the action went through, but the user
     *  should look at something (e.g. a goods position saved at 0,00 €). Auto-dismisses like
     *  [success]; anything that must not be missed belongs in [error] instead. */
    fun warning(message: String) {
        present(message, WARNING_MS, NotificationVariant.LUMO_WARNING)
    }

    /** PO report 04.09.2026 — a fourth, neutral tier: the message is not a statement about the
     *  data being wrong or worth a second look at all (e.g. "VIES has no entry for this country,
     *  nothing to check"), so neither [warning]'s amber nor [error]'s red belongs on it. Lumo's
     *  `PRIMARY` variant (blue, the same colour Lumo itself calls informational) rather than a
     *  hand-picked colour — same reasoning as [success]/[warning]/[error] all deferring to the
     *  theme instead of a call-site style. Same duration as [warning]: these messages carry
     *  call-site-specific detail (e.g. a country code) worth actually reading. */
    fun info(message: String) {
        present(message, WARNING_MS, NotificationVariant.LUMO_PRIMARY)
    }

    fun error(message: String): Notification {
        persistError(message)
        return presentError(message)
    }

    private fun presentError(message: String): Notification =
        present(message, 0, NotificationVariant.LUMO_ERROR, isError = true, actionFor = { notification ->
            AppButtons.icon(VaadinIcon.CLOSE_SMALL, UiTranslations.tr("app.close")) {
                notification.close()
            }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        })

    /** A dismissable notification carrying one inline action button (e.g. "Undo" after a
     *  single detach): same row shape as [error], so the same styling and keyboard/AT
     *  reachability applies. [onAction] fires once, then closes the notification; the
     *  notification also auto-dismisses after [durationMs] like any other toast. */
    fun actionable(message: String, actionLabel: String, durationMs: Int = 6000, onAction: () -> Unit): Notification =
        present(message, durationMs, null, actionFor = { notification ->
            AppButtons.tertiary(actionLabel) {
                notification.close()
                onAction()
            }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
        })

    /** What a button showed before [confirmOn] replaced it, plus the pending restore. */
    private class Confirmation {
        var text = ""
        var icon: Component? = null
        var restore: ScheduledFuture<*>? = null
    }

    private val confirmScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ui-confirmation").apply { isDaemon = true }
    }

    /**
     * Confirms a save on the very button that was clicked (check icon plus "saved") instead of a
     * toast that would sit over other controls. The original label and icon come back after
     * [SUCCESS_MS]; a repeated call restarts the timer but keeps the true original.
     */
    fun confirmOn(button: Button) {
        var state = ComponentUtil.getData(button, Confirmation::class.java)
        if (state == null) {
            state = Confirmation()
            ComponentUtil.setData(button, Confirmation::class.java, state)
            button.addDetachListener { restoreConfirmation(button) }
        }
        // While a confirmation is pending the button shows the check, not its real content.
        if (state.restore == null) {
            state.text = button.text
            state.icon = button.icon
        }
        state.restore?.cancel(false)
        button.text = UiTranslations.tr(button, "message.saved")
        button.icon = Icon(VaadinIcon.CHECK)
        state.restore = confirmScheduler.schedule({
            button.ui.ifPresent { ui -> ui.access { restoreConfirmation(button) } }
        }, SUCCESS_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Puts the label and icon [confirmOn] replaced back; a no-op when none is pending. */
    internal fun restoreConfirmation(button: Button) {
        val state = ComponentUtil.getData(button, Confirmation::class.java)?.takeIf { it.restore != null } ?: return
        state.restore?.cancel(false)
        state.restore = null
        button.text = state.text
        button.icon = state.icon
    }

    /** Marker so [errorOn] registers its clearing listener once per field. */
    private class FieldErrorClearing

    /**
     * Shows an input error on the field that caused it instead of a toast: the message sits
     * under the field, the field takes focus, scrolls into view and flashes. The
     * `lt-field-flash` animation is re-triggered even if it is already playing —
     * `requestAnimationFrame` lands the class removal in its own paint frame so the browser
     * treats the re-add as a fresh start. Editing the value clears the error again. Field
     * errors are not persisted to the message center; only system failures belong there.
     */
    fun <T> errorOn(field: T, message: String?) where T : Component, T : HasValidation, T : Focusable<*> {
        field.isInvalid = true
        message?.let { field.errorMessage = it }
        // Registered before the visibility check: a field marked while hidden must still clear
        // once the user reaches it and corrects the value.
        registerClearOnEdit(field)
        if (!isShown(field)) {
            // A mark the user cannot see would leave the rejected action without any feedback.
            (message ?: field.errorMessage)?.takeIf { it.isNotBlank() }?.let { text -> presentError(text) }
            return
        }
        field.focus()
        flash(field)
    }

    /** Whether the user can currently see [component]: attached, and neither it nor an ancestor is
     *  hidden, in an unselected tab or in a collapsed accordion panel. */
    private fun isShown(component: Component): Boolean =
        component.ui.isPresent && generateSequence(component) { it.parent.orElse(null) }.all { node ->
            val parent = node.parent.orElse(null)
            node.isVisible &&
                !(parent is TabSheet && isUnselectedContent(parent, node)) &&
                !(parent is AccordionPanel && !parent.isOpened)
        }

    private fun isUnselectedContent(sheet: TabSheet, content: Component): Boolean {
        val tab = sheet.getTab(content)
        return tab != null && sheet.selectedTab != tab
    }

    fun flash(component: Component) {
        component.element.executeJs(
            "this.classList.remove('lt-field-flash');" +
                "requestAnimationFrame(() => this.classList.add('lt-field-flash'));" +
                "this.scrollIntoView({behavior: 'smooth', block: 'center'});"
        )
    }

    /**
     * Points at the first field a form binder rejected. The binder has already marked every
     * invalid field with its own message, so this only moves focus there and flashes it; it
     * answers `false` when the failure names no focusable field, so the caller can fall back to a
     * general notice instead of leaving the user without any hint.
     */
    fun focusFirstInvalid(failure: ValidationException): Boolean {
        val target = failure.fieldValidationErrors.firstNotNullOfOrNull { it.field as? Component } ?: return false
        // A field the user cannot see gives no hint; the caller then keeps its general notice.
        if (!isShown(target)) return false
        (target as? Focusable<*>)?.focus()
        flash(target)
        return true
    }

    private fun <T> registerClearOnEdit(field: T) where T : Component, T : HasValidation {
        if (field !is HasValue<*, *> || ComponentUtil.getData(field, FieldErrorClearing::class.java) != null) return
        ComponentUtil.setData(field, FieldErrorClearing::class.java, FieldErrorClearing())
        field.addValueChangeListener { field.isInvalid = false }
    }

    fun tr(key: String): String =
        UiTranslations.tr(key)

    fun orEmpty(value: String?): String = value ?: ""

    fun error(source: Component, exception: Exception) {
        error(resolveMessage(source, exception))
    }

    /** The human-readable message [error] would show for [exception] — exposed so callers that
     *  also need the text (e.g. to set on a field's own error label) don't re-derive it.
     *
     *  Nothing technical ever reaches the toast: a database rejection (Hibernate's
     *  constraint violation, any other persistence/SQL failure in the cause chain) is reduced to
     *  what the user can act on — "value already taken", "still referenced", "required value
     *  missing" — while the exact constraint and statement stay in the server log, where the
     *  caller logs the exception with its stack trace. */
    fun resolveMessage(source: Component, exception: Exception): String = when (exception) {
        is InvoiceValidationException -> resolveValidationError(exception)
        // WP1 (0.9.10) — a position edited/deleted concurrently: the item/trip write paths throw
        // these two directly (no ApplicationUserException wrapper at this UI boundary, unlike a
        // REST controller), so without this branch they fell through to the generic "an error
        // occurred" message below, losing the "someone else deleted this meanwhile" information
        // entirely. Mirrors [OptimisticLockException] right below — both are "reload the page"
        // outcomes of the same lost-update fix.
        is InvoiceItemNotFoundException, is InvoicePositionNotFoundException ->
            UiTranslations.tr(source, "message.error.itemDeletedConcurrently")
        // Concurrency-0.9.10 follow-up — header/item/trip edits against an invoice another
        // session voided or finalized meanwhile used to fall through to the generic "an error
        // occurred" message below, same defect the two branches above were fixed for. Same
        // "reload the page" family as [OptimisticLockException] right below, just triggered by a
        // status change instead of a version mismatch.
        is InvoiceStatusTransitionException -> UiTranslations.tr(source, "message.error.statusChangedConcurrently")
        is InvoiceImmutableException -> UiTranslations.tr(source, "message.error.invoiceImmutable")
        // ApplicationUserException and InvoiceDomainException are sealed, and every concrete
        // subclass takes a non-null `message: String` constructor parameter (see
        // ApplicationExceptions.kt / Exceptions.kt) — the same provably-unreachable-fallback
        // situation ApplicationExceptionTranslator.from already documents with
        // `exception.message!!` for the underlying domain exceptions.
        is ApplicationUserException -> exception.message!!
        is InvoiceDomainException -> exception.message!!
        // § 14 Abs. 4 Nr. 6 UStG send pre-check: plain RuntimeException with a non-null German message.
        is InvoiceDateBeforeServiceEndException -> exception.message!!
        is PdfUnrenderableTextException -> exception.message!!
        is ConstraintViolationException -> UiTranslations.tr(source, "message.error.constraintViolation")
        is OptimisticLockException -> UiTranslations.tr(source, "message.error.optimisticLock")
        // Commit-time conflicts arrive wrapped (RollbackException/ARJUNA016053) — checked before
        // persistenceMessageKey, whose PersistenceException match would otherwise win.
        else -> if (exception.isOptimisticLockFailure()) {
            UiTranslations.tr(source, "message.error.optimisticLock")
        } else {
            persistenceMessageKey(exception)?.let { UiTranslations.tr(source, it) }
                ?: UiTranslations.tr(source, "message.error.generic")
        }
    }

    /** The i18n key for a database rejection found anywhere in [exception]'s cause chain, or
     *  `null` when the failure has nothing to do with persistence. Hibernate classifies the
     *  violated constraint (unique / foreign key / not-null / check) from the JDBC error, which is
     *  exactly the granularity a user message needs; the constraint's name is not part of it. */
    private fun persistenceMessageKey(exception: Throwable): String? {
        val chain = generateSequence(exception) { it.cause?.takeIf { cause -> cause !== it } }.toList()
        val violation = chain.filterIsInstance<org.hibernate.exception.ConstraintViolationException>().firstOrNull()
        return when {
            violation != null -> constraintMessageKey(violation.kind)
            chain.any { it is jakarta.persistence.PersistenceException || it is SQLException } -> "message.error.db.generic"
            else -> null
        }
    }

    private fun constraintMessageKey(kind: ConstraintKind?): String = when (kind) {
        ConstraintKind.UNIQUE -> "message.error.db.unique"
        ConstraintKind.FOREIGN_KEY -> "message.error.db.foreignKey"
        ConstraintKind.NOT_NULL -> "message.error.db.notNull"
        else -> "message.error.db.generic"
    }

    /**
     * Resolves an [InvoiceValidationException] to a human-readable message using the i18n bundle.
     * The key is derived from the error code: [de.littletrotter.domain.exception.InvoiceValidationError.INVOICE_NUMBER_MISSING] → key
     * "domain.validation.invoiceNumberMissing". Positional `args` from the exception are forwarded
     * to MessageFormat.
     */
    private fun resolveValidationError(ex: InvoiceValidationException): String {
        val key = "domain.validation." + errorCodeToCamelCase(ex.error.name)
        return if (ex.args.isEmpty()) {
            UiTranslations.tr(key)
        } else {
            UiTranslations.tr(key, *ex.args.toTypedArray())
        }
    }

    /** Converts SCREAMING_SNAKE_CASE to camelCase: INVOICE_NUMBER_MISSING → invoiceNumberMissing. */
    private fun errorCodeToCamelCase(name: String): String =
        name.split("_")
            .mapIndexed { i, word -> if (i == 0) word.lowercase() else word.lowercase().replaceFirstChar(Char::uppercase) }
            .joinToString("")

    fun formatCityCountry(city: String?, country: Any?): String {
        val c = city ?: ""
        // Locale.forLanguageTag is documented as lenient — it never throws for a malformed tag,
        // it just returns its best-effort match — so the try/catch this line used to carry around
        // a raw String country code was unreachable dead code, not a real defensive guard.
        val cn = when (country) {
            is Country -> Locale.forLanguageTag("und-${country.alpha2}").displayCountry
            is String -> Locale.forLanguageTag("und-$country").displayCountry
            else -> ""
        }
        return when {
            c.isBlank() -> cn
            cn.isBlank() -> c
            else -> "$c, $cn"
        }
    }
}
