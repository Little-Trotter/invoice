package de.littletrotter.application

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.port.inbound.SaveSenderProfileUseCase
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.value.Email
import de.littletrotter.ui.view.masterdata.SenderProfileFormData
import de.littletrotter.ui.view.masterdata.SenderProfileSaveInputs
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The edit dialog's load path against a real PostgreSQL.
 *
 * Why here and not in the UI layer: Karibu-Testing self-skips in this repo (Vaadin version
 * mismatch), so a click-level test would pass without asserting anything. The dialog builds its
 * form through [SenderProfileFormData.from] over what the service loads, so testing that pair
 * against the database covers exactly the failure the task describes.
 *
 * The test methods are deliberately **not** `@Transactional`: the bug only appears once the
 * service's own transaction has committed and the entity is detached — which is precisely the
 * state the Vaadin grid hands to the editor.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SenderProfileEditLoadIT {

    companion object {
        private val counter = AtomicInteger(0)
    }

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var senderProfileQueries: SenderProfileQueryPort

    @Inject
    lateinit var saveSenderProfile: SaveSenderProfileUseCase

    @Inject
    lateinit var em: EntityManager

    /**
     * `invoice_prefix` is unique table-wide -- every test brings its own.
     *
     * The counter lives in the `companion object`, shared across all test instances, so it is
     * actually monotonic. A plain instance field would restart at 0 for every test method
     * (JUnit creates a new instance per method), collapsing uniqueness onto `System.nanoTime()`
     * alone -- too narrow a window under coarse timer resolution to guarantee distinct values.
     * `@field:Size(max = 20)` on `invoicePrefix` is respected: "AI" + counter + "-" stays well
     * under it.
     */
    private fun uniqueSuffix(): String = "AI${counter.incrementAndGet()}"

    /** The seeded `language` row for [code] (`language.id`/`code` are schema, not seed —
     *  see `V1__baseline_schema.sql`'s own comment on why they live in the DDL file). */
    @Transactional
    fun findLanguage(code: String): Language =
        em.createQuery("SELECT l FROM Language l WHERE l.code = :code", Language::class.java)
            .setParameter("code", code).singleResult

    @Transactional
    fun persistPaymentTerm(name: String): PaymentTerm {
        val term = aPaymentTerm {
            changeName(findLanguage("de"), name)
            dueInDays = 7
            isActive = true
        }
        em.persist(term)
        em.flush()
        return term
    }

    /** A profile as the UI would create it: entity first, address through the service. */
    private fun createProfile(
        withAddress: Boolean = true,
        paymentTerm: PaymentTerm? = null,
    ): SenderProfile {
        val suffix = uniqueSuffix()
        val profile = aSenderProfile {
            name = "Anbieter $suffix"
            invoicePrefix = "$suffix-"
            email = Email.ofNullable("anbieter@example.org")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789") // required by SellerProfileService.validate
            registerCourt = "AG Musterstadt"
            changeInvoiceNote(findLanguage("de"), "Fußzeile $suffix")
            managingDirector = "M. Muster"
            sellerIdentifier = PartyIdentifier.of("SELLER-$suffix", "0088")
        }
        val saved = sellerProfileService.save(profile, defaults = SellerDefaultSelections(paymentTermId = paymentTerm?.id))
        if (withAddress) {
            sellerProfileService.saveAddress(
                saved.id!!, Address.AddressType.BILLING, "Musterweg 1", "12345", "Musterstadt", "DE",
            )
        }
        return saved
    }

    @Test
    fun `a saved profile with address and payment term comes back complete`() {
        val term = persistPaymentTerm("A3 Zahlungsziel")
        val created = createProfile(paymentTerm = term)

        // Exactly what the dialog does: load, then build the form — outside any transaction.
        val loaded = senderProfileQueries.findForEdit(created.id!!)
        assertNotNull(loaded, "the profile must be loadable for editing")
        val form = SenderProfileFormData.from(loaded)

        assertEquals(created.name, form.name)
        assertEquals("Musterweg 1", form.street)
        assertEquals("12345", form.postalCode)
        assertEquals("Musterstadt", form.city)
        assertEquals(Country.DE, form.country)
        assertEquals(term.id, form.defaultPaymentTerm?.id, "the LAZY payment term must be loaded, not lost")
        assertEquals("A3 Zahlungsziel", form.defaultPaymentTerm?.nameFor("de"), "the picker labels the term outside the session")
        assertEquals("AG Musterstadt", form.registerCourt)
        assertTrue(form.sellerIdentifierValue.isNotBlank(), "BT-29 must survive the round trip")
    }

    @Test
    fun `a grid row never yields a silently empty form`() {
        val term = persistPaymentTerm("A3 Grid")
        val created = createProfile(paymentTerm = term)

        // The grid's own query returns lean read-model rows, never an entity with unloaded relations.
        val fromGrid = senderProfileQueries.findAll(0, 100, null).firstOrNull { it.id == created.id }
        assertNotNull(fromGrid, "the profile must appear in the grid query")
        assertEquals(created.name, fromGrid!!.name)

        // And the path the dialog actually uses is always complete.
        val viaEditPath = SenderProfileFormData.from(senderProfileQueries.findForEdit(created.id!!))
        assertEquals(created.name, viaEditPath.name)
        assertEquals(term.id, viaEditPath.defaultPaymentTerm?.id)
    }

    @Test
    fun `a profile without an address loads with empty address fields only`() {
        val created = createProfile(withAddress = false)

        val form = SenderProfileFormData.from(senderProfileQueries.findForEdit(created.id!!))

        assertEquals("", form.street)
        assertEquals("", form.postalCode)
        assertEquals("", form.city)
        assertEquals(created.name, form.name, "everything else must still be filled")
        assertEquals("AG Musterstadt", form.registerCourt)
    }

    @Test
    fun `a profile without a default payment term loads with the rest intact`() {
        val created = createProfile(paymentTerm = null)

        val form = SenderProfileFormData.from(senderProfileQueries.findForEdit(created.id!!))

        assertNull(form.defaultPaymentTerm)
        assertEquals(created.name, form.name)
        assertEquals("Musterweg 1", form.street)
    }

    @Test
    fun `switching the default to a second existing profile is accepted by the single-default index`() {
        // Regression against a real session: the defaults lookup auto-flushes the dirty managed
        // entity, so marking the second profile default before revoking the first used to
        // put two is_default = TRUE rows in front of uq_sender_profile_single_default.
        val first = createProfile(withAddress = false)
        val second = createProfile(withAddress = false)
        val firstAsDefault = sellerProfileService.findByIdForEdit(first.id!!)!!.edit { isDefault = true }
        sellerProfileService.save(firstAsDefault, defaults = SellerDefaultSelections())

        val secondAsDefault = sellerProfileService.findByIdForEdit(second.id!!)!!.edit { isDefault = true }
        sellerProfileService.save(secondAsDefault, defaults = SellerDefaultSelections())

        assertTrue(sellerProfileService.findByIdForEdit(second.id!!)!!.isDefault)
        assertEquals(false, sellerProfileService.findByIdForEdit(first.id!!)!!.isDefault)
    }

    @Test
    fun `a blank form cannot address an existing record`() {
        // After A1.2 a failed load never produces a form over an existing record: the dialog does
        // not open at all. The invariant that makes overwriting impossible is that a blank form
        // carries no id — saving it would create a new profile, never replace one.
        assertNull(SenderProfileFormData().id)
        assertNull(SenderProfileFormData.from(null).id)

        // And an id that does not exist yields null from the load path, which the view turns into
        // an error instead of an empty dialog.
        assertNull(sellerProfileService.findByIdForEdit(987_654_321L))
    }

    @Test
    fun `editing an existing profile persists every field the form can write`() {
        // Regression: save() copied the form's values onto the managed entity field by field and
        // the list was incomplete — defaultPaymentTerm, registerCourt and the three identifier
        // fields were dropped, so those edits vanished without any error.
        val first = persistPaymentTerm("A3 vorher")
        val second = persistPaymentTerm("A3 nachher")
        val created = createProfile(paymentTerm = first)

        val edited = SenderProfileFormData.from(senderProfileQueries.findForEdit(created.id!!)).apply {
            registerCourt = "AG Neustadt"
            sellerIdentifierValue = "NEU-4711"
            sellerIdentifierScheme = "0088"
            defaultPaymentTerm = PaymentTermDto(id = second.id)
        }
        saveSenderProfile.saveSenderProfile(
            edited.toCommand(
                SenderProfileSaveInputs(
                    address = AddressDto(null, edited.street, edited.postalCode, edited.city, edited.country.alpha2),
                    invoiceNotesByLanguage = mapOf("de" to "Neue Fußzeile"),
                    partyPairs = emptyMap(),
                    vatRegistrations = emptyList(),
                    proposedSellerNumber = null,
                ),
            ),
        )

        val form = SenderProfileFormData.from(senderProfileQueries.findForEdit(created.id!!))
        assertEquals(second.id, form.defaultPaymentTerm?.id, "changed payment term must be persisted")
        assertEquals("Neue Fußzeile", form.invoiceNotesByLanguageCode["de"])
        assertEquals("AG Neustadt", form.registerCourt)
        assertEquals("NEU-4711", form.sellerIdentifierValue)
    }

    /**
     * `sender_profile.vat_id` is `VARCHAR(64)` (`V1__baseline_schema.sql`) so official foreign
     * VAT-id formats are never clamped. `VatId` accepts an
     * unrecognised-but-syntactically-complete prefix (a real third-country identifier
     * `VatIdValidator` has no regex for), so this now uses one instead of arbitrary garbage: a
     * pure Kotlin/entity test cannot prove the column itself was actually widened — only a real
     * round trip against Postgres can — so this saves a 34-character value (well past the old
     * 30-character limit) and asserts it comes back whole, not silently truncated.
     */
    @Test
    fun `an unrecognised-prefix vat id well past the old 30-character limit persists in full (unknown prefix accepted, wave E, widened column)`() {
        val suffix = uniqueSuffix()
        val longVatId = "JP" + "1".repeat(32) // 34 characters -- JP has no recognised VatIdValidator format
        val profile = aSenderProfile {
            name = "Foreign Anbieter $suffix"
            invoicePrefix = "$suffix-"
            email = Email.ofNullable("foreign-$suffix@example.org")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId(longVatId)
            registerCourt = "AG Musterstadt"
        }
        val saved = sellerProfileService.save(profile, defaults = SellerDefaultSelections())

        val reloaded = sellerProfileService.findByIdForEdit(saved.id!!)!!

        assertEquals(34, longVatId.length)
        assertEquals(longVatId, reloaded.vatId?.value, "the full 34-character value must survive the round trip unclamped")
    }
}
