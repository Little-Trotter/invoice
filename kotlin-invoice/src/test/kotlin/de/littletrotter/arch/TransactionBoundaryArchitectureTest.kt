package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import de.littletrotter.architecture.rules.ProductionClasses
import de.littletrotter.architecture.rules.TransactionRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Transaction boundaries: a use case in the application layer starts the transaction, and nothing
 * slow or irrevocable happens inside it.
 *
 * The rules and their parameters live in [TransactionRules]; this gate names the packages it covers,
 * the I/O types of this service and the baselines. A baseline entry is a known violation with its
 * reason; the lists only shrink, a fixed entry has to be deleted ("stale baseline entry").
 */
class TransactionBoundaryArchitectureTest {

    private val productionClasses: JavaClasses = ProductionClasses.of(
        "de.littletrotter.domain",
        "de.littletrotter.application",
        "de.littletrotter.infrastructure",
        "de.littletrotter.ui",
        "de.littletrotter.spi",
    )

    // ── production gate ──

    @Test
    fun `transactions start in the application layer only`() {
        transactionLocation(INFRASTRUCTURE_TRANSACTION_BASELINE).check(productionClasses)
    }

    @Test
    fun `no I-O inside a transaction`() {
        noIo(IO_IN_TRANSACTION_BASELINE).check(productionClasses)
    }

    @Test
    fun `no CDI event is fired inside a transaction`() {
        noEvent(EVENT_IN_TRANSACTION_BASELINE).check(productionClasses)
    }

    @Test
    fun `independent transactions are opened in the application layer only`() {
        nestedTransaction(NESTED_TRANSACTION_BASELINE).check(productionClasses)
    }

    @Test
    fun `transactional methods can be intercepted`() {
        TransactionRules.transactionalMethodsAreInterceptable().check(productionClasses)
    }

    @Test
    fun `no blocking wait inside a transaction`() {
        TransactionRules.noBlockingInTransaction(BLOCKING_IN_TRANSACTION_BASELINE).check(productionClasses)
    }

    @Test
    fun `stale baseline entry - every listed violation still exists`() {
        assertEquals(emptySet<String>(), TransactionRules.staleEntries(INFRASTRUCTURE_TRANSACTION_BASELINE, productionClasses, ::transactionLocation), "stale baseline entry (location)")
        assertEquals(emptySet<String>(), TransactionRules.staleEntries(IO_IN_TRANSACTION_BASELINE, productionClasses, ::noIo), "stale baseline entry (I/O)")
        assertEquals(emptySet<String>(), TransactionRules.staleEntries(EVENT_IN_TRANSACTION_BASELINE, productionClasses, ::noEvent), "stale baseline entry (event)")
        assertEquals(emptySet<String>(), TransactionRules.staleEntries(NESTED_TRANSACTION_BASELINE, productionClasses, ::nestedTransaction), "stale baseline entry (nested)")
        assertEquals(emptySet<String>(), TransactionRules.staleEntries(BLOCKING_IN_TRANSACTION_BASELINE, productionClasses, TransactionRules::noBlockingInTransaction), "stale baseline entry (blocking)")
    }

    // ── specimens: every rule reports the violating look-alike and stays silent on the clean one ──

    private val specimens = ClassFileImporter().importPackages(SPECIMENS)

    private fun reportOf(rule: ArchRule): String = rule.evaluate(specimens).failureReport.details.joinToString("\n")

    @Test
    fun `negative specimens - transaction in an adapter is rejected`() {
        val report = reportOf(transactionLocation(emptySet()))
        listOf("TransactionInAdapterSpecimen", "ClassLevelTransactionInAdapterSpecimen").forEach {
            assertTrue(report.contains(".$it "), "expected rejection of $it in:\n$report")
        }
        assertFalse(report.contains("PlainAdapterSpecimen") || report.contains("ApplicationTransactionSpecimen"), report)
    }

