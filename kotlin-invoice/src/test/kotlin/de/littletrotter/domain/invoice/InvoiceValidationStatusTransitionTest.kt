package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationStatusTransitionException
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ( pattern transferred from [GenerationJob.transitionTo]/[GenerationJobStateMachine])
 * — [Invoice.transitionValidationStatusTo] is the single entity-level entry point into
 * [de.littletrotter.domain.shared.ValidationStatus.canTransitionTo]'s guard, mirrored here the
 * same way [GenerationJobStateMachineTest] covers [GenerationJob.transitionTo].
 */
class InvoiceValidationStatusTransitionTest {

    @Test
    fun `transitionValidationStatusTo changes validationStatus on a legal edge`() {
        val invoice = Invoice().apply { validationStatus = ValidationStatus.NOT_VALIDATED }

        invoice.transitionValidationStatusTo(ValidationStatus.PENDING)

        assertEquals(ValidationStatus.PENDING, invoice.validationStatus)
    }

    @Test
    fun `transitionValidationStatusTo allows the PENDING self-edge (a second publish while a job is still in flight)`() {
        val invoice = Invoice().apply { validationStatus = ValidationStatus.PENDING }

        invoice.transitionValidationStatusTo(ValidationStatus.PENDING)

        assertEquals(ValidationStatus.PENDING, invoice.validationStatus)
    }

    @Test
    fun `transitionValidationStatusTo moves PENDING to VALID INVALID or UNAVAILABLE`() {
        listOf(ValidationStatus.VALID, ValidationStatus.INVALID, ValidationStatus.UNAVAILABLE).forEach { target ->
            val invoice = Invoice().apply { validationStatus = ValidationStatus.PENDING }
            invoice.transitionValidationStatusTo(target)
            assertEquals(target, invoice.validationStatus)
        }
    }

    @Test
    fun `transitionValidationStatusTo throws and leaves validationStatus unchanged on an illegal edge`() {
        val invoice = Invoice().apply { validationStatus = ValidationStatus.NOT_VALIDATED }

        assertThrows(InvoiceValidationStatusTransitionException::class.java) {
            invoice.transitionValidationStatusTo(ValidationStatus.VALID)
        }

        assertEquals(
            ValidationStatus.NOT_VALIDATED,
            invoice.validationStatus,
            "an illegal transitionValidationStatusTo call must not mutate validationStatus",
        )
    }

    @Test
    fun `transitionValidationStatusTo throws with a precise message naming both statuses`() {
        val invoice = Invoice().apply { validationStatus = ValidationStatus.VALID }

        val ex = assertThrows(InvoiceValidationStatusTransitionException::class.java) {
            invoice.transitionValidationStatusTo(ValidationStatus.INVALID)
        }

        assertTrue(ex.message!!.contains("VALID"))
        assertTrue(ex.message!!.contains("INVALID"))
    }

    @Test
    fun `canTransitionValidationStatusTo mirrors ValidationStatus canTransitionTo`() {
        val invoice = Invoice().apply { validationStatus = ValidationStatus.PENDING }

        assertTrue(invoice.canTransitionValidationStatusTo(ValidationStatus.VALID))
        assertFalse(invoice.canTransitionValidationStatusTo(ValidationStatus.NOT_VALIDATED))
    }
}
