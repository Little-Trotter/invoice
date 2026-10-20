package de.littletrotter.ui.view

import de.littletrotter.application.error.ApplicationConflictException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import org.hibernate.exception.ConstraintViolationException
import org.jboss.logging.Logger
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.sql.SQLException

class RejectionLoggingTest {

    private val log = mock<Logger>()

    @Test
    fun `domain rejection logs INFO without trace`() {
        logRejection(log, "save", InvalidParameterException("x"))
        verify(log).info(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `application conflict logs WARN`() {
        logRejection(log, "save", ApplicationConflictException("stale"))
        verify(log).warn(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `hibernate constraint violation logs WARN`() {
        logRejection(log, "save", ConstraintViolationException("dup", SQLException("dup"), "uq_x"))
        verify(log).warn(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `undefined failure stays ERROR with trace`() {
        logRejection(log, "save", IllegalStateException("boom"))
        verify(log).error(any<Any>(), any<Throwable>())
        verify(log, never()).warn(any<Any>())
    }

    @Test
    fun `InvoiceDomainException from invoice actions logs WARN`() {
        logDomainRejection(log, "print", InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_INVALID_FORMAT, message = "bad"))
        verify(log).warn(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `unexpected exception in invoice actions stays ERROR`() {
        logDomainRejection(log, "print", RuntimeException("boom"))
        verify(log).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `wrapped optimistic lock failure logs INFO without trace`() {
        val wrapped = RuntimeException("rollback", RuntimeException("commit", jakarta.persistence.OptimisticLockException("stale")))
        logRejection(log, "save", wrapped)
        verify(log).info(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `immutable invoice logs INFO without trace`() {
        logRejection(log, "add item", de.littletrotter.domain.exception.InvoiceImmutableException("R-1"))
        verify(log).info(any<Any>())
        verify(log, never()).error(any<Any>(), any<Throwable>())
    }

    @Test
    fun `concurrency conflict classifier covers lock, wrapped lock, immutable and transition`() {
        kotlin.test.assertTrue(isConcurrencyConflict(jakarta.persistence.OptimisticLockException("x")))
        kotlin.test.assertTrue(isConcurrencyConflict(RuntimeException("w", jakarta.persistence.OptimisticLockException("x"))))
        kotlin.test.assertTrue(isConcurrencyConflict(de.littletrotter.domain.exception.InvoiceImmutableException("R-1")))
        kotlin.test.assertTrue(isConcurrencyConflict(de.littletrotter.domain.exception.InvoiceStatusTransitionException("ISSUED", "DRAFT")))
        kotlin.test.assertFalse(isConcurrencyConflict(IllegalStateException("boom")))
        kotlin.test.assertFalse(isConcurrencyConflict(InvalidParameterException("x")))
    }
}
