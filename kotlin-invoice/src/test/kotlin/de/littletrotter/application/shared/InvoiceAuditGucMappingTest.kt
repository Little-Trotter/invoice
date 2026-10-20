package de.littletrotter.application.shared

import de.littletrotter.application.invoice.DocumentGeneratedReactionHandler
import de.littletrotter.application.invoice.DocumentJobPublishTransaction
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceReverseChargeService
import de.littletrotter.application.invoice.InvoiceSendService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.TripService
import de.littletrotter.domain.compliance.InvoiceAuditAction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reflection guard: every `@SetsAuditGuc`-annotated method on every entry point that
 * mutates an audited table (`invoice`/`invoice_item`/`invoice_document`/`trip_expense`/the two
 * receipt link tables) must declare a real [InvoiceAuditAction], never leave the annotation at its
 * [InvoiceAuditAction.UNSPECIFIED] default — that value is the trigger's own signal for "no GUC
 * set" (a write outside the application), not a valid application-declared action.
 * [AuditGucInterceptor] itself is deliberately excluded: its own class-level `@SetsAuditGuc` is the
 * CDI interceptor-binding declaration every `@Interceptor` implementing a binding annotation must
 * carry, not a business method.
 */
class InvoiceAuditGucMappingTest {

    /** Every class this package's business methods live on, for the "list every
     *  `@SetsAuditGuc` site" step. */
    private val classesUnderTest = listOf(
        InvoiceLifecycleService::class.java,
        // Moved off InvoiceLifecycleService: item mutation, header update and the
        // reverse-charge decision workflow each got their own @SetsAuditGuc-annotated entry
        // methods on their own class.
        InvoiceItemMutationService::class.java,
        InvoiceHeaderUpdateService::class.java,
        InvoiceReverseChargeService::class.java,
        InvoiceSendService::class.java,
        ReceiptLinkService::class.java,
        TripService::class.java,
        // GenerationJobService (a pure delegation facade) no longer exists; createJob's own
        // @SetsAuditGuc now lives directly on the collaborator that carries the logic.
        GenerationJobCreationService::class.java,
        DocumentJobPublishTransaction::class.java,
        DocumentGeneratedReactionHandler::class.java,
        InvoiceCancellationService::class.java,
    )

    private fun annotatedMethods(clazz: Class<*>) =
        clazz.declaredMethods.filter { it.isAnnotationPresent(SetsAuditGuc::class.java) }

    @Test
    fun `II4-A-B9 - every SetsAuditGuc method declares an action other than UNSPECIFIED`() {
        val offenders = classesUnderTest.flatMap { clazz ->
            annotatedMethods(clazz)
                .filter { it.getAnnotation(SetsAuditGuc::class.java).action == InvoiceAuditAction.UNSPECIFIED }
                .map { "${clazz.simpleName}.${it.name}" }
        }
        assertTrue(offenders.isEmpty(), "Methods left at the UNSPECIFIED default: $offenders")
    }

    @Test
    fun `II4-A-B9 - at least one SetsAuditGuc method exists on every class under test`() {
        // A regression that accidentally drops every annotation off a class (e.g. a bad merge)
        // would otherwise pass the "no offenders" assertion above vacuously.
        classesUnderTest.forEach { clazz ->
            assertTrue(annotatedMethods(clazz).isNotEmpty(), "Expected at least one @SetsAuditGuc method on ${clazz.simpleName}")
        }
    }

    @Test
    fun `II4-A-B9 - no class under test still carries the annotation at class level`() {
        // A2 — moved from class-level onto individual methods, so each declares its own action;
        // a class-level annotation left behind would make every method on that bean share one
        // action again, silently reintroducing the exact ambiguity this package removed.
        val stillClassAnnotated = classesUnderTest.filter { it.isAnnotationPresent(SetsAuditGuc::class.java) }
        assertFalse(stillClassAnnotated.isNotEmpty(), "Classes still carrying a class-level @SetsAuditGuc: ${stillClassAnnotated.map { it.simpleName }}")
    }
}
