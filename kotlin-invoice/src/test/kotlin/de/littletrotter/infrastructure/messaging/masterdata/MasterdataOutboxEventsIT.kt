package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
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
    @Inject lateinit var senders: SenderProfileRepository
    @Inject lateinit var articles: ArticleRepository
    @Inject lateinit var taxRates: TaxRateRepository
    @Inject lateinit var numberRanges: NumberRangeRepository
    @Inject lateinit var paymentTerms: PaymentTermRepository
    @Inject lateinit var profiles: B2bProfileRepository
    @Inject lateinit var featureGate: FeatureSettingsGate

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private fun events(type: String, id: Long?): List<Pair<String, String>> = tx {
        em.createNativeQuery("SELECT subject, payload FROM outbox WHERE aggregate_type = :t AND aggregate_id = :i ORDER BY id")
            .setParameter("t", type).setParameter("i", id.toString()).resultList
            .map { row -> (row as Array<*>).let { it[0] as String to it[1] as String } }
    }

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun newRecipient() = aRecipient {
        customerNumber = CustomerNumber("KD-${unique()}")
        name = "Acme ${unique()}"
    }

    @Test
    fun `saving a customer appends one customer event and an address write another`() {
        val recipient = tx { recipients.save(newRecipient()) }
        val id = recipient.id!!

        val created = events("customer", id).single()
        assertEquals(MasterdataSubjects.CUSTOMER_UPSERTED, created.first)
        assertEquals(recipient.name, MasterdataWireCodec.decode(created.second, CustomerUpserted::class.java).name)

        tx {
            val loaded = recipients.findById(id)!!
            loaded.addAddress(Address.forRecipient(loaded, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin"))
            recipients.save(loaded)
        }

        val all = events("customer", id)
        assertEquals(2, all.size)
        assertEquals("Berlin", MasterdataWireCodec.decode(all.last().second, CustomerUpserted::class.java).addresses.single().city)
    }

    @Test
    fun `several writes to one customer in a transaction yield a single event with the final state`() {
        val id = tx {
            val saved = recipients.save(newRecipient())
            saved.addAddress(Address.forRecipient(saved, Address.AddressType.BILLING, "Weg 1", "80331", "Muenchen"))
            saved.edit { name = "Final name" }
            recipients.save(saved)
            saved.id!!
        }

        val event = MasterdataWireCodec.decode(events("customer", id).single().second, CustomerUpserted::class.java)
        assertEquals("Final name", event.name)
        assertEquals(1, event.addresses.size)
    }

    @Test
    fun `a customer side b2b value is part of the customer event`() {
        val fieldId = tx {
            val profile = profiles.save(B2bProfile().edit { name = "P-${unique()}" })
            val field = B2bProfileField().edit {
                this.profile = profile
                term = B2bTerm.BT_19
                businessDefinition = "Buchungsreferenz"
                requirement = B2bFieldRequirement.OPTIONAL
                placement = B2bPlacement.INVOICE
            }
            profile.defineField(field)
            profiles.saveAndFlush(profile)
            field.id!!
        }
        val id = tx { recipients.save(newRecipient()).id!! }

        tx {
            val loaded = recipients.findById(id)!!
            loaded.recordB2bFieldValue(fieldId, "REF-1")
            recipients.save(loaded)
        }

        val last = MasterdataWireCodec.decode(events("customer", id).last().second, CustomerUpserted::class.java)
        assertEquals("REF-1", last.b2bValues.single().value)
        assertEquals("BT-19", last.b2bValues.single().btCode)
    }

    @Test
    fun `a customer change is still recorded while the offer module is switched off`() {
        val id = tx { recipients.save(newRecipient()).id!! }
        tx { featureGate.setEnabled(FeatureFlag.QUOTE, false, "it") }
        try {
            tx { recipients.save(recipients.findById(id)!!.edit { name = "Renamed while off" }) }

            val last = MasterdataWireCodec.decode(events("customer", id).last().second, CustomerUpserted::class.java)
            assertEquals("Renamed while off", last.name)
        } finally {
            tx { featureGate.setEnabled(FeatureFlag.QUOTE, true, "it") }
        }
    }

    @Test
    fun `deleting a customer appends a deleted event`() {
        val id = tx { recipients.save(newRecipient()).id!! }

        tx { recipients.delete(id) }

        val last = events("customer", id).last()
        assertEquals(MasterdataSubjects.CUSTOMER_DELETED, last.first)
        assertEquals(CustomerDeleted(id), MasterdataWireCodec.decode(last.second, CustomerDeleted::class.java))
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
            senders.save(aSenderProfile { name = "Seller ${unique()}"; invoicePrefix = unique(); registerCourt = "" })
        }
        val id = seller.id!!
        assertEquals(MasterdataSubjects.SELLER_UPSERTED, events("seller", id).single().first)

        tx {
            val loaded = senders.findById(id)!!
            loaded.addAddress(Address.forSender(loaded, Address.AddressType.BILLING, "Allee 5", "50667", "Koeln"))
            senders.save(loaded)
        }
        assertEquals("Koeln", MasterdataWireCodec.decode(events("seller", id).last().second, SellerUpserted::class.java).addresses.single().city)

        tx { senders.delete(id) }
        val last = events("seller", id).last()
        assertEquals(MasterdataSubjects.SELLER_DELETED, last.first)
        assertEquals(SellerDeleted(id), MasterdataWireCodec.decode(last.second, SellerDeleted::class.java))
    }

    @Test
    fun `article save and delete append article events`() {
        val article = tx {
            articles.save(anArticle { name = "Art ${unique()}"; defaultUnitPriceNet = Money.of("95.00") })
        }
        val id = article.id!!
        val created = MasterdataWireCodec.decode(events("article", id).single().second, ArticleUpserted::class.java)
        assertEquals("95.00", created.defaultUnitPriceNet)

        tx { articles.delete(id) }

        val last = events("article", id).last()
        assertEquals(MasterdataSubjects.ARTICLE_DELETED, last.first)
        assertEquals(ArticleDeleted(id), MasterdataWireCodec.decode(last.second, ArticleDeleted::class.java))
    }

    @Test
    fun `customer references travel with the article and a ref change or customer removal republishes it`() {
        val recipientId = tx { recipients.save(newRecipient()).id!! }
        val articleId = tx {
            val article = anArticle { name = "Art ${unique()}"; defaultUnitPriceNet = Money.of("95.00") }
            article.replaceRecipientRefs(listOf(ArticleRecipientRef.of(article, recipients.findById(recipientId), "K-7", "Kundenrohr")))
            articles.save(article).id!!
        }
        val created = MasterdataWireCodec.decode(events("article", articleId).last().second, ArticleUpserted::class.java)
        assertEquals(listOf(ArticleCustomerReferenceWire(recipientId, "K-7", "Kundenrohr")), created.customerReferences)

        tx {
            val article = articles.findById(articleId)!!
            article.replaceRecipientRefs(listOf(ArticleRecipientRef.of(article, recipients.findById(recipientId), "K-8", null)))
            // The management service forces a new version on every edit, because a changed child
            // row alone does not touch the article row.
            articles.forceVersionIncrement(article)
            articles.save(article)
        }
        val changed = MasterdataWireCodec.decode(events("article", articleId).last().second, ArticleUpserted::class.java)
        assertEquals("K-8", changed.customerReferences.single().buyerArticleNumber)
        assertTrue(changed.version > created.version, "a changed reference must raise the article version")

        tx { recipients.delete(recipientId) }
        val afterRemoval = MasterdataWireCodec.decode(events("article", articleId).last().second, ArticleUpserted::class.java)
        assertEquals(emptyList<ArticleCustomerReferenceWire>(), afterRemoval.customerReferences)
        assertTrue(afterRemoval.version > changed.version, "a cascaded reference removal must raise the article version")
    }

    @Test
    fun `tax rate save and delete append tax rate events`() {
        val rate = tx {
            taxRates.save(
                TaxRate().edit {
                    code = "T${unique()}"
                    category = "S"
                    name = "VAT 19"
                    this.rate = TaxRatePercent(BigDecimal("19.00"))
                },
            )
        }
        val id = rate.id!!
        val created = MasterdataWireCodec.decode(events("taxrate", id).single().second, TaxRateUpserted::class.java)
        assertEquals("19.00", created.rate)

        tx { taxRates.delete(id) }

        val last = events("taxrate", id).last()
        assertEquals(MasterdataSubjects.TAX_RATE_DELETED, last.first)
        assertEquals(TaxRateDeleted(id), MasterdataWireCodec.decode(last.second, TaxRateDeleted::class.java))
    }

    private fun newRange(subject: NumberRangeSubject) = NumberRange().edit {
        code = "T${unique()}"
        displayName = "Test ${unique()}"
        this.subject = subject
        isActive = false
        isDefault = false
    }

    @Test
    fun `a quote number range save and delete append number range events`() {
        val range = tx { numberRanges.save(newRange(NumberRangeSubject.QUOTE).edit { fixedPrefix = "AN" }) }
        val id = range.id!!
        val created = events("numberrange", id).single()
        assertEquals(MasterdataSubjects.NUMBER_RANGE_UPSERTED, created.first)
        assertEquals("QUOTE", MasterdataWireCodec.decode(created.second, NumberRangeUpserted::class.java).subject)

        tx { numberRanges.delete(id) }

        val last = events("numberrange", id).last()
        assertEquals(MasterdataSubjects.NUMBER_RANGE_DELETED, last.first)
        assertEquals(NumberRangeDeleted(id), MasterdataWireCodec.decode(last.second, NumberRangeDeleted::class.java))
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
            paymentTerms.save(aPaymentTerm { changeName(language, "Erster ${unique()}"); dueInDays = 30; isDefault = true })
        }
        val firstId = first.id!!
        assertEquals(true, MasterdataWireCodec.decode(events("paymentterm", firstId).single().second, PaymentTermUpserted::class.java).isDefault)

        tx { paymentTerms.releaseDefaultsExcept(firstId + 1_000_000) }

        val demoted = MasterdataWireCodec.decode(events("paymentterm", firstId).last().second, PaymentTermUpserted::class.java)
        assertEquals(false, demoted.isDefault)
        assertEquals(2, events("paymentterm", firstId).size)
    }

    @Test
    fun `profile field changes are profile events and a profile delete is a deleted event`() {
        val profileId = tx { profiles.save(B2bProfile().edit { name = "P-${unique()}" }).id!! }
        assertEquals(1, events("b2bprofile", profileId).size)

        val fieldId = tx {
            val profile = profiles.findById(profileId)!!
            val field = B2bProfileField().edit {
                this.profile = profile
                term = B2bTerm.BT_19
                businessDefinition = "Buchungsreferenz"
                requirement = B2bFieldRequirement.MANDATORY
                placement = B2bPlacement.INVOICE
            }
            profile.defineField(field)
            profiles.saveAndFlush(profile)
            field.id!!
        }
        val withField = MasterdataWireCodec.decode(events("b2bprofile", profileId).last().second, B2bProfileUpserted::class.java)
        assertEquals("BT-19", withField.fields.single().btCode)

        tx {
            val profile = profiles.findById(profileId)!!
            profile.removeField(fieldId)
            profiles.saveAndFlush(profile)
        }
        val afterFieldDelete = events("b2bprofile", profileId).last()
        assertEquals(MasterdataSubjects.B2B_PROFILE_UPSERTED, afterFieldDelete.first)

        tx { profiles.delete(profileId) }
        val last = events("b2bprofile", profileId).last()
        assertEquals(MasterdataSubjects.B2B_PROFILE_DELETED, last.first)
        assertEquals(B2bProfileDeleted(profileId), MasterdataWireCodec.decode(last.second, B2bProfileDeleted::class.java))
    }
}
