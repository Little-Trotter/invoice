package de.littletrotter.e2e.nats

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.PollEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.NavigationTrigger
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinService
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
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.e2e.support.buildInvoiceDetailView
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.PollTickTestSeam
import de.littletrotter.ui.view.invoice.LlmCheckAvailability
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The real-infrastructure end-to-end counterpart of the finding: a **DRAFT** invoice's print job
 * reaching COMPLETED must update [InvoiceDetailView]'s own held read model
 * ([InvoiceDetailDto.latestGenerationJob]) promptly. [GenerationJobService.markCompleted]'s own
 * emission guard (`invoice.status.isIssued || trigger == SEND`) makes `DocumentGenerated`
 * structurally impossible to fire for a DRAFT invoice's job — no push ever reaches
 * [de.littletrotter.application.invoice.GenerationJobUiPushHandler], so this suite instead
 * drives the poll fallback directly (same technique [PrintJobListViewLiveUpdateE2EIT]
 * established for the sibling queue view) to prove the row updates **without relying on a
 * push at all** — the poll fallback is what covers a dropped/never-fired push, and per the
 * "which mechanism is authoritative" decision (see [de.littletrotter.ui.view.invoice
 * .InvoiceExportPanel.refresh]'s own KDoc) it must work completely independently of whether the
 * push ever arrives.
 *
 * A real, CDI-injected [InvoiceDetailView] (not a Karibu-mocked stand-in), a real Postgres
 * (fixture + [GenerationJobResultConsumer.consume]'s own persistence), and Flow's own public
 * `PollEvent` mechanism (headless Karibu has no client to send a live browser's poll tick, so this
 * suite fires it itself, exactly like [PrintJobListViewLiveUpdateE2EIT]) — no mocked collaborators
 * anywhere in the chain under test.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceDetailViewDraftJobLiveUpdateE2EIT {

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

    private data class Fixture(val invoiceId: Long, val jobId: Long)

    /**
     * A **DRAFT** invoice (deliberately never transitioned to IN_REVIEW/ISSUED — this is the exact
     * "ordinary print job on a draft" flow [de.littletrotter.ui.view.invoice.InvoiceExportPanel
     * .schedulePrintJob] drives, MANUAL trigger, `GenerationJob.issued = false`) with one
     * [GenerationJob] persisted directly in DELEGATED — the state a real worker delegation left it
     * in just before finishing. Direct `EntityManager` persistence for fixture parts that don't
     * themselves exercise business logic, same convention [PrintJobListViewLiveUpdateE2EIT]'s own
     * fixture KDoc documents.
     */
    private fun buildFixture(tag: String): Fixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
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
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().edit {
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
        // Deliberately NOT transitioned to IN_REVIEW/ISSUED -- this invoice stays DRAFT for the
        // whole test, which is the entire point of this scenario.

        val jobId = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val job = aGenerationJob {
                this.invoice = invoice
                this.jobType = "PRINT_JOB"
                this.status = GenerationJobStatus.DELEGATED
                this.createdAt = OffsetDateTime.now()
                // A review-time/draft job never counts as the issued document; mirrors
                // exactly what GenerationJobService.createJob would have set for this invoice's
                // status at creation (see that field's own KDoc).
                this.issued = false
            }
            em.persist(job)
            em.flush()
            job.id!!
        }
        return Fixture(invoiceId, jobId)
    }

    // Every collaborator here is the same real, CDI-injected, Postgres-backed bean
    // InvoicePrintControls/InvoiceGenerationJobsPanel receive directly —
    // this suite drives the poll fallback through the real generation-job/export chain, so none
    // of it is mocked. The cockpit hero/Positionen tab this view also requires are not
    // themselves exercised by this suite; buildInvoiceDetailView defaults their remaining
    // leaf collaborators to mocks, the same idiom DetailViewTestHarness (kotlin-invoice) uses.
    private fun newView(): InvoiceDetailView = buildInvoiceDetailView(
        taxRateQueries = taxRateQueries,
        generationJobQueryPort = generationJobQueryService,
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
    )

    /** Same construction [de.littletrotter.ui.view.invoice.InvoiceDetailView.beforeEnter] needs —
     *  a real [BeforeEnterEvent], not a bare mock, since [InvoiceDetailView.beforeEnter] actually
     *  reads `routeParameters`/`location.queryParameters` off it. Same technique
     *  `InvoiceDetailViewLiveUpdateTest` (kotlin-invoice) already established for exactly this
     *  purpose. */
    private fun beforeEnterEvent(id: Long): BeforeEnterEvent {
        val router = VaadinService.getCurrent().router
        val ui = requireNotNull(UI.getCurrent())
        return BeforeEnterEvent(
            router, NavigationTrigger.PROGRAMMATIC, Location("invoice/$id"),
            InvoiceDetailView::class.java, RouteParameters("id", id.toString()), ui, emptyList(),
        )
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

    /** Reads the job status straight off [InvoiceDetailView]'s own held `invoice` DTO field — the
     *  exact same [InvoiceDetailDto.latestGenerationJob] the versions panel and cockpit both
     *  render from, not a fresh, test-driven query. */
    private fun latestJobStatusInView(view: InvoiceDetailView): String? {
        val dto: InvoiceDetailDto? = heldDto(view)
        return dto?.latestGenerationJob?.status
    }

    @Test
    fun `L24 - a DRAFT invoice's job reaching COMPLETED updates InvoiceDetailView's read model via the poll fallback alone, no push involved`() {
        val fixture = buildFixture("E2EL24")

        MockVaadin.setup()
        try {
            val view = newView()
            val ui = UI.getCurrent()!!
            ui.add(view) // onAttach -- registers on the real UiEventBus AND arms the poll fallback;
            // this test never asserts on the push leg at all (see this class's own KDoc).
            view.beforeEnter(beforeEnterEvent(fixture.invoiceId))

            assertEquals(
                GenerationJobStatus.DELEGATED.name, latestJobStatusInView(view),
                "fixture precondition: the job must start out DELEGATED (\"Weitergeleitet\") in the view",
            )

            // Prime the poll fallback's own active/inactive tracking (sawActiveGenerationJobs),
            // mirroring shouldRefreshOnPollTick's transition contract, while the job is genuinely
            // still active -- a real browser would already have sent several such ticks during
            // delegation. See shouldRefreshOnPollTick's own KDoc / PrintJobListViewLiveUpdateE2EIT
            // for why an unprimed first tick would otherwise correctly stay quiet.
            ComponentUtil.fireEvent(ui, PollEvent(ui, true))
            // Poll ticks load off the session lock on an executor thread: wait until the priming
            // tick has recorded the still-active job state before the job completes.
            PollTickTestSeam.awaitTriggeredTicks { MockVaadin.runUIQueue(true) }

            // The real chain: GenerationJobResultConsumer -> GenerationJobService.markCompleted.
            // markCompleted's own emission guard means DocumentGenerated never fires
            // for this DRAFT invoice at all -- this test does not depend on it firing (see this
            // class's own KDoc), only on the DB write markCompleted itself always performs.
            val payload = """{"id":"${fixture.jobId}","status":"COMPLETED","resultRefs":{"pdf":"s3://bucket/${fixture.jobId}.pdf"}}"""
            generationJobResultConsumer.consume(payload)
            val start = System.currentTimeMillis()

            // Deliberately only PollEvents from here on -- no UiEventBus publish is awaited or
            // relied upon; a still-broken poll fallback (or a push-only fix) would time this loop
            // out rather than pass it.
            val deadlineMs = start + 2_000
            var sawCompleted = false
            while (System.currentTimeMillis() < deadlineMs) {
                ComponentUtil.fireEvent(ui, PollEvent(ui, true))
                MockVaadin.runUIQueue(true) // the tick's apply phase waits in the access queue while this thread holds the lock
                if (latestJobStatusInView(view) == GenerationJobStatus.COMPLETED.name) {
                    sawCompleted = true
                    break
                }
                Thread.sleep(20)
            }
            val elapsedMs = System.currentTimeMillis() - start

            assertTrue(
                sawCompleted,
                "L24: a DRAFT invoice's job reaching COMPLETED must update InvoiceDetailView's " +
                    "read model via the poll fallback alone within 2 seconds -- it had not after ${elapsedMs}ms",
            )
        } finally {
            MockVaadin.tearDown()
        }
    }
}
