package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.mail.MailDeliveryOutcome
import de.littletrotter.shared.mail.MailSendOutcome
import de.littletrotter.shared.mail.MailSendResultEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

/**
 * Unit tests for the inbound (send-result) translator, the contract-facing half
 * of the anti-corruption layer that used to be inlined in
 * [de.littletrotter.application.mail.MailSendResultProjection].
 */
@DisplayName("MailSendResultTranslator (MailSendResultEvent -> MailDeliveryReport)")
class MailSendResultTranslatorTest {

    private val sentAt: Instant = Instant.parse("2026-09-07T10:15:30Z")

    private fun event(
        status: MailSendOutcome = MailSendOutcome.SENT,
        messageId: String? = "<abc@mail.example>",
    ) = MailSendResultEvent(
        journalId = 17L,
        referenceKey = "RE-2026-0001",
        status = status,
        messageId = messageId,
        sentAt = sentAt,
    )

    // ── Outcome — every MailSendOutcome branch ─────────────────────────────────

    @ParameterizedTest
    @EnumSource(MailSendOutcome::class)
    @DisplayName("every MailSendOutcome maps to a MailDeliveryOutcome of the same name")
    fun everyOutcomeMaps(status: MailSendOutcome) {
        val report = MailSendResultTranslator.toDeliveryReport(event(status = status))
        assertEquals(MailDeliveryOutcome.valueOf(status.name), report.outcome)
    }

    // ── Envelope fields ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("journalId, referenceKey and sentAt are carried through verbatim")
    fun carriesEnvelopeFields() {
        val report = MailSendResultTranslator.toDeliveryReport(event())
        assertEquals(17L, report.journalId)
        assertEquals("RE-2026-0001", report.referenceKey)
        assertEquals(sentAt, report.sentAt)
    }

    // ── messageId — optional field ─────────────────────────────────────────────

    @Test
    @DisplayName("a present messageId is carried through")
    fun carriesPresentMessageId() {
        val report = MailSendResultTranslator.toDeliveryReport(event(messageId = "<abc@mail.example>"))
        assertEquals("<abc@mail.example>", report.messageId)
    }

    @Test
    @DisplayName("a FAILED event's absent messageId stays null")
    fun absentMessageIdStaysNull() {
        val report = MailSendResultTranslator.toDeliveryReport(event(status = MailSendOutcome.FAILED, messageId = null))
        assertNull(report.messageId)
    }
}
