package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceSequence
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class OtherEntitiesCrudIT {

    @Inject
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `GenerationJob - insert update delete (no @Version)`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.now()
            addItem(InvoiceItem().apply {
                positionId = "1"
                description = "Job Test"
                quantity = BigDecimal.ONE
                unitPriceNet = Money(BigDecimal("10.00"))
            })
        }
        em.persist(invoice)
        em.flush()

        val job = GenerationJob().apply {
            this.invoice = invoice
            jobType = "PDF"
            status = GenerationJobStatus.PENDING
            correlationId = java.util.UUID.randomUUID().toString()
        }
        em.persist(job)
        em.flush()

        assertNotNull(job.id)

        job.status = GenerationJobStatus.COMPLETED
        em.merge(job)
        em.flush()
        em.clear()

        val reloaded = em.find(GenerationJob::class.java, job.id)!!
        assertEquals(GenerationJobStatus.COMPLETED, reloaded.status)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(GenerationJob::class.java, reloaded.id))
    }

    @Test
    @Transactional
    fun `InvoiceSequence - insert update delete (no @Version)`() {
        val sp = SenderProfile().apply { name = "SeqSender"; invoicePrefix = "SQ-"; registerCourt = "" }
        em.persist(sp)
        em.flush()

        val seq = InvoiceSequence().apply {
            senderProfileId = sp.id!!
            year = 2099
            month = 1
            lastValue = 0L
        }
        em.persist(seq)
        em.flush()

        assertNotNull(seq.id)

        seq.lastValue = 42L
        em.merge(seq)
        em.flush()
        em.clear()

        val reloaded = em.find(InvoiceSequence::class.java, seq.id)!!
        assertEquals(42L, reloaded.lastValue)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(InvoiceSequence::class.java, seq.id))
    }

    @Test
    @Transactional
    fun `DatabaseMaintenanceEvent - insert update delete (no @Version)`() {
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.VACUUM,
            status = DatabaseMaintenanceStatus.SUCCESS,
            details = "Routine vacuum completed",
        )
        em.persist(event)
        em.flush()

        assertNotNull(event.id)

        event.status = DatabaseMaintenanceStatus.FAILED
        em.merge(event)
        em.flush()
        em.clear()

        val reloaded = em.find(DatabaseMaintenanceEvent::class.java, event.id)!!
        assertEquals(DatabaseMaintenanceStatus.FAILED, reloaded.status)

        em.remove(reloaded)
        em.flush()
        em.clear()
        assertNull(em.find(DatabaseMaintenanceEvent::class.java, event.id))
    }

    @Test
    @Transactional
    fun `InvoiceItem PER_DIEM with TravelExpenseEntry - insert and verify cascade`() {
        val sender = SenderProfile().apply { name = "PerDiem Sender"; invoicePrefix = "PD-"; registerCourt = "" }
        val recipient = Recipient().apply { customerNumber = CustomerNumber("KD-PD-001"); name = "PerDiem Kunde" }
        val paymentTerm = PaymentTerm().apply { setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "Sofort"); dueInDays = 0 }
        val taxRate = TaxRate().apply {
            code = "IT_OTHER_CRUD_DE_0" // NOT NULL since V904
            category = "S" // chk_tax_rate_selectable_needs_category (V904)
            name = "MwSt 0%"; rate = TaxRatePercent(BigDecimal.ZERO); country = de.littletrotter.shared.country.Country.DE
            validFrom = LocalDate.of(2024, 1, 1); isDefault = false
            // Fixture, not a selectable reference rate — see NumberJournalReportServiceIT for the
            // same pattern.
            isSelectable = false
        }
        val perDiemRate = PerDiemRate().apply {
            source = "Testdaten OtherEntitiesCrudIT" // chk_per_diem_rate_active_needs_source (V905)
            // Fixture row, not a selectable reference rate: is_active = false keeps it out of
            // selection AND out of TravelRateMigrationIT's assertions ("exactly one active
            // domestic row").
            isActive = false
            country = de.littletrotter.shared.country.Country.DE; region = ""
            fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
            inlandRules = true; isDefault = true; validFrom = LocalDate.of(2024, 1, 1)
        }
        listOf(sender, recipient, paymentTerm, taxRate, perDiemRate).forEach { em.persist(it) }

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("PD-2026-0001")
            senderProfile = sender; this.recipient = recipient
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 4, 5)
        }
        val perDiemItem = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Verpflegungspauschale Berlin"
            quantity = BigDecimal.ONE; unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("8.40")); taxRateRef = taxRate
            sortOrder = 0; calculateAmounts()
        }
        val entry = TravelExpenseEntry().apply {
            shortName = "DE 2026-04-05"; country = de.littletrotter.shared.country.Country.DE
            description = "Verpflegungspauschale Berlin"; participant = "Verpflegung Berlin"
            travelDate = LocalDate.of(2026, 4, 5)
            isArrivalDeparture = true
            breakfastDeduction = false; lunchDeduction = false; dinnerDeduction = false
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("8.40"); employerContribution = Money.ZERO
            this.perDiemRate = perDiemRate; invoiceItem = perDiemItem
        }
        perDiemItem.perDiemEntries.add(entry)
        invoice.addItem(perDiemItem); invoice.recalculateTotals()

        em.persist(invoice); em.flush(); em.clear()

        val loaded = em.find(Invoice::class.java, invoice.id)
        assertNotNull(loaded)
        assertEquals(1, loaded.itemList.size)
        val loadedItem = loaded.itemList.single()
        assertEquals(InvoiceItemCategory.TRIP, loadedItem.category)
        assertEquals(1, loadedItem.perDiemEntries.size)
        val loadedEntry = loadedItem.perDiemEntries.single()
        assertEquals(LocalDate.of(2026, 4, 5), loadedEntry.travelDate)
        assertEquals(de.littletrotter.shared.country.Country.DE, loadedEntry.country)
        assertEquals(Money.of("8.40"), loadedEntry.calculatedAmount)

        em.remove(loaded); em.flush(); em.clear()
        assertNull(em.find(Invoice::class.java, invoice.id))
    }

    /** `travel_expense_entry.participant` is `NOT NULL` (`V1__baseline_schema.sql`,
     *  renamed from the former `description`, which meant the traveler before it was split into a
     *  separate block-level `description`). A raw native insert bypasses the entity's own
     *  `participant: String = ""` default and
     *  [de.littletrotter.domain.reference.TravelExpenseEntry.validate], so this exercises the real
     *  database constraint itself, not just the application-level check ([DomainConstraintsTest]
     *  already covers that one). */
    @Test
    @Transactional
    fun `travel_expense_entry rejects a NULL participant at the database constraint level`() {
        val (sender, recipient, paymentTerm, taxRate) = otherCrudNullTestFixtures("IT_OTHER_CRUD_DE_1", "KD-PDN-001")
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("PDN-2026-0001")
            senderProfile = sender; this.recipient = recipient
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 4, 5)
        }
        val perDiemItem = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Verpflegungspauschale ohne Teilnehmer"
            quantity = BigDecimal.ONE; unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("8.40")); taxRateRef = taxRate
            sortOrder = 0; calculateAmounts()
        }
        invoice.addItem(perDiemItem); invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO travel_expense_entry (short_name, invoice_item_id, country, participant, description, travel_date, " +
                    "full_day_rate_snapshot, arrival_departure_rate_snapshot, calculated_amount, employer_contribution) " +
                    "VALUES ('DE 2026-04-05', ${perDiemItem.id}, 276, NULL, 'Verpflegungspauschale ohne Teilnehmer', '2026-04-05', 28.00, 14.00, 8.40, 0.00)",
            ).executeUpdate()
            em.flush()
        }
        assertTrue(ex.message?.contains("participant") == true || (ex.cause?.message?.contains("participant") == true))
    }

    /** `travel_expense_entry.description` (the block-level description, denormalized onto
     *  every row) is `NOT NULL` — the counterpart of the `participant` constraint test above. */
    @Test
    @Transactional
    fun `travel_expense_entry rejects a NULL description at the database constraint level`() {
        val (sender, recipient, paymentTerm, taxRate) = otherCrudNullTestFixtures("IT_OTHER_CRUD_DE_2", "KD-PDN-002")
        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("PDN-2026-0002")
            senderProfile = sender; this.recipient = recipient
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 4, 5)
        }
        val perDiemItem = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Verpflegungspauschale ohne Beschreibung"
            quantity = BigDecimal.ONE; unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("8.40")); taxRateRef = taxRate
            sortOrder = 0; calculateAmounts()
        }
        invoice.addItem(perDiemItem); invoice.recalculateTotals()
        em.persist(invoice); em.flush()

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO travel_expense_entry (short_name, invoice_item_id, country, participant, description, travel_date, " +
                    "full_day_rate_snapshot, arrival_departure_rate_snapshot, calculated_amount, employer_contribution) " +
                    "VALUES ('DE 2026-04-05', ${perDiemItem.id}, 276, 'Verpflegung Berlin', NULL, '2026-04-05', 28.00, 14.00, 8.40, 0.00)",
            ).executeUpdate()
            em.flush()
        }
        assertTrue(ex.message?.contains("description") == true || (ex.cause?.message?.contains("description") == true))
    }

    /** Shared fixture rows for the two NULL-constraint tests above — each test needs its own
     *  distinct `taxRate.code` (`NOT NULL UNIQUE`) but otherwise identical sender/recipient/
     *  payment-term scaffolding. */
    private data class OtherCrudNullTestFixtures(
        val sender: SenderProfile,
        val recipient: Recipient,
        val paymentTerm: PaymentTerm,
        val taxRate: TaxRate,
    )

    private fun otherCrudNullTestFixtures(taxRateCode: String, customerNumber: String): OtherCrudNullTestFixtures {
        val sender = SenderProfile().apply { name = "PerDiem Null Sender"; invoicePrefix = "PDN-"; registerCourt = "" }
        val recipient = Recipient().apply { this.customerNumber = CustomerNumber(customerNumber); name = "PerDiem Null Kunde" }
        val paymentTerm = PaymentTerm().apply { setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "Sofort"); dueInDays = 0 }
        val taxRate = TaxRate().apply {
            code = taxRateCode
            category = "S"
            name = "MwSt 0%"; rate = TaxRatePercent(BigDecimal.ZERO); country = de.littletrotter.shared.country.Country.DE
            validFrom = LocalDate.of(2024, 1, 1); isDefault = false
            isSelectable = false
        }
        listOf(sender, recipient, paymentTerm, taxRate).forEach { em.persist(it) }
        return OtherCrudNullTestFixtures(sender, recipient, paymentTerm, taxRate)
    }
}
