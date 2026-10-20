package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ST-1 delete-vs-use race proof (0.9.10) — reproduces, with a real Postgres, the exact finding:
 * `invoice.create: ... foreign key constraint "invoice_recipient_id_fkey" ... Detail: Key
 * (recipient_id)=(8) is not present in table "recipient"` — a recipient/article deleted
 * concurrently (its own "still referenced"/"in use" pre-check passed because the racing
 * invoice/item write had not committed yet) while that write was still in flight.
 *
 * Each test commits the delete FIRST (mirrors the finding: the deleted row is already gone by
 * the time the write attempts to persist), then builds the write with `em.getReference` — the
 * same "id known, never re-checked against the DB" shape [InvoiceLifecycleService.create]'s own
 * `recipientRepo.findById` result (or [de.littletrotter.application.invoice
 * .InvoiceItemMutationService.addItem]'s `articleRepo.findById` result) carries into the
 * eventual [PanacheInvoiceRepository.save] flush under genuine concurrency — without needing two
 * live threads to reproduce it deterministically. Asserts the exact readable
 * [InvalidParameterException] [PanacheInvoiceRepository.save] now maps the FK violation to,
 * never the raw `org.hibernate.exception.ConstraintViolationException`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceForeignKeyRaceIT {

    companion object {
        private val uniqueCounter = AtomicInteger(0)
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var articleManagementService: ArticleManagementService

    private fun createValidSenderProfile(name: String): SenderProfile = aSenderProfile {
        this.name = name
        invoicePrefix = "FKR${uniqueCounter.incrementAndGet()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }.also { sellerProfileService.save(it, defaults = SellerDefaultSelections()) }

    private fun createValidRecipient(name: String): Recipient = aRecipient {
        this.name = name
        email = Email("$name@example.invalid".replace(" ", "").lowercase())
        customerType = CustomerType.CONSUMER
        vatId = VatId("DE987654321")
    }.also { customerManagementService.save(it) }

    private fun createValidArticle(name: String): Article = anArticle {
        this.name = name
    }.also { articleManagementService.save(it) }

    // ── (a) invoice insert naming a recipient deleted meanwhile ──────────────────

    @Test
    fun `save maps a recipient deleted meanwhile to InvalidParameterException, not a raw constraint violation`() {
        val senderId = QuarkusTransaction.requiringNew()
            .call<Long> { createValidSenderProfile("Sender FK-A").id!! }
        val recipientId = QuarkusTransaction.requiringNew()
            .call<Long> { createValidRecipient("Kunde FK-A").id!! }

        // Committed BEFORE the invoice write below — the recipient row is already gone by the
        // time [PanacheInvoiceRepository.save] flushes, exactly like the finding's timing.
        QuarkusTransaction.requiringNew().run { customerManagementService.delete(recipientId) }

        val ex = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val invoice = Invoice().edit {
                    senderProfile = em.find(SenderProfile::class.java, senderId)
                    // em.getReference never round-trips to the DB — carries the id the same way
                    // a findById result loaded before the concurrent delete would, under real
                    // concurrency.
                    recipient = em.getReference(Recipient::class.java, recipientId)
                }
                invoiceRepository.save(invoice)
            }
        }

        assertEquals("Der Kunde wurde inzwischen gelöscht.", ex.message)
    }

    // ── (b) invoice-item insert naming an article deleted meanwhile ──────────────

    @Test
    fun `save maps an article deleted meanwhile to InvalidParameterException, not a raw constraint violation`() {
        val invoiceId = QuarkusTransaction.requiringNew().call<Long> {
            val sender = createValidSenderProfile("Sender FK-B")
            val recipient = createValidRecipient("Kunde FK-B")
            invoiceLifecycleService.create(sender.id!!, recipient.id!!).id!!
        }
        val articleId = QuarkusTransaction.requiringNew()
            .call<Long> { createValidArticle("Artikel FK-B").id!! }

        // Committed BEFORE the item write below — same timing as (a).
        QuarkusTransaction.requiringNew().run { articleManagementService.delete(articleId) }

        val ex = assertThrows(InvalidParameterException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val invoice = invoiceRepository.findByIdWithItems(invoiceId)!!
                // invoice_item.tax_rate_id is NOT NULL and checked before the article FK, so the
                // item needs a real (seeded) rate for the article FK to be the violation hit.
                val seededRate = em.createQuery("SELECT t FROM TaxRate t", TaxRate::class.java)
                    .setMaxResults(1).singleResult
                invoice.addItem(
                    InvoiceItem().edit {
                        taxRateRef = seededRate
                        articleRef = em.getReference(Article::class.java, articleId)
                    },
                )
                invoiceRepository.save(invoice)
            }
        }

        assertEquals("Die Leistungsposition wurde inzwischen gelöscht.", ex.message)
    }
}
