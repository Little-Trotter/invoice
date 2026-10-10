package de.littletrotter.ui.view.invoice.report

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.page.Page
import com.vaadin.flow.component.page.PendingJavaScriptResult
import com.vaadin.flow.function.SerializableConsumer
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.requireAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * [copyMarkdownToClipboard] is the sole clipboard idiom
 * [ValidationReportDialog] and [de.littletrotter.ui.view.invoice.AiReportDialog]'s copy buttons
 * share. Every "has a UI" scenario runs against the real [MockVaadin] session's own current UI,
 * unmodified — neither a bare `mock<UI>()` nor a [Mockito.spy] of it carries the exact identity
 * Vaadin's own internals (e.g. [com.vaadin.flow.component.UI.setChildComponentModal]'s "is this
 * component attached to THIS UI" check) compare against, so either substitute makes
 * [com.vaadin.flow.component.notification.Notification.open] fail. Instead, the real UI's private,
 * `final` `page` field is overwritten via reflection to a controlled [Page] double — the UI object
 * itself, and its identity, never changes. `Page.executeJs` is a Java varargs method that Mockito's
 * matcher-based stubbing does not reliably intercept, so its stub uses an [org.mockito.stubbing
 * .Answer] keyed on the method name instead.
 */
class ClipboardSupportTest {

    private val trEcho: (String) -> String = { it }
    private val button = Button("copy")

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    /** Success toasts ([de.littletrotter.ui.notification.UiNotifications.success]) are plain
     *  [Notification.show] ones with a "text" element property; error toasts
     *  ([de.littletrotter.ui.notification.UiNotifications.error]) carry the message in a child
     *  [Span] instead — see that class's own KDoc. */
    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    /** A [Page] whose only stubbed behaviour is `executeJs` returning [pending] — every other
     *  call keeps Mockito's default (null/no-op), which nothing under test relies on. */
    private fun pageReturning(pending: PendingJavaScriptResult): Page =
        Mockito.mock(Page::class.java) { invocation ->
            if (invocation.method.name == "executeJs") pending else null
        }

    /** Overwrites the real current [UI]'s private `page` field via reflection, so
     *  [com.vaadin.flow.component.notification.Notification.open]'s own internal identity checks
     *  (comparing against `UI.getCurrent()`) keep working against the one true UI object, while
     *  `ui.page` resolves to the controlled [Page] double for `executeJs`. */
    private fun installPage(page: Page): UI {
        val ui = UI.getCurrent()
        val field = UI::class.java.getDeclaredField("page")
        field.isAccessible = true
        field.set(ui, page)
        return ui
    }

    private fun stubThen(pending: PendingJavaScriptResult, invoke: (onSuccess: SerializableConsumer<Any?>, onError: SerializableConsumer<String>) -> Unit) {
        Mockito.doAnswer { invocation ->
            val onSuccess = invocation.arguments[0].requireAs<SerializableConsumer<Any?>>()
            val onError = invocation.arguments[1].requireAs<SerializableConsumer<String>>()
            invoke(onSuccess, onError)
            null
        }.`when`(pending).then(Mockito.any(), Mockito.any())
    }

    @Test
    fun `happy path - a successful clipboard write shows confirms on the copy button instead of a toast`() {
        val pending = mock<PendingJavaScriptResult>()
        stubThen(pending) { onSuccess, _ -> onSuccess.accept(null) }
        val page = pageReturning(pending)
        installPage(page)

        copyMarkdownToClipboard("**hello**", button, trEcho)

        verify(page).executeJs($$"navigator.clipboard.writeText($0)", "**hello**")
        assertEquals(0, getNotifications().size)
        assertEquals(UiTranslations.tr("message.saved"), button.text)
    }

    @Test
    fun `boundary - a rejected clipboard write shows the error toast with the browser error appended`() {
        val pending = mock<PendingJavaScriptResult>()
        stubThen(pending) { _, onError -> onError.accept("NotAllowedError") }
        val page = pageReturning(pending)
        installPage(page)

        copyMarkdownToClipboard("md", button, trEcho)

        assertEquals(
            "invoiceDetail.jobs.report.copy.error: NotAllowedError",
            notificationText(getNotifications().single()),
        )
    }

    @Test
    fun `boundary - with no current UI, the error toast is shown and no script ever runs`() {
        Mockito.mockStatic(UI::class.java, Mockito.CALLS_REAL_METHODS).use { staticUi ->
            staticUi.`when`<UI> { UI.getCurrent() }.thenReturn(null).thenCallRealMethod()

            copyMarkdownToClipboard("md", button, trEcho)
        }

        assertEquals(1, getNotifications().size)
        assertEquals("invoiceDetail.jobs.report.copy.error", notificationText(getNotifications().single()))
    }

    @Test
    fun `boundary - a custom error key is honoured instead of the default`() {
        val pending = mock<PendingJavaScriptResult>()
        stubThen(pending) { _, onError -> onError.accept("denied") }
        val page = pageReturning(pending)
        installPage(page)

        copyMarkdownToClipboard("md", button, trEcho, errorKey = "custom.error")

        assertEquals("custom.error: denied", notificationText(getNotifications().single()))
    }
}
