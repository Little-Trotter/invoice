package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.RecipientB2bFieldValueRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.masterdata.ArticleCustomerReferenceWire
import de.littletrotter.shared.masterdata.ArticleDeleted
import de.littletrotter.shared.masterdata.ArticleUpserted
import de.littletrotter.shared.masterdata.B2bProfileDeleted
import de.littletrotter.shared.masterdata.B2bProfileUpserted
import de.littletrotter.shared.masterdata.CustomerDeleted
import de.littletrotter.shared.masterdata.CustomerUpserted
import de.littletrotter.shared.masterdata.MasterdataSubjects
import de.littletrotter.shared.masterdata.MasterdataWireCodec
import de.littletrotter.shared.masterdata.NumberRangeDeleted
import de.littletrotter.shared.masterdata.NumberRangeUpserted
import de.littletrotter.shared.masterdata.PaymentTermUpserted
import de.littletrotter.shared.masterdata.SellerDeleted
import de.littletrotter.shared.masterdata.SellerUpserted
import de.littletrotter.shared.masterdata.TaxRateDeleted
import de.littletrotter.shared.masterdata.TaxRateUpserted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Every master data write path appends exactly one outbox event per aggregate and commit, with the
 * state as committed. Writes go through the repositories (the persistence boundary), which is the
 * single place every caller, UI or import, passes through.
 */
