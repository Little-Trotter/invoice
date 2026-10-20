package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceReferencesChange
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import de.littletrotter.application.invoice.port.outbound.InvoiceStatusPublisher

/**
 * The finalization gate over the FULL persistence path (real PostgreSQL, real CDI-wired
 * [InvoiceLifecycleService]): a blocked attempt must leave the DB state untouched, and an allowed
 * one must persist ISSUED. The in-memory unit tests cover the same decision logic
 * without a database; this test covers what they structurally cannot — that the block happens
 * before `invoiceRepo.save` and nothing half-finalized reaches the table.
 *
 * Stage C (BLOCK) is forced via [BlockStageProfile]: the production default OBSERVE only logs,
 * which would make every assertion in this class vacuous.
 */
private const val NEGATIVE_WAIT_MS = 1000L
private const val POLL_MS = 50L

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(BlockStageProfile::class)
class FinalizationGateIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var sendTransitionRunner: SendTransitionRunner

    @Inject
    lateinit var statusRecorder: RecordingInvoiceStatusPublisher

    @BeforeEach
    fun clearRecordedStatusChanges() = statusRecorder.published.clear()

    // The UI is told once, and only after the ISSUED status is durable.
    @Test
    fun `a committed send transition publishes exactly one status change`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistInvoice(ValidationStatus.VALID, KositOutcome.VALID, "F4GS") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        sendTransitionRunner.transitionToSent(invoiceId)

        awaitPublished(listOf(invoiceId to InvoiceStatus.ISSUED))
        assertEquals(InvoiceStatus.ISSUED, QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.status })
    }

    // A blocked transition rolls back; announcing ISSUED would show a status that was never stored.
    @Test
    fun `a blocked send transition publishes no status change`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistInvoice(ValidationStatus.INVALID, KositOutcome.INVALID, "F4GT") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        assertThrows(Exception::class.java) { sendTransitionRunner.transitionToSent(invoiceId) }

        // The push is asynchronous: give a wrongly fired event time to arrive before asserting silence.
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(emptyList<Pair<Long, InvoiceStatus>>(), statusRecorder.published.toList())
    }

    private fun awaitPublished(expected: List<Pair<Long, InvoiceStatus>>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (statusRecorder.published.toList() != expected && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertEquals(expected, statusRecorder.published.toList())
    }

    // A blocked attempt persists nothing: the invoice stays where it was.
    @Test
    fun `blocked INVALID invoice is not finalized in the database`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistInvoice(ValidationStatus.INVALID, KositOutcome.INVALID) }

        QuarkusTransaction.requiringNew().run {
            lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) // allowed — no gate on this edge
        }

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run {
                lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED)
            }
        }

        assertEquals(GateBlockReason.VALIDATION_INVALID, ex.reason)
        assertEquals(1, ex.findings.count { it.ruleId == "BR-DE-15" }, "block message must carry the findings")

        QuarkusTransaction.requiringNew().run {
            val found = em.find(Invoice::class.java, invoiceId)
            assertEquals(InvoiceStatus.IN_REVIEW, found!!.status, "a blocked attempt must not persist ISSUED")
        }
    }

    // The other half of the proof: at stage C a VALID invoice still finalizes — the gate is a
    // check, not a wall. Without this test, "everything blocks" would look identical to
    // "only INVALID blocks".
    @Test
    fun `VALID invoice finalizes through the gate on the persistence path`() {
        val invoiceId = QuarkusTransaction.requiringNew().call { persistInvoice(ValidationStatus.VALID, KositOutcome.VALID) }

        QuarkusTransaction.requiringNew().run {
            lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW)
        }

        QuarkusTransaction.requiringNew().run {
            lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED)
        }

        QuarkusTransaction.requiringNew().run {
            val found = em.find(Invoice::class.java, invoiceId)
            assertEquals(InvoiceStatus.ISSUED, found!!.status)
        }
    }

    // N9 (BR-DE-6/BR-DE-27, German KoSIT CIUS) — BT-42 over the full persistence path: this check
    // runs unconditionally, ahead of the F4 gate this class otherwise exercises, so it blocks
    // regardless of BlockStageProfile even though it isn't itself staged.
    @Test
    fun `an invoice whose seller has no telephone number cannot reach the sent state, and the exception names the field`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            persistInvoice(ValidationStatus.VALID, KositOutcome.VALID, tag = "F4GP", phone = null)
        }

        QuarkusTransaction.requiringNew().run {
            lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW)
        }

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run {
                lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED)
            }
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertEquals("CORE.SELLER_PHONE", ex.findings.single().ruleId)
        assertEquals("compliance.core.seller.phone", ex.findings.single().message, "the message key names the phone field")

        QuarkusTransaction.requiringNew().run {
            val found = em.find(Invoice::class.java, invoiceId)
            assertEquals(InvoiceStatus.IN_REVIEW, found!!.status, "a blocked attempt must not persist ISSUED")
        }
    }

    /**
     * A finalizable invoice (BT-10 set, no tax-category snapshot on the item so R5's
     * consistency check doesn't participate) plus a COMPLETED generation job whose kositReport
     * is what [InvoiceLifecycleService.latestFindings] decodes for the gate's block message.
     * The sender carries a USt-IdNr., so the profile axis resolves to "XRechnung" — seeded
     * ACTIVE, i.e. the profile axis must not be what blocks.
     */
    private fun persistInvoice(
        validationStatus: ValidationStatus,
        kositOutcome: KositOutcome,
        tag: String? = null,
        phone: String? = "+49 30 1234567",
    ): Long {
        val valid = validationStatus == ValidationStatus.VALID
        val prefix = tag ?: if (valid) "F4GV" else "F4GI" // unique column — one prefix per test
        val sender = aSenderProfile {
            name = "Sender GmbH"
            invoicePrefix = "$prefix-"
            registerCourt = "AG Musterstadt" // NOT NULL since V007 — Hibernate sends null, the DB default never applies
            email = Email.ofNullable("billing@sender.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
            this.phone = phone // N9 — BT-42; null exercises FinalizationPolicy.enforceSellerTelephoneNumber's rejection
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$prefix-100") // unique column
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
        }
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${prefix}_STD19" // unique column — the seed already owns DE_STANDARD_19
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        em.persist(taxRate)

        val invoice = anInvoice(
            senderProfile = sender,
            recipient = recipient,
            invoiceNumber = InvoiceNumber("$prefix-2026-0001"),
            invoiceDate = LocalDate.of(2026, 8, 1),
            dueDate = LocalDate.of(2026, 8, 31),
            // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
            // littletrotter.compliance-rules.blocking-rules' default, so it now enforces even at
            // this class's own OBSERVE-default complianceStage (only validation-gate.stage is
            // forced to BLOCK here via BlockStageProfile). BT-72 satisfies it cheaply, the same
            // shape used elsewhere for this exact rule (e.g. Bt19EndToEndE2EIT.buildInvoice).
            deliveryDate = LocalDate.now(),
            validationStatus = validationStatus,
            // BT-10 guard passes -- only the gate is under test
            references = InvoiceReferencesChange(buyerReference = "PO-F4-1"),
        )

        val item = anInvoiceItem(
            positionId = "1",
            category = InvoiceItemCategory.SERVICE,
            description = "Consulting",
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.HOUR,
            unitPriceNet = Money.of("100.00"),
            taxRateRef = taxRate,
            sortOrder = 0,
        )
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)

        val result = KositValidationResult(
            status = kositOutcome,
            details = if (kositOutcome == KositOutcome.INVALID) {
                listOf(ValidationMessage(severity = "ERROR", message = "Käuferreferenz fehlt", ruleId = "BR-DE-15"))
            } else {
                emptyList()
            },
        )
        em.persist(
            aGenerationJob {
                this.invoice = invoice
                jobType = "PDF_EXPORT"
                status = GenerationJobStatus.COMPLETED
                kositReport = KositReportPayload.encode(result)
            },
        )
        em.flush()

        return invoice.id!!
    }
}

/** Stage C — the gate blocks, it does not merely observe. Top-level on purpose: ArchUnit's
 *  test engine instantiates nested classes of a requested test class, and a Kotlin `object`
 *  (no public constructor) breaks its discovery. */
class BlockStageProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("littletrotter.validation-gate.stage" to "BLOCK")
}

/** Replaces the UI bus as the status publisher so the test can observe what would be pushed. */
@Alternative
@Priority(1)
@ApplicationScoped
class RecordingInvoiceStatusPublisher : InvoiceStatusPublisher {
    val published = CopyOnWriteArrayList<Pair<Long, InvoiceStatus>>()

    override fun publishInvoiceStatusChanged(invoiceId: Long, status: InvoiceStatus) {
        published.add(invoiceId to status)
    }
}
