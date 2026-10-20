package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.ValkeyTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Real-Hibernate coverage for [PanacheTaxRateRepository]'s remaining public surface not
 * already exercised by [PanacheTaxRateRepositoryFindByCodeIT] (findByCode) or
 * [PanacheRepositoryCriteriaOverviewIT] (name/country filter shapes). Own `COV8-TR-` token so
 * assertions stay relative to this test's own fixtures in the shared dev-services database
 * (project rule: never assert on absolute counts of a shared table).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class PanacheTaxRateRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: TaxRateRepository

    @AfterEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice_item WHERE position_id LIKE 'COV8-TR-%'").executeUpdate()
            em.createNativeQuery("DELETE FROM invoice WHERE invoice_number LIKE 'COV8TR-%'").executeUpdate()
            em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'COV8-TR-%'").executeUpdate()
            em.createNativeQuery("DELETE FROM sender_profile WHERE invoice_prefix LIKE 'COV8TR%'").executeUpdate()
            em.createNativeQuery("DELETE FROM tax_rate WHERE code LIKE 'COV8-TR-%'").executeUpdate()
        }
    }

    private fun newRate(name: String, country: Country, code: String): TaxRate =
        TaxRate().edit {
            this.name = name
            this.country = country
            rate = TaxRatePercent(BigDecimal("19.00"))
            this.code = code
            category = "S"
            isSelectable = false
        }

    @Test
    fun `happy path - save inserts and updates a rate, findAll sorts name matches with an id tiebreak, and delete removes it`() {
        val first = repository.save(newRate("COV8-TR Alpha", Country.DE, "COV8-TR-A"))
        val second = repository.save(newRate("COV8-TR Alpha", Country.DE, "COV8-TR-B"))

        val sorted = repository.findAll(0, 50, "cov8-tr alpha", sortField = "name", sortDir = SortDirection.ASC)
        val ids = sorted.map { it.id }
        assertTrue(ids.indexOf(first.id) < ids.indexOf(second.id), "same name -> id decides the tiebreak")

        // merge branch of save(): id already assigned.
        first.edit { name = "COV8-TR Alpha Updated" }
        val updated = repository.save(first)
        assertEquals("COV8-TR Alpha Updated", updated.name)
        val persistedName = QuarkusTransaction.requiringNew().call<String> {
            em.createNativeQuery("SELECT name FROM tax_rate WHERE id = :id")
                .setParameter("id", first.id).singleResult as String
        }
        assertEquals("COV8-TR Alpha Updated", persistedName, "the merge must actually commit to Postgres")

        // Verify the delete inside its own transaction, reading back in the same session — a
        // separate repository.findById afterward is not guaranteed to see a just-deleted row if
        // an earlier call in this test already cached an entity for this id.
        val stillPresent = QuarkusTransaction.requiringNew().call<Boolean> {
            repository.delete(first.id!!)
            em.find(TaxRate::class.java, first.id) != null
        }
        assertFalse(stillPresent, "delete must actually remove the row from Postgres")
    }

    @Test
    fun `boundary - descending name sort reverses both the name and the id tiebreak`() {
        val first = repository.save(newRate("COV8-TR Desc Alpha", Country.DE, "COV8-TR-DESC-A"))
        val second = repository.save(newRate("COV8-TR Desc Alpha", Country.DE, "COV8-TR-DESC-B"))
        val other = repository.save(newRate("COV8-TR Desc Zulu", Country.DE, "COV8-TR-DESC-Z"))

        val sorted = repository.findAll(0, 50, "cov8-tr desc", sortField = "name", sortDir = SortDirection.DESC)
        val ids = sorted.map { it.id }
        assertTrue(ids.indexOf(other.id) < ids.indexOf(first.id), "Zulu must sort before Alpha in descending name order")
        assertTrue(ids.indexOf(second.id) < ids.indexOf(first.id), "same name -> higher id first in descending order")
    }

    @Test
    fun `boundary - an unknown sort field keeps the default country, rate order instead of erroring`() {
        // Country.country persists by its ISO-3166-1 numeric code (CountryAttributeConverter):
        // FR is 250, DE is 276 — the default (country, rate) ascending order must put FR first.
        val higherCountry = repository.save(newRate("COV8-TR Sort High", Country.DE, "COV8-TR-SORT-HI"))
        val lowerCountry = repository.save(newRate("COV8-TR Sort Low", Country.FR, "COV8-TR-SORT-LO"))

        val defaultOrder = repository.findAll(0, 5000, sortField = null)
        val positions = defaultOrder.map { it.id }
        assertTrue(positions.indexOf(lowerCountry.id) < positions.indexOf(higherCountry.id))
    }

    @Test
    fun `boundary - findValidAt only returns rates whose validity window covers the date`() {
        val probe = LocalDate.of(2026, 6, 15)
        val expired = repository.save(
            newRate("COV8-TR Expired", Country.DE, "COV8-TR-EXPIRED").edit {
                validFrom = LocalDate.of(2020, 1, 1)
                validTo = LocalDate.of(2020, 12, 31)
            },
        )
        val openEnded = repository.save(
            newRate("COV8-TR OpenEnded", Country.DE, "COV8-TR-OPEN").edit {
                validFrom = LocalDate.of(2026, 1, 1)
                validTo = null
            },
        )
        val unbounded = repository.save(newRate("COV8-TR Unbounded", Country.DE, "COV8-TR-UNBOUND"))

        val validIds = repository.findValidAt(probe).map { it.id }
        assertTrue(openEnded.id in validIds, "open-ended validity starting before the probe date must match")
        assertTrue(unbounded.id in validIds, "no validity bounds at all must match")
        assertFalse(expired.id in validIds, "a window that ended before the probe date must not match")
    }

    @Test
    fun `boundary - findDefault and findDefaults return only rows actually marked isDefault`() {
        // uq_tax_rate_single_default_per_country forbids inserting a second default for a country
        // that already has one, so this reads the existing shared-DB state structurally instead
        // of creating a colliding row.
        val default = repository.findDefault()
        if (default != null) assertTrue(default.isDefault, "findDefault must only ever return an isDefault row")
        assertTrue(repository.findDefaults().all { it.isDefault }, "findDefaults must only ever return isDefault rows")
    }

    @Test
    fun `boundary - findReferences filters by country and the isReference flag`() {
        val references = repository.findReferences(Country.DE)
        assertTrue(references.all { it.country == Country.DE && it.isReference })
    }

    @Test
    fun `boundary - isInUse and usageCounts see a rate referenced by an invoice item and ignore an unused one`() {
        val used = repository.save(newRate("COV8-TR Used", Country.DE, "COV8-TR-USED"))
        val unused = repository.save(newRate("COV8-TR Unused", Country.DE, "COV8-TR-UNUSED"))

        QuarkusTransaction.requiringNew().run {
            val sender = aSenderProfile {
                name = "COV8-TR Sender"
                email = Email("cov8-tr@overview.example")
                taxNumber = TaxNumber("12/345/67891")
                vatId = VatId("DE111111111")
                iban = Iban("DE89370400440532013000")
                registerCourt = "AG Musterstadt"
                invoicePrefix = "COV8TR-"
            }
            val recipient = aRecipient {
                customerNumber = CustomerNumber("COV8-TR-KD")
                name = "COV8-TR Kunde"
                email = Email("kunde@overview.example")
                vatId = VatId("DE222222222")
                iban = Iban("DE02120300000000202051")
            }
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice().edit {
                invoiceNumber = InvoiceNumber("COV8TR-2026-0001")
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                totals = InvoiceTotals(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("119.00")))
            }
            val item = InvoiceItem().edit {
                positionId = "COV8-TR-1"
                description = "COV8-TR test position"
                taxRateRef = em.getReference(TaxRate::class.java, used.id!!)
            }
            invoice.addItem(item)
            em.persist(invoice)
        }

        assertTrue(repository.isInUse(used.id!!), "a rate referenced by an invoice_item row must be reported in use")
        assertFalse(repository.isInUse(unused.id!!), "a rate with no referencing row anywhere must not be in use")

        val counts = repository.usageCounts()
        assertTrue((counts[used.id] ?: 0L) >= 1L, "usageCounts must include the used rate's id")
        assertFalse(unused.id in counts, "usageCounts must not carry an entry for an unused rate")
    }
}
