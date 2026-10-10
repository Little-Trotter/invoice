package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.PendingVatIdVerification
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.value.VatIdValidator
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * `vat-id-field-ergonomics` — proves the ONLY column-level contract this task touches (the field
 * ergonomics themselves — auto-prefix, suffix, live validation — are [de.littletrotter.ui.component
 * .VatIdField]'s own Karibu suite; the spec is explicit the storage/persistence shape is
 * unchanged): a normalised VAT id round-trips through Postgres byte-for-byte, a foreign prefix on a
 * domestic address is not rejected anywhere below the UI layer (only
 * [de.littletrotter.ui.view.masterdata.RecipientView]'s [de.littletrotter.ui.component.VatIdField]
 * shows it as an informational mismatch — that never reaches the application save path exercised
 * here), and the verification columns ([Recipient.vatIdVerificationStatus],
 * [Recipient.vatIdVerificationNote]) survive a save unrelated to them.
 *
 * Same idiom as the sibling ITs in this package/module (`MasterDataSnapshotsViesRegistrationsIT`,
 * `MasterDataNumberCollisionIT`, `RecipientUpdateSaveIT`): real Postgres via [PostgresTestResource],
 * the [ManageRecipientUseCase.saveRecipientWithAddresses] entry point [de.littletrotter.ui.view
 * .masterdata.RecipientView.persistEntity] itself calls, and a read-back through [EntityManager.find] — never
 * the in-memory instance the save call returned — to prove the value actually reached the database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class RecipientVatIdPersistenceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var manageRecipient: ManageRecipientUseCase

    private fun freshRecipient(customerNumber: String, vatId: String, street: String) = RecipientDraft.blank().copy(
        customerNumber = customerNumber,
        name = "VAT-IT $customerNumber GmbH",
        email = "$customerNumber@example.invalid".lowercase(),
        vatId = vatId,
        customerType = CustomerType.BUSINESS,
        addresses = RecipientAddressInput(
            billingStreet = street,
            billingPostalCode = "10115",
            billingCity = "Berlin",
            billingCountry = "DE",
        ),
    )

    /**
     * The value [VatIdValidator.normalize] produces for "de 143 454 214" (spaces stripped,
     * upper-cased) — the same normalisation [de.littletrotter.ui.component.VatIdField] writes back
     * into the field on blur — persists exactly, and a fresh recipient's verification columns stay
     * at their unset default (`null`, equivalent to `UNVERIFIED` — see `V1__baseline_schema.sql`'s
     * own comment "UNVERIFIED=0, VERIFIED=1, FAILED=2" and `chk_recipient_vat_id_verification_status`
     * allowing `NULL`).
     */
    @Test
    fun `a normalised DE VAT id persists exactly and leaves verification unset`() {
        val normalised = VatIdValidator.normalize("de 143 454 214")
        assertEquals("DE143454214", normalised, "sanity: this is the exact value the field would write back")

        val id = QuarkusTransaction.requiringNew().call<Long> {
            manageRecipient.saveRecipientWithAddresses(freshRecipient("VATIT-001", normalised, "Musterstraße 1")).id
        }

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient> {
            em.find(Recipient::class.java, id)
        }
        assertEquals("DE143454214", reloaded.vatId?.value, "the normalised value must persist byte-for-byte")
        assertNull(
            reloaded.vatIdVerificationStatus,
            "a freshly saved recipient must not be marked verified — null is UNVERIFIED (0)",
        )
        assertNull(reloaded.vatIdVerifiedAt, "no verification timestamp without a VIES call")
    }

    /**
     * A German billing address with an Austrian VAT id ("AT" prefix ≠ "DE" address country) is a
     * legitimate business case (spec: "German company invoicing from an Austrian registration") —
     * [de.littletrotter.ui.component.VatIdField] shows it as an informational helper, never an
     * error, for [de.littletrotter.ui.view.masterdata.RecipientView]. Nothing below the UI layer
     * even looks at the address country when saving a VAT id, so the save must simply succeed.
     */
    @Test
    fun `a foreign VAT id prefix on a domestic billing address saves successfully`() {
        val id = QuarkusTransaction.requiringNew().call<Long> {
            manageRecipient.saveRecipientWithAddresses(freshRecipient("VATIT-002", "ATU12345678", "Musterstraße 2")).id
        }

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient> {
            em.find(Recipient::class.java, id)
        }
        assertEquals("ATU12345678", reloaded.vatId?.value, "the AT-prefixed id must persist unchanged next to the DE billing address")
    }

    /**
     * A recipient VIES already marked [VatIdVerificationStatus.FAILED], with a free-text
     * [Recipient.vatIdVerificationNote] recorded alongside it, round-trips both columns unchanged —
     * the FAILED outcome is handed in as the dialog's in-session VIES result, exactly as
     * [de.littletrotter.ui.view.masterdata.RecipientView] records it.
     */
    @Test
    fun `a FAILED verification status and its note round-trip unchanged`() {
        val verifiedAt = OffsetDateTime.now().withNano(0)
        val note = "VIES 2026-09-14: Anfrage von VIES nicht akzeptiert."

        val id = QuarkusTransaction.requiringNew().call<Long> {
            val recipient = freshRecipient("VATIT-003", "DE143454214", "Musterstraße 3").copy(
                vatIdValidationNote = note,
                pendingVatIdVerification = PendingVatIdVerification("DE143454214", VatIdVerificationStatus.FAILED, verifiedAt),
            )
            manageRecipient.saveRecipientWithAddresses(recipient).id
        }

        val reloaded = QuarkusTransaction.requiringNew().call<Recipient> {
            em.find(Recipient::class.java, id)
        }
        assertEquals(VatIdVerificationStatus.FAILED, reloaded.vatIdVerificationStatus, "the FAILED status must survive the round trip")
        // Postgres normalises the offset to UTC ("Z") on read-back — same instant, different
        // zone representation — so `isEqual` (instant comparison) is the correct check here,
        // not `assertEquals` (which also compares the offset itself).
        assertTrue(verifiedAt.isEqual(reloaded.vatIdVerifiedAt), "the verification timestamp must survive unchanged (same instant): $verifiedAt vs ${reloaded.vatIdVerifiedAt}")
        assertEquals(note, reloaded.vatIdVerificationNote, "the free-text note must survive unchanged")
    }
}
