package de.littletrotter.ui.notification

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.message.SystemMessageFilter
import de.littletrotter.domain.message.SystemMessageSource
import de.littletrotter.domain.repository.SystemMessageRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [UiNotifications.error]'s message-center persistence hook against a real CDI
 * container + Postgres, complementing the plain-JUnit (no-container) coverage in
 * [UiNotificationsTest]: that class has no Arc/CDI container active at all (it only sets up
 * Karibu's mock Vaadin, never Quarkus), so its `error(...)` calls already exercise the "container
 * absent, degrade silently" path this task doc asks for — nothing further needed there. This
 * class exercises the other side: a container genuinely present.
 *
 * Karibu's [MockVaadin] is still needed here too — not for the persistence hook itself, but
 * because `Notification.open()` (later in [UiNotifications.error]/`success`) throws
 * `IllegalStateException` without an active `UI` regardless of CDI. Same setup/teardown pattern
 * as [UiNotificationsTest].
 *
 * Every message text is a fresh [UUID] so parallel test methods (and this container's otherwise
 * empty database — [PostgresTestResource] starts a new, unshared instance per test class) never
 * collide.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class UiNotificationsMessageCenterIT {

    @Inject
    lateinit var repository: SystemMessageRepository

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun uiRows(): List<de.littletrotter.domain.message.SystemMessage> =
        repository.find(userId = "system", filter = SystemMessageFilter(source = setOf(SystemMessageSource.UI)), pageSize = 200)

    @Test
    fun `error persists a message-center row when a CDI container is present`() {
        val message = "IT-error-${UUID.randomUUID()}"

        UiNotifications.error(message)

        val row = uiRows().firstOrNull { it.resolvedText == message }
        assertTrue(row != null, "the shown error must have been persisted via MessageCenterService")
        assertEquals("ui.error.raw", row!!.messageKey)
        assertEquals(SystemMessageSource.UI, row.source)
        assertEquals(de.littletrotter.domain.message.SystemMessageSeverity.ERROR, row.severity)
        assertEquals(message, row.argsList().firstOrNull())
    }

    @Test
    fun `success never persists to the message center`() {
        val message = "IT-success-${UUID.randomUUID()}"

        UiNotifications.success(message)

        assertTrue(uiRows().none { it.resolvedText == message }, "success toasts must never be persisted")
    }

    @Test
    fun `an immediate repeat of the same error text is suppressed by the dedup guard`() {
        val message = "IT-dedup-${UUID.randomUUID()}"

        UiNotifications.error(message)
        UiNotifications.error(message)

        val matches = uiRows().count { it.resolvedText == message }
        assertEquals(1, matches, "the same text fired twice within the dedup window must only persist once")
    }

    @Test
    fun `two different error texts both persist`() {
        val first = "IT-distinct-a-${UUID.randomUUID()}"
        val second = "IT-distinct-b-${UUID.randomUUID()}"

        UiNotifications.error(first)
        UiNotifications.error(second)

        assertTrue(uiRows().any { it.resolvedText == first })
        assertTrue(uiRows().any { it.resolvedText == second })
        assertFalse(first == second)
    }
}
