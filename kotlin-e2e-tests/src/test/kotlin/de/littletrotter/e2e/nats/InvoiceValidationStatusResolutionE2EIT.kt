package de.littletrotter.e2e.nats

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.LlmAnalysisFeatureConfig
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.ValidationSettingsGate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.ValkeyTestResource
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.LlmCheckAvailability
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.e2e.support.buildInvoiceDetailView
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The production bug this suite reproduces end to end: an invoice with
 * TWO [GenerationJob]s kept showing "Validierung läuft" ([ValidationStatus.PENDING]) forever, even
 * though both jobs had long since completed. Root cause (see
 * [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler
 * .onDocumentGeneratedUpdateValidationStatus]'s own KDoc for the full diagnosis): the newest job's
 * own KoSIT report was null (the documented degrade path —
 * [GenerationJobResultConsumer.resolveKositReport] returns null on an expired/missing Valkey ref)
 * and the old "decode-before-checking-latest" handler bailed out before ever falling back to the
 * older job's perfectly good report.
 *
 * Drives the real chain exactly the way production does: two COMPLETED results delivered through
 * the real, CDI-injected [GenerationJobResultConsumer] (the same bean the NATS adapter calls once
 * a message is off the wire, per [PrintJobListViewLiveUpdateE2EIT]'s own convention for this
 * package) — the older job's result carries a `kositReportRef` resolved through a REAL
 * [PayloadExchange] ([ValkeyTestResource], stacked alongside [PostgresTestResource] here since
 * this is the one scenario in this package that needs a genuine KoSIT-report round trip rather
 * than the graceful "Valkey unreachable" `null` degrade every other suite here relies on); the
 * newer job's result carries no ref at all, reproducing the degrade path directly.
 *
 * The 2-second contract itself is asserted against the [Invoice.validationStatus] column
 * directly — [EntityManager.clear] before every re-read, the exact pattern
 * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumerStaleSessionIT]
 * (`validationStatusOf`) establishes for this codebase's own documented Hibernate
 * first-level-cache hazard — a `find()` without clearing the
 * session first can keep handing back the SAME stale, pre-resolution entity for the life of this
 * test's `EntityManager`. This is what [DocumentGeneratedReactionHandler
 * .onDocumentGeneratedUpdateValidationStatus] actually writes to; a still-buggy handler leaves
 * this column PENDING forever, correctly failing the await loop below regardless of any UI
 * mechanism.
 *
 * [InvoiceDetailView] is additionally constructed and driven here (seeded the same way
 * [InvoiceDetailView.beforeEnter] would populate it) purely to confirm the UI-facing read model
 * agrees: once the column above is confirmed resolved, a plain, fresh
 * [InvoiceDetailQueryPort.findInvoiceDetailDto] read — the exact same call InvoiceDetailView's own poll
 * listener/UiEventBus callback perform on every real refresh, and the exact same
 * [InvoiceDetailDto.validationStatus] field InvoiceListView's row chip renders "Validierung
 * läuft"/etc. from — must also show the resolved value, not PENDING. This is deliberately NOT
 * itself part of the 2-second budget: firing Vaadin's own `PollEvent` synthetically in a tight
 * loop to race a real CDI async-executor dispatch is non-deterministic under heavy load
 * (no second, guaranteed signal within the harness to fall back
 * on, unlike a real browser's fixed-interval poll timer) — the backend column is the actual thing
 * [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler] resolves, and the fix's
 * correctness does not depend on exactly when Vaadin's own live-update machinery happens to next
 * poll.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceValidationStatusResolutionE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var generationJobResultConsumer: GenerationJobResultConsumer

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    @Inject
    lateinit var invoiceReceiptQueryPort: InvoiceReceiptQueryPort

    @Inject
    lateinit var attachReceiptsUseCase: AttachReceiptsUseCase

    @Inject
    lateinit var invoiceDetailQueryPort: InvoiceDetailQueryPort

    @Inject
    lateinit var recipientQueryPort: RecipientQueryPort

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobQueryService: GenerationJobQueryService

    @Inject
    lateinit var documentJobPublisher: DocumentJobPublisher

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var uiEventBus: UiEventBus

    @Inject
    lateinit var invoiceFormModuleRegistry: InvoiceFormModuleRegistry

    @Inject
    lateinit var llmAnalysisFeatureConfig: LlmAnalysisFeatureConfig

    @Inject
    lateinit var validationSettingsGate: ValidationSettingsGate

    @Inject
    lateinit var payloadExchange: PayloadExchange

    private val mapper = jacksonObjectMapper()

    private data class Fixture(val invoiceId: Long, val olderJobId: Long, val newerJobId: Long)

    /**
     * A finalized invoice (required for [de.littletrotter.domain.invoice.InvoiceNumber
     * .isAssigned] to hold, same precondition [GenerationJobService.markCompleted]'s own
     * `DocumentGenerated` firing needs) with [Invoice.validationStatus] set directly to
     * [ValidationStatus.PENDING] and TWO [GenerationJob] rows persisted directly in DELEGATED —
     * the state a real worker delegation left them in just before finishing. Direct
     * `EntityManager` persistence for fixture parts that don't themselves exercise business logic
     * (same convention [PrintJobListViewLiveUpdateE2EIT]'s own fixture KDoc documents) — the
     * PENDING write itself is normally [de.littletrotter.application.invoice
     * .DocumentJobPublishTransaction.publishTransactional]'s job, not the concern of this suite.
     */
    private fun buildFixture(tag: String): Fixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
                phone = "+49 30 1234567"
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().apply {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        // finding-fixes — CORE.SERVICE_PERIOD_XOR ships in
        // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
        // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }

        val (olderJobId, newerJobId) = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.validationStatus = ValidationStatus.PENDING

            val older = GenerationJob().apply {
                this.invoice = invoice
                this.jobType = "PDF_EXPORT"
                this.status = GenerationJobStatus.DELEGATED
                this.engine = DocumentEngine.MUSTANG
                this.createdAt = OffsetDateTime.now().minusMinutes(5)
            }
            em.persist(older)
            val newer = GenerationJob().apply {
                this.invoice = invoice
                this.jobType = "PDF_EXPORT"
                this.status = GenerationJobStatus.DELEGATED
                this.engine = DocumentEngine.MUSTANG
                this.createdAt = OffsetDateTime.now()
            }
            em.persist(newer)
            em.flush()
            older.id!! to newer.id!!
        }

        return Fixture(invoiceId, olderJobId, newerJobId)
    }

    // Every collaborator here is the same real, CDI-injected, Postgres-backed bean the view's
    // Versionen-tab sub-panels receive directly — this suite confirms the UI-facing read
    // model against the real KoSIT-report resolution chain, so none of it is mocked. The cockpit
    // hero/Positionen tab this view also requires are not themselves exercised by this
    // suite; buildInvoiceDetailView defaults their remaining leaf collaborators to mocks, the
    // same idiom DetailViewTestHarness (kotlin-invoice) uses.
    private fun newView(): InvoiceDetailView = buildInvoiceDetailView(
        taxRateQueries = taxRateQueries,
        generationJobQueryService = generationJobQueryService,
        invoiceDetailQueryPort = invoiceDetailQueryPort,
        recipientQueryPort = recipientQueryPort,
        invoiceReceiptQueryPort = invoiceReceiptQueryPort,
        attachReceiptsUseCase = attachReceiptsUseCase,
        uiEventBus = uiEventBus,
        invoiceFormModuleRegistry = invoiceFormModuleRegistry,
        generationJobCreationService = generationJobCreationService,
        documentJobPublisher = documentJobPublisher,
        documentStore = documentStore,
        llmCheckAvailability = LlmCheckAvailability(validationSettingsGate, llmAnalysisFeatureConfig),
        validationSettingsGate = validationSettingsGate,
    )

    private fun setPrivateField(view: InvoiceDetailView, name: String, value: Any?) {
        val f = InvoiceDetailView::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(view, value)
    }

    /** The view keeps the opened invoice in its private `state` ([InvoiceDetailState]). */
    private fun detailState(view: InvoiceDetailView): Any {
        val f = InvoiceDetailView::class.java.getDeclaredField("state")
        f.isAccessible = true
        return checkNotNull(f.get(view))
    }

    private fun heldDto(view: InvoiceDetailView): InvoiceDetailDto? {
        val state = detailState(view)
        return state.javaClass.getMethod("getDto").invoke(state) as InvoiceDetailDto?
    }

    private fun holdDto(view: InvoiceDetailView, dto: InvoiceDetailDto?) {
        val state = detailState(view)
        state.javaClass.getMethod("setDto", InvoiceDetailDto::class.java).invoke(state, dto)
    }

    /** Seeds `invoiceId`/`invoice` directly (both are `internal`/`private` fields, invisible to
     *  this module's Kotlin compiler — see this class's own KDoc rationale for reflection here,
     *  same as [PrintJobListViewLiveUpdateE2EIT]'s) — the exact same state
     *  [InvoiceDetailView.beforeEnter] would populate, without needing to fabricate a working
     *  Vaadin `BeforeEnterEvent` (its own `location`/`routeParameters` are non-trivial to
     *  construct outside a real router round trip). */
    private fun seedInvoice(view: InvoiceDetailView, invoiceId: Long) {
        setPrivateField(view, "invoiceId", invoiceId)
        holdDto(view, invoiceDetailQueryPort.findInvoiceDetailDto(invoiceId))
    }

    /** Reads the invoice's [ValidationStatus] straight off [InvoiceDetailView]'s own held
     *  `invoice` field — the exact same DTO both InvoiceDetailView's cockpit hero and
     *  InvoiceListView's row chip would render "Validierung läuft"/etc. from — not a fresh,
     *  test-driven query. */
    private fun currentValidationStatusInView(view: InvoiceDetailView): ValidationStatus? {
        val dto: InvoiceDetailDto? = heldDto(view)
        return dto?.validationStatus?.let { ValidationStatus.entries.getOrNull(it) }
    }

    /** The ground truth
     *  [de.littletrotter.application.invoice.DocumentGeneratedReactionHandler.onDocumentGeneratedUpdateValidationStatus]
     *  actually writes. `em.clear()` before every read — without it, this test's own
     *  `EntityManager` (shared across the whole method, including [buildFixture]'s own reads via
     *  the same injected instance) keeps handing back the SAME first-level-cache-managed [Invoice]
     *  instance for its entire lifetime, exactly the Hibernate first-level-cache hazard
     *  [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumerStaleSessionIT]'s own
     *  `validationStatusOf` helper works around, never observing the async handler's own,
     *  separately-committed transaction. */
    private fun currentValidationStatusInDb(invoiceId: Long): ValidationStatus =
        QuarkusTransaction.requiringNew().call<ValidationStatus> {
            em.clear()
            em.find(Invoice::class.java, invoiceId).validationStatus
        }

    @Test
    fun `PO bug 30 08 2026 - two jobs, newest without a decodable report, still resolve the invoice out of PENDING within 2 seconds`() {
        val fixture = buildFixture("E2EVS")

        // Producer side: the OLDER job's KoSIT report travels via a real Valkey, keyed by
        // its own jobId — the report the newer job's own (undecodable) result must be able to fall
        // back to.
        val kositReportKey = "lt:kosit:${fixture.olderJobId}"
        val kositReportJson = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.INVALID, summary = "1 validation error(s)"),
        )
        payloadExchange.put(kositReportKey, kositReportJson, 60)

        assertEquals(
            ValidationStatus.PENDING, currentValidationStatusInDb(fixture.invoiceId),
            "fixture precondition: the invoice must start out PENDING (\"Validierung läuft\")",
        )

        // The real chain: GenerationJobResultConsumer -> GenerationJobService.markCompleted ->
        // DocumentGenerated (async) -> DocumentGeneratedReactionHandler resolves the invoice.
        // Deliver the NEWER job's result FIRST — exactly the ordering the bug report reproduced
        // (the most-recently-created job also happens to be the one whose report never made it
        // back from the worker).
        val newerPayload = mapper.writeValueAsString(
            mapOf(
                "id" to fixture.newerJobId.toString(),
                "status" to "COMPLETED",
                "resultRefs" to mapOf("pdf" to "s3://bucket/${fixture.newerJobId}.pdf"),
            ),
        )
        generationJobResultConsumer.consume(newerPayload)

        val olderPayload = mapper.writeValueAsString(
            mapOf(
                "id" to fixture.olderJobId.toString(),
                "status" to "COMPLETED",
                "resultRefs" to mapOf("pdf" to "s3://bucket/${fixture.olderJobId}.pdf"),
                "kositReportRef" to mapOf("key" to kositReportKey),
            ),
        )
        generationJobResultConsumer.consume(olderPayload)
        val start = System.currentTimeMillis()

        // PO's explicit 2-second contract: DocumentGeneratedReactionHandler's own async
        // resolution must land within this window regardless of any UI polling cadence.
        val deadlineMs = start + 2_000
        var resolved: ValidationStatus = ValidationStatus.PENDING
        while (System.currentTimeMillis() < deadlineMs) {
            resolved = currentValidationStatusInDb(fixture.invoiceId)
            if (resolved != ValidationStatus.PENDING) break
            Thread.sleep(20)
        }
        val elapsedMs = System.currentTimeMillis() - start

        assertNotEquals(
            ValidationStatus.PENDING, resolved,
            "PO bug (report 30.08.2026): the invoice must resolve out of PENDING " +
                "(\"Validierung läuft\") within 2 seconds of both jobs completing — it had not after ${elapsedMs}ms",
        )
        assertTrue(elapsedMs <= 2_000, "PO's 2-second contract was violated: took ${elapsedMs}ms")
        // The older job's decodable INVALID report is the only usable outcome between the two
        // completed jobs — the fix must fall back to it rather than leaving the invoice
        // UNAVAILABLE (which would also be "no longer PENDING" but would silently discard a
        // perfectly good result).
        assertEquals(ValidationStatus.INVALID, resolved)

        // UI-facing confirmation (not itself time-boxed, see this class's own KDoc): the exact
        // same read model InvoiceDetailView/InvoiceListView actually render from must agree —
        // "Validierung läuft" is provably gone from every surface in the app for this invoice.
        MockVaadin.setup()
        try {
            val view = newView()
            seedInvoice(view, fixture.invoiceId)
            UI.getCurrent()!!.add(view)
            assertEquals(ValidationStatus.INVALID, currentValidationStatusInView(view))
        } finally {
            MockVaadin.tearDown()
        }
    }
}
