package de.littletrotter.shared.masterdata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins subject and stream names, the JSON round trip of every DTO and the envelope size limit. */
class MasterdataContractTest {

  private val address = AddressWire(1, "BILLING", "Musterstrasse 1", "10115", "Berlin", "DE", true)
  private val longText = "x".repeat(200)

  private val customer = CustomerUpserted(
    id = 7, version = 3, customerNumber = "K-1", name = "ACME", vatId = "DE123456789", email = "a@b.de",
    phone = "1", contactPerson = "P", customerType = "COMPANY", documentLanguage = "de", invoiceFormat = "GERMAN",
    reverseChargePreference = "ASK", routingId = "991-1", iban = "DE02120300000000202051", b2bProfileId = 2,
    addresses = listOf(address), b2bValues = listOf(CustomerB2bValueWire(5, "BT-10", "ref")),
  )
  private val seller = SellerUpserted(
    id = 1, version = 0, sellerNumber = "S-1", name = "Seller", invoicePrefix = "RE-", isDefault = true,
    taxNumber = "1", vatId = "DE1", iban = "DE02", bic = "BIC", bankName = "Bank", phone = "1", email = "s@s.de",
    website = "w", commercialRegisterNumber = "HRB", registerCourt = "AG", managingDirector = "MD",
    legalForm = "GMBH", defaultPaymentTermId = 4, addresses = listOf(address),
    additionalVatRegistrations = listOf(SellerVatRegistrationWire("FR", "FR1")),
  )
  private val article = ArticleUpserted(
    id = 3, version = 1, articleNumber = "A-1", name = "Art", description = "d", category = "SERVICE",
    defaultUnit = "HOUR", defaultQuantity = "1.0000", defaultUnitPriceNet = "99.50", defaultTaxRateId = 2,
    isDefault = false, taxVariants = listOf(ArticleTaxVariantWire("AT", 9)),
    customerReferences = listOf(ArticleCustomerReferenceWire(5, "K-1", null)),
  )
  private val taxRate = TaxRateUpserted(
    id = 2, version = 0, name = "19 %", code = "S", category = "S", rate = "19.00", country = "DE",
    validFrom = "2007-01-01", validTo = null, isDefault = true, isReference = true, isHotelRate = false,
    isActive = true, isSelectable = true, exemptionReasonCode = null, exemptionReasonText = null, legalBasis = null,
  )
  private val paymentTerm = PaymentTermUpserted(
    id = 4, version = 2, names = mapOf("de" to "30 Tage", "en" to "30 days"), description = null, dueInDays = 30,
    discountPercent = "2.00", discountDays = 10, isDefault = true, isActive = true,
  )
  private val numberRange = NumberRangeUpserted(
    id = 9, version = 3, code = "QUOTE_DEFAULT", subject = "QUOTE", fixedPrefix = "AN", datePart = "YEAR",
    counterWidth = 4, resetCycle = "YEARLY", isActive = true, isDefault = true,
  )
  private val profile = B2bProfileUpserted(
    id = 2, version = 1, name = "Profil", description = "d", active = true, countries = listOf("DE", "AT"),
    fields = listOf(B2bProfileFieldWire(5, "BT-10", "Leitweg-ID", "MANDATORY", null, "INVOICE", 1, "x")),
  )

  private fun <T : Any> roundTrip(value: T) =
    assertEquals(value, MasterdataWireCodec.decode(MasterdataWireCodec.encode(value), value.javaClass))

  @Test
  fun `stream limits are shared by owner and replicas`() {
    assertEquals(java.time.Duration.ofDays(30), MasterdataStreams.MAX_AGE)
    assertEquals(256L * 1024 * 1024, MasterdataStreams.MAX_BYTES)
    assertEquals(java.time.Duration.ofHours(1), MasterdataStreams.DUPLICATE_WINDOW)
  }

