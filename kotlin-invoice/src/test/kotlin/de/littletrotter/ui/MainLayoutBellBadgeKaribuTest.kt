package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.LocationChangeEvent
import com.vaadin.flow.router.NavigationTrigger
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.message.MessageRecorded
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * UI-5 slice 3c — [MainLayout.refreshBellBadge]: every existing `afterNavigation` test in this
 * module stubs `unreadCountFor` to return `0L`, so the badge's "there are unread messages" side
 * (visible text, non-empty aria-label suffix) had never run — only the "hidden at zero" side.
 */
class MainLayoutBellBadgeKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun mainLayoutWithMockedCdi(unreadCount: Long): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterService>().also { whenever(it.unreadCountFor(any())).thenReturn(unreadCount) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorProvider>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(mainLayout, "appRoleProvider", mock<AppRoleProvider>())
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        return mainLayout
    }

    private fun afterNavigationEventFor(path: String): AfterNavigationEvent {
        val ui = UI.getCurrent()!!
        val locationChangeEvent = LocationChangeEvent(
            ui.internals.router,
            ui,
            NavigationTrigger.PROGRAMMATIC,
            Location(path),
            emptyList(),
        )
        return AfterNavigationEvent(locationChangeEvent)
    }

    private fun allDescendants(root: com.vaadin.flow.component.Component): List<com.vaadin.flow.component.Component> {
        val result = mutableListOf<com.vaadin.flow.component.Component>()
        root.children.forEach { child ->
            result.add(child)
            result.addAll(allDescendants(child))
        }
        return result
    }

    private fun bellBadge(mainLayout: MainLayout): Span =
        allDescendants(mainLayout).filterIsInstance<Span>().first { it.classNames.contains("bell-badge") }

    private fun bellButton(mainLayout: MainLayout): Button =
        allDescendants(mainLayout).filterIsInstance<Button>().first { it.classNames.contains("bell-button") }

    @Test
    fun `an unread count of zero keeps the badge hidden and the aria-label plain`() {
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 0L)

        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))

        assertFalse(bellBadge(mainLayout).isVisible)
        assertEquals(UiTranslations.tr("messageCenter.title"), bellButton(mainLayout).element.getAttribute("aria-label"))
    }

    @Test
    fun `a positive unread count shows the badge text and appends the count to the aria-label`() {
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 7L)

        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))

        assertTrue(bellBadge(mainLayout).isVisible)
        assertEquals("7", bellBadge(mainLayout).text)
        assertEquals(
            "${UiTranslations.tr("messageCenter.title")} (7)",
            bellButton(mainLayout).element.getAttribute("aria-label"),
        )
    }

    // ── line 162 — content?.element?.setAttribute("id", "main-content") ────────────────────────

    @Test
    fun `afterNavigation tags the routed content element with the main-content id when content is set`() {
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 0L)
        val routedContent = com.vaadin.flow.component.html.Div()
        mainLayout.content = routedContent

        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))

        assertEquals("main-content", routedContent.element.getAttribute("id"))
    }

    @Test
    fun `afterNavigation does not throw when no content has been routed yet`() {
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 0L)

        mainLayout.afterNavigation(afterNavigationEventFor("statistics"))
        // No assertion beyond "did not throw" — content is null before any child view is routed.
    }

    @Test
    fun `a recorded message while attached refreshes the badge and detach removes the listener`() {
        val bus = UiEventBus()
        bus.scheduler = ImmediateScheduler.create()
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 0L)
        val service = mock<MessageCenterService>()
        whenever(service.unreadCountFor(any())).thenReturn(3L)
        setPrivateField(mainLayout, "messageCenterService", service)
        mainLayout.uiEventBus = bus
        val ui = UI.getCurrent()!!

        ui.add(mainLayout)
        assertEquals(1, bus.messageListenerCount())
        bus.onMessageRecorded(MessageRecorded)
        MockVaadin.clientRoundtrip()

        assertTrue(bellBadge(mainLayout).isVisible)
        assertEquals("3", bellBadge(mainLayout).text)

        ui.remove(mainLayout)
        assertEquals(0, bus.messageListenerCount())
    }

    @Test
    fun `re-attaching replaces the registration instead of stacking listeners`() {
        val bus = UiEventBus()
        val mainLayout = mainLayoutWithMockedCdi(unreadCount = 0L)
        mainLayout.uiEventBus = bus
        val ui = UI.getCurrent()!!

        ui.add(mainLayout)
        ui.remove(mainLayout)
        ui.add(mainLayout)

        assertEquals(1, bus.messageListenerCount())
    }

    private object ImmediateScheduler {
        fun create(): java.util.concurrent.ScheduledExecutorService = mock<java.util.concurrent.ScheduledExecutorService>().also { mock ->
            whenever(mock.schedule(any<Runnable>(), any(), any())).thenAnswer {
                (it.arguments[0] as Runnable).run()
                mock<java.util.concurrent.ScheduledFuture<*>>()
            }
        }
    }
}
