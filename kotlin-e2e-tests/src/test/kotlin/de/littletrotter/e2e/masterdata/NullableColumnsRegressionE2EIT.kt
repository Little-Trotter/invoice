package de.littletrotter.e2e.masterdata

import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * `sender_profile.register_court` is nullable in both the schema and the entity, so "no register
 * court" round-trips as `null` rather than as an empty string. (`receipt.description` used to be
 * covered here too; it is mandatory and NOT NULL since receipt-mandatory-fields, PO 2026-09-04 —
 * the refusal of a description-less upload is pinned in `ReceiptServiceTest` and, server-side
 * end-to-end, in `S3RoundtripBordersE2EIT`.)
 *
 * Every row this suite inserts is removed again in [cleanup] — the [SenderProfile] via
 * [SellerProfileService.delete], not a raw `em.remove`: that method already contains the correct
 * "promote another profile back to default if the one just removed held it" logic
 * ([SellerProfileService.save] mirrors it as "ensure at least one default exists" whenever a new
 * profile is saved while `findDefault() == null` table-wide) — this suite must never leave the
 * shared `sender_profile` table with zero or with more than one `is_default = TRUE` row
 * (`uq_sender_profile_single_default`).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class NullableColumnsRegressionE2EIT {

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    private val createdSenderProfileIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdSenderProfileIds.forEach { id ->
            // SellerProfileService.delete (not a raw em.remove) — see class KDoc: it already
            // re-promotes another sender profile to default if the one removed here held it.
            QuarkusTransaction.requiringNew().run { sellerProfileService.delete(id) }
        }
    }

    // ── SenderProfile.registerCourt = null survives the normal service path ─────────────────────

    @Test
    fun `a SenderProfile saved through SellerProfileService with a null registerCourt reads back as null`() {
        val profile = aSenderProfile {
            name = "NCR1 Sender GmbH"
            invoicePrefix = "NCR1-"
            registerCourt = null
            email = Email.ofNullable("billing@ncr1.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        profile.addAddress(Address.forSender(profile, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))

        val saved = QuarkusTransaction.requiringNew().call { sellerProfileService.save(profile, defaults = SellerDefaultSelections()) }
        createdSenderProfileIds += saved.id!!

        val reloaded = QuarkusTransaction.requiringNew().call { senderProfileRepository.findById(saved.id!!)!! }
        assertNull(reloaded.registerCourt, "register_court is nullable now (V1__baseline_schema.sql) - a null value must survive the round trip")
    }
}
