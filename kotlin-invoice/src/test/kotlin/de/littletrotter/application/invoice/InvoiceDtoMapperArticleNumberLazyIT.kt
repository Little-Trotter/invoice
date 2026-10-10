package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hibernate.LazyInitializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

/**
 * BUGFIX — production crash: `org.hibernate.LazyInitializationException: Unable to perform
 * requested lazy initialization [de.littletrotter.domain.masterdata.Article.articleNumber] -
 * no session and settings disallow loading outside the Session`.
 *
 * Real mechanism (verified against real PostgreSQL, see [de.littletrotter.application.masterdata
 * .ArticleDetachedReadIT]'s class KDoc for the ruled-out alternative): [InvoiceItem.articleRef]
 * is a genuine `@ManyToOne(fetch = FetchType.LAZY)`. When it is never dereferenced while its
 * loading session is open, Hibernate's bytecode enhancement represents the target as the real
 * `Article` class with only its id populated and a lazy-attribute interceptor on every other
 * field — reading any of them (`articleNumber` here) once that session has closed throws exactly
 * the reported message. [InvoiceDtoMapper.toDto] did precisely that:
 * `articleNumber = articleRef?.articleNumber`.
 *
 * That call is currently safe only because its one caller ([DocumentJobPublishTransaction
 * .publishTransactional]) happens to be `@Transactional` — an implicit, easily broken contract
 * (the sibling `receipts` read three lines above already has a defensive comment + try/catch for
 * the identical "detached entity" risk). This test calls [InvoiceDtoMapper.mapToDto] directly,
 * with no transaction of its own, on an `Invoice` loaded (and detached) in an earlier, separate
 * transaction — i.e. exactly what happens for any caller that isn't
 * `DocumentJobPublishTransaction.publishTransactional`.
 *
 * NEIGHBOURS FOUND, NOT FIXED HERE — while narrowing this test down, calling [InvoiceDtoMapper
 * .mapToDto] fully detached also threw the identical exception shape for unfetched `addresses`
 * collections on both `Recipient` and `SenderProfile` (`recipient.billingAddress`/
 * `sender.billingAddress`) — not related to [Article]. This test sidesteps that by pre-warming both
 * `addresses` collections while the loading session is still open, to keep its scope to the
 * `articleNumber` bug it targets. The risk is real but out of scope here: `mapToDto`'s only
 * current caller is `@Transactional`, so it is not reachable in production today the way
 * `articleRef.articleNumber` was.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceDtoMapperArticleNumberLazyIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var invoiceDtoMapper: InvoiceDtoMapper

    @Test
    fun `mapToDto's articleNumber must not require a live session for an untouched articleRef`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply { name = "IDM Sender"; invoicePrefix = "IDM-"; registerCourt = "" }
            val recipient = Recipient().apply { customerNumber = CustomerNumber("IDM-KD-1"); name = "IDM Recipient" }
            val article = Article().apply { name = "IDM Article"; articleNumber = ArticleNumber("ART-IDM-1") }
            em.persist(sender); em.persist(recipient); em.persist(article)

            val invoice = Invoice().apply {
                invoiceNumber = InvoiceNumber("IDM-2041-01-00001")
                selectedSeller = sender
                this.recipient = recipient
                invoiceDate = LocalDate.of(2041, 1, 1)
                addItem(
                    InvoiceItem().apply {
                        description = "IDM Position"
                        unitPriceNet = Money(BigDecimal("10.00"))
                        quantity = BigDecimal.ONE
                        articleRef = article
                        // effectiveTaxRate() (called by toDto() before articleNumber is even
                        // reached) needs either a snapshot or a taxRateRef -- irrelevant to this
                        // test's actual concern, so the cheaper snapshot avoids a TaxRate row.
                        taxRateSnapshot = TaxRatePercent(BigDecimal("19.00"))
                    },
                )
            }
            em.persist(invoice)
            em.flush()
            invoice.id!!
        }

        // Loaded (and the loading transaction closed) BEFORE mapToDto ever runs -- articleRef is
        // never dereferenced here, so it is still whatever Hibernate installed for a LAZY
        // @ManyToOne target.
        val invoice = QuarkusTransaction.requiringNew().call {
            val inv = invoiceRepository.findByIdWithItems(invoiceId)!!
            // Pre-warm Recipient/SenderProfile.addresses (plain @OneToMany, LAZY by JPA default)
            // while the session is still open: mapToDto's own recipient.billingAddress and
            // sender.billingAddress reads are further, pre-existing detached-read risks unrelated
            // to Article (see finding-fixes note in the KDoc) -- once-initialized collections stay
            // readable after detach, so touching them here keeps this test isolated to the
            // articleRef/articleNumber bug it targets.
            inv.recipient?.addresses?.size
            inv.selectedSeller?.addresses?.size
            inv
        }

        // No transaction here at all -- see class KDoc.
        val dto = try {
            invoiceDtoMapper.mapToDto(invoice)
        } catch (ex: LazyInitializationException) {
            throw AssertionError(
                "InvoiceDtoMapper.mapToDto must not require a live Hibernate session to resolve " +
                    "InvoiceItemDto.articleNumber -- it must not read a field off InvoiceItem" +
                    ".articleRef directly (a LAZY @ManyToOne), only its id, the same way " +
                    "buyerArticleDescriptionFor already resolves the article safely.",
                ex,
            )
        }
        assertEquals("ART-IDM-1", dto.items.first().articleNumber)
    }
}
