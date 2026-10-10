package de.littletrotter.ui.notification

import de.littletrotter.application.message.MessageCenterService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.UUID
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [UiNotifications.persistError]'s `UI.getCurrent()?.internals?...` view-path chain
 * inside an active CDI container: [UiNotificationsTest]'s own CDI-mock test runs under
 * `MockVaadin.setup()`, so `UI.getCurrent()` is never null there — this class deliberately never
 * calls it, so the CDI lookup succeeds while `UI.getCurrent()` still returns null (a background
 * job calling `error()` indirectly, per that line's own KDoc).
 */
class UiNotificationsNoUiTest {

    @Test
    fun `boundary - a CDI-successful error with no active UI still persists, view path simply omitted`() {
        val service = mock<MessageCenterService>()

        withMockedCdi(service, FIXED_TIME_SOURCE) {
            // persistError() (message-center persistence, including the view-path lookup) runs
            // BEFORE the Notification itself is built — whether opening a Notification with no
            // active UI throws is a separate, unrelated concern this test does not assert on.
            runCatching { UiNotifications.error("No active UI, distinct message ${UUID.randomUUID()}") }
        }

        verify(service).record(any(), recipientUserId = anyOrNull())
    }
}
