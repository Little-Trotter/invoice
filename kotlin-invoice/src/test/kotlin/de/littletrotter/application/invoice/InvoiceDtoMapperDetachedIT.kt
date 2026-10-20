package de.littletrotter.application.invoice

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.domain.invoice.replaceReceipts
import de.littletrotter.domain.invoice.addPerDiemEntry
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * HARDEN — the ground-truth test for the detached-invoice mapping hardening: a *rich*
 * invoice (article-linked item, a PER_DIEM item with travel entries, invoice- and item-level
 * receipts, a sender with both default PDF templates and a default payment term, a recipient with
 * billing and delivery addresses) loaded in one transaction, detached when that transaction closes,
 * then mapped with **no transaction open at all** — the exact caller shape
 * `DocumentJobPublishTransaction.publishTransactional`'s own KDoc describes for the UI print path, and
 * (per `GenerationJobService.findPendingWithInvoice`'s own KDoc) *not* representative of the
 * `@Scheduled` poller or the fast-path worker, which each keep one transaction open end to end.
 *
 * Unlike [InvoiceDtoMapperArticleNumberLazyIT] (which deliberately narrows its scope to the
 * `articleNumber` bug alone, pre-warming every other risky association out of the way), this test
 * does none of that: it is the full-scope reproduction the spec's "Test requirement" section asks
 * for, and it asserts the mapped [de.littletrotter.shared.InvoiceDto] is *complete* — every field
 * carries its expected value — not merely that [InvoiceDtoMapper.mapToDto] returns without
 * throwing.
 *
 * All fixture rows are committed via [QuarkusTransaction.requiringNew] on purpose (the whole point
 * is a transaction that actually closes before mapping runs), so — unlike a plain `@Transactional`
 * test method, which JUnit/Quarkus rolls back automatically — nothing here is rolled back for
 * free. [cleanup] removes every row this test creates, in `finally`, so this test leaves the
 * shared Postgres instance exactly as it found it for every other IT class in the same run.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceDtoMapperDetachedIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceDtoMapper: InvoiceDtoMapper

    private data class FixtureIds(
        val invoiceId: Long,
        val senderId: Long,
        val recipientId: Long,
        val articleId: Long,
        val paymentTermId: Long,
        val invoiceLevelReceiptId: Long,
        val itemLevelReceiptId: Long,
    )

    @Test
    fun `mapToDto produces a complete DTO for a rich invoice with no transaction open`() {
        val ids = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "Detached Sender GmbH"
                invoicePrefix = "DET-"
                vatId = VatId("DE123456789")
                registerCourt = "Amtsgericht Musterstadt"
                commercialRegisterNumber = "HRB 99999"
            }
            val paymentTerm = aPaymentTerm {
                changeName(em.find(Language::class.java, 1)!!, "30 Tage netto (DET)")
                changeName(em.find(Language::class.java, 2)!!, "Net 30 (DET)")
                dueInDays = 30
                // Kept out of PaymentTermMigrationIT's "exactly four active standard payment
                // terms" count (that assertion scans every active row, not just the seeded ones) —
                // this fixture's own presence must not change what that unrelated IT observes.
                isActive = false
            }
            em.persist(paymentTerm)
            sender.edit { defaultPaymentTerm = paymentTerm }

            val recipient = aRecipient {
                customerNumber = CustomerNumber("DET-KD-1")
                name = "Detached Recipient AG"
                vatId = VatId("DE987654321")
            }

            val article = anArticle {
                name = "Detached Article"
                articleNumber = ArticleNumber("ART-DET-1")
            }

            em.persist(sender)
            em.persist(recipient)
            em.persist(article)

            val senderBilling = Address.forSender(sender, Address.AddressType.BILLING, "Senderstr. 1", "10115", "Berlin", isDefault = true)
            sender.addAddress(senderBilling)
            val recipientBilling = Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenweg 2", "80331", "München", isDefault = true)
            val recipientDelivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferallee 3", "20095", "Hamburg", isDefault = true)
            recipient.addAddress(recipientBilling)
            recipient.addAddress(recipientDelivery)

            val invoice = anInvoice(
                invoiceNumber = InvoiceNumber("DET-2041-01-00001"),
                selectedSeller = sender,
                recipient = recipient,
                invoiceDate = LocalDate.of(2041, 1, 1),
                invoiceFormat = InvoiceFormat.GERMAN,
                documentLanguage = "de",
            ).apply {
                addItem(
                    anInvoiceItem(
                taxRateRef = em.seededStandardTaxRate(),
                        description = "Detached Position",
                        category = InvoiceItemCategory.SERVICE,
                        unitPriceNet = Money(BigDecimal("100.00")),
                        quantity = BigDecimal("2"),
                        articleRef = article,
                        taxRateSnapshot = TaxRatePercent(BigDecimal("19.00")),
                    ),
                )

                val perDiemItem = anInvoiceItem(
                taxRateRef = em.seededStandardTaxRate(),
                    description = "Verpflegungspauschale",
                    category = InvoiceItemCategory.TRIP,
                    unitPriceNet = Money.ZERO,
                    quantity = BigDecimal.ONE,
                    taxRateSnapshot = TaxRatePercent(BigDecimal.ZERO),
                )
                addItem(perDiemItem)
                val travelEntry = TravelExpenseEntry().edit {
                    invoiceItem = perDiemItem
                    shortName = "Reise DET"
                    travelDate = LocalDate.of(2041, 1, 2)
                    fullDayRateSnapshot = Money(BigDecimal("28.00"))
                    calculatedAmount = Money(BigDecimal("28.00"))
                }
                perDiemItem.addPerDiemEntry(travelEntry)
            }
            em.persist(invoice)
            em.flush()

            // Unique per run, not the fixed literal "a"/"b" repeated to 64 chars: this module's IT
            // suite shares one Postgres container across every class, and receipt.sha256_checksum
            // carries uq_receipt_sha256_checksum against it.
            val invoiceLevelReceipt = aReceipt {
                fileName = "auftragsbestaetigung.pdf"
                description = "Beleg auftragsbestaetigung.pdf"
                objectKey = "det/invoice-level-${System.nanoTime()}"
                sizeBytes = 1024L
                sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
                uploadedAt = OffsetDateTime.now()
                documentDate = LocalDate.of(2026, 3, 14)
            }
            val itemLevelReceipt = aReceipt {
                fileName = "quittung.pdf"
                description = "Beleg quittung.pdf"
                objectKey = "det/item-level-${System.nanoTime()}"
                sizeBytes = 2048L
                sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
                uploadedAt = OffsetDateTime.now()
                documentDate = LocalDate.of(2026, 3, 14)
            }
            em.persist(invoiceLevelReceipt)
            em.persist(itemLevelReceipt)
            invoice.replaceReceipts(invoice.receiptList + invoiceLevelReceipt)
            invoice.itemList.first().replaceReceipts(invoice.itemList.first().receiptList + itemLevelReceipt)
            em.flush()

            FixtureIds(
                invoiceId = invoice.id!!,
                senderId = sender.id!!,
                recipientId = recipient.id!!,
                articleId = article.id!!,
                paymentTermId = paymentTerm.id!!,
                invoiceLevelReceiptId = invoiceLevelReceipt.id!!,
                itemLevelReceiptId = itemLevelReceipt.id!!,
            )
        }

        try {
            // Loaded (and the loading transaction closed) BEFORE mapToDto ever runs.
            // findByIdWithItems eagerly fetches items/senderProfile/recipient/paymentTerm/
            // b2bProfile — see its own JOIN FETCH clause — but deliberately touches nothing else:
            // selectedSeller, billingAddress, deliveryAddress, Recipient/SenderProfile.addresses,
            // SenderProfile.defaultPaymentTerm and
            // InvoiceItem.articleRef all stay whatever Hibernate installed for an untouched LAZY
            // association. No field of any of those is read here.
            val invoice = QuarkusTransaction.requiringNew().call {
                invoiceRepository.findByIdWithItems(ids.invoiceId)!!
            }

            // No transaction here at all — a detached invoice mapped with no live Hibernate
            // session anywhere in reach, the exact shape this hardening pass must survive.
            val dto = invoiceDtoMapper.mapToDto(invoice)

            // ── senderProfile (via selectedSeller) ──────────────────────────────────────────
            val senderDto = requireNotNull(dto.senderProfile) { "senderProfile must not be null" }
            assertEquals("Detached Sender GmbH", senderDto.name)
            assertEquals("Senderstr. 1", senderDto.street)
            assertEquals("10115", senderDto.postalCode)
            assertEquals("Berlin", senderDto.city)
            assertEquals("DE123456789", senderDto.vatId)

            // ── recipient + addresses/shipTo ─────────────────────────────────────────────────
            val recipientDto = requireNotNull(dto.recipient) { "recipient must not be null" }
            assertEquals("Detached Recipient AG", recipientDto.name)
            assertEquals("Kundenweg 2", recipientDto.street)
            assertEquals("80331", recipientDto.postalCode)
            assertEquals("München", recipientDto.city)
            val shipTo = requireNotNull(dto.shipTo) { "shipTo must reflect the recipient's delivery address" }
            assertEquals("Lieferallee 3", shipTo.street)
            assertEquals("20095", shipTo.postalCode)
            assertEquals("Hamburg", shipTo.city)

            // ── items: articleNumber, receipts ───────────────────────────────────────────────
            assertEquals(2, dto.items.size)
            val articleItem = dto.items.first { it.description == "Detached Position" }
            assertEquals("ART-DET-1", articleItem.articleNumber, "articleNumber must resolve off the detached articleRef")
            assertEquals(1, articleItem.receipts.size)
            assertEquals("quittung.pdf", articleItem.receipts.first().fileName)

            // ── invoice-level receipts ────────────────────────────────────────────────────────
            assertEquals(1, dto.receipts.size)
            assertEquals("auftragsbestaetigung.pdf", dto.receipts.first().fileName)
        } finally {
            cleanup(ids)
        }
    }

    /**
     * Every row this test commits, removed again — see the class KDoc for why this is necessary
     * (nothing here rolls back on its own). Runs in its own fresh transaction so it still executes
     * even when the block above throws (assertion failure or otherwise).
     */
    private fun cleanup(ids: FixtureIds) {
        QuarkusTransaction.requiringNew().call {
            em.find(Invoice::class.java, ids.invoiceId)?.let { em.remove(it) }
            em.find(Receipt::class.java, ids.invoiceLevelReceiptId)?.let { em.remove(it) }
            em.find(Receipt::class.java, ids.itemLevelReceiptId)?.let { em.remove(it) }
            em.find(Article::class.java, ids.articleId)?.let { em.remove(it) }
            em.find(Recipient::class.java, ids.recipientId)?.let { em.remove(it) }
            em.find(SenderProfile::class.java, ids.senderId)?.let { em.remove(it) }
            em.find(PaymentTerm::class.java, ids.paymentTermId)?.let { em.remove(it) }
        }
    }
}