    @Test
    fun `negative specimens - I-O inside a transaction is rejected`() {
        val report = reportOf(noIo(emptySet()))
        assertTrue(report.contains("IoViaHelperInTransactionSpecimen.direct"), report)
        assertTrue(report.contains("IoViaHelperInTransactionSpecimen.viaLambda"), report)
        assertFalse(report.contains("IoOutsideTransactionSpecimen"), report)
    }

    @Test
    fun `negative specimens - event fired inside a transaction is rejected`() {
        val report = reportOf(noEvent(emptySet()))
        assertTrue(report.contains("EventInTransactionSpecimen.change"), report)
        assertFalse(report.contains("EventOutsideTransactionSpecimen"), report)
    }

    @Test
    fun `negative specimens - blocking inside a transaction is rejected`() {
        val report = reportOf(TransactionRules.noBlockingInTransaction(emptySet()))
        listOf("sleeps", "joins", "gets").forEach {
            assertTrue(report.contains("BlockingInTransactionSpecimen.$it"), "expected $it in:\n$report")
        }
        assertFalse(report.contains("BlockingOutsideTransactionSpecimen"), report)
    }

    @Test
    fun `negative specimens - independent transactions in an adapter are rejected`() {
        val report = reportOf(nestedTransaction(emptySet()))
        assertTrue(report.contains(".RequiresNewInAdapterSpecimen "), report)
        assertTrue(report.contains(".QuarkusTransactionInAdapterSpecimen "), report)
        assertFalse(report.contains("JoiningAdapterSpecimen") || report.contains("ApplicationTransactionSpecimen"), report)
    }

    @Test
    fun `negative specimens - non-interceptable transactional code is rejected`() {
        val report = reportOf(TransactionRules.transactionalMethodsAreInterceptable())
        assertTrue(report.contains("PrivateTransactionalSpecimen.hidden"), report)
        assertTrue(report.contains(".FinalUnscopedTransactionalSpecimen "), report)
        assertTrue(report.contains("InternalTransactionalSpecimen.mangled"), report)
        assertFalse(report.contains("InterceptableTransactionalSpecimen"), report)
    }

    @Test
    fun `stale baseline entry is reported`() {
        val baseline = setOf("$SPECIMENS.TransactionInAdapterSpecimen", "$SPECIMENS.PlainAdapterSpecimen")
        val stale = TransactionRules.staleEntries(baseline, specimens, ::transactionLocation)
        assertEquals(setOf("$SPECIMENS.PlainAdapterSpecimen"), stale)
    }

    @Test
    fun `sanity - specimen packages are not swept into the production scan`() {
        val leaked = productionClasses.filter { it.packageName.contains(".specimen") }
        assertTrue(leaked.isEmpty(), "test-only specimen classes must never reach the production import: $leaked")
    }

    // ── rule wiring ──

    private fun transactionLocation(baseline: Set<String>): ArchRule =
        TransactionRules.transactionalOnlyInApplication(APPLICATION, baseline)

    private fun noIo(baseline: Set<String>): ArchRule = TransactionRules.noIoInTransaction(IO_TYPES, baseline)

    private fun noEvent(baseline: Set<String>): ArchRule = TransactionRules.noEventFiredInTransaction(baseline)

    private fun nestedTransaction(baseline: Set<String>): ArchRule =
        TransactionRules.noNestedTransactionOutsideApplication(APPLICATION, baseline)

