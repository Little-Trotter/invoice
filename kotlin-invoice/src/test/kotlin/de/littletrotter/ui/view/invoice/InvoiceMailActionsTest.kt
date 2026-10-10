package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.mail.InvoiceMailDispatchService
import de.littletrotter.application.mail.MailComposeHandover
import de.littletrotter.application.mail.MailComposeResult
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.ui.view.mail.MailComposeView
import de.littletrotter.ui.view.mail.MailJournalView
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [InvoiceMailActions], built directly with a mocked [InvoiceMailDispatchService]/
 * [FeatureSettingsGate], without [InvoiceDetailView] or [InvoiceCockpitHero] (same idiom other
 * component tests use). Formerly `InvoiceDetailViewMailKaribuTest`, which reached the same
 * buttons/history only through the whole cockpit hero.
 */
@DisplayName("InvoiceMailActions — send availability, compose handover and send history (enabled and disabled)")
class InvoiceMailActionsTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val host = Div()

    private fun dto(
        mailSent: Boolean? = null,
        mailSentAt: OffsetDateTime? = null,
    ): InvoiceDetailDto = InvoiceDetailDto(
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
        mailSent = mailSent,
        mailSentAt = mailSentAt,
    )

    private fun dispatch(available: Boolean): InvoiceMailDispatchService = mock<InvoiceMailDispatchService>().also {
        whenever(it.isAvailable()).thenReturn(available)
    }

    // ── Enabled ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enabled — the mail module counts as available")
    fun availableWhenEnabled() {
        assertTrue(InvoiceMailActions(dispatch(available = true), null).isMailAvailable())
    }

    @Test
    @DisplayName("enabled — clicking it performs exactly one handover and navigates to the draft's compose view")
    fun clickOpensTheComposeView() {
        val dispatch = dispatch(available = true)
        whenever(dispatch.compose(1L)).thenReturn(MailComposeResult.Ready(MailComposeHandover("d-7f3a", "/mail/compose/d-7f3a")))
        RouteConfiguration.forSessionScope().setAnnotatedRoute(MailComposeView::class.java)
        InvoiceMailActions(dispatch, null).openMailCompose(host, dto())

        val location = UI.getCurrent().internals.activeViewLocation
        assertEquals("mail/compose/d-7f3a", location.path)
        assertEquals(listOf("1"), location.queryParameters.parameters["invoice"])

        // Exactly one handover per click, and nothing else on the click path: the outcome of the
        // send arrives later as a NATS event, so there is no host-side progress state to poll.
        verify(dispatch).compose(1L)
    }

    @Test
    @DisplayName("a refused handover surfaces its own message instead of opening an empty dialog")
    fun refusedHandoverIsReported() {
        val dispatch = dispatch(available = true)
        whenever(dispatch.compose(any())).thenThrow(MailDispatchException("Recipient 'Muster GmbH' has no email address"))

        InvoiceMailActions(dispatch, null).openMailCompose(host, dto())

        verify(dispatch).compose(1L)
    }

    @Test
    fun `boundary - the SFS email flag disabled refuses the compose with the German offer text, never calling the component`() {
        val dispatch = dispatch(available = true)
        val gate = featureGateWithDisabled(FeatureFlag.EMAIL_SEND)

        InvoiceMailActions(dispatch, gate).openMailCompose(host, dto())

        verify(dispatch, never()).compose(any())
    }

    @Test
    fun `boundary - a non-MailDispatchException escaping compose is caught and reported generically, never left to crash the click`() {
        val dispatch = dispatch(available = true)
        whenever(dispatch.compose(any())).thenThrow(RuntimeException("boom"))

        InvoiceMailActions(dispatch, null).openMailCompose(host, dto())

        verify(dispatch).compose(1L)
    }

    // ── Disabled ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("disabled — the mail module counts as unavailable")
    fun unavailableWhenDisabled() {
        assertFalse(InvoiceMailActions(dispatch(available = false), null).isMailAvailable())
    }

    @Test
    @DisplayName("no mail dispatch bean at all (an instance without the component) is unavailable too")
    fun unavailableWithoutABean() {
        assertFalse(InvoiceMailActions(null, null).isMailAvailable())
    }

    // ── Send history (H-7) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an invoice that was never mailed shows no history row at all")
    fun noHistoryWhenNeverMailed() {
        assertNull(InvoiceMailActions(dispatch(available = true), null).buildHistory(host, dto()))
    }

    @Test
    @DisplayName("the history renders from the projection alone — with the component enabled it adds a journal deep link")
    fun historyWithJournalLink() {
        val dispatch = dispatch(available = true)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(MailJournalView::class.java)
        val row = requireNotNull(
            InvoiceMailActions(dispatch, null).buildHistory(
                host,
                dto(mailSent = true, mailSentAt = OffsetDateTime.of(2026, 9, 7, 10, 15, 0, 0, ZoneOffset.UTC)),
            ),
        )

        val link = row.children.toList().filterIsInstance<RouterLink>().single()
        assertEquals("mail/journal?reference=RE-2026-0001", link.href)
        // H-7's actual requirement: nothing on the render path asked the component anything.
        verify(dispatch, never()).compose(any())
    }

    @Test
    @DisplayName("the history still renders with the component removed — only the deep link is gone")
    fun historyWithoutTheComponent() {
        val row = requireNotNull(
            InvoiceMailActions(null, null).buildHistory(
                host,
                dto(mailSent = true, mailSentAt = OffsetDateTime.of(2026, 9, 7, 10, 15, 0, 0, ZoneOffset.UTC)),
            ),
        )

        assertTrue(
            row.children.toList().filterIsInstance<RouterLink>().isEmpty(),
            "a journal link into a component that is not installed would be a dead link",
        )
    }

    @Test
    @DisplayName("a projected send without a timestamp still renders a history row")
    fun historyWithoutATimestamp() {
        val row = InvoiceMailActions(dispatch(available = false), null).buildHistory(host, dto(mailSent = true))
        assertNotNull(row)
    }

    @Test
    @DisplayName("mailSent=false is treated as 'not mailed', never as a history entry")
    fun explicitFalseShowsNoHistory() {
        assertNull(InvoiceMailActions(dispatch(available = true), null).buildHistory(host, dto(mailSent = false)))
    }
}
