package de.littletrotter.application.mail

import de.littletrotter.application.mail.port.inbound.MailSendGate
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.fixture.aGenerationJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("mailSendGateOf")
class MailSendGateTest {

    private fun gate(
        status: InvoiceStatus = InvoiceStatus.ISSUED,
        validation: ValidationStatus = ValidationStatus.VALID,
        jobs: List<GenerationJob> = emptyList(),
        attachable: Boolean = true,
        reproduced: Boolean = false,
        email: String? = "kunde@example.com",
    ) = mailSendGateOf(MailSendFacts(status, validation, jobs, attachable, reproduced, email))

    private fun sendJob(status: GenerationJobStatus) = aGenerationJob {
        trigger = GenerationJobTrigger.SEND
        this.status = status
    }

    @Test
    fun `ready when every condition holds`() = assertEquals(MailSendGate.READY, gate())

    @Test
    fun `in flight send jobs block`() {
        listOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING).forEach {
            assertEquals(MailSendGate.JOB_IN_FLIGHT, gate(jobs = listOf(sendJob(it))))
        }
    }

    @Test
    fun `finished send jobs and in flight jobs of other triggers do not block`() {
        val manual = aGenerationJob { trigger = GenerationJobTrigger.MANUAL; status = GenerationJobStatus.RUNNING }
        assertEquals(MailSendGate.READY, gate(jobs = listOf(sendJob(GenerationJobStatus.COMPLETED), manual)))
    }

    @Test
    fun `an invoice that is not sent is not issued`() =
        assertEquals(MailSendGate.NOT_ISSUED, gate(status = InvoiceStatus.IN_REVIEW))

    @Test
    fun `failed validation is not valid`() =
        assertEquals(MailSendGate.NOT_VALID, gate(validation = ValidationStatus.INVALID))

    @Test
    fun `unavailable validation passes, pending is its own reason`() {
        assertEquals(MailSendGate.READY, gate(validation = ValidationStatus.UNAVAILABLE))
        assertEquals(MailSendGate.VALIDATION_PENDING, gate(validation = ValidationStatus.PENDING))
    }

    @Test
    fun `a running validation ranks below the issued and in flight checks and above not valid`() {
        assertEquals(
            MailSendGate.NOT_ISSUED,
            gate(status = InvoiceStatus.IN_REVIEW, validation = ValidationStatus.PENDING),
        )
        assertEquals(
            MailSendGate.JOB_IN_FLIGHT,
            gate(validation = ValidationStatus.PENDING, jobs = listOf(sendJob(GenerationJobStatus.RUNNING))),
        )
        assertEquals(MailSendGate.NOT_VALID, gate(validation = ValidationStatus.INVALID))
        assertEquals(MailSendGate.VALIDATION_PENDING, gate(validation = ValidationStatus.PENDING, attachable = false))
    }

    @Test
    fun `no attachable document`() = assertEquals(MailSendGate.DOCUMENT_MISSING, gate(attachable = false))

    @Test
    fun `reproduced document`() = assertEquals(MailSendGate.REPRODUCED, gate(reproduced = true))

    @Test
    fun `blank or missing recipient email`() {
        assertEquals(MailSendGate.NO_RECIPIENT_EMAIL, gate(email = null))
        assertEquals(MailSendGate.NO_RECIPIENT_EMAIL, gate(email = "  "))
    }

    @Test
    fun `the first violated condition wins`() {
        assertEquals(
            MailSendGate.JOB_IN_FLIGHT,
            gate(status = InvoiceStatus.IN_REVIEW, jobs = listOf(sendJob(GenerationJobStatus.RUNNING))),
        )
        assertEquals(MailSendGate.NOT_ISSUED, gate(status = InvoiceStatus.IN_REVIEW, validation = ValidationStatus.INVALID))
        assertEquals(MailSendGate.NOT_VALID, gate(validation = ValidationStatus.INVALID, attachable = false))
        assertEquals(MailSendGate.DOCUMENT_MISSING, gate(attachable = false, reproduced = true))
        assertEquals(MailSendGate.REPRODUCED, gate(reproduced = true, email = null))
    }
}
