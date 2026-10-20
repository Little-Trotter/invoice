package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.domain.compliance.NumberJournalFinding
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.NotSealableException
import de.littletrotter.domain.reference.NumberContext
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PeriodAlreadySealedException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Integration test for [NumberJournalReportService] validating full invoice allocation cycle:
 * actual number assignment via [InvoiceLifecycleService.transition], journal entries, and
 * reconciliation against persisted invoices.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class NumberJournalReportServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var reportService: NumberJournalReportService

    @Inject
    lateinit var rangeService: NumberRangeService

    // Each test gets its own dedicated invoice number range (isolated from the factory seed) to
    // prevent execution-order dependency across the full integration test suite. reportFor(INVOICE)
    // and sealPeriod() resolve via findActive, so toggling the factory range on/off per test
    // achieves isolation without duplicating test logic. The reconciliation against finalized
    // invoices deliberately queries ALL invoices (not filtered by range) because invoice numbering
    // is global across all ranges within a firm — these tests hold exclusive write access to the
    // entire invoice table (DELETE FROM invoice below) for their duration. That DELETE is only
    // safe because `restrictToAnnotatedClass = true` on the class-level `@QuarkusTestResource`
    // gives this class its own Postgres container: on the module's shared container, this same
    // DELETE used to fail on invoice_item_receipt's foreign key whenever another class's still-
    // linked receipts survived it, and would otherwise wipe every other class's invoices too.
    private var originalActiveInvoiceRangeIds: List<Long> = emptyList()
    private var dedicatedInvoiceRangeId: Long = 0

    @BeforeEach
    fun setUpDedicatedInvoiceCircle() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice").executeUpdate()

            val activeIds = em.createNativeQuery(
                "SELECT id FROM number_range WHERE subject = 0 AND is_active = true",
            ).resultList.map { it as Number }
            check(activeIds.isNotEmpty()) { "kein aktiver INVOICE-Kreis vorhanden — V904-Seed fehlt?" }
            originalActiveInvoiceRangeIds = activeIds.map { it.toLong() }

            em.createNativeQuery("UPDATE number_range SET is_active = false WHERE subject = 0 AND is_active = true")
                .executeUpdate()

            val testCode = "IT-${UUID.randomUUID().toString().substring(0, 12)}"
            dedicatedInvoiceRangeId = (
                em.createNativeQuery(
                    """INSERT INTO number_range
                         (code, display_name, subject, start_value, prefix_source, fixed_prefix,
                          prefix_data_field, date_part, counter_width, reset_cycle, scope, continuity,
                          is_active, is_default)
                       SELECT :code, display_name, subject, start_value, prefix_source, fixed_prefix,
                              prefix_data_field, date_part, counter_width, reset_cycle, scope, continuity,
                              TRUE, FALSE
                         FROM number_range WHERE id = :templateId
                       RETURNING id""",
                )
                    .setParameter("code", testCode)
                    .setParameter("templateId", originalActiveInvoiceRangeIds.first())
                    .singleResult as Number
                ).toLong()
        }
    }

    /**
     * Restores factory range to active and test range to inactive, even on test failure
     * (JUnit guarantees @AfterEach runs), preventing range exhaustion for subsequent tests.
     * Deliberately does not delete the test range or its journal entries: number_journal enforces
     * append-only semantics (the trg_number_journal_append_only trigger, V1__baseline_schema
     * .sql), so a DELETE would now raise loudly instead of silently no-op'ing — either way,
     * number_range would still be unable to drop via foreign key while journal rows referencing
     * it remain. The test range stays inactive with its journal intact — PostgresTestResource
     * provides a fresh container per test run anyway.
     */
    @AfterEach
    fun tearDownDedicatedInvoiceCircle() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("UPDATE number_range SET is_active = false WHERE id = :id")
                .setParameter("id", dedicatedInvoiceRangeId).executeUpdate()
            // Loop individually instead of IN(:ids): native query list parameter expansion varies
            // across Hibernate versions; in practice, this is always exactly one range.
            originalActiveInvoiceRangeIds.forEach { id ->
                em.createNativeQuery("UPDATE number_range SET is_active = true WHERE id = :id")
                    .setParameter("id", id).executeUpdate()
            }
        }
    }

    @Test
    fun `a normally finalized invoice yields a clean report`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistScenario("N4A") }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        val report = QuarkusTransaction.requiringNew().call { reportService.reportFor(NumberRangeSubject.INVOICE) }!!

        assertTrue(report.isClean, "Befunde: ${report.findings}")
    }

    @Test
    fun `a number written directly into the invoice table without going through allocation is caught`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistScenario("N4B") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        val strayId = QuarkusTransaction.requiringNew().call { persistScenario("N4B2") }
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "UPDATE invoice SET status = :status, invoice_number = :num, invoice_number_prefix = :pfx, invoice_number_sequence = 999999 WHERE id = :id",
            )
                // InvoiceStatus persists as ordinal via InvoiceStatusConverter, not name.
                .setParameter("status", InvoiceStatus.ISSUED.ordinal)
                .setParameter("num", "N4B2-2026-08-21-99999")
                .setParameter("pfx", "N4B2")
                .setParameter("id", strayId)
                .executeUpdate()
        }

        val report = QuarkusTransaction.requiringNew().call { reportService.reportFor(NumberRangeSubject.INVOICE) }!!

        assertTrue(
            report.findings.any { it is NumberJournalFinding.InvoiceWithoutJournalEntry && it.invoiceId == strayId },
            "Befunde: ${report.findings}",
        )
    }

    @Test
    fun `sealing a period records a chain head that a matching external copy verifies clean`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistScenario("N4S1") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        val seal = QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.INVOICE, "2026") }

        val report = QuarkusTransaction.requiringNew()
            .call { reportService.reportFor(NumberRangeSubject.INVOICE, externalSeals = mapOf("2026" to seal.prevHash)) }!!

        assertTrue(report.isClean, "Befunde: ${report.findings}")
    }

    @Test
    fun `an external chain head that does not match the sealed one is a SealMismatch`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistScenario("N4S2") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.INVOICE, "2026") }

        val wrongExternalHead = "0".repeat(64)
        val report = QuarkusTransaction.requiringNew()
            .call { reportService.reportFor(NumberRangeSubject.INVOICE, externalSeals = mapOf("2026" to wrongExternalHead)) }!!

        assertTrue(
            report.findings.any { it is NumberJournalFinding.SealMismatch && it.periodKey == "2026" && it.externalChainHead == wrongExternalHead },
            "Befunde: ${report.findings}",
        )
    }

    @Test
    fun `IMP-2 - eine bereits versiegelte Periode laesst sich kein zweites Mal versiegeln`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistScenario("N4SEAL2") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }

        val first = QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.INVOICE, "2026") }

        val ex = assertThrows(PeriodAlreadySealedException::class.java) {
            QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.INVOICE, "2026") }
        }

        assertEquals("2026", ex.periodKey)
        assertEquals(
            first.prevHash, ex.chainHead,
            "exception must carry the original chain head for UI re-display",
        )

        // Verify exactly one seal exists (scoped to this test's dedicated range, not global).
        val seals = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery(
                "SELECT COUNT(*) FROM number_journal WHERE event = 'PERIOD_SEALED' AND period_key = '2026' AND number_range_id = :rangeId",
            ).setParameter("rangeId", dedicatedInvoiceRangeId).singleResult as Number
        }
        assertEquals(1L, seals.toLong(), "exactly one seal per period")
    }

    @Test
    fun `IMP-2 - ein Kreis ohne Journal laesst sich nicht versiegeln`() {
        assertThrows(NotSealableException::class.java) {
            QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.CUSTOMER, "2026") }
        }
    }

    /**
     * PostgresNumberJournalRepository.lockChain uses pg_advisory_xact_lock to serialize all
     * journal appends for a range (both allocate() via writeJournal and sealPeriod()). Without
     * this lock, concurrent readers of the same prevHash would fork the chain by writing
     * independent entries. This test deliberately races 20 allocations against a concurrent seal
     * and verifies via the real report service that the chain remains unforkd and gap-free.
     */
    @Test
    fun `IMP-5 - sealPeriod running concurrently with active allocation does not fork the chain`() {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val allocations = pool.submit<List<String>> {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                (1..20).map { n ->
                    QuarkusTransaction.requiringNew().call {
                        rangeService.allocate(
                            NumberRangeSubject.INVOICE,
                            NumberContext(date = LocalDate.of(2026, 6, 15), entityType = "INVOICE", entityId = n.toLong()),
                        ).value
                    }
                }
            }
            val seal = pool.submit<de.littletrotter.domain.compliance.NumberJournalEntry> {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                QuarkusTransaction.requiringNew().call { rangeService.sealPeriod(NumberRangeSubject.INVOICE, "2026") }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown()

            val numbers = allocations.get(20, TimeUnit.SECONDS)
            val sealEntry = seal.get(20, TimeUnit.SECONDS)

            assertEquals(20, numbers.toSet().size, "no duplicate numbers despite concurrent seal: $numbers")
            assertTrue(sealEntry.entryHash.isNotBlank())

            val report = QuarkusTransaction.requiringNew().call { reportService.reportFor(NumberRangeSubject.INVOICE) }!!
            assertTrue(report.isClean, "chain must not fork under concurrency: ${report.findings}, ${report.chainVerification}")
        } finally {
            pool.shutdown()
        }
    }

    /**
     * When a transaction aborts **after** number allocation, neither the counter nor journal
     * should advance. InvoiceLifecycleService.transition provides a real failure case: the
     * hasMixedReverseChargeAndStandard check runs immediately after number assignment and
     * throws MixedTaxCategoryException in the same @Transactional context, causing rollback
     * of both the number assignment and the status change.
     */
    @Test
    fun `IMP-5 - a rollback after number allocation leaves neither counter nor journal entry behind`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistMixedTaxScenario("N4R") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        val peekBefore = QuarkusTransaction.requiringNew().call {
            rangeService.peek(NumberRangeSubject.INVOICE, de.littletrotter.domain.reference.NumberContext(date = LocalDate.of(2026, 2, 10)))
        }

        assertThrows(de.littletrotter.domain.compliance.MixedTaxCategoryException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        }

        val peekAfter = QuarkusTransaction.requiringNew().call {
            rangeService.peek(NumberRangeSubject.INVOICE, de.littletrotter.domain.reference.NumberContext(date = LocalDate.of(2026, 2, 10)))
        }
        assertEquals(peekBefore, peekAfter, "counter must not advance through rollback")

        val journalCount = QuarkusTransaction.requiringNew().call {
            (
                em.createNativeQuery("SELECT COUNT(*) FROM number_journal WHERE event = 'ALLOCATED' AND number_range_id = :rangeId")
                    .setParameter("rangeId", dedicatedInvoiceRangeId).singleResult as Number
                ).toLong()
        }
        assertEquals(0L, journalCount, "rollback must eliminate the failed allocation journal entry")

        val reloaded = QuarkusTransaction.requiringNew().call { em.find(de.littletrotter.domain.invoice.Invoice::class.java, invoiceId) }
        assertTrue(!reloaded.invoiceNumber.isAssigned(), "invoice must not have a number after rollback")
        assertEquals(InvoiceStatus.IN_REVIEW, reloaded.status, "status change must also be rolled back")
    }

    /** Like [persistScenario], but with mixed standard and reverse-charge line items to trigger
     * hasMixedReverseChargeAndStandard() validation after number assignment. */
    private fun persistMixedTaxScenario(tag: String): Long {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "N4 GmbH"
            invoicePrefix = tag
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@n4.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Kunde GmbH"
            email = Email.ofNullable("ap@kunde.example")
            vatId = VatId("DE987654321")
        }
        em.persist(sender)
        em.persist(recipient)

        val standardRate = TaxRate().edit {
            code = "${tag}_STD"; category = "S"; name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00")); country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1); isDefault = false; isSelectable = false
        }
        val reverseChargeRate = TaxRate().edit {
            code = "${tag}_AE"; category = "AE"; name = "Reverse Charge"
            rate = TaxRatePercent(BigDecimal("0.00")); country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1); isDefault = false; isSelectable = false
            exemptionReasonCode = "VATEX-EU-AE"
        }
        em.persist(standardRate)
        em.persist(reverseChargeRate)

        val invoice = de.littletrotter.domain.invoice.Invoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // CORE.SERVICE_PERIOD_XOR enforces by default; BT-72 satisfies it.
            deliveryDate = LocalDate.now()
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung (Regelsatz)"
                quantity = BigDecimal("8")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                sortOrder = 0
            }.also { it.applyTaxRule(standardRate) },
        )
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "2"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung (Reverse Charge)"
                quantity = BigDecimal("1")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("50.00")
                sortOrder = 1
            }.also { it.applyTaxRule(reverseChargeRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        return invoice.id!!
    }

    private fun persistScenario(tag: String): Long {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "N4 GmbH"
            invoicePrefix = tag
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@n4.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "Kunde GmbH"
            email = Email.ofNullable("ap@kunde.example")
            vatId = VatId("DE987654321")
        }
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
            // Test fixture: must not appear in TaxRuleMigrationIT's seed validation.
            isSelectable = false
        }
        em.persist(taxRate)

        val invoice = de.littletrotter.domain.invoice.Invoice().edit {
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // CORE.SERVICE_PERIOD_XOR enforces by default; BT-72 satisfies it.
            deliveryDate = LocalDate.now()
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            InvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                quantity = BigDecimal("8")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                taxRateRef = taxRate
                sortOrder = 0
            },
        )
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        return invoice.id!!
    }
}
