package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * finding-fixes (2026-09-01) — [DatabaseMaintenanceService.performRecalculateTotals] used to load
 * whole [Invoice] entities via [de.littletrotter.domain.repository.InvoiceRepository
 * .findAllForRecalculation] (`SELECT i FROM Invoice i ... WHERE i.needsRecalculation = true`). A
 * single row anywhere in the table whose `invoice_number` fails
 * [de.littletrotter.domain.invoice.InvoiceNumberConverter]'s format check made that bulk SELECT
 * itself throw — before the per-invoice `try`/`catch` in
 * [de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService
 * .performRecalculateTotals] ever got a chance to see it — killing the whole sweep for every other
 * invoice too. Fixed by reading **ids** first
 * ([de.littletrotter.domain.repository.InvoiceRepository.findAllIdsForRecalculation], a scalar
 * projection that runs no converter) and loading + recalculating each invoice inside its own
 * guard, so exactly that row becomes one counted failure instead of a dead job.
 *
 * `kotlin-invoice`'s own `PostgresDatabaseMaintenanceServiceIT` already covers this defect against
 * its own module-local Postgres, but relies on a leftover malformed row from another IT class
 * happening to still be there — this suite instead builds a deterministic, self-contained fixture
 * (its own malformed row, its own clean invoices) and asserts the specific claims that matter: the
 * sweep completes (does not crash with an uncaught [jakarta.persistence.PersistenceException]) and
 * reports the malformed row by id.
 *
 * N15 (PO decision 2026-09-11 evening) — the sweep no longer counts a failure and continues; it
 * aborts at the FIRST invoice whose recalculation fails. Our clean invoices are created with lower
 * ids than the malformed row, so they are still recalculated (they sort first,
 * `InvoiceRepository.findAllIdsForRecalculation` is `ORDER BY i.id`) before the sweep reaches and
 * aborts on the malformed one — see `RecalculationAbortE2EIT` for the dedicated abort-contract
 * coverage (a DRAFT with a broken position, the FAILED event naming it, the remedy-by-delete path).
 *
 * Shared-Postgres discipline (this module never resets the database between test classes): every
 * assertion below is phrased as "at least" over the message's parsed counters (other suites in a
 * full run may also have rows needing recalculation) but "exactly" over our own tagged invoices,
 * which are read back individually and always removed again in [cleanup].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecalculationSweepMalformedRowE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var maintenanceService: DatabaseMaintenanceService

    private val createdInvoiceIds = mutableListOf<Long>()
    private var malformedInvoiceNumber: String? = null

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            malformedInvoiceNumber?.let { number ->
                em.createNativeQuery("DELETE FROM invoice WHERE invoice_number = :num")
                    .setParameter("num", number)
                    .executeUpdate()
            }
        }
    }

    /** One sender/recipient/tax-rate/item invoice, correctly recalculated by [InvoiceLifecycleService
     *  .addItem] itself — then deliberately corrupted (wrong totals, [de.littletrotter.domain.invoice
     *  .Invoice.needsRecalculation] forced back to `true`) via direct field-setting, the same
     *  shortcut [InvoiceDetailFacadeStornoE2EIT] uses for header state this suite has no
     *  dedicated command for. This simulates "an invoice some master-data change marked dirty"
     *  without needing to reproduce that trigger — [performRecalculateTotals] must restore the
     *  correct totals regardless of why the flag was set. */
    private fun dirtyInvoice(tag: String): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            val taxRate = TaxRate().apply {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        // Corrupt: addItem already recalculated correctly (net 100.00/vat 19.00/gross 119.00,
        // needsRecalculation = false) - force it back to a dirty, wrong state so the sweep below
        // has something real to fix, not a no-op it would skip anyway (see
        // DatabaseMaintenanceService.performRecalculateTotals's own KDoc on why an already-current
        // invoice is a no-op).
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.totals.netAmount = Money.ZERO
            invoice.totals.vatAmount = Money.ZERO
            invoice.totals.grossAmount = Money.ZERO
            invoice.needsRecalculation = true
        }
        createdInvoiceIds += invoiceId
        return invoiceId
    }

    private fun insertMalformedInvoiceNeedingRecalculation(): Long {
        // A raw SQL insert bypassing InvoiceNumberConverter entirely - the only way to get an
        // un-parseable invoice_number into the table at all, since every entity-level write goes
        // through the converter and would reject this string itself. "DEFAULT" where every valid
        // pattern requires a 4-digit year makes this fail all three InvoiceNumber patterns
        // regardless of the unique numeric suffix appended for cross-run uniqueness.
        val number = "L-DEFAULT-RC3-${System.nanoTime()}"
        malformedInvoiceNumber = number
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("INSERT INTO invoice (invoice_number, invoice_date, needs_recalculation) VALUES (:num, '2026-06-15', TRUE)")
                .setParameter("num", number)
                .executeUpdate()
        }
        return QuarkusTransaction.requiringNew().call {
            (em.createNativeQuery("SELECT id FROM invoice WHERE invoice_number = :num")
                .setParameter("num", number)
                .singleResult as Number).toLong()
        }
    }

    private fun reload(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

    /** Parses `"Recalculated N invoices successfully, M skipped (read-only), K failed..."`. */
    private fun parseCounts(message: String): Triple<Int, Int, Int> {
        val match = Regex("""Recalculated (\d+) invoices successfully, (\d+) skipped \(read-only\), (\d+) failed""")
            .find(message)
        assertTrue(match != null, "unexpected message shape: $message")
        val (success, skipped, failed) = match!!.destructured
        return Triple(success.toInt(), skipped.toInt(), failed.toInt())
    }

    // ── Happy path — a sweep over several dirty invoices reports the right success count ────────

    @Test
    fun `a sweep over several invoices needing recalculation reports them all as successes and fixes their totals`() {
        val ids = listOf("RC1A", "RC1B", "RC1C").map { dirtyInvoice(it) }

        val message = maintenanceService.performRecalculateTotals()

        val (success, _, _) = parseCounts(message)
        assertTrue(success >= ids.size, "our ${ids.size} dirty invoices must all be counted as successes: $message")

        ids.forEach { id ->
            val invoice = reload(id)
            assertFalse(invoice.needsRecalculation, "invoice $id must be marked current again after the sweep")
            assertEquals(Money.of("100.00"), invoice.totals.netAmount, "invoice $id's totals must be recomputed from its real item, not left at the corrupted 0.00")
            assertEquals(Money.of("19.00"), invoice.totals.vatAmount)
            assertEquals(Money.of("119.00"), invoice.totals.grossAmount)
        }
    }

    // ── Border — a malformed row must not crash the sweep; it aborts the sweep, naming it ───────

    @Test
    fun `a malformed invoice_number row aborts the sweep instead of crashing it, naming its id`() {
        val cleanIds = listOf("RC2A", "RC2B").map { dirtyInvoice(it) }
        val malformedId = insertMalformedInvoiceNeedingRecalculation()

        // Before the ids-first fix this call itself threw a PersistenceException out of the bulk
        // SELECT; reaching the line below at all is already most of the original regression test.
        // Under N15's abort-on-first-failure contract (PO decision 2026-09-11 evening) the sweep
        // must also stop right here and report the malformed row by id, rather than the pre-N15
        // "count it and keep going" behavior.
        val message = maintenanceService.performRecalculateTotals()

        assertTrue(message.startsWith("Recalculation aborted at invoice "), message)
        assertTrue(message.contains("id $malformedId"), "the message must name the malformed row by id: $message")
        val event = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
        assertNotNull(event)
        assertEquals(DatabaseMaintenanceStatus.FAILED, event!!.status)

        // Our clean invoices carry lower ids than the malformed row (created first) -- the sweep
        // (ORDER BY id ASC) must still have recalculated them before it reached and aborted on the
        // malformed one.
        cleanIds.forEach { id ->
            val invoice = reload(id)
            assertFalse(invoice.needsRecalculation, "invoice $id must have been recalculated before the sweep reached the malformed row")
            assertEquals(Money.of("100.00"), invoice.totals.netAmount)
            assertEquals(Money.of("119.00"), invoice.totals.grossAmount)
        }
    }
}
