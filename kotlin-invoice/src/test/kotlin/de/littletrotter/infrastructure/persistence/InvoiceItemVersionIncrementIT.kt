package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * ST-1 invariant 6 proof (0.9.10) — [InvoiceItemMutationService.updateItem]/[addItem]/
 * [deleteItemByPosition] must bump the owning [Invoice.version] on EVERY successful item write,
 * even one that touches no column of `invoice` itself. An [de.littletrotter.domain.invoice
 * .InvoiceItem] is a separately `@Version`-ed entity on the FK-owning side of a `mappedBy`
 * collection — see [PanacheInvoiceRepository.forceVersionIncrement]'s own KDoc for why
 * Hibernate's plain dirty check alone never bumps `invoice.version` for an item-only edit that
 * leaves [Invoice.totals]/`updatedBy` unchanged. Without that forced bump, two item edits
 * carrying the same `expectedVersion` both pass [de.littletrotter.domain.invoice
 * .requireExpectedVersion] and silently overwrite one another — the lost update
 * `ConcurrentCrudStressE2EIT` observed on invoice 15 ("write marker=U264 returned version 1,
 * which is not greater than the version it read (1)").
 *
 * Fixture recipe mirrors [de.littletrotter.application.invoice.InvoiceAuditActionCaptureIT]'s own
 * `createValidSenderProfile`/`createValidRecipient`/`createMinimalItem`. Every write below runs in
 * its own committed [QuarkusTransaction.requiringNew] transaction (same idiom that IT uses), and
 * [Invoice.version] is always read back through a **fresh** transaction/`em.find` — the version a
 * concurrent second writer would actually see — never the in-memory instance a write handed back.
 * This holds regardless of whether a caller reads `updated.version` before or after its own
 * transaction commits (H1): the assertions here never depend on that timing.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceItemVersionIncrementIT {

    companion object {
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    private fun createValidSenderProfile(name: String): SenderProfile = SenderProfile().apply {
        this.name = name
        invoicePrefix = "IVI${uniqueCounter.incrementAndGet()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        registerCourt = "Amtsgericht Test"
        phone = "+49 30 1234567"
    }.also { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }

    private fun createValidRecipient(name: String): Recipient = Recipient().apply {
        this.name = name
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }.also { customerManagementService.save(it) }

    private fun createTaxRate(tag: String): Long {
        val taxRate = TaxRate().apply {
            name = "19%"
            rate = TaxRatePercent(BigDecimal("19.00"))
            code = "IVI_TEST_19_$tag"
            category = "S"
            isSelectable = false
        }
        em.persist(taxRate)
        em.flush()
        return taxRate.id!!
    }

    /** DRAFT invoice with one SERVICE item, ready for [itemMutationService] calls.
     *  Returns (invoiceId, itemId of the initial position, taxRateId to reuse for further items). */
    private fun createDraftInvoiceWithItem(tag: String): Triple<Long, Long, Long> =
        QuarkusTransaction.requiringNew().call<Triple<Long, Long, Long>> {
            val sender = createValidSenderProfile("ACME $tag")
            val recipient = createValidRecipient("Kunde $tag")
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            val taxRateId = createTaxRate(tag)
            val added = itemMutationService.addItem(
                invoice.id!!,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Initial position",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.FLAT_RATE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = taxRateId,
                ),
            )
            Triple(added.id!!, added.itemList.single().id!!, taxRateId)
        }

    /** The invoice's [Invoice.version] as a fresh transaction would see it — never the
     *  in-memory instance a prior write handed back. */
    private fun versionOf(invoiceId: Long): Long =
        QuarkusTransaction.requiringNew().call<Long> { em.find(Invoice::class.java, invoiceId).version!! }

    // ── (a) a description-only updateItem still bumps invoice.version ────────────

    @Test
    fun `updateItem changing only the description increments invoice version`() {
        val (invoiceId, itemId, taxRateId) = createDraftInvoiceWithItem("A1")
        val versionBefore = versionOf(invoiceId)

        QuarkusTransaction.requiringNew().run {
            itemMutationService.updateItem(
                invoiceId,
                InvoiceItemCommand(
                    itemId = itemId,
                    category = InvoiceItemCategory.SERVICE,
                    description = "Changed description only",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.FLAT_RATE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        val versionAfter = versionOf(invoiceId)
        assertTrue(
            versionAfter > versionBefore,
            "invoice $invoiceId: version must increment on a description-only item edit (before=$versionBefore, after=$versionAfter)",
        )
    }

    // ── (b) two updateItem calls, same expectedVersion, sequential separate transactions ──

    @Test
    fun `second updateItem carrying a stale expectedVersion throws OptimisticLockException`() {
        val (invoiceId, itemId, taxRateId) = createDraftInvoiceWithItem("A2")
        val staleExpectedVersion = versionOf(invoiceId)

        fun command(desc: String) = InvoiceItemCommand(
            itemId = itemId,
            category = InvoiceItemCategory.SERVICE,
            description = desc,
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.FLAT_RATE,
            unitPriceNet = Money.of("50.00"),
            taxRateId = taxRateId,
            expectedVersion = staleExpectedVersion,
        )

        QuarkusTransaction.requiringNew().run { itemMutationService.updateItem(invoiceId, command("First writer")) }

        assertThrows(OptimisticLockException::class.java) {
            QuarkusTransaction.requiringNew().run { itemMutationService.updateItem(invoiceId, command("Second writer, stale expectedVersion")) }
        }
    }

    // ── (c) same proof for deleteItemByPosition ───────────────────────────────────

    @Test
    fun `deleteItemByPosition bumps invoice version, and a second delete with the stale expectedVersion throws`() {
        val (invoiceId, _, taxRateId) = createDraftInvoiceWithItem("A3")
        // A second position, so the invoice is never left item-less mid-test (an empty invoice
        // is a separate concern, not under test here).
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Second position",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.FLAT_RATE,
                    unitPriceNet = Money.of("30.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        val staleExpectedVersion = versionOf(invoiceId)

        QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "1", staleExpectedVersion) }

        val versionAfter = versionOf(invoiceId)
        assertTrue(
            versionAfter > staleExpectedVersion,
            "invoice $invoiceId: version must increment on delete (before=$staleExpectedVersion, after=$versionAfter)",
        )

        assertThrows(OptimisticLockException::class.java) {
            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, "2", staleExpectedVersion) }
        }
    }

    // ── (c) same proof for addItem ────────────────────────────────────────────────

    @Test
    fun `addItem bumps invoice version, and a second addItem with the stale expectedVersion throws`() {
        val (invoiceId, _, taxRateId) = createDraftInvoiceWithItem("A4")
        val staleExpectedVersion = versionOf(invoiceId)

        fun command(desc: String) = InvoiceItemCommand(
            category = InvoiceItemCategory.SERVICE,
            description = desc,
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.FLAT_RATE,
            unitPriceNet = Money.of("20.00"),
            taxRateId = taxRateId,
            expectedVersion = staleExpectedVersion,
        )

        QuarkusTransaction.requiringNew().run { itemMutationService.addItem(invoiceId, command("First writer's new position")) }

        val versionAfter = versionOf(invoiceId)
        assertTrue(
            versionAfter > staleExpectedVersion,
            "invoice $invoiceId: version must increment on addItem (before=$staleExpectedVersion, after=$versionAfter)",
        )

        assertThrows(OptimisticLockException::class.java) {
            QuarkusTransaction.requiringNew().run { itemMutationService.addItem(invoiceId, command("Second writer, stale expectedVersion")) }
        }
    }
}