  @Test
  fun `subject and stream names are stable`() {
    assertEquals("MASTERDATA", MasterdataStreams.MASTERDATA)
    assertEquals("masterdata.>", MasterdataSubjects.ALL)
    assertEquals("masterdata.customer.upserted", MasterdataSubjects.CUSTOMER_UPSERTED)
    assertEquals("masterdata.customer.deleted", MasterdataSubjects.CUSTOMER_DELETED)
    assertEquals("masterdata.seller.upserted", MasterdataSubjects.SELLER_UPSERTED)
    assertEquals("masterdata.seller.deleted", MasterdataSubjects.SELLER_DELETED)
    assertEquals("masterdata.article.upserted", MasterdataSubjects.ARTICLE_UPSERTED)
    assertEquals("masterdata.article.deleted", MasterdataSubjects.ARTICLE_DELETED)
    assertEquals("masterdata.taxrate.upserted", MasterdataSubjects.TAX_RATE_UPSERTED)
    assertEquals("masterdata.taxrate.deleted", MasterdataSubjects.TAX_RATE_DELETED)
    assertEquals("masterdata.paymentterm.upserted", MasterdataSubjects.PAYMENT_TERM_UPSERTED)
    assertEquals("masterdata.paymentterm.deleted", MasterdataSubjects.PAYMENT_TERM_DELETED)
    assertEquals("masterdata.b2bprofile.upserted", MasterdataSubjects.B2B_PROFILE_UPSERTED)
    assertEquals("masterdata.b2bprofile.deleted", MasterdataSubjects.B2B_PROFILE_DELETED)
    assertEquals("masterdata.numberrange.upserted", MasterdataSubjects.NUMBER_RANGE_UPSERTED)
    assertEquals("masterdata.numberrange.deleted", MasterdataSubjects.NUMBER_RANGE_DELETED)
    assertEquals("masterdata.snapshot.requested", MasterdataSubjects.SNAPSHOT_REQUESTED)
    assertEquals("masterdata.snapshot.chunk", MasterdataSubjects.SNAPSHOT_CHUNK)
  }

  @Test
  fun `every upsert and delete event survives a round trip`() {
    roundTrip(customer)
    roundTrip(CustomerDeleted(7))
    roundTrip(seller)
    roundTrip(SellerDeleted(1))
    roundTrip(article)
    roundTrip(ArticleDeleted(3))
    roundTrip(taxRate)
    roundTrip(TaxRateDeleted(2))
    roundTrip(paymentTerm)
    roundTrip(PaymentTermDeleted(4))
    roundTrip(profile)
    roundTrip(B2bProfileDeleted(2))
    roundTrip(numberRange)
    roundTrip(NumberRangeDeleted(9))
  }

  @Test
  fun `snapshot messages survive a round trip`() {
    roundTrip(SnapshotRequested("r-1", listOf(MasterdataEntityTypes.CUSTOMER)))
    roundTrip(SnapshotRequested("r-2"))
    roundTrip(SnapshotChunk("r-1", 0, MasterdataEntityTypes.CUSTOMER, true, listOf(MasterdataWireCodec.encode(customer))))
  }

  @Test
  fun `entity types are delivered references first`() {
    assertEquals(
      listOf("taxrate", "paymentterm", "b2bprofile", "article", "seller", "customer", "numberrange"),
      MasterdataEntityTypes.ALL,
    )
  }

  @Test
  fun `a reader ignores fields a newer producer added`() {
    val json = MasterdataWireCodec.encode(CustomerDeleted(9)).dropLast(1) + ""","added":"later"}"""

    assertEquals(CustomerDeleted(9), MasterdataWireCodec.decode(json, CustomerDeleted::class.java))
  }

  @Test
  fun `size is measured in utf8 bytes`() {
    assertEquals(2, MasterdataWireCodec.sizeInBytes("ä"))
  }

  @Test
  fun `maximal aggregates stay below the envelope limit`() {
    val fat = customer.copy(
      name = longText,
      addresses = List(20) { address.copy(id = it.toLong(), street = longText) },
      b2bValues = List(30) { CustomerB2bValueWire(it.toLong(), "BT-$it", longText) },
    )
    val fatProfile = profile.copy(
      fields = List(40) { B2bProfileFieldWire(it.toLong(), "BT-$it", longText, "OPTIONAL", "BT-1", "INVOICE", it, longText) },
    )

    listOf(fat, fatProfile, seller.copy(addresses = List(20) { address.copy(street = longText) })).forEach {
      assertTrue(MasterdataWireCodec.sizeInBytes(MasterdataWireCodec.encode(it)) < MasterdataWireCodec.MAX_ENVELOPE_BYTES)
    }
  }
}
