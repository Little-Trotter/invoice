package de.littletrotter.e2e.nats

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.PollEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.invoice.PrintJobOverviewQueryService
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
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
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.Money
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.view.invoice.PollTickTestSeam
import de.littletrotter.ui.view.invoice.PrintJobListView
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * PO report 30.08.2026, reworked for E2 — live updates of the "E-Rechnungs-Aufträge" queue view
 * ([PrintJobListView], Karibu-driven) against the real, CDI-injected, Postgres-backed services. Unlike
 * this package's other suites ([NatsRoundTripE2EIT] etc.) nothing here goes over the NATS wire; the
 * worker-side leg is driven through [GenerationJobResultConsumer.consume] or by persisting the job
 * row directly (a job created by a separate worker process is, from the view's side, just a new row).
 *
 * Since E2 push and poll only recompute the "N new" badge; the grid reloads on entry and on the
 * Search button. This suite therefore pins two things: a job that appears or finishes outside this
 * JVM's event bus reaches the badge within the poll contract without any navigation or Search, and
 * a Search afterwards loads the job into the grid and resets the badge.
 *
 * The view's push chain has two legs (see [PrintJobListView.onAttach]'s own KDoc): the fast path via
 * [UiEventBus] (asynchronous, not what this suite pins) and the guaranteed poll fallback, driven here
 * either by [ComponentUtil.fireEvent]/[PollEvent] (headless Karibu has no client to send the browser's
 * tick) or by the server-owned tick of `PollingViewLifecycle`, which needs no client at all.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PrintJobListViewLiveUpdateE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var generationJobResultConsumer: GenerationJobResultConsumer

    @Inject
    lateinit var printJobOverviewQueryService: PrintJobOverviewQueryService

    @Inject
    lateinit var contactDataFacade: RecipientQueryPort

    @Inject
    lateinit var documentStore: DocumentStore

    // The real CDI-singleton bus — the SAME instance GenerationJobUiPushHandler publishes onto in
    // production; not a test double.
    @Inject
    lateinit var uiEventBus: UiEventBus

    /**
     * A finalized invoice (so [de.littletrotter.domain.invoice.InvoiceNumber.isAssigned] holds —
     * required for [GenerationJobResultHandler.markCompleted]'s own `DocumentGenerated` firing), with NO
     * [GenerationJob] of its own yet. Split out from [buildFixture] so
     * [`F4 a job that finishes its whole lifetime between attaching and the first poll tick still
     * lands`][PrintJobListViewLiveUpdateE2EIT] can attach the view against a real, empty result set
     * before creating the job itself.
     */
    private fun buildInvoice(tag: String): Long {
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
        // finding-fixes (2026-08-31) — CORE.SERVICE_PERIOD_XOR ships in
        // littletrotter.compliance-rules.blocking-rules' default and enforces even at this
        // suite's own OBSERVE-default complianceStage; BT-72 satisfies it cheaply.
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = LocalDate.now() }
        // Finalize — assigns the invoice number the DocumentGenerated event (and the queue view's
        // own Rechnung column) both need.
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        return invoiceId
    }

    /** Persists one [GenerationJob] directly in [status] against an already-finalized invoice —
     *  same "direct EntityManager persistence for fixture parts that don't themselves exercise
     *  business logic" convention [de.littletrotter.e2e.profile.NewFieldEndToEndE2EIT]'s own
     *  fixture KDoc documents; the state machine's own transitions are exercised elsewhere. */
    private fun createJob(invoiceId: Long, status: GenerationJobStatus): Long =
        QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val job = GenerationJob().apply {
                this.invoice = invoice
                this.jobType = "PDF_EXPORT"
                this.status = status
                this.engine = DocumentEngine.MUSTANG
                this.createdAt = OffsetDateTime.now()
            }
            em.persist(job)
            em.flush()
            job.id!!
        }

    /** The recipient row [buildInvoice] created for [invoiceId] — the unique-per-suite filter
     *  value that keeps other suites' jobs out of the badge count. */
    private fun recipientOf(invoiceId: Long): RecipientSummaryDto {
        val recipientId = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.recipient!!.id!! }
        return contactDataFacade.findRecipientById(recipientId)!!
    }

    private fun newView(): PrintJobListView =
        PrintJobListView(
            printJobOverviewQueryService, contactDataFacade, documentStore, uiEventBus,
            time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        )

    private fun <T : Any> privateField(view: PrintJobListView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(PrintJobListView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    /** Sets a star-projected [HasValue]'s value via its own public setter, found by plain
     *  reflection: narrowing a [ComboBox]`<*>` found this way back to its real, `internal` item
     *  type would be an unchecked generic cast, so this never attempts that cast at all. */
    private fun setFieldValue(field: com.vaadin.flow.component.HasValue<*, *>, value: Any?) {
        field.javaClass.getMethod("setValue", Any::class.java).invoke(field, value)
    }

    /**
     * Reads the given job's status straight off [PrintJobListView]'s own private `dayNodes`/
     * `treeDataProvider` state — the exact same state the real TreeGrid renders from, not a fresh,
     * test-driven query — via plain reflection rather than the types themselves
     * (`PrintJobTreeNode`/`DayNode`/`JobNode`) since those are `internal` to kotlin-invoice and not
     * visible from this module (Kotlin's `internal` is a compile-time check only; the generated
     * members are ordinary public JVM methods, so reflection crosses that boundary cleanly).
     * `null` while the job's day hasn't been (re)loaded into `dayNodes` yet, or the job isn't
     * among that day's fetched children.
     */
    private fun currentStatusInView(view: PrintJobListView, jobId: Long): GenerationJobStatus? {
        val dayNodes: List<*> = privateField(view, "dayNodes", List::class)
        val provider: Any = privateField(view, "treeDataProvider", Any::class)
        val fetchChildren = provider.javaClass.methods.first { it.name == "fetchChildren" && it.parameterCount == 1 }
        for (day in dayNodes) {
            val query = HierarchicalQuery<Any, Any>(0, 50, emptyList(), null, null, day)
            val children = (fetchChildren.invoke(provider, query) as java.util.stream.Stream<*>).toList()
            for (child in children.filterNotNull()) {
                if (child.javaClass.simpleName != "JobNode") continue
                val row = requireNotNull(child.javaClass.getMethod("getRow").invoke(child))
                val rowJobId = row.javaClass.getMethod("getJobId").invoke(row) as Long
                if (rowJobId == jobId) {
                    return row.javaClass.getMethod("getStatus").invoke(row) as GenerationJobStatus
                }
            }
        }
        return null
    }

    /** The badge's text while it is visible, `null` while hidden. */
    private fun visibleBadgeText(view: PrintJobListView): String? =
        privateField(view, "newCountBadge", Span::class).takeIf { it.isVisible }?.text

    /** Waits up to [timeoutMs] for the badge to show; fires a client poll on every round when [firePoll]. */
    private fun awaitBadge(view: PrintJobListView, ui: UI, firePoll: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (firePoll) ComponentUtil.fireEvent(ui, PollEvent(ui, true))
            MockVaadin.runUIQueue(true) // a server tick's UI.access only runs while the session lock is released or drained
            if (visibleBadgeText(view) != null) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Attaches a fresh view, loads the grid (entry) and stages the recipient filter without searching. */
    private fun attachViewFilteredTo(recipient: RecipientSummaryDto): PrintJobListView {
        val view = newView()
        UI.getCurrent()!!.add(view) // onAttach: registers on the real UiEventBus and arms the poll fallback
        view.beforeEnter(mock())
        setFieldValue(privateField(view, "customerFilter", ComboBox::class), recipient)
        return view
    }

    @Test
    fun `E2-10 a job created outside this JVM shows in the badge within the poll contract, Search then loads it and resets the badge`() {
        val invoiceId = buildInvoice("E2ELU")

        MockVaadin.setup()
        try {
            val ui = UI.getCurrent()!!
            val view = attachViewFilteredTo(recipientOf(invoiceId))
            assertNull(visibleBadgeText(view), "fixture precondition: nothing new for this recipient yet")
            // The grid's own root snapshot from the entry load: "the grid does not move" means this
            // list is not replaced by a live update. (Children of an already loaded day are fetched
            // lazily from the database, so a live child fetch is no evidence of a grid reload.)
            val rootsAtEntry: List<*> = privateField(view, "dayNodes", List::class)

            // A separate worker process registers a job for the filtered recipient; this JVM's event
            // bus never hears about it, only the poll can see it. The clock starts once the row
            // exists: the 2-second contract is about how fast it becomes visible.
            val jobId = createJob(invoiceId, GenerationJobStatus.DELEGATED)
            val start = System.currentTimeMillis()

            val shown = awaitBadge(view, ui, firePoll = true, timeoutMs = 2_000)
            val elapsedMs = System.currentTimeMillis() - start

            assertTrue(shown, "the badge must show the new job within the 2-second poll contract, it had not after ${elapsedMs}ms")
            assertTrue(visibleBadgeText(view)!!.startsWith("1"), "exactly one new job, got: ${visibleBadgeText(view)}")
            assertSame(rootsAtEntry, privateField(view, "dayNodes", List::class), "the poll must not have reloaded the grid")

            // The job finishes; the badge keeps its count and the grid still does not move.
            generationJobResultConsumer.consume(
                """{"id":"$jobId","status":"COMPLETED","resultRefs":{"pdf":"s3://bucket/$jobId.pdf"}}""",
            )
            ComponentUtil.fireEvent(ui, PollEvent(ui, true))
            PollTickTestSeam.awaitTriggeredTicks { MockVaadin.runUIQueue(true) } // the tick has run before asserting it changed nothing
            assertSame(rootsAtEntry, privateField(view, "dayNodes", List::class), "a finished job must not repaint the grid without a Search")

            privateField(view, "searchButton", Button::class).click()

            assertEquals(GenerationJobStatus.COMPLETED, currentStatusInView(view, jobId), "Search loads the finished job")
            assertNull(visibleBadgeText(view), "Search resets the badge")
        } finally {
            MockVaadin.tearDown()
        }
    }

    /**
     * QOD-3 (PO fix 2026-09-02, "Chrome shows no live updates, Firefox does") — the server-owned tick
     * of `PollingViewLifecycle` (its own executor, delivered via `UI.access`/`@Push`, independent of the
     * client timer Chrome throttles in background tabs) alone carries a new job into the badge. This
     * test never fires a [PollEvent].
     */
    @Test
    fun `QOD-3 - the server-driven tick alone (no client poll fired) carries a new job into the badge`() {
        val invoiceId = buildInvoice("E2ESRV")

        MockVaadin.setup()
        try {
            val ui = UI.getCurrent()!!
            val view = attachViewFilteredTo(recipientOf(invoiceId))
            createJob(invoiceId, GenerationJobStatus.DELEGATED)

            // The first server tick fires one poll interval (2_000ms, internal to kotlin-invoice and
            // therefore inlined) after attaching; the margin covers scheduling jitter.
            val shown = awaitBadge(view, ui, firePoll = false, timeoutMs = 4_000)

            assertTrue(shown, "the server-driven tick never carried the new job into the badge within 4s")
        } finally {
            MockVaadin.tearDown()
        }
    }

    /**
     * F4 (fix-job-panel-flicker-and-vanishing-entry, PO report 04.09.2026) — a job whose whole life
     * (created and already COMPLETED) falls between attaching this view and its very first poll tick
     * must still be picked up: the flag `sawActiveJobsInResultSet` is seeded true on attach, so the
     * first tick always acts once. Deliberately exactly ONE unprimed tick.
     */
    @Test
    fun `F4 a job that finishes its whole lifetime between attaching and the first poll tick still reaches the badge`() {
        val invoiceId = buildInvoice("E2EF4")

        MockVaadin.setup()
        try {
            val ui = UI.getCurrent()!!
            val view = attachViewFilteredTo(recipientOf(invoiceId))
            createJob(invoiceId, GenerationJobStatus.COMPLETED)

            ComponentUtil.fireEvent(ui, PollEvent(ui, true))
            PollTickTestSeam.awaitTriggeredTicks { MockVaadin.runUIQueue(true) }

            assertTrue(
                visibleBadgeText(view)?.startsWith("1") == true,
                "F4: the first poll tick after attach must recount the badge even though the job was never seen active",
            )
        } finally {
            MockVaadin.tearDown()
        }
    }
}
