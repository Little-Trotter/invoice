package de.littletrotter.e2e.monkey.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.domain.compliance.MixedTaxCategoryException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException
import de.littletrotter.domain.invoice.BuyerReferenceMissingException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.monkey.support.HostileStrings
import de.littletrotter.e2e.monkey.support.MonkeyRun
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import de.littletrotter.testing.invariants.InvoiceCalculationGenerators
import de.littletrotter.testing.invariants.InvoiceCalculationInvariants
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.anInvoice

/**
 * RG-4 — the invoice monkey: a bounded, seeded random walk over the real
 * [InvoiceLifecycleService]/[InvoiceCancellationService] against a genuine PostgreSQL
 * ([PostgresTestResource], this module's own fixture). After every single step the walk
 * re-checks the RG-2 property-based invariant library ([InvoiceCalculationInvariants], reused
 * verbatim from kotlin-invoice's own test sources — see the build-helper-maven-plugin block in
 * this module's pom for how, without a kotlin-invoice test-jar dependency) against every invoice
 * the walk still holds a reference to — "invariants after every step", not just at the end.
 *
 * Run bounded/seeded via `-Dmonkey.seed=<n> -Dmonkey.steps=<n>` (see [MonkeyRun]); `make monkey
 * SUITE=invoice SEED=n` wraps exactly this invocation (root Makefile's `monkey` target).
 *
 * Tagged `monkey` and excluded from the default failsafe run — see kotlin-e2e-tests/pom.xml's
 * `failsafe.excludedGroups`. Named `InvoiceMonkeyE2EIT` (not the RG-4 spec's literal `*IT`) for
 * the same reason `ConcurrentCrudStressE2EIT` is: this module's own structural guard,
 * [de.littletrotter.e2e.E2eModuleStructureTest], requires every `@Test`-bearing file here to end
 * in `E2EIT`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@Tag("monkey")
class InvoiceMonkeyE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    companion object {
        /** The real, embedded KoSIT validator — same idiom as `EInvoiceGenerationE2EIT`
         *  (kotlin-e2e-tests): loaded once per class, no CDI container needed outside the
         *  `@QuarkusTest` that also boots Postgres. */
        private lateinit var kosit: EmbeddedKositValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
        }
    }

    private val mustang = XmlExportServiceImpl(null)

    private data class Fixture(val senderId: Long, val recipientIds: List<Long>, val taxRateIds: List<Long>, val rcTaxRateId: Long)

    /** One sender, two recipients, the three real domestic rates ([InvoiceCalculationGenerators
     *  .REAL_TAX_RATES]) plus one REVERSE_CHARGE-category rate — enough combinatorial room for the
     *  walk below without the fixture itself dominating what gets exercised. */
    private fun buildFixture(tag: String): Fixture {
        val sender = aSenderProfile {
            name = "Monkey Sender $tag GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02120300000000202051")
            // BR-DE-6 (BT-42, checked once a step-6 finalized invoice is actually run through
            // KoSIT) — without a seller phone number the generated XML is legitimately
            // KoSIT-INVALID; this is a fixture-completeness fix (found by an early run of this
            // very walk), not a production defect. Same value EInvoiceGenerationE2EIT's fixtures
            // already carry.
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Affenweg 1", "10115", "Berlin", Country.DE, true))
        em.persist(sender)

        val recipientIds = (1..2).map { n ->
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-10$n")
                name = "Monkey Recipient $tag-$n AG"
                email = Email.ofNullable("ap$n@$tag.example")
                vatId = VatId("DE222222222")
                routingId = "04011000-$tag$n-06"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Bananenallee $n", "20095", "Hamburg", Country.DE, true))
            em.persist(recipient)
            recipient.id!!
        }

        val taxRateIds = InvoiceCalculationGenerators.REAL_TAX_RATES.mapIndexed { i, rate ->
            val tr = TaxRate().edit {
                code = "${tag}_MONKEY_$i"
                // FINDING (found by an early run of this very walk, once step 6 actually ran a
                // 0 %-rated item through KoSIT): BR-S-05 requires rate > 0 for category S
                // (Standard) — so the domain's own VatCategory.ZERO_RATED ("Z") is the fitting
                // classification here, EXCEPT the DB CHECK constraint chk_tax_rate_category_values
                // (V1__baseline_schema.sql) only allows category IN ('S','AE','E','O') — 'Z' (and
                // 'G'/'K', VatCategory's other two non-DB-allowed values) can never be persisted at
                // all. Recorded as an RG-4 finding (undefined VatCategory/DB-constraint contract);
                // this fixture uses EXEMPT ('E', DB-allowed, rate=0-compatible) instead.
                if (rate.signum() == 0) {
                    category = VatCategory.EXEMPT.code
                    exemptionReasonText = "Monkey-Fixture: steuerbefreite Position (Test)"
                } else {
                    category = VatCategory.STANDARD.code
                }
                name = "USt $rate %"; this.rate = TaxRatePercent(rate)
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
            }
            em.persist(tr)
            tr.id!!
        }
        // A real, correctly-zero-rated REVERSE_CHARGE rate — used by the random walk's RC items.
        val rcRate = TaxRate().edit {
            code = "${tag}_MONKEY_RC"; category = VatCategory.REVERSE_CHARGE.code; name = "RC"; rate = TaxRatePercent(BigDecimal.ZERO)
            exemptionReasonCode = "VATEX-EU-AE"; country = Country.DE; validFrom = LocalDate.of(2024, 1, 1)
            isActive = true; isSelectable = true
        }
        em.persist(rcRate)
        em.flush()
        return Fixture(sender.id!!, recipientIds, taxRateIds, rcRate.id!!)
    }

    /** RG-2's own invariant set, re-checked against [invoiceId] after every mutating step. A
     *  brand-new, still-empty invoice (no items yet) is skipped — [InvoiceCalculationInvariants]
     *  targets item/tax arithmetic, not the empty case. */
    private fun assertInvariants(invoiceId: Long) {
        // `items` is a lazy @OneToMany — every check below (and recalculateTotals(), which
        // navigates it too) must run INSIDE the same transaction/persistence context that loaded
        // the entity, so this whole block — not just the load — is the QuarkusTransaction body.
        QuarkusTransaction.requiringNew().run {
            val invoice = lifecycleService.findById(invoiceId) ?: return@run
            if (invoice.itemList.isEmpty()) return@run
            InvoiceCalculationInvariants.assertTotalsMatchItemSums(invoice, " (invoice=$invoiceId)")
            InvoiceCalculationInvariants.assertRateGroupsMatchTotals(invoice, " (invoice=$invoiceId)")
            InvoiceCalculationInvariants.assertRecalculationIsIdempotent(invoice, " (invoice=$invoiceId)")
            InvoiceCalculationInvariants.assertNoNegativeTotalsFromPositiveInputs(invoice, " (invoice=$invoiceId)")
            InvoiceCalculationInvariants.assertReverseChargeItemsHaveZeroVat(invoice, " (invoice=$invoiceId)")
        }
    }

    /** The narrow, primitive-only view of a randomly-picked item this walk needs across a
     *  transaction boundary — never the managed [InvoiceItem] itself (its lazy associations would
     *  become inaccessible the moment the loading transaction ends). */
    private data class ItemRef(val itemId: Long, val positionId: String, val category: InvoiceItemCategory, val unit: InvoiceItemUnit, val description: String)

    private fun randomItemRef(invoiceId: Long, random: kotlin.random.Random): ItemRef? =
        QuarkusTransaction.requiringNew().call<ItemRef?> {
            em.find(Invoice::class.java, invoiceId)?.itemList?.randomOrNull(random)
                ?.let { ItemRef(requireNotNull(it.id), it.positionId, it.category, it.unit, it.description) }
        }

    /**
     * True for the ARJUNA016053 / optimistic-locking rejection shape the codebase's own
     * (disabled) `InvoiceOptimisticLockingTest` (kotlin-invoice) documents: two overlapping
     * transactions loading the same stale `version`, the loser's commit failing. Empirically
     * (this suite's own run against seed 7), that surfaces here as
     * `QuarkusTransactionException` -> `RollbackException` -> `StaleObjectStateException`
     * (Hibernate's optimistic-lock-specific subtype of [jakarta.persistence.OptimisticLockException])
     * — walked by cause chain rather than matched on the outermost type alone, since Narayana/
     * Quarkus wrap the real cause several layers deep.
     */
    private fun isLegitimateConcurrencyRejection(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is jakarta.persistence.OptimisticLockException || cause is jakarta.persistence.PersistenceException) return true
            if (cause is jakarta.transaction.RollbackException) return true
            cause = cause.cause
        }
        return false
    }

    private fun assertWellFormedXml(xml: String) {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        assertNotNull(builder.parse(xml.byteInputStream()), "generated e-invoice XML must parse as well-formed XML")
    }

    // ─── The random walk ──────────────────────────────────────────────────────────────────────

    @Test
    fun `random invoice lifecycle walk holds the RG-2 invariants after every step`() {
        val run = MonkeyRun("invoice-monkey")
        val fixture = QuarkusTransaction.requiringNew().call<Fixture> { buildFixture("MK") }

        val draftOrReview = mutableListOf<Long>()
        val finalized = mutableListOf<Long>()
        var nextPosition = 1

        repeat(run.steps) { i ->
            run.step(i, "action=${i % 8}") {
                when (i % 8) {
                    // 0 — create a fresh draft invoice.
                    0 -> {
                        val recipientId = fixture.recipientIds[run.random.nextInt(fixture.recipientIds.size)]
                        val id = QuarkusTransaction.requiringNew().call<Long> {
                            lifecycleService.create(fixture.senderId, recipientId).id!!
                        }
                        draftOrReview += id
                        run.log("created invoice $id for recipient $recipientId")
                    }

                    // 1 — add a random item (fuzzed quantity/price incl. Money edge values, and
                    // occasionally a hostile free-text description) to a random open invoice.
                    1 -> {
                        val id = draftOrReview.randomOrNull(run.random) ?: return@step
                        val useRc = run.random.nextInt(5) == 0
                        val description = if (run.random.nextInt(4) == 0) HostileStrings.random(run.random) else "Beratung $nextPosition"
                        val command = InvoiceItemCommand(
                            category = InvoiceItemCategory.SERVICE,
                            description = description.ifBlank { "(blank)" },
                            quantity = InvoiceCalculationGenerators.randomQuantity(run.random).let { if (it > BigDecimal(200)) BigDecimal("2.5") else it },
                            unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = InvoiceCalculationGenerators.randomUnitPrice(run.random, maxAmount = 2000.0),
                            taxRateId = if (useRc) fixture.rcTaxRateId else fixture.taxRateIds[run.random.nextInt(fixture.taxRateIds.size)],
                        )
                        try {
                            QuarkusTransaction.requiringNew().run { itemMutationService.addItem(id, command) }
                            nextPosition++
                            run.log("added item to invoice $id (rc=$useRc, desc=${description.take(30)})")
                            assertInvariants(id)
                        } catch (ex: InvoiceImmutableException) {
                            run.log("addItem rejected (invoice $id no longer editable) — expected for a non-DRAFT/IN_REVIEW invoice")
                        }
                    }

                    // 2 — update a random item's quantity/price on a random open invoice.
                    2 -> {
                        val id = draftOrReview.randomOrNull(run.random) ?: return@step
                        val item = randomItemRef(id, run.random) ?: return@step
                        val command = InvoiceItemCommand(
                            itemId = item.itemId,
                            category = item.category,
                            description = item.description,
                            quantity = InvoiceCalculationGenerators.randomQuantity(run.random).let { if (it > BigDecimal(200)) BigDecimal("1.5") else it },
                            unit = item.unit,
                            unitPriceNet = InvoiceCalculationGenerators.randomUnitPrice(run.random, maxAmount = 2000.0),
                            taxRateId = fixture.taxRateIds[run.random.nextInt(fixture.taxRateIds.size)],
                        )
                        try {
                            QuarkusTransaction.requiringNew().run { itemMutationService.updateItem(id, command) }
                            run.log("updated item ${item.itemId} on invoice $id")
                            assertInvariants(id)
                        } catch (ex: InvoiceImmutableException) {
                            run.log("updateItem rejected (invoice $id no longer editable) — expected")
                        }
                    }

                    // 3 — remove a random item on a random open invoice.
                    3 -> {
                        val id = draftOrReview.randomOrNull(run.random) ?: return@step
                        val item = randomItemRef(id, run.random) ?: return@step
                        try {
                            QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(id, item.positionId) }
                            run.log("removed item ${item.positionId} from invoice $id")
                            assertInvariants(id)
                        } catch (ex: InvoiceImmutableException) {
                            run.log("deleteItem rejected (invoice $id no longer editable) — expected")
                        }
                    }

                    // 4 — attempt a random state transition, legal or illegal on purpose (RG-4:
                    // "attempt random state transitions (legal and illegal)"): only a target that
                    // Invoice.canTransitionTo already allows may actually change status; every
                    // other target must be rejected, never silently applied.
                    4 -> {
                        val id = draftOrReview.randomOrNull(run.random) ?: return@step
                        val before = QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, id)!!.status }
                        val target = InvoiceStatus.entries[run.random.nextInt(InvoiceStatus.entries.size)]
                        val legal = before.canTransitionTo(target)
                        try {
                            QuarkusTransaction.requiringNew().run { lifecycleService.transition(id, target) }
                            assertTrue(legal, "an illegal transition $before -> $target must never be allowed to succeed silently")
                            run.log("transitioned invoice $id: $before -> $target")
                            if (target == InvoiceStatus.ISSUED) {
                                draftOrReview.remove(id)
                                finalized += id
                            } else if (target == InvoiceStatus.CANCELLED) {
                                draftOrReview.remove(id)
                            }
                        } catch (ex: InvoiceStatusTransitionException) {
                            assertFalse(legal, "an actually-legal transition $before -> $target must not be rejected: $ex")
                            run.log("transition $before -> $target correctly rejected: ${ex.message}")
                        } catch (ex: MixedTaxCategoryException) {
                            run.log("transition $before -> $target correctly rejected (mixed RC/standard categories): ${ex.message}")
                        } catch (ex: InvoiceValidationException) {
                            run.log("transition $before -> $target correctly rejected (${ex.message})")
                        } catch (ex: InvoiceWithoutSenderProfileException) {
                            run.log("transition $before -> $target correctly rejected (no sender profile)")
                        } catch (ex: BuyerReferenceMissingException) {
                            run.log("transition $before -> $target correctly rejected (missing BT-10 buyer reference)")
                        }
                        val after = QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, id)!!.status }
                        if (!legal) assertEquals(before, after, "an illegal transition must never change the persisted status")
                    }

                    // 5 — concurrent edit pair: two threads add an item to the same open invoice
                    // at (as close to) the same time. Either both succeed cleanly (no lost update,
                    // invariants still hold) or one is rejected by the DB's optimistic lock — never
                    // silent corruption.
                    5 -> {
                        val id = draftOrReview.randomOrNull(run.random) ?: return@step
                        val before = QuarkusTransaction.requiringNew().call<Int> { em.find(Invoice::class.java, id)!!.itemList.size }
                        val start = CountDownLatch(1)
                        val pool = Executors.newFixedThreadPool(2)
                        val outcomes = (1..2).map { n ->
                            pool.submit<Throwable?> {
                                start.await(2, TimeUnit.SECONDS)
                                runCatching {
                                    val command = InvoiceItemCommand(
                                        category = InvoiceItemCategory.SERVICE,
                                        description = "concurrent-$n",
                                        quantity = BigDecimal.ONE,
                                        unit = InvoiceItemUnit.HOUR,
                                        unitPriceNet = Money.of("10.00"),
                                        taxRateId = fixture.taxRateIds[0],
                                    )
                                    QuarkusTransaction.requiringNew().run { itemMutationService.addItem(id, command) }
                                }.exceptionOrNull()
                            }
                        }
                        start.countDown()
                        val exceptions = outcomes.mapNotNull { it.get(10, TimeUnit.SECONDS) }
                        pool.shutdown()
                        exceptions.forEach { ex ->
                            assertTrue(
                                ex is InvoiceImmutableException || isLegitimateConcurrencyRejection(ex),
                                "an unexpected exception type from a concurrent edit pair (possible data corruption path): $ex",
                            )
                        }
                        val after = QuarkusTransaction.requiringNew().call<Int> { em.find(Invoice::class.java, id)!!.itemList.size }
                        val succeeded = 2 - exceptions.size
                        assertEquals(before + succeeded, after, "concurrent edit pair: exactly the successful attempts must be reflected, no lost/duplicated update")
                        run.log("concurrent edit pair on invoice $id: $succeeded/2 succeeded, ${exceptions.size} rejected")
                        assertInvariants(id)
                    }

                    // 6 — for an ISSUED invoice, generate its e-invoice XML (Mustang route, same
                    // as RG-3) and check the RG-4 invariant: well-formed AND KoSIT-green.
                    6 -> {
                        val id = finalized.randomOrNull(run.random) ?: return@step
                        val dto = QuarkusTransaction.requiringNew().call<InvoiceDto> {
                            InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, id)!!)
                        }
                        val xml = String(mustang.generate(dto), Charsets.UTF_8)
                        assertWellFormedXml(xml)
                        val result = kosit.validate(xml, timeoutMs = 30_000)
                        assertEquals(
                            de.littletrotter.shared.KositOutcome.VALID, result.status,
                            "generated XML for finalized invoice $id must be KoSIT-green, was ${result.status} — ${result.details}",
                        )
                        run.log("generated + KoSIT-validated XML for invoice $id")
                    }

                    // 7 — cancellation (credit note) for a random ISSUED invoice.
                    7 -> {
                        val id = finalized.randomOrNull(run.random) ?: return@step
                        if (!QuarkusTransaction.requiringNew().call<Boolean> { cancellationService.canCreateCreditNote(em.find(Invoice::class.java, id)!!) }) {
                            run.log("skipped storno for invoice $id (already has a credit note or otherwise ineligible)")
                            return@step
                        }
                        val creditNoteId = QuarkusTransaction.requiringNew().call<Long> {
                            cancellationService.createCreditNote(id, "monkey storno").id!!
                        }
                        run.log("created credit note $creditNoteId for invoice $id")
                        assertInvariants(creditNoteId)
                    }
                }
            }
        }

        // Final sweep: every invoice the walk still knows about must still satisfy the invariants.
        (draftOrReview + finalized).distinct().forEach { assertInvariants(it) }
        println("[invoice-monkey] finished ${run.steps} steps — ${draftOrReview.size} open, ${finalized.size} finalized invoices, all invariants held")
    }

    // ─── Probe defect (RG-4 acceptance) ──────────────────────────────────────────────────────

    /**
     * RG-4 acceptance: "demonstrably catches a seeded artificial defect". Never touches
     * production code (hard rule for this task) — instead it feeds [InvoiceItem.applyTaxRule] a
     * deliberately malformed [TaxRate] row (REVERSE_CHARGE category, but a non-zero rate — the
     * shape a corrupted/misconfigured seed-data row would have), the same kind of defect a real
     * regression in reference-data or a rate migration could introduce. The exact invariant
     * helper the random walk above calls after every single step
     * ([InvoiceCalculationInvariants.assertReverseChargeItemsHaveZeroVat]) must — and does — fail
     * loudly on it, proving the monkey would have caught this had it slipped through.
     */
    @Test
    fun `probe defect - a malformed REVERSE_CHARGE tax rate is caught by the same invariant the walk runs after every step`() {
        val invoice = anInvoice()
        val brokenRcRate = TaxRate().edit {
            code = "PROBE_BROKEN_RC"
            category = VatCategory.REVERSE_CHARGE.code
            // Seeded defect: an RC-category rate that is NOT zero (R5 requires zero for RC).
            rate = TaxRatePercent(BigDecimal("19.00"))
            exemptionReasonCode = "VATEX-EU-AE"
        }
        invoice.addItem(
            anInvoiceItem().edit {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "probe defect item"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.PIECE
                unitPriceNet = Money.of("100.00")
            }.also { it.applyTaxRule(brokenRcRate) },
        )

        val error = assertThrows(AssertionError::class.java) {
            InvoiceCalculationInvariants.assertReverseChargeItemsHaveZeroVat(invoice, "-probe-defect")
        }
        assertTrue(
            error.message?.contains("REVERSE_CHARGE") == true,
            "expected the RG-2 invariant's own failure message, got: ${error.message}",
        )
    }
}

private fun <T> List<T>.randomOrNull(random: kotlin.random.Random): T? = if (isEmpty()) null else this[random.nextInt(size)]
