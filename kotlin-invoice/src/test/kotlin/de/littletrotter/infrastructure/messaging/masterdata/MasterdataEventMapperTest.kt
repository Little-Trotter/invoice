package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.shared.masterdata.ArticleCustomerReferenceWire
import de.littletrotter.domain.masterdata.LegalForm
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.ResetCycle
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.masterdata.CustomerDeleted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MasterdataEventMapperTest {

    private val mapper = MasterdataEventMapper()

    @Test
    fun `a populated customer maps with addresses and b2b values`() {
        val profile = B2bProfile().apply { id = 4 }
        val recipient = Recipient().apply {
            id = 1
            version = 5
            customerNumber = CustomerNumber("KD-1")
            name = "Acme"
            email = Email("a@acme.de")
            b2bProfile = profile
        }
        val address = Address.forRecipient(recipient, Address.AddressType.BILLING, "Weg 1", "10115", "Berlin")
        val value = RecipientB2bFieldValue().apply { profileFieldId = 9; this.value = "REF" }

        val event = mapper.customer(recipient, listOf(address), listOf(value), mapOf(9L to B2bTerm.BT_19))

        assertEquals(5, event.version)
        assertEquals("KD-1", event.customerNumber)
        assertEquals("a@acme.de", event.email)
        assertEquals(4, event.b2bProfileId)
        assertEquals("Berlin", event.addresses.single().city)
        assertEquals("BT-19", event.b2bValues.single().btCode)
    }

    @Test
    fun `an empty customer maps with absent optionals and unknown field terms`() {
        val event = mapper.customer(
            Recipient().apply { id = 2; version = null },
            emptyList(),
            listOf(RecipientB2bFieldValue().apply { profileFieldId = 3 }),
            emptyMap(),
        )

        assertEquals(0, event.version)
        assertNull(event.customerNumber)
        assertNull(event.b2bProfileId)
        assertNull(event.b2bValues.single().btCode)
    }

    @Test
    fun `a populated seller maps registrations and an empty one maps defaults`() {
        val seller = SenderProfile().apply {
            id = 1
            version = 2
            name = "Seller"
            invoicePrefix = "RE-"
            legalForm = LegalForm.GMBH
            defaultPaymentTerm = PaymentTerm().apply { id = 8 }
            updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.FR, VatId("FR12345678901"))), LocalDate.of(2026, 1, 1))
        }

        val event = mapper.seller(seller, emptyList())

        assertEquals("GMBH", event.legalForm)
        assertEquals(8, event.defaultPaymentTermId)
        assertEquals("FR", event.additionalVatRegistrations.single().country)

        val empty = mapper.seller(SenderProfile().apply { id = 3; version = null }, emptyList())
        assertEquals(0, empty.version)
        assertNull(empty.legalForm)
        assertNull(empty.defaultPaymentTermId)
    }

    @Test
    fun `articles map with and without references`() {
        val rate = TaxRate().apply { id = 6; country = Country.AT }
        val article = Article().apply {
            id = 1
            version = 1
            name = "Art"
            defaultUnitPriceNet = Money.of("95.00")
            defaultTaxRateRef = rate
            replaceTaxVariants(
                listOf(ArticleTaxVariant().apply { country = Country.AT; taxRateRef = rate }),
                LocalDate.of(2026, 1, 1),
            )
        }

        val buyer = Recipient().apply { id = 9 }
        val refs = listOf(
            ArticleRecipientRef.of(article, buyer, "K-7", "Kundenrohr"),
            ArticleRecipientRef().apply { this.article = article },
        )

        val event = mapper.article(article, refs)

        assertEquals("95.00", event.defaultUnitPriceNet)
        assertEquals(6, event.defaultTaxRateId)
        assertEquals(6, event.taxVariants.single().taxRateId)
        assertEquals(listOf(ArticleCustomerReferenceWire(9, "K-7", "Kundenrohr")), event.customerReferences)

        val bare = mapper.article(Article().apply { id = 2; version = null }, emptyList())
        assertNull(bare.defaultTaxRateId)
        assertEquals(0, bare.version)
    }

    @Test
    fun `tax rates and payment terms map their reference data`() {
        val rate = mapper.taxRate(
            TaxRate().apply {
                id = 1
                version = 0
                this.rate = TaxRatePercent(BigDecimal("7.00"))
                validFrom = LocalDate.of(2020, 1, 1)
                validTo = LocalDate.of(2020, 12, 31)
            },
        )
        assertEquals("7.00", rate.rate)
        assertEquals("2020-01-01", rate.validFrom)
        assertEquals("2020-12-31", rate.validTo)
        val open = mapper.taxRate(TaxRate().apply { id = 2; version = null })
        assertNull(open.validFrom)
        assertNull(open.validTo)

        val german = Language().apply { id = 1; code = "de" }
        val term = mapper.paymentTerm(
            PaymentTerm().apply { id = 3; version = 1; setName(german, "30 Tage"); dueInDays = 30 },
        )
        assertEquals(mapOf("de" to "30 Tage"), term.names)
        val unnamed = mapper.paymentTerm(PaymentTerm().apply { id = 4; version = null })
        assertTrue(unnamed.names.isEmpty())
    }

    @Test
    fun `profiles map their fields in sort order with countries sorted`() {
        val profile = B2bProfile().apply {
            id = 1
            version = 3
            name = "Profil"
            countries = mutableSetOf(Country.DE, Country.AT)
        }
        profile.fields = mutableListOf(
            B2bProfileField().apply { id = 2; sortOrder = 2; term = B2bTerm.BT_19; conditionTerm = B2bTerm.BT_19 },
            B2bProfileField().apply { id = 1; sortOrder = 1 },
        )

        val event = mapper.b2bProfile(profile)

        assertEquals(listOf("AT", "DE"), event.countries)
        assertEquals(listOf(1L, 2L), event.fields.map { it.id })
        assertNull(event.fields.first().btCode)
        assertEquals("BT-19", event.fields.last().conditionBtCode)
        assertEquals(0, mapper.b2bProfile(B2bProfile().apply { id = 2; version = null }).version)
    }

    @Test
    fun `a number range maps its shape with enum names`() {
        val event = mapper.numberRange(
            NumberRange().apply {
                id = 9
                version = 3
                code = "QUOTE_DEFAULT"
                subject = NumberRangeSubject.QUOTE
                fixedPrefix = "AN"
                datePart = DatePart.YEAR
                counterWidth = 4
                resetCycle = ResetCycle.YEARLY
                isActive = true
                isDefault = true
            },
        )

        assertEquals(9, event.id)
        assertEquals(3, event.version)
        assertEquals("QUOTE", event.subject)
        assertEquals("AN", event.fixedPrefix)
        assertEquals("YEAR", event.datePart)
        assertEquals(4, event.counterWidth)
        assertEquals("YEARLY", event.resetCycle)
        assertTrue(event.isActive && event.isDefault)
        assertEquals(0, mapper.numberRange(NumberRange().apply { id = 1; version = null }).version)
    }

    @Test
    fun `a deleted event exists for every aggregate`() {
        assertEquals(CustomerDeleted(7), mapper.deleted(MasterdataAggregate.CUSTOMER, 7))
        MasterdataAggregate.entries.forEach { assertTrue(mapper.deleted(it, 7).toString().contains("7")) }
    }
}