    private companion object {
        const val SPECIMENS = "de.littletrotter.arch.specimen.transaction"

        val APPLICATION = setOf("de.littletrotter.application", "$SPECIMENS.application")

        val IO_TYPES = TransactionRules.COMMON_IO.plus(
            TransactionRules.IoTargets(
                classNames = setOf(
                    "$SPECIMENS.SpecimenIoPort",
                    "de.littletrotter.application.mail.port.outbound.MailDeliveryPort",
                    "de.littletrotter.application.mail.port.outbound.MailDispatchPort",
                    "de.littletrotter.application.mail.port.outbound.DocumentPresignPort",
                    "de.littletrotter.application.invoice.port.outbound.PdfCheckRequestPort",
                    "de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort",
                ),
                packages = listOf("de.littletrotter.infrastructure.vies", "de.littletrotter.infrastructure.http", "de.littletrotter.infrastructure.mail"),
                remotePortPackages = listOf("de.littletrotter.application"),
            ),
        )

        val INFRASTRUCTURE_TRANSACTION_BASELINE: Set<String> = setOf(
            // development seed runs outside any request and has no application caller
            "de.littletrotter.infrastructure.dev.DevDataFixtures",
            // paging job of the masterdata publisher runs outside a request and needs its own read transaction
            "de.littletrotter.infrastructure.messaging.masterdata.MasterdataEventSource",
            // maintenance queue worker runs outside a request and claims jobs in its own short transactions
            "de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.BasePanacheRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheArticleRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheB2bProfileRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheFeatureSettingRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheNumberRangeRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanachePaymentTermRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheRecipientRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheSystemMessageRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheSystemSettingRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheValidationFindingRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PanacheValidationSettingRepository",
            // finder or save is called from query services and UI without an application transaction, so the repository method opens its own
            "de.littletrotter.infrastructure.persistence.PostgresNumberCounterRepository",
        )

        val IO_IN_TRANSACTION_BASELINE: Set<String> = setOf(
            // publish without waiting for the broker ack; a lost publish is recovered from the persisted generation job
            "de.littletrotter.application.invoice.DocumentJobPublishTransaction.publishTransactional",
            // object delete under the checksum lock keeps a concurrent upload of the same content from seeing a row whose object is gone; a failed delete is logged, not rethrown
            "de.littletrotter.application.invoice.ReceiptPoolService.delete",
            // the reply port writes an outbox row in the same transaction; the relay sends after commit
            "de.littletrotter.application.invoice.QuoteReplyRecorder.created",
            // the reply port writes an outbox row in the same transaction; the relay sends after commit
            "de.littletrotter.application.invoice.QuoteReplyRecorder.rejected",
            // the reply port writes an outbox row in the same transaction; the relay sends after commit
            "de.littletrotter.application.invoice.QuoteInvoiceDraftWriter.write",
            // presigning computes a signature locally and does not call the store
            "de.littletrotter.application.mail.InvoiceMailComposeTransactions.prepare",
        )

        val EVENT_IN_TRANSACTION_BASELINE: Set<String> = setOf(
            // synchronous fire for an AFTER_SUCCESS observer: CDI delivers it only after the commit
            "de.littletrotter.application.invoice.GenerationJobCreationService.createJob",
            // synchronous fire for an AFTER_SUCCESS observer (MaintenanceJobQueued): delivered only after the commit
            "de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService.queueIssuedGenerationJobsSweep",
            "de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService.queueRecalculation",
            "de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService.recordMaintenanceEvent",
        )

        val NESTED_TRANSACTION_BASELINE: Set<String> = setOf(
            // page read must not join a caller transaction; runs from the publisher loop
            "de.littletrotter.infrastructure.messaging.masterdata.MasterdataEventSource",
            // flushing save commits on its own so a constraint violation surfaces to the caller without poisoning the outer transaction
            "de.littletrotter.infrastructure.persistence.BasePanacheRepository",
            // findings are stored independently so a failed write cannot mark the shared result transaction rollback-only
            "de.littletrotter.infrastructure.persistence.PanacheValidationFindingRepository",
            // one short transaction per invoice and per queue step, started from a worker without a request
            "de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService",
            // drain lease claims and releases in its own short transactions
            "de.littletrotter.infrastructure.persistence.PostgresMaintenanceQueueAdapter",
            // raising the counter must survive a rollback of the allocating transaction
            "de.littletrotter.infrastructure.persistence.PostgresNumberCounterRepository",
        )

        val BLOCKING_IN_TRANSACTION_BASELINE: Set<String> = setOf(
            // bounded wait for the publish ack so the bulkhead limits concurrent fast-path publishes; times out and falls back to the queued path
            "de.littletrotter.application.invoice.GenerationJobFastPublishTransaction.publishOne",
        )
    }
}
