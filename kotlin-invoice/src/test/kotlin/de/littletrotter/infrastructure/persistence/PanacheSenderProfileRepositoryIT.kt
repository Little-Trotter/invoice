package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.exception.DuplicateInvoicePrefixException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aSellerVatRegistration
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.DiscountPercent
import java.math.BigDecimal
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId

/**
 * AP- [PanacheSenderProfileRepository] had almost no direct test before this class
 * (21 % branch coverage; `SenderProfileIT` only exercises the raw table via JDBC, never this
 * repository). `sender_profile` has no V2 baseline seed rows at all, and `invoice_prefix` is
 * `UNIQUE` (`uq_sender_profile_invoice_prefix`, max 10 chars) and `is_default` allows at most one
 * `TRUE` row (`uq_sender_profile_single_default`) — this fixture gives every row its own short
 * `ZZCOV4-*` prefix and explicitly revokes the one `isDefault = true` row it creates before the
 * test ends, the same care [SenderProfileIT] already takes with its raw-JDBC row, so it never
 * leaves the shared, uncleaned database without a usable default slot for other tests. Unlike
 * [PanacheMileageRateRepositoryIT], the free-text filter here is on `s.name`/`a.city` (both
 * `String`), not a `Country`-converted-to-Integer column, so it does not hit the `lower()`-on-
 * Integer Hibernate bug documented there.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheSenderProfileRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: SenderProfileRepository

    private fun newSender(name: String, invoicePrefix: String) = aSenderProfile {
        this.name = name
        this.invoicePrefix = invoicePrefix
        // register_court is NOT NULL DEFAULT '' at the DB level (a legacy column kept alongside
        // courtOfJurisdiction, see V1's comment on it), but the entity field itself is nullable —
        // Hibernate sends an explicit NULL for an unset field rather than falling back to the
        // column's SQL DEFAULT, so this must be set explicitly or every insert violates the
        // NOT NULL constraint.
        this.registerCourt = ""
    }

    private fun persist(sender: SenderProfile): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(sender)
            em.flush()
            sender.id!!
        }

    @Test
    fun `findById force-initializes addresses and returns null for an unknown id`() {
        val id = persist(newSender("ZZ-COV4 FindById", "ZZCOV4-01"))

        val found = repository.findById(id)
        assertNotNull(found)
        assertEquals("ZZ-COV4 FindById", found!!.name)
        assertTrue(found.addressList.isEmpty())

        assertNull(repository.findById(-1L))
    }

    @Test
    fun `findByIdForEdit joins the editable relations and returns null for an unknown id`() {
        val id = persist(newSender("ZZ-COV4 FindByIdForEdit", "ZZCOV4-02"))

        val found = repository.findByIdForEdit(id)
        assertNotNull(found)
        assertEquals("ZZ-COV4 FindByIdForEdit", found!!.name)

        assertNull(repository.findByIdForEdit(-1L))
    }

    // findByIdForEdit's own two lazy-init safe-call chains
    // (`sender?.invoiceNote?.entries?.forEach { it.language?.code }` and
    // `sender?.defaultPaymentTerm?.nameGroup?.entries?.forEach { it.language?.code }`) both stay on
    // their leftmost null arm above (a sender with neither an invoiceNote nor a defaultPaymentTerm)
    // -- this fixture gives the profile a real invoiceNote entry and a real defaultPaymentTerm with
    // its own nameGroup entry, both bound to a real, non-null `language`, so every `?.` in both
    // chains is actually walked past instead of short-circuiting.
    @Test
    fun `findByIdForEdit force-initializes a real invoiceNote entry and defaultPaymentTerm nameGroup entry`() {
        val german = em.find(Language::class.java, 1)!!
        val termId = QuarkusTransaction.requiringNew().call<Long> {
            val term = aPaymentTerm {
                changeName(german, "ZZ-COV4 Zahlungsziel")
                description = "ZZ-COV4 term"
                dueInDays = 14
                discountPercent = DiscountPercent(BigDecimal.ZERO)
                isDefault = false
                isActive = true
            }
            em.persist(term)
            em.flush()
            term.id!!
        }

        val id = QuarkusTransaction.requiringNew().call<Long> {
            val sender = newSender("ZZ-COV4 FindByIdForEdit Full", "ZZCOV4-11")
            sender.changeInvoiceNote(german, "ZZ-COV4 Hinweistext")
            sender.edit { defaultPaymentTerm = em.find(PaymentTerm::class.java, termId) }
            em.persist(sender)
            em.flush()
            sender.id!!
        }

        try {
            val found = repository.findByIdForEdit(id)

            assertNotNull(found)
            assertEquals("ZZ-COV4 Hinweistext", found!!.invoiceNote?.entries?.firstOrNull { it.language?.id == german.id }?.value)
            assertEquals(termId, found.defaultPaymentTerm?.id)
            assertEquals(
                "ZZ-COV4 Zahlungsziel",
                found.defaultPaymentTerm?.nameGroup?.entries?.firstOrNull { it.language?.id == german.id }?.value,
            )
        } finally {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE sender_profile SET default_payment_term_id = NULL WHERE id = :id")
                    .setParameter("id", id).executeUpdate()
                em.createNativeQuery("DELETE FROM payment_term WHERE id = :id").setParameter("id", termId).executeUpdate()
            }
        }
    }

    @Test
    fun `findAll and count filter across name and the billing city of an owned address`() {
        val sender = newSender("ZZ-COV4 FilterName", "ZZCOV4-03")
        val id = persist(sender)
        QuarkusTransaction.requiringNew().run {
            val owner = em.find(SenderProfile::class.java, id)!!
            em.persist(Address.forSender(owner, Address.AddressType.BILLING, "Musterstr. 1", "12345", "ZZ-COV4-City", Country.DE, true))
        }

        // Exercises the "no filter" branch of findAll, but does NOT look for this fixture's own
        // row in it: that overload pages at 60 rows ORDER BY isDefault DESC, name ASC, and this
        // shared database holds an unknown number of senders — whether "ZZ-COV4 FilterName" lands
        // on page 0 depends on what every other fixture happened to insert.
        assertTrue(repository.findAll(0, 1000, null).isNotEmpty())

        val byName = repository.findAll(0, 10, "zz-cov4 filtername")
        assertEquals(listOf(id), byName.map { it.id })
        assertEquals(1L, repository.count("zz-cov4 filtername"))

        // Matches only via the EXISTS(... a.city ...) half of the OR, not the name — proves that
        // branch of the filter, not just the name half, actually participates.
        val byCity = repository.findAll(0, 10, "zz-cov4-city")
        assertEquals(listOf(id), byCity.map { it.id })
        assertEquals(1L, repository.count("zz-cov4-city"))

        assertTrue(repository.findAll(0, 10, "no-such-sender-marker").isEmpty())
        assertEquals(0L, repository.count("no-such-sender-marker"))
    }

    // -- `!filterText.isNullOrBlank()` guards both findAllSorted's WHERE clause and
    // count's, and every other test in this class passes either null or genuine non-blank text --
    // a blank-but-non-null string (isNullOrBlank's OWN second, isBlank() arm, distinct from its
    // null-check arm) had no test of its own.
    @Test
    fun `findAll, findAllSorted and count treat a blank, non-null filterText the same as no filter at all`() {
        val expectedTotal = (em.createNativeQuery("SELECT COUNT(*) FROM sender_profile").singleResult as Number).toLong()

        assertEquals(expectedTotal, repository.count("   "))
        assertEquals(minOf(expectedTotal, 1000L), repository.findAll(0, 1000, "   ").size.toLong())
        assertEquals(minOf(expectedTotal, 1000L), repository.findAllSorted(0, 1000, "   ", GridSort("", SortDirection.ASC)).size.toLong())
    }

    @Test
    fun `findDefault and findDefaults see a freshly-marked default row, revoked again afterward`() {
        // uq_sender_profile_single_default allows at most one TRUE row across the whole table, and
        // this shared, never-cleaned database normally already has one. Borrow the slot and give
        // it back, rather than assuming it is free — assuming it was free made this test fail with
        // a ConstraintViolation as soon as it ran after a fixture that marks a default.
        val previousDefaultId = QuarkusTransaction.requiringNew().call<Long?> {
            val previous = em.createQuery(
                "SELECT s FROM SenderProfile s WHERE s.isDefault = true",
                SenderProfile::class.java,
            ).resultList.firstOrNull()
            previous?.revokeDefault()
            previous?.id
        }

        try {
            val id = persist(newSender("ZZ-COV4 Default", "ZZCOV4-04").apply { markAsDefault() })

            val default = repository.findDefault()
            assertNotNull(default)
            assertEquals(id, default!!.id)
            assertTrue(default.isDefault)

            assertTrue(repository.findDefaults().any { it.id == id })

            QuarkusTransaction.requiringNew().run {
                em.find(SenderProfile::class.java, id)?.revokeDefault()
            }
        } finally {
            QuarkusTransaction.requiringNew().run {
                previousDefaultId?.let { em.find(SenderProfile::class.java, it)?.markAsDefault() }
            }
        }
    }

    @Test
    fun `findPersistedAdditionalVatCountries reflects a persisted registration and is empty without one`() {
        val withRegistration = persist(newSender("ZZ-COV4 VatCountries", "ZZCOV4-5A"))
        QuarkusTransaction.requiringNew().run {
            val owner = em.find(SenderProfile::class.java, withRegistration)!!
            em.persist(
                aSellerVatRegistration {
                    this.senderProfile = owner
                    // chk_seller_vat_registration_country curates this to FR/BE/NL/LU only — a
                    // narrower, hardcoded set than the domain's
                    // Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES (all EU members except
                    // DE, computed dynamically); LU is in both.
                    this.country = Country.LU
                    this.vatId = VatId("LU12345678")
                },
            )
        }
        assertEquals(setOf(Country.LU), repository.findPersistedAdditionalVatCountries(withRegistration))

        val withoutRegistration = persist(newSender("ZZ-COV4 NoVatCountries", "ZZCOV4-5B"))
        assertTrue(repository.findPersistedAdditionalVatCountries(withoutRegistration).isEmpty())
    }

    @Test
    fun `save inserts, saveAndFlush updates immediately, delete removes it`() {
        val sender = newSender("ZZ-COV4 Save v1", "ZZCOV4-06")

        val saved = QuarkusTransaction.requiringNew().call<SenderProfile> { repository.save(sender) }
        assertNotNull(saved.id)

        saved.edit { this.name = "ZZ-COV4 Save v2" }
        QuarkusTransaction.requiringNew().run { repository.saveAndFlush(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-COV4 Save v2", reloaded?.name)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }

    @Test
    fun `boundary - saving a second profile with a colliding invoicePrefix translates the unique violation into DuplicateInvoicePrefixException`() {
        persist(newSender("ZZ-COV4 Prefix v1", "ZZCOV4-07"))
        val colliding = newSender("ZZ-COV4 Prefix v2", "ZZCOV4-07")

        val thrown = assertThrows(DuplicateInvoicePrefixException::class.java) {
            QuarkusTransaction.requiringNew().run { repository.save(colliding) }
        }
        assertNotNull(thrown.cause, "the unique-constraint violation must be chained as the cause")
    }

    @Test
    fun `forceVersionIncrement bumps the optimistic-lock version without any other field change`() {
        val id = persist(newSender("ZZ-COV4 VersionBump", "ZZCOV4-09"))
        val before = QuarkusTransaction.requiringNew().call<Long> { em.find(SenderProfile::class.java, id)!!.version!! }

        QuarkusTransaction.requiringNew().run {
            repository.forceVersionIncrement(em.find(SenderProfile::class.java, id)!!)
        }

        val after = QuarkusTransaction.requiringNew().call<Long> { em.find(SenderProfile::class.java, id)!!.version!! }
        assertTrue(after > before, "forceVersionIncrement must bump the @Version column")
    }

    @Test
    fun `findAllSellerNumbers returns every non-blank sellerNumber, this fixture's own row included`() {
        val id = persist(newSender("ZZ-COV4 SellerNumber", "ZZCOV4-10").edit { sellerNumber = "ZZ-COV4-SN-01" })

        val numbers = repository.findAllSellerNumbers()

        assertTrue(numbers.contains("ZZ-COV4-SN-01"))
        assertNotNull(id)
    }

    @Test
    fun `findAllSorted orders by the billing city for cityCountry and by invoicePrefix, both server-side`() {
        val first = newSender("ZZ-COV4 SortCity A", "ZZCOV4-8A")
        val second = newSender("ZZ-COV4 SortCity B", "ZZCOV4-8B")
        val idA = persist(first)
        val idB = persist(second)
        QuarkusTransaction.requiringNew().run {
            em.persist(Address.forSender(em.find(SenderProfile::class.java, idA)!!, Address.AddressType.BILLING, "Musterstr. 1", "12345", "ZZ-COV4-City-A", Country.DE, true))
            em.persist(Address.forSender(em.find(SenderProfile::class.java, idB)!!, Address.AddressType.BILLING, "Musterstr. 2", "54321", "ZZ-COV4-City-B", Country.DE, true))
        }

        val byCity = repository.findAllSorted(0, 1000, null, GridSort("cityCountry", SortDirection.ASC))
        val cityIndexA = byCity.indexOfFirst { it.id == idA }
        val cityIndexB = byCity.indexOfFirst { it.id == idB }
        assertTrue(cityIndexA in 0 until cityIndexB, "ascending city sort must place City-A before City-B")

        val byPrefixDesc = repository.findAllSorted(0, 1000, null, GridSort("invoicePrefix", SortDirection.DESC))
        val prefixIndexA = byPrefixDesc.indexOfFirst { it.id == idA }
        val prefixIndexB = byPrefixDesc.indexOfFirst { it.id == idB }
        assertTrue(prefixIndexB in 0 until prefixIndexA, "descending prefix sort must place ZZCOV4-8B before ZZCOV4-8A")
    }
}
