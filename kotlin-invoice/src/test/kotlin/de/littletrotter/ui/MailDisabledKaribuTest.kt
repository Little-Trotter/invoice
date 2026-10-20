package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.port.outbound.MailDispatchPort
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.view.invoice.InvoiceMailActions
import de.littletrotter.ui.view.settings.SettingsView
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `mail.enabled=false` (the default): no UI element may lead into the mail component. Uses the real
 * [MailAvailability] and the real [InvoiceMailDispatchService] over an unresolvable adapter
 * instance — exactly what CDI yields without the HTTP adapter bean — instead of mocking the
 * availability answer.
 */
@DisplayName("MAIL_ENABLED=false — no dead mail entry point")
class MailDisabledKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun descendants(root: Component): List<Component> =
        root.children.toList().flatMap { listOf(it) + descendants(it) }

    private fun disabledDispatch(): InvoiceMailDispatchService {
        val port = mock<Instance<MailDispatchPort>>()
        whenever(port.isResolvable).thenReturn(false)
        return InvoiceMailDispatchService(MailAvailability(false), port, mock())
    }

    @Nested
    @DisplayName("invoice detail mail actions")
    inner class InvoiceDetail {

        private fun dto() = InvoiceDetailDto(
            id = 1L,
            invoiceNumber = "RE-2026-0001",
            status = InvoiceStatus.DRAFT.ordinal,
            invoiceDate = null,
            netAmount = null,
            vatAmount = null,
            grossAmount = null,
            sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
            recipient = null,
            items = emptyList(),
            latestGenerationJob = null,
            mailSent = true,
            mailSentAt = OffsetDateTime.of(2026, 9, 7, 10, 15, 0, 0, ZoneOffset.UTC),
        )

        @Test
        fun `the real service reports unavailable`() {
            assertFalse(disabledDispatch().isAvailable())
        }

        @Test
        fun `the mail module counts as unavailable`() {
            assertFalse(InvoiceMailActions(disabledDispatch(), null).isMailAvailable())
        }

        @Test
        fun `the send history carries no link into the mail component`() {
            val row = InvoiceMailActions(disabledDispatch(), null).buildHistory(Div(), dto())
            assertNotNull(row, "history stays readable from the local projection")
            assertTrue(descendants(row!!).filterIsInstance<RouterLink>().isEmpty())
        }
    }

    @Nested
    @DisplayName("settings mail card")
    inner class Settings {

        private val actor = object : AuditActorQueryPort {
            override fun currentActor(): String = "stefan"
        }

        private fun view() = SettingsView(
            featureGateWithDisabled(),
            MailAvailability(false),
            actor,
            WordingSettingsGate(InMemorySystemSettingRepository(), "HANDWERK", time = FIXED_TIME_SOURCE),
            de.littletrotter.ui.settings.quoteCardsAbsent(),
            de.littletrotter.ui.settings.archiveHandoverCardsAbsent(),
        )

        @Test
        fun `the email card is not rendered`() {
            val ids = descendants(view()).map { it.id.orElse("") }
            assertTrue(ids.none { it.endsWith("-email") }, "email card elements present: $ids")
            assertTrue(ids.contains("settings-card-perdiem"))
        }

        @Test
        fun `no element links into the mail component`() {
            val links = descendants(view()).filterIsInstance<Anchor>().map { it.href }
            assertTrue(links.none { it.contains("/mail") }, "unexpected mail links: $links")
        }
    }
}
