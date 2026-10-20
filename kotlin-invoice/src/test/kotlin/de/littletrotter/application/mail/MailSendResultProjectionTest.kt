package de.littletrotter.application.mail

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The projection's four documented behaviours, each asserted on the outcome value
 * rather than on log output: normal projection, redelivery is a no-op, an unknown reference key is
 * tolerated, and a FAILED report writes nothing because this read model has no column for it.
 *
 * [MailSendResultProjection] now consumes [MailDeliveryReport], this application's
 * own local model, rather than the contract's `MailSendResultEvent` — this test builds reports
 * directly instead of contract events.
 */
@DisplayName("MailSendResultProjection (event-fed read model on the invoice)")
class MailSendResultProjectionTest {

    private lateinit var repository: InvoiceRepository
    private lateinit var projection: MailSendResultProjection

    private val sentAt: Instant = Instant.parse("2026-09-07T10:15:30Z")

    @BeforeEach
    fun setUp() {
        repository = mock()
        projection = MailSendResultProjection(repository)
    }

    private fun invoice(number: String = "RE-2026-0001") = Invoice().edit {
        this.id = 7L
        this.invoiceNumber = InvoiceNumber(number)
    }

    private fun report(
        reference: String = "RE-2026-0001",
        outcome: MailDeliveryOutcome = MailDeliveryOutcome.SENT,
        at: Instant = sentAt,
        journalId: Long = 99L,
    ) = MailDeliveryReport(
        journalId = journalId,
        referenceKey = reference,
        outcome = outcome,
        messageId = if (outcome == MailDeliveryOutcome.SENT) "<msg@example.com>" else null,
        sentAt = at,
    )

    @Test
    @DisplayName("a SENT report sets the flag and the timestamp")
    fun projectsSent() {
        val invoice = invoice()
        whenever(repository.findByInvoiceNumber("RE-2026-0001")).thenReturn(invoice)

        assertEquals(MailProjectionOutcome.APPLIED, projection.project(report()))

        assertEquals(true, invoice.mailSent)
        assertEquals(sentAt, invoice.mailSentAt?.toInstant())
        verify(repository).save(invoice)
    }

    @Test
    @DisplayName("the same report delivered twice changes nothing")
    fun redeliveryIsANoOp() {
        val invoice = invoice().edit {
            mailSent = true
            mailSentAt = sentAt.atOffset(ZoneOffset.UTC)
        }
        whenever(repository.findByInvoiceNumber("RE-2026-0001")).thenReturn(invoice)

        assertEquals(MailProjectionOutcome.DUPLICATE, projection.project(report()))

        assertEquals(true, invoice.mailSent)
        assertEquals(sentAt, invoice.mailSentAt?.toInstant())
        verify(repository, never()).save(any())
    }

    @Test
    @DisplayName("a redelivery whose timestamp differs is a genuine re-send and does update")
    fun laterSendUpdatesTheTimestamp() {
        val earlier = Instant.parse("2026-09-01T08:00:00Z")
        val invoice = invoice().edit {
            mailSent = true
            mailSentAt = earlier.atOffset(ZoneOffset.UTC)
        }
        whenever(repository.findByInvoiceNumber("RE-2026-0001")).thenReturn(invoice)

        assertEquals(MailProjectionOutcome.APPLIED, projection.project(report()))
        assertEquals(sentAt, invoice.mailSentAt?.toInstant())
    }

    @Test
    @DisplayName("a referenceKey that is no invoice number is ignored, never an exception")
    fun invalidReferenceIsIgnored() {
        assertEquals(
            MailProjectionOutcome.INVALID_REFERENCE,
            projection.project(report(reference = "AN-2026-0006-R2")),
        )
        verify(repository, never()).findByInvoiceNumber(any())
        verify(repository, never()).save(any())
    }

    @Test
    @DisplayName("an unknown referenceKey is logged and acknowledged, never a crash")
    fun unknownReferenceIsTolerated() {
        whenever(repository.findByInvoiceNumber("RE-2026-9999")).thenReturn(null)

        assertEquals(
            MailProjectionOutcome.UNKNOWN_REFERENCE,
            projection.project(report(reference = "RE-2026-9999")),
        )
        verify(repository, never()).save(any())
    }

    @Test
    @DisplayName("a FAILED report writes nothing — this projection has two columns and no failure state")
    fun failedIsNotProjected() {
        assertEquals(
            MailProjectionOutcome.FAILURE_NOT_PROJECTED,
            projection.project(report(outcome = MailDeliveryOutcome.FAILED)),
        )
        // Not even a lookup: the reference key of a failed send is of no interest to the read model.
        verify(repository, never()).findByInvoiceNumber(any())
        verify(repository, never()).save(any())
    }

    @Test
    @DisplayName("a FAILED report never clears an earlier SENT flag")
    fun failedDoesNotClearAnEarlierSend() {
        val invoice = invoice().edit {
            mailSent = true
            mailSentAt = sentAt.atOffset(ZoneOffset.UTC)
        }
        whenever(repository.findByInvoiceNumber("RE-2026-0001")).thenReturn(invoice)

        projection.project(report(outcome = MailDeliveryOutcome.FAILED))

        assertEquals(true, invoice.mailSent)
        assertEquals(sentAt, invoice.mailSentAt?.toInstant())
    }

    @Test
    @DisplayName("an invoice that was never mailed carries nulls, not false")
    fun neverMailedIsNull() {
        val invoice = invoice()
        assertNull(invoice.mailSent)
        assertNull(invoice.mailSentAt)
    }

    @Test
    @DisplayName("the stored timestamp is UTC, whatever offset the reader later renders it in")
    fun storedTimestampIsUtc() {
        val invoice = invoice()
        whenever(repository.findByInvoiceNumber("RE-2026-0001")).thenReturn(invoice)

        projection.project(report())

        val stored: OffsetDateTime = requireNotNull(invoice.mailSentAt)
        assertTrue(stored.offset == ZoneOffset.UTC, "expected a UTC offset, was ${stored.offset}")
    }
}