@QuarkusTest
@TestProfile(QuoteEnabledProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class MasterdataOutboxEventsIT {

    @Inject lateinit var em: EntityManager
    @Inject lateinit var recipients: RecipientRepository
    @Inject lateinit var addresses: AddressRepository
    @Inject lateinit var customerValues: RecipientB2bFieldValueRepository
    @Inject lateinit var senders: SenderProfileRepository
    @Inject lateinit var articles: ArticleRepository
    @Inject lateinit var taxRates: TaxRateRepository
    @Inject lateinit var numberRanges: NumberRangeRepository
    @Inject lateinit var paymentTerms: PaymentTermRepository
    @Inject lateinit var profiles: B2bProfileRepository
    @Inject lateinit var profileFields: B2bProfileFieldRepository

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private fun events(type: String, id: Long?): List<Pair<String, String>> = tx {
        em.createNativeQuery("SELECT subject, payload FROM outbox WHERE aggregate_type = :t AND aggregate_id = :i ORDER BY id")
            .setParameter("t", type).setParameter("i", id.toString()).resultList
            .map { row -> (row as Array<*>).let { it[0] as String to it[1] as String } }
    }

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun newRecipient() = Recipient().apply {
        customerNumber = CustomerNumber("KD-${unique()}")
        name = "Acme ${unique()}"
    }

    @Test
    fun `saving a customer appends one customer event and an address write another`() {
        val recipient = tx { recipients.save(newRecipient()) }
        val id = recipient.id!!

        val created = events("customer", id).single()
        assertEquals(MasterdataSubjects.CUSTOMER_UPSERTED, created.first)
        assertEquals(recipient.name, MasterdataWireCodec.decode<CustomerUpserted>(created.second).name)

        tx { addresses.save(Address.forRecipient(recipients.findById(id)!!, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin")) }

        val all = events("customer", id)
        assertEquals(2, all.size)
        assertEquals("Berlin", MasterdataWireCodec.decode<CustomerUpserted>(all.last().second).addresses.single().city)
    }

    @Test
    fun `several writes to one customer in a transaction yield a single event with the final state`() {
        val id = tx {
            val saved = recipients.save(newRecipient())
            addresses.save(Address.forRecipient(saved, Address.AddressType.BILLING, "Weg 1", "80331", "Muenchen"))
            saved.name = "Final name"
            recipients.save(saved)
            saved.id!!
        }

        val event = MasterdataWireCodec.decode<CustomerUpserted>(events("customer", id).single().second)
        assertEquals("Final name", event.name)
        assertEquals(1, event.addresses.size)
    }

    @Test
    fun `a customer side b2b value is part of the customer event`() {
        val fieldId = tx {
            val profile = profiles.save(B2bProfile().apply { name = "P-${unique()}" })
            profileFields.save(
                B2bProfileField().apply {
                    this.profile = profile
                    term = B2bTerm.BT_19
                    businessDefinition = "Buchungsreferenz"
                    requirement = B2bFieldRequirement.OPTIONAL
                    placement = B2bPlacement.INVOICE
                },
            ).id!!
        }
        val id = tx { recipients.save(newRecipient()).id!! }

        tx { customerValues.save(RecipientB2bFieldValue().apply { recipientId = id; profileFieldId = fieldId; value = "REF-1" }) }

        val last = MasterdataWireCodec.decode<CustomerUpserted>(events("customer", id).last().second)
        assertEquals("REF-1", last.b2bValues.single().value)
        assertEquals("BT-19", last.b2bValues.single().btCode)
    }

    @Test
    fun `deleting a customer appends a deleted event`() {
        val id = tx { recipients.save(newRecipient()).id!! }

        tx { recipients.delete(id) }

        val last = events("customer", id).last()
        assertEquals(MasterdataSubjects.CUSTOMER_DELETED, last.first)
        assertEquals(CustomerDeleted(id), MasterdataWireCodec.decode<CustomerDeleted>(last.second))
    }

    @Test
    fun `a rolled back write appends no event`() {
        var id: Long? = null
        assertThrows(IllegalStateException::class.java) {
            tx {
                id = recipients.save(newRecipient()).id
                error("boom")
            }
        }

        assertEquals(0, events("customer", id).size)
    }

    @Test
    fun `seller changes and address writes append seller events`() {
        val seller = tx {
            senders.save(SenderProfile().apply { name = "Seller ${unique()}"; invoicePrefix = unique(); registerCourt = "" })
        }
        val id = seller.id!!
        assertEquals(MasterdataSubjects.SELLER_UPSERTED, events("seller", id).single().first)

        tx { addresses.save(Address.forSender(senders.findById(id)!!, Address.AddressType.BILLING, "Allee 5", "50667", "Koeln")) }
        assertEquals("Koeln", MasterdataWireCodec.decode<SellerUpserted>(events("seller", id).last().second).addresses.single().city)

        tx { senders.delete(id) }
        val last = events("seller", id).last()
        assertEquals(MasterdataSubjects.SELLER_DELETED, last.first)
        assertEquals(SellerDeleted(id), MasterdataWireCodec.decode<SellerDeleted>(last.second))
    }

    @Test
    fun `article save and delete append article events`() {
        val article = tx {
            articles.save(Article().apply { name = "Art ${unique()}"; defaultUnitPriceNet = Money.of("95.00") })
        }
        val id = article.id!!
        val created = MasterdataWireCodec.decode<ArticleUpserted>(events("article", id).single().second)
        assertEquals("95.00", created.defaultUnitPriceNet)

        tx { articles.delete(id) }

        val last = events("article", id).last()
        assertEquals(MasterdataSubjects.ARTICLE_DELETED, last.first)
        assertEquals(ArticleDeleted(id), MasterdataWireCodec.decode<ArticleDeleted>(last.second))
    }

    @Test
    fun `customer references travel with the article and a ref change or customer removal republishes it`() {
        val recipientId = tx { recipients.save(newRecipient()).id!! }
        val articleId = tx {
            val article = Article().apply { name = "Art ${unique()}"; defaultUnitPriceNet = Money.of("95.00") }
            article.replaceRecipientRefs(listOf(ArticleRecipientRef.of(article, recipients.findById(recipientId), "K-7", "Kundenrohr")))
            articles.save(article).id!!
        }
        val created = MasterdataWireCodec.decode<ArticleUpserted>(events("article", articleId).last().second)
        assertEquals(listOf(ArticleCustomerReferenceWire(recipientId, "K-7", "Kundenrohr")), created.customerReferences)

        tx {
            val article = articles.findById(articleId)!!
            article.replaceRecipientRefs(listOf(ArticleRecipientRef.of(article, recipients.findById(recipientId), "K-8", null)))
            // The management service forces a new version on every edit, because a changed child
            // row alone does not touch the article row.
            articles.forceVersionIncrement(article)
            articles.save(article)
        }
        val changed = MasterdataWireCodec.decode<ArticleUpserted>(events("article", articleId).last().second)
        assertEquals("K-8", changed.customerReferences.single().buyerArticleNumber)
        assertTrue(changed.version > created.version, "a changed reference must raise the article version")

        tx { recipients.delete(recipientId) }
        val afterRemoval = MasterdataWireCodec.decode<ArticleUpserted>(events("article", articleId).last().second)
        assertEquals(emptyList<ArticleCustomerReferenceWire>(), afterRemoval.customerReferences)
        assertTrue(afterRemoval.version > changed.version, "a cascaded reference removal must raise the article version")
    }

    @Test
    fun `tax rate save and delete append tax rate events`() {
        val rate = tx {
            taxRates.save(
                TaxRate().apply {
                    code = "T${unique()}"
                    category = "S"
                    name = "VAT 19"
                    this.rate = TaxRatePercent(BigDecimal("19.00"))
                },
            )
        }
        val id = rate.id!!
        val created = MasterdataWireCodec.decode<TaxRateUpserted>(events("taxrate", id).single().second)
        assertEquals("19.00", created.rate)

        tx { taxRates.delete(id) }

        val last = events("taxrate", id).last()
        assertEquals(MasterdataSubjects.TAX_RATE_DELETED, last.first)
        assertEquals(TaxRateDeleted(id), MasterdataWireCodec.decode<TaxRateDeleted>(last.second))
    }

    private fun newRange(subject: NumberRangeSubject) = NumberRange().apply {
        code = "T${unique()}"
        displayName = "Test ${unique()}"
        this.subject = subject
        isActive = false
        isDefault = false
    }

    @Test
    fun `a quote number range save and delete append number range events`() {
        val range = tx { numberRanges.save(newRange(NumberRangeSubject.QUOTE).apply { fixedPrefix = "AN" }) }
        val id = range.id!!
        val created = events("numberrange", id).single()
        assertEquals(MasterdataSubjects.NUMBER_RANGE_UPSERTED, created.first)
        assertEquals("QUOTE", MasterdataWireCodec.decode<NumberRangeUpserted>(created.second).subject)

        tx { numberRanges.delete(id) }

        val last = events("numberrange", id).last()
        assertEquals(MasterdataSubjects.NUMBER_RANGE_DELETED, last.first)
        assertEquals(NumberRangeDeleted(id), MasterdataWireCodec.decode<NumberRangeDeleted>(last.second))
    }

    @Test
    fun `a number range of another subject appends nothing`() {
        val id = tx { numberRanges.save(newRange(NumberRangeSubject.CUSTOMER)) }.id!!

        assertTrue(events("numberrange", id).isEmpty())
    }

    @Test
    fun `payment term save appends an event and a new default demotes the old one`() {
        val language = tx { em.find(Language::class.java, 1)!! }
        tx { paymentTerms.releaseDefaultsExcept(null) }
        val first = tx {
            paymentTerms.save(PaymentTerm().apply { setName(language, "Erster ${unique()}"); dueInDays = 30; isDefault = true })
        }
        val firstId = first.id!!
        assertEquals(true, MasterdataWireCodec.decode<PaymentTermUpserted>(events("paymentterm", firstId).single().second).isDefault)

        tx { paymentTerms.releaseDefaultsExcept(firstId + 1_000_000) }

        val demoted = MasterdataWireCodec.decode<PaymentTermUpserted>(events("paymentterm", firstId).last().second)
        assertEquals(false, demoted.isDefault)
        assertEquals(2, events("paymentterm", firstId).size)
    }

    @Test
    fun `profile field changes are profile events and a profile delete is a deleted event`() {
        val profileId = tx { profiles.save(B2bProfile().apply { name = "P-${unique()}" }).id!! }
        assertEquals(1, events("b2bprofile", profileId).size)

        val fieldId = tx {
            profileFields.save(
                B2bProfileField().apply {
                    profile = profiles.findById(profileId)
                    term = B2bTerm.BT_19
                    businessDefinition = "Buchungsreferenz"
                    requirement = B2bFieldRequirement.MANDATORY
                    placement = B2bPlacement.INVOICE
                },
            ).id!!
        }
        val withField = MasterdataWireCodec.decode<B2bProfileUpserted>(events("b2bprofile", profileId).last().second)
        assertEquals("BT-19", withField.fields.single().btCode)

        tx { profileFields.delete(fieldId) }
        val afterFieldDelete = events("b2bprofile", profileId).last()
        assertEquals(MasterdataSubjects.B2B_PROFILE_UPSERTED, afterFieldDelete.first)

        tx { profiles.delete(profileId) }
        val last = events("b2bprofile", profileId).last()
        assertEquals(MasterdataSubjects.B2B_PROFILE_DELETED, last.first)
        assertEquals(B2bProfileDeleted(profileId), MasterdataWireCodec.decode<B2bProfileDeleted>(last.second))
    }
}
