package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
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
import de.littletrotter.fixture.edit

/**
 * The PO's own "nice case" for the E2E level (PO decision 2026-09-11 evening): a DRAFT with a deliberately broken position aborts the recalculation sweep, naming that invoice; the user deletes the offending position through the real [InvoiceLifecycleService.deleteItemByPosition]; a re-run then ends SUCCESS. In the same run, an ISSUED invoice that also needs recalculation is skipped — never recalculated, byte-identical before and after — proving issued invoices can no longer ever reach the issued-invoice freeze-guard trigger through this sweep.
 *
 * Exercises [DatabaseMaintenanceService.performRecalculateTotals] directly (same convention as the sibling [RecalculationSweepMalformedRowE2EIT] and `DemoIssuedDocumentsE2EIT`): the claim/dispatch plumbing around [DatabaseMaintenanceService.queueRecalculation] and [de.littletrotter.application.invoice.MaintenanceQueueProcessor] is already covered by `PostgresDatabaseMaintenanceServiceIT`/`MaintenanceQueueProcessorTest` — calling the processor for real here would risk claiming an unrelated PENDING row some other IT class in this shared, never-cleaned database left behind, which is exactly the kind of cross-test flake this suite's own tagging discipline exists to avoid.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecalculationAbortE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var maintenanceService: DatabaseMaintenanceService

    private val createdInvoiceIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
        }
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun addPlainItem(invoiceId: Long, tag: String, taxRateId: Long) {
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
    }

    /** Marks [invoiceId] as needing recalculation without touching any column the issued-invoice freeze
     *  trigger (`guard_invoice_freeze`, `V1__baseline_schema.sql`) watches -- `needs_recalculation`
     *  is not one of them, so this alone can never trip the trigger even on an issued invoice. */
    private fun forceNeedsRecalculation(invoiceId: Long) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE invoice SET needs_recalculation = true WHERE id = :id")
                .setParameter("id", invoiceId)
                .executeUpdate()
        }
    }

    /** Breaks position [positionId] of [invoiceId] the way a corrupted row shows up in production:
     *  quantity and unit price are stored at the column maximum, so recomputing the position yields
     *  a net amount that no longer fits `invoice_item.net_amount` and the recalculation's own commit
     *  fails. The tax reference cannot be broken any more -- `tax_rate_id` is NOT NULL -- so the
     *  failure has to come from the amounts. */
    private fun breakPositionAmounts(invoiceId: Long, positionId: String) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "UPDATE invoice_item SET quantity = 9999999999.9999, unit_price_net = 999999999999.99 " +
                    "WHERE invoice_id = :id AND position_id = :pos",
            )
                .setParameter("id", invoiceId)
                .setParameter("pos", positionId)
                .executeUpdate()
        }
    }

    private fun reload(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

    @Test
    fun `a DRAFT with a broken position aborts the sweep naming it -- an ISSUED invoice in the same run is skipped and byte-identical -- deleting the position and re-running then ends SUCCESS`() {
        // ── Fixture 1: an ISSUED invoice that also needs recalculation -- created first so it sorts
        // before the DRAFT below (findAllIdsForRecalculation is ORDER BY id ASC) and is therefore
        // actually visited (and skipped) by the sweep before it aborts on the DRAFT. ────────────
        val sentParties = buildParties("N15ES")
        val sentInvoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(sentParties.senderId, sentParties.recipientId).id!!
        }
        createdInvoiceIds += sentInvoiceId
        addPlainItem(sentInvoiceId, "N15ES", sentParties.taxRateId)
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, sentInvoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(sentInvoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(sentInvoiceId, InvoiceStatus.ISSUED) }
        forceNeedsRecalculation(sentInvoiceId)
        val sentBefore = reload(sentInvoiceId)

        // ── Fixture 2: a DRAFT with two positions, the second one broken at the SQL level. ──────
        val draftParties = buildParties("N15ED")
        val draftInvoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(draftParties.senderId, draftParties.recipientId).id!!
        }
        createdInvoiceIds += draftInvoiceId
        addPlainItem(draftInvoiceId, "N15ED-1", draftParties.taxRateId)
        addPlainItem(draftInvoiceId, "N15ED-2", draftParties.taxRateId)
        breakPositionAmounts(draftInvoiceId, "2")
        forceNeedsRecalculation(draftInvoiceId)

        // ── Run 1: queued recalculation ends FAILED, naming the DRAFT; the ISSUED invoice, sorted
        // before it, was already skipped by the time the sweep reached and aborted on the DRAFT. ─
        val firstMessage = maintenanceService.performRecalculateTotals()

        assertTrue(firstMessage.startsWith("Recalculation aborted at invoice "), firstMessage)
        assertTrue(firstMessage.contains("id $draftInvoiceId"), "must name the broken DRAFT by id: $firstMessage")
        assertTrue(firstMessage.contains("skipped (read-only)"), firstMessage)
        val firstEvent = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
        assertNotNull(firstEvent)
        assertEquals(DatabaseMaintenanceStatus.FAILED, firstEvent!!.status)
        assertTrue(firstEvent.details?.contains("id $draftInvoiceId") == true, firstEvent.details.toString())

        val sentAfterFirstRun = reload(sentInvoiceId)
        assertEquals(InvoiceStatus.ISSUED, sentAfterFirstRun.status, "the issued invoice must be left exactly as it was")
        assertEquals(sentBefore.totals.netAmount, sentAfterFirstRun.totals.netAmount, "byte-identical -- skipping never calls recalculate()")
        assertEquals(sentBefore.totals.vatAmount, sentAfterFirstRun.totals.vatAmount)
        assertEquals(sentBefore.totals.grossAmount, sentAfterFirstRun.totals.grossAmount)

        // ── Remedy: delete the offending position through the real service -- this must work even
        // though a plain recalculate() currently fails on this invoice (deleteItemByPosition
        // removes the item BEFORE it recalculates totals, so the broken item is already gone). ───
        QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(draftInvoiceId, "2") }
        // `items` is a lazy collection -- read it inside its own transaction, not off a detached
        // entity returned by reload() (that would throw LazyInitializationException).
        val draftPositionsAfterDelete = QuarkusTransaction.requiringNew().call {
            em.find(Invoice::class.java, draftInvoiceId)!!.itemList.map { it.positionId }
        }
        assertEquals(listOf("1"), draftPositionsAfterDelete)
        val draftAfterDelete = reload(draftInvoiceId)
        assertFalse(draftAfterDelete.needsRecalculation, "deleteItemByPosition recalculates the remaining items itself")

        // ── Run 2: re-queued recalculation now ends SUCCESS. The ISSUED invoice is still untouched. ─
        val secondMessage = maintenanceService.performRecalculateTotals()

        assertTrue(secondMessage.startsWith("Recalculated "), secondMessage)
        val secondEvent = maintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
        assertNotNull(secondEvent)
        assertEquals(DatabaseMaintenanceStatus.SUCCESS, secondEvent!!.status, secondMessage)

        val sentAfterSecondRun = reload(sentInvoiceId)
        assertEquals(sentBefore.totals.netAmount, sentAfterSecondRun.totals.netAmount, "still byte-identical after the second run")
        assertEquals(sentBefore.totals.vatAmount, sentAfterSecondRun.totals.vatAmount)
        assertEquals(sentBefore.totals.grossAmount, sentAfterSecondRun.totals.grossAmount)
        assertEquals(InvoiceStatus.ISSUED, sentAfterSecondRun.status)
    }
}
