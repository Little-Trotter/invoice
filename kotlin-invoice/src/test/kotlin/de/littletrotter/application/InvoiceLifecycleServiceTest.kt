package de.littletrotter.application

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.invoice.buyerSnapshot
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.isReadOnly
import de.littletrotter.domain.invoice.sellerSnapshot
import de.littletrotter.domain.value.TaxRatePercent

import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceLifecycleServiceTestBuilder
import de.littletrotter.application.invoice.InvoiceNumberService
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCreated
import de.littletrotter.domain.exception.RecipientNotFoundException
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.ValidationProfile
import de.littletrotter.domain.compliance.ValidationProfileState
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.anArticleTaxVariant
import de.littletrotter.fixture.edit
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.shared.tax.VatCategory
import jakarta.enterprise.event.Event
import jakarta.enterprise.event.NotificationOptions
import jakarta.enterprise.util.TypeLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.shared.country.Country
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.test.FIXED_TODAY

// The real system clock, not a fixed instant: this suite's assertions expect dueDate/invoiceDate/
// the generated number's date segment to track real "today", matching this service's behavior
// before it read TimeSource (a plain LocalDate.now()/OffsetDateTime.now() call).
private val TEST_TIME = ClockTimeSource(java.time.Clock.systemUTC())

class InvoiceLifecycleServiceTest {

    // The number is assigned already at draft stage (InvoiceLifecycleService.create()), mirroring the cancellation/credit-note precedent below. Gaplessness comes from abolishing hard-delete (see InvoiceStateMachine.isIssued's KDoc), not from deferring the draw.
    @Test
    fun `N5 - create draws a number immediately`() {
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val invoiceRepo = InMemoryInvoiceRepository()
        val numberService = InvoiceNumberService(freshNumberRangeService())
        val eventBus = RecordingEventBus()

        // prepare recipient with addresses
        val customer = recipient(id = 2L)
        customer.designateBillingAddress(Address.forRecipient(customer, Address.AddressType.BILLING, "Empfängerstr. 1", "10115", "Berlin", Country.DE, true))
        customer.addDeliveryAddress(Address.forRecipient(customer, Address.AddressType.DELIVERY, "Lieferstr. 2", "10117", "Berlin", Country.DE, false))
        val recipientRepoWithAddress = recipientRepoWith(customer)

        val invoice = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepoWithAddress, numberService, eventBus).build().create(1L, 2L)

        assertTrue(invoice.invoiceNumber.isAssigned(), "ein Entwurf traegt ab N5 sofort eine Nummer")
        assertEquals(1, eventBus.fired.filterIsInstance<InvoiceCreated>().size)
        assertEquals("Musterstr. 1", invoice.billingAddress?.street)
        assertEquals("Lieferstr. 2", invoice.deliveryAddress?.street)
    }

    // N5 — the actual assignment now happens at create(), via the real NumberRangeService (not
    // the legacy allocator adapter), with a GAPLESS/YEAR_MONTH_DAY sequence like the one V931
    // seeds. The ISSUED transition no longer changes the number — it was already there.
    @Test
    fun `N5 - create assigns a number from the real NumberRangeService, ISSUED leaves it unchanged`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "DEVEDGE"))
        val recipientRepo = recipientRepoWith(recipient(id = 1L))
        val numberRangeService = de.littletrotter.application.reference.NumberRangeService(
            InMemoryNumberRangeRepository(invoiceGaplessRange()),
            InMemoryNumberCounterRepository(),
            de.littletrotter.application.reference.RecordingNumberJournalRepository(),
            TEST_TIME,
        )
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo, senderRepo = senderRepo, recipientRepo = recipientRepo,
            invoiceNumberService = InvoiceNumberService(numberRangeService), domainEventBus = RecordingEventBus(),
            gateStageConfig = "OBSERVE", complianceStageConfig = "OBSERVE",
        ).build()

        val first = service.create(1L, 1L)
        val today = LocalDate.now()
        val expected = "DEVEDGE-%04d-%02d-%02d-00001".format(today.year, today.monthValue, today.dayOfMonth)
        assertEquals(expected, first.invoiceNumber.value, "create() must already carry the formatted number")
        assertEquals("DEVEDGE", first.invoiceNumberPrefix)
        assertEquals(1L, first.invoiceNumberSequence)

        first.edit { references.buyerReference = "PROJ-1" }; first.addItem(populatedItem()); invoiceRepo.save(first)
        service.transition(first.id!!, InvoiceStatus.IN_REVIEW)
        val finalized = service.transition(first.id!!, InvoiceStatus.ISSUED)
        assertEquals(expected, finalized.invoiceNumber.value, "ISSUED must not redraw the number")

        // a second invoice from the same sender draws the next number in the same sequence,
        // already at create() — no ISSUED transition needed to observe the increment.
        val second = service.create(1L, 1L)
        assertEquals(2L, second.invoiceNumberSequence)
    }

    @Test
    fun `transition never re-assigns a number an invoice already carries`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.edit { references.buyerReference = "PROJ-1" }
        invoice.addItem(populatedItem())
        // Bestand vor N2 (see assignInvoiceNumberIfNeeded's own KDoc): an already-numbered draft
        // must never be renumbered at its ISSUED transition.
        invoice.edit { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2020-0007") }
        invoiceRepo.save(invoice)

        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        val finalized = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals("RE-2020-0007", finalized.invoiceNumber.value)
    }

    @Test
    fun `N5 - assignIfNeeded is idempotent at create-time too`() {
        val numberService = InvoiceNumberService(freshNumberRangeService())
        val assigner = de.littletrotter.application.invoice.InvoiceNumberAssignment(numberService)
        val invoice = Invoice().edit {
            id = 1L
            senderProfile = sender(id = 1L, prefix = "RE-")
        }

        assigner.assignIfNeeded(invoice)
        val firstDraw = invoice.invoiceNumber.value
        assigner.assignIfNeeded(invoice)

        assertEquals(firstDraw, invoice.invoiceNumber.value, "a second call must not consume another sequence number")
    }

    @Test
    fun `transition throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            service.transition(999L, InvoiceStatus.IN_REVIEW)
        }
    }

    @Test
    fun `recalculate throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            service.recalculate(999L)
        }
    }

    @Test
    fun `N2-10 - ST-3 - a credit note draws its number when it is created, not at an ISSUED edge`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipient(id = 1L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val cancellation = de.littletrotter.application.invoice.InvoiceCancellationService(
            invoiceRepo,
            invoiceRepo,
            DomainEventDispatcher(RecordingEventBus(), null),
            de.littletrotter.application.invoice.InvoiceNumberAssigner { it.edit { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("ST-2026-0001") } },
            TEST_TIME,
        )

        val original = service.create(1L, 1L)
        original.edit { references.buyerReference = "PROJ-1" }; original.addItem(populatedItem()); invoiceRepo.save(original)
        service.transition(original.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(original.id!!, InvoiceStatus.ISSUED)

        val creditNote = cancellation.createCreditNote(original.id!!, reason = "Testgrund")
        // ST-3 — the cancellation document is born terminal, so it never reaches an ISSUED edge; if it did
        // not draw its number here it would never draw one at all.
        assertTrue(creditNote.invoiceNumber.isAssigned(), "die Stornorechnung ist sofort nummeriert")
        assertEquals(InvoiceStatus.CANCELLED, creditNote.status)
        assertEquals(InvoiceStatus.CANCELLED, invoiceRepo.findByIdWithItems(original.id!!)!!.status)
    }

    @Test
    fun `create throws when sender not found`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()

        assertThrows(SenderProfileNotFoundException::class.java) {
            service.create(99L, 1L)
        }
    }

    /**
     * IMP-6 — covers the second place where a missing `senderProfile` surfaces: not at
     * creation (there the id is known, `SenderProfileNotFoundException` fits), but when
     * finalizing an invoice whose `senderProfile` relation is simply `null` — reachable only
     * via existing data, since `Invoice.validate()` requires a sender on every regular path
     * there. The exception must name the invoice, not a made-up sender id.
     */
    @Test
    fun `IMP-6 - finalizing an invoice without a sender profile relation names the invoice, not a fake sender id`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val invoice = Invoice().edit {
            id = 42L
            senderProfile = null
            recipient = recipient(id = 1L)
            references.buyerReference = "PROJ-TEST"
            addItem(populatedItem())
            transitionTo(InvoiceStatus.IN_REVIEW)
            validationStatus = ValidationStatus.VALID
        }
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException::class.java) {
            service.transition(42L, InvoiceStatus.ISSUED)
        }
        assertTrue(ex.message!!.contains("42"), "die Meldung muss die Rechnung nennen, nicht eine erfundene Anbieter-id: ${ex.message}")
        assertTrue(!ex.message!!.contains("-1"), "kein Sentinel-Wert in der Meldung: ${ex.message}")
    }

    @Test
    fun `create throws when recipient not found`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()

        assertThrows(RecipientNotFoundException::class.java) {
            service.create(1L, 99L)
        }
    }

    // ── Anbieter-Standardzahlungsbedingung — inherited at create(), same shape as BT-10 above ──

    private fun paymentTerm(id: Long, name: String) = aPaymentTerm { this.id = id; changeName(germanFallbackLanguage(), name) }

    private fun paymentTermRepoWith(default: PaymentTerm?): PaymentTermRepository = object : PaymentTermRepository {
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection) = listOfNotNull(default)
        override fun count(filterText: String?): Long = if (default != null) 1L else 0L
        override fun countInactive(filterText: String?): Long = 0L
        override fun findDefault(): PaymentTerm? = default
        override fun findDefaults(): List<PaymentTerm> = listOfNotNull(default)
        override fun releaseDefaultsExcept(keepId: Long?) = Unit
        override fun findById(id: Long): PaymentTerm? = default?.takeIf { it.id == id }
        override fun save(term: PaymentTerm): PaymentTerm = term
        override fun delete(id: Long) {}
        override fun isInUse(id: Long): Boolean = false
        override fun usageCounts(): Map<Long, Long> = emptyMap()
    }

    @Test
    fun `create uses the sender's own default payment term when configured`() {
        val sofort = paymentTerm(1L, "Sofort fällig")
        val net14 = paymentTerm(2L, "14 Tage netto")
        val senderProfile = sender(id = 1L, prefix = "RE-").edit { defaultPaymentTerm = sofort }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = InMemoryInvoiceRepository(),
            senderRepo = senderRepoWith(senderProfile),
            recipientRepo = recipientRepoWith(recipient(id = 1L)),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net14),
        ).build()

        val invoice = service.create(1L, 1L)

        assertEquals(sofort, invoice.paymentTerm)
    }

    @Test
    fun `create falls back to the global default payment term when the sender has none configured`() {
        val net14 = paymentTerm(2L, "14 Tage netto")
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = InMemoryInvoiceRepository(),
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(recipient(id = 1L)),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net14),
        ).build()

        val invoice = service.create(1L, 1L)

        assertEquals(net14, invoice.paymentTerm)
    }

    // dueDate at creation must be computed from the sender's own resolved payment term, not from
    // Invoice.paymentTermsDays' class-default (14), which would otherwise silently disagree with
    // the payment term actually attached (invisible with the seeded default term, also 14 days).
    // Pinned here at the unit level with a sender default term whose dueInDays (30) differs from
    // the class default.
    @Test
    fun `create derives dueDate from the sender's own resolved payment term, not the class default`() {
        val net30 = paymentTerm(1L, "30 Tage netto").edit { dueInDays = 30 }
        val net14 = paymentTerm(2L, "14 Tage netto")
        val senderProfile = sender(id = 1L, prefix = "RE-").edit { defaultPaymentTerm = net30 }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = InMemoryInvoiceRepository(),
            senderRepo = senderRepoWith(senderProfile),
            recipientRepo = recipientRepoWith(recipient(id = 1L)),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net14),
        ).build()

        val invoice = service.create(1L, 1L)

        assertEquals(net30, invoice.paymentTerm)
        assertEquals(LocalDate.now().plusDays(30), invoice.dueDate)
    }

    // Same derivation, global-default-term path: the resolved term is the global
    // PaymentTerm.isDefault row (no sender default configured), not the sender's own; dueDate
    // must still follow whichever term actually got attached.
    @Test
    fun `create derives dueDate from the global default payment term when the sender has none configured`() {
        val net45 = paymentTerm(2L, "45 Tage netto").edit { dueInDays = 45 }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = InMemoryInvoiceRepository(),
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(recipient(id = 1L)),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net45),
        ).build()

        val invoice = service.create(1L, 1L)

        assertEquals(net45, invoice.paymentTerm)
        assertEquals(LocalDate.now().plusDays(45), invoice.dueDate)
    }

    // The one case Invoice.paymentTermsDays' class default (14) is still the right fallback: no
    // sender default AND no global default row resolve at all, so no PaymentTerm ever gets attached.
    @Test
    fun `create falls back to the class-default paymentTermsDays when no payment term resolves at all`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = InMemoryInvoiceRepository(),
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(recipient(id = 1L)),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(null),
        ).build()

        val invoice = service.create(1L, 1L)

        assertNull(invoice.paymentTerm)
        assertEquals(LocalDate.now().plusDays(invoice.paymentTermsDays.toLong()), invoice.dueDate)
    }

    // ── BT-10 default + party snapshots at creation ────────────────────

    @Test
    fun `P2-T01 - create defaults buyerReference from the recipient's routingId and freezes party snapshots`() {
        val customer = recipient(id = 2L).edit { routingId = "991-01484-64" }
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(customer), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        val invoice = service.create(1L, 2L)

        assertEquals("991-01484-64", invoice.references.buyerReference)
        assertEquals("991-01484-64", invoice.buyerSnapshot()?.buyerReference)
        assertEquals("Acme GmbH", invoice.sellerSnapshot()?.name)
        assertEquals("Kunde GmbH", invoice.buyerSnapshot()?.name)
    }

    @Test
    fun `P2-T02 - a later change to the recipient's routingId does not change the already-created invoice's buyerReference`() {
        val customer = recipient(id = 2L).edit { routingId = "991-01484-64" }
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(customer), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        val invoice = service.create(1L, 2L)
        customer.edit { routingId = "999-99999-99" }

        assertEquals("991-01484-64", invoice.references.buyerReference, "buyerReference must not follow a later routingId change")
        assertEquals("991-01484-64", invoice.buyerSnapshot()?.buyerReference)
    }

    // ── Stammdaten erneut übernehmen ────────────────────────────────────

    @Test
    fun `P2-T04-T05 - refreshPartySnapshots replaces the snapshot from current master data and fires an audit event`() {
        val customer = recipient(id = 2L)
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(customer)
        val eventBus = RecordingEventBus()
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), eventBus).build()

        val invoice = service.create(1L, 2L)
        customer.edit { this.name = "Renamed AG" }

        val preview = service.previewPartySnapshotRefresh(invoice.id!!)
        assertTrue(preview.hasChanges)

        val refreshed = service.refreshPartySnapshots(invoice.id!!)

        assertEquals("Renamed AG", refreshed.buyerSnapshot()?.name)
        assertEquals(1, eventBus.fired.filterIsInstance<de.littletrotter.domain.event.PartySnapshotsRefreshed>().size)
    }

    @Test
    fun `P2-T06 - refreshPartySnapshots is blocked on a finalized invoice`() {
        val customer = recipient(id = 2L)
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(customer)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 2L)
        invoice.forceStatusForTest(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            service.refreshPartySnapshots(invoice.id!!)
        }
    }

    @Test
    fun `refreshPartySnapshots rejects an invoice whose senderProfile was already cleared`() {
        // requireNotNull(invoice.senderProfile) guards a state updateHeader itself can legally
        // produce (senderProfileId = null is a valid, nullable command field, same as recipientId
        // — see "updateHeader treats assigning a senderProfile as a change..." elsewhere in this
        // file) — refreshPartySnapshots needs both parties' live master data to build a snapshot
        // from, so this defensive guard is a real, reachable state, not dead code.
        val customer = recipient(id = 2L)
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(customer)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 2L)
        invoice.edit { senderProfile = null }
        invoiceRepo.save(invoice)

        assertThrows(IllegalArgumentException::class.java) {
            service.refreshPartySnapshots(invoice.id!!)
        }
    }

    @Test
    fun `refreshPartySnapshots rejects an invoice whose recipient was already cleared`() {
        val customer = recipient(id = 2L)
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(customer)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 2L)
        invoice.edit { recipient = null }
        invoiceRepo.save(invoice)

        assertThrows(IllegalArgumentException::class.java) {
            service.refreshPartySnapshots(invoice.id!!)
        }
    }

    @Test
    fun `refreshPartySnapshots throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            service.refreshPartySnapshots(999L)
        }
    }

    @Test
    fun `previewPartySnapshotRefresh throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            service.previewPartySnapshotRefresh(999L)
        }
    }

    // finding-fixes (2026-08-31) — save() (and updateHeader() below) must not require any items:
    // both run on every draft header save, including the very first edit right after create(),
    // when the invoice legitimately has zero positions yet. Before this fix, Invoice.validate()
    // threw NO_ITEMS here, making the header itself unsavable until at least one position existed
    // — impossible for a UI that adds items only from within an already-saved invoice.
    @Test
    fun `save succeeds for a freshly created draft with zero items`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        assertTrue(invoice.itemList.isEmpty(), "Vorbedingung: ein frischer Entwurf traegt noch keine Positionen")

        val saved = service.save(invoice)

        assertEquals(InvoiceStatus.DRAFT, saved.status)
        assertTrue(saved.itemList.isEmpty())
    }

    @Test
    fun `save fires accumulated domain events`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val eventBus = RecordingEventBus()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            eventBus,
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        eventBus.fired.clear()

        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        service.save(invoice)

        assertTrue(eventBus.fired.isNotEmpty())
    }

    @Test
    fun `recalculate fixes stale totals even when the invoice fails other validation rules`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(
            de.littletrotter.domain.invoice.InvoiceItem().edit {
                positionId = "1"; description = "Beratung"
                quantity = java.math.BigDecimal("2")
                unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("50"))
                taxRateRef = de.littletrotter.domain.reference.TaxRate().edit { rate = TaxRatePercent(java.math.BigDecimal("19")) }
                // Deliberately wrong -- what recalculate() must correct back to 100.00/19.00/119.00.
                netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("1"))
                vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("1"))
                grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("2"))
            }
        )
        // Unrelated validation problem: validate() must fail for a reason that has nothing
        // to do with the totals themselves.
        invoice.edit { paymentTermsDays = -1 }
        invoiceRepo.save(invoice)
        assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) { invoice.validate() }

        val recalculated = service.recalculate(invoice.id!!)

        assertEquals(0, java.math.BigDecimal("100.00").compareTo(recalculated.totals.netAmount.amount))
        assertEquals(0, java.math.BigDecimal("19.00").compareTo(recalculated.totals.vatAmount.amount))
        assertEquals(0, java.math.BigDecimal("119.00").compareTo(recalculated.totals.grossAmount.amount))
        // The unrelated validation problem is untouched -- recalculate() only fixes totals.
        assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) { recalculated.validate() }
    }

    @Test
    fun `recalculate refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) { service.recalculate(invoice.id!!) }
    }

    @Test
    fun `addItem refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            itemMutationService.addItem(invoice.id!!, minimalItemCommand())
        }
    }

    @Test
    fun `deleteItemByPosition refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            itemMutationService.deleteItemByPosition(invoice.id!!, "1")
        }
    }

    @Test
    fun `T14 - deleting the only position of an IN_REVIEW invoice leaves zero totals and ISSUED is then refused with NO_ITEMS`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.edit { references.buyerReference = "PROJ-1" }
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)

        val afterDelete = itemMutationService.deleteItemByPosition(invoice.id!!, "1")

        assertTrue(afterDelete.itemList.isEmpty())
        assertEquals(0, afterDelete.totals.netAmount.amount.compareTo(java.math.BigDecimal.ZERO))
        assertEquals(0, afterDelete.totals.vatAmount.amount.compareTo(java.math.BigDecimal.ZERO))
        assertEquals(0, afterDelete.totals.grossAmount.amount.compareTo(java.math.BigDecimal.ZERO))
        assertEquals(InvoiceStatus.IN_REVIEW, afterDelete.status)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.NO_ITEMS, ex.error)
        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status, "a refused finalize must leave the invoice untouched")
    }

    // ── addItem — Positionsnummerierung, Sortierung, Summenneuberechnung ────────

    @Test
    fun `addItem throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildItemMutationService()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            itemMutationService.addItem(999L, minimalItemCommand())
        }
    }

    @Test
    fun `addItem assigns positionId 1 and sortOrder 1 to the first position and recalculates totals`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        // A pending domain event (production always saves right after every mutation) so
        // addItem's own `pullDomainEvents().forEach` below has something to iterate at least once.
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoiceRepo.save(invoice)

        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))

        val added = result.itemList.single()
        // W4 — InvoiceItemCommand carries no positionId/sortOrder at all (Invoice.addItem, the
        // single source of truth, always auto-numbers a fresh command-built item); the "explicitly
        // given positionId/sortOrder survives unchanged" capability this block used to also cover
        // no longer exists at this API — no caller (only the UI dialogs use addItem) ever needed it.
        assertEquals("1", added.positionId, "die erste Position ohne vorgegebene Nummer beginnt bei 1")
        assertEquals(1, added.sortOrder, "die erste Position ohne vorgegebene Sortierung beginnt bei 1")
        assertEquals(0, java.math.BigDecimal("119.00").compareTo(result.totals.grossAmount.amount), "addItem berechnet die Summen neu")
    }

    @Test
    fun `addItem persists BT-154 itemDescription, leaving it null when not supplied`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)

        val withDescription = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!).copy(itemDescription = "Ausfuehrliche Beschreibung"))
        assertEquals("Ausfuehrliche Beschreibung", withDescription.itemList.single().itemDescription)

        val invoice2 = service.create(1L, 1L)
        val withoutDescription = itemMutationService.addItem(invoice2.id!!, populatedCommand(taxRate.id!!))
        org.junit.jupiter.api.Assertions.assertNull(withoutDescription.itemList.single().itemDescription, "BT-154 ist optional (EN 16931 verlangt nur BT-153)")
    }

    @Test
    fun `addItem continues positionId and sortOrder numbering from the highest existing value`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        // Seeds a position with a non-sequential positionId/sortOrder directly on the entity —
        // InvoiceItemCommand itself can no longer request one (see the comment above).
        invoice.addItem(populatedItem().edit { positionId = "5"; sortOrder = 2 })
        invoiceRepo.save(invoice)

        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))

        val added = result.itemList.single { it.positionId != "5" }
        assertEquals("6", added.positionId, "die naechste automatische Nummer folgt der hoechsten vorhandenen, nicht der Anzahl der Positionen")
        assertEquals(3, added.sortOrder, "die naechste automatische Sortierung folgt dem hoechsten vorhandenen Wert")
    }

    /**
     * IMP-5 — the actual damage caused by the old `item.sortOrder = items.size + 1`: after
     * deleting a middle line item, `items.size` drops, but the highest sortOrder ever assigned
     * does not. The next line item then got a value that was already in use. Because the
     * display sorts via `items.sortedBy { it.sortOrder }`
     * ([de.littletrotter.ui.view.invoice.InvoiceDetailViewItems]), the line order on the
     * invoice and the PDF then became ambiguous — no small matter on a tax document.
     */
    @Test
    fun `addItem vergibt nach dem Loeschen einer mittleren Position keinen bereits belegten sortOrder`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        assertEquals(listOf(1, 2, 3), invoiceRepo.findByIdWithItems(invoice.id!!)!!.itemList.map { it.sortOrder }.sorted())

        // Die mittlere Position faellt weg: size == 2, hoechster Sortierwert weiterhin 3.
        itemMutationService.deleteItemByPosition(invoice.id!!, "2")

        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))

        val sortOrders = result.itemList.map { it.sortOrder }
        assertEquals(sortOrders.size, sortOrders.toSet().size, "kein Sortierwert darf doppelt vorkommen: $sortOrders")
        assertEquals(4, result.itemList.single { it.positionId !in setOf("1", "3") }.sortOrder,
            "die neue Position zaehlt vom hoechsten vergebenen Wert weiter, nicht von der Anzahl")
    }

    // ── addItem / applyItemTaxAndEntries — every InvoiceItemCategory branch ────
    // applyItemTaxAndEntries is addItem's own tax/entry resolution (updateItem has its own
    // separate implementation, see the PER_DIEM test above), and only its plain
    // SERVICE-with-taxRateId path was exercised before. These close every remaining branch: the
    // seller-country tax variant, the "no tax rate resolvable" guard, the R7 exemption
    // overrides, TRAVEL_EXPENSE/EXPENSE's R2 tax inheritance (with and without a main-service item
    // to inherit from), and PER_DIEM's own entry-rebuild + total-based re-derivation (per-diem/travel
    // calculation properties invariant).

    private fun articleRepoWith(vararg articles: de.littletrotter.domain.masterdata.Article): ArticleRepository =
        object : ArticleRepository {
            private val data = articles.associateBy { it.id!! }
            override fun findAll(pageIndex: Int, pageSize: Int, numberFilter: String?, textFilter: String?, sortField: String, sortDir: de.littletrotter.domain.shared.SortDirection) =
                data.values.toList()
            override fun count(numberFilter: String?, textFilter: String?): Long = data.size.toLong()
            override fun findForSelect(category: de.littletrotter.shared.catalogue.InvoiceItemCategory, filter: String?, pageIndex: Int, pageSize: Int): List<de.littletrotter.domain.masterdata.Article> =
                data.values.filter { it.category == category }.drop(pageIndex * pageSize).take(pageSize)
            override fun countForSelect(category: de.littletrotter.shared.catalogue.InvoiceItemCategory, filter: String?): Long =
                data.values.count { it.category == category }.toLong()
            override fun findById(id: Long) = data[id]
            override fun save(article: de.littletrotter.domain.masterdata.Article) = article
            override fun forceVersionIncrement(article: de.littletrotter.domain.masterdata.Article) {
                article.edit { version = (article.version ?: 0L) + 1L }
            }
            override fun delete(id: Long) {}
        }

    @Test
    fun `addItem derives tax from the article's seller-country variant when a foreign VAT registration is active`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domesticRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val atRate = de.littletrotter.domain.reference.TaxRate().edit { id = 10L; rate = TaxRatePercent(java.math.BigDecimal("20")); country = Country.AT }
        val article = anArticle {
            id = 5L
            name = "Beratungsleistung"
            replaceTaxVariants(listOf(anArticleTaxVariant { country = Country.AT; taxRateRef = atRate }), today = FIXED_TODAY)
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(domesticRate, atRate),
            articleRepo = articleRepoWith(article),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(domesticRate, atRate),
            articleRepo = articleRepoWith(article),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.edit { sellerVatRegistrationCountry = Country.AT }
        invoiceRepo.save(invoice)

        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(domesticRate.id!!).copy(articleId = article.id))

        val added = result.itemList.single()
        assertEquals(0, java.math.BigDecimal("20.00").compareTo(added.effectiveTaxRate()), "eine Verkaeufer-Registrierung in AT sticht die im Dialog gewaehlte inlaendische Steuer")
    }

    @Test
    fun `addItem falls back to the dialog's own taxRateId when a seller-country registration is active but the position has no bound article`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domesticRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(domesticRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(domesticRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.edit { sellerVatRegistrationCountry = Country.AT }
        invoiceRepo.save(invoice)

        // No articleId in the command -> item.articleRef stays null -> the seller-country branch's
        // second condition is false, falling through to the plain taxRateId resolution below.
        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(domesticRate.id!!))

        assertEquals(0, java.math.BigDecimal("19.00").compareTo(result.itemList.single().effectiveTaxRate()))
    }

    private fun serviceWithArticle(
        invoiceRepo: InMemoryInvoiceRepository,
        article: de.littletrotter.domain.masterdata.Article,
        vararg taxRates: de.littletrotter.domain.reference.TaxRate,
    ) = InvoiceLifecycleServiceTestBuilder(
        invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
        InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        taxRateRepo = taxRateRepoWith(*taxRates),
        articleRepo = articleRepoWith(article),
    ).build()

    private fun itemMutationServiceWithArticle(
        invoiceRepo: InMemoryInvoiceRepository,
        article: de.littletrotter.domain.masterdata.Article,
        vararg taxRates: de.littletrotter.domain.reference.TaxRate,
    ) = InvoiceLifecycleServiceTestBuilder(
        invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
        InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        taxRateRepo = taxRateRepoWith(*taxRates),
        articleRepo = articleRepoWith(article),
    ).buildItemMutationService()

    @Test
    fun `addItem rejects a catalogue entry whose category differs from the position category`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val goods = anArticle {
            id = 5L; name = "Schraube"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT
        }
        val service = serviceWithArticle(invoiceRepo, goods, rate)
        val itemMutationService = itemMutationServiceWithArticle(invoiceRepo, goods, rate)
        val invoice = service.create(1L, 1L)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.addItem(invoice.id!!, populatedCommand(rate.id!!).copy(articleId = goods.id))
        }
        assertTrue(ex.message!!.contains("Catalogue category"), ex.message)
    }

    @Test
    fun `addItem falls back to the country reference rate when the article has no variant for the seller country`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domesticRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val atReference = de.littletrotter.domain.reference.TaxRate().edit {
            id = 10L; rate = TaxRatePercent(java.math.BigDecimal("20")); country = Country.AT; isReference = true
            validFrom = LocalDate.of(2026, 1, 1); code = "AT_STANDARD_20"; category = "S"
        }
        val article = anArticle { id = 5L; name = "Beratungsleistung" }
        val service = serviceWithArticle(invoiceRepo, article, domesticRate, atReference)
        val itemMutationService = itemMutationServiceWithArticle(invoiceRepo, article, domesticRate, atReference)
        val invoice = service.create(1L, 1L)
        invoice.edit { sellerVatRegistrationCountry = Country.AT }
        invoice.edit { invoiceDate = LocalDate.of(2026, 9, 16) }
        invoiceRepo.save(invoice)

        val result = itemMutationService.addItem(invoice.id!!, populatedCommand(domesticRate.id!!).copy(articleId = article.id))

        assertEquals(0, java.math.BigDecimal("20.00").compareTo(result.itemList.single().effectiveTaxRate()))
    }

    @Test
    fun `updateItem rejects a changed position category`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val item = itemWithCategory("S", "19.00").edit { id = 77L }
        invoice.addItem(item)
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.updateItem(
                invoice.id!!,
                populatedCommand(rate.id!!).copy(itemId = 77L, category = de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT),
            )
        }
        assertTrue(ex.message!!.contains("category is immutable"), ex.message)
    }

    @Test
    fun `create demands an explicit reverse charge answer when seller and recipient VAT ids belong to different countries`() {
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            service.create(1L, customer.id!!)
        }
        assertTrue(ex.message!!.contains("Reverse Charge ausdrücklich"), ex.message)
    }

    @Test
    fun `addItem throws when a PRODUCT SERVICE position has no resolvable tax rate`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.addItem(invoice.id!!, minimalItemCommand())
        }
        assertTrue(ex.message!!.contains("Tax rate is required"))
    }

    @Test
    fun `addItem applies R7 exemption-text and legal-basis overrides on creation`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("0")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)

        val result = itemMutationService.addItem(
            invoice.id!!,
            populatedCommand(taxRate.id!!).copy(
                exemptionReasonTextOverride = "Steuerfreie innergemeinschaftliche Lieferung",
                taxLegalBasisOverride = "§4 Nr. 1b UStG",
            ),
        )

        val added = result.itemList.single()
        assertEquals("Steuerfreie innergemeinschaftliche Lieferung", added.exemptionReasonText)
        assertEquals("§4 Nr. 1b UStG", added.taxLegalBasis)
    }

    @Test
    fun `addItem rejects TRIP outright — a business trip goes through TripService now`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.addItem(invoice.id!!, minimalItemCommand().copy(category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP))
        }
        assertTrue(ex.message!!.contains("TripService"))
    }

    // The PER_DIEM/TRIP-specific addItem cases this block used to hold (main-service
    // rate inheritance, unknown per-diem rate id, positive-total-without-main-service, region
    // handling, the zero-value day) moved to TripServiceTest, exercising TripService
    // .addTrip directly: addItem itself now only ever sees SERVICE/PRODUCT.

    // ── deleteItemByPosition ──────────────────────────────────────────────────

    @Test
    fun `deleteItemByPosition throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildItemMutationService()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            itemMutationService.deleteItemByPosition(999L, "1")
        }
    }

    @Test
    fun `deleteItemByPosition throws InvoicePositionNotFoundException when no item matches the position`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))

        assertThrows(de.littletrotter.domain.exception.InvoicePositionNotFoundException::class.java) {
            itemMutationService.deleteItemByPosition(invoice.id!!, "does-not-exist")
        }
    }

    @Test
    fun `deleteItemByPosition removes the matching position and recalculates totals`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        // A pending domain event (production always saves right after every mutation) so
        // deleteItemByPosition's own `pullDomainEvents().forEach` below has something to iterate
        // at least once — the two addItem calls above already pulled and cleared their own.
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoiceRepo.save(invoice)

        val result = itemMutationService.deleteItemByPosition(invoice.id!!, "1")

        assertEquals(listOf("2"), result.itemList.map { it.positionId })
        assertEquals(0, java.math.BigDecimal("119.00").compareTo(result.totals.grossAmount.amount), "die Summe sinkt auf die verbleibende Position")
    }

    // N15 (PO decision 2026-09-11 evening) — "Remedy path for drafts": while an invoice is
    // DRAFT/IN_REVIEW, the offending position can be deleted, after which a re-queued
    // recalculation succeeds. This proves deleteItemByPosition itself never runs the full
    // recalculation over a position it is about to remove -- it removes first, then recalculates
    // only the remaining items, so a broken position can always be deleted regardless of whether
    // recalculate() would currently fail on the invoice.
    @Test
    fun `deleteItemByPosition on a draft succeeds even though the invoice would otherwise fail recalculation`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))
        itemMutationService.addItem(invoice.id!!, populatedCommand(taxRate.id!!))

        // Break position "2" the way a corrupted row shows up in production: neither a frozen
        // snapshot nor a live tax-rate reference -- InvoiceItem.effectiveTaxRate() throws for
        // exactly this shape (ITEM_TAX_RATE_REFERENCE_MISSING), the same failure the
        // recalculation sweep aborts on (see PostgresDatabaseMaintenanceService.performRecalculateTotals).
        val broken = invoiceRepo.findByIdWithItems(invoice.id!!)!!
        broken.itemList.single { it.positionId == "2" }.also {
            it.edit { taxRateRef = null }
            it.edit { taxRateSnapshot = null }
        }
        invoiceRepo.save(broken)

        // Sanity: the invoice really is broken -- a plain recalculate() must fail on it.
        assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.recalculate(invoice.id!!)
        }

        // The remedy: deleting the offending position must still work.
        val result = itemMutationService.deleteItemByPosition(invoice.id!!, "2")

        assertEquals(listOf("1"), result.itemList.map { it.positionId })
        assertEquals(0, java.math.BigDecimal("119.00").compareTo(result.totals.grossAmount.amount))
    }

    @Test
    fun `save refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            service.save(invoice)
        }
    }

    @Test
    fun `findAllRecipients uses configured default page size`() {
        var capturedPageSize = -1
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)) { _, pageSize, _, _, _ -> capturedPageSize = pageSize },
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
            configuredDefaultPageSize = 25,
        ).build()

        service.findAllRecipients()

        assertEquals(25, capturedPageSize)
    }

    @Test
    fun `findAllSenders falls back to 60 when configured default is invalid`() {
        var capturedPageSize = -1
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(),
            senderRepoWith(sender(id = 1L, prefix = "RE-")) { _, pageSize, _ -> capturedPageSize = pageSize },
            recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
            configuredDefaultPageSize = 0,
        ).build()

        service.findAllSenders()

        assertEquals(60, capturedPageSize)
    }

    // ── R3 — document language resolved once at creation, never re-derived ─────

    @Test
    fun `R3-T21 - the invoice takes the recipient's document language flag and a later flag change does not alter it`() {
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val frenchRecipient = recipient(id = 2L).edit {
            designateBillingAddress(Address.forRecipient(entity, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
            documentLanguage = "en"
        }
        val recipientRepo = recipientRepoWith(frenchRecipient)
        val service = InvoiceLifecycleServiceTestBuilder(InMemoryInvoiceRepository(), senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 2L)
        assertEquals(DocumentLanguage.EN, invoice.documentLanguage, "the recipient's flag is copied at creation")

        // Master data changes after the fact — the invoice's own field must not follow it.
        frenchRecipient.edit { documentLanguage = "de" }

        assertEquals(DocumentLanguage.EN, invoice.documentLanguage, "documentLanguage stays frozen once taken")
    }

    @Test
    fun `R3-T22 - the country of the recipient no longer decides the invoice language, only the flag does`() {
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val frenchRecipient = recipient(id = 2L).apply {
            designateBillingAddress(Address.forRecipient(this, Address.AddressType.BILLING, "Rue de Paris 1", "75001", "Paris", Country.FR, true))
        }
        val service = InvoiceLifecycleServiceTestBuilder(InMemoryInvoiceRepository(), senderRepo, recipientRepoWith(frenchRecipient), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        assertEquals(DocumentLanguage.DE, service.create(1L, 2L).documentLanguage)
    }

    // ── F4 — gate vor ISSUED ──────────────────────────────────────────────

    private fun gateService(
        invoiceRepo: InMemoryInvoiceRepository = InMemoryInvoiceRepository(),
        stage: String = "BLOCK",
        profileState: ValidationProfileState? = ValidationProfileState.ACTIVE,
        generationJobRepo: GenerationJobRepository? = null,
        // Rule evaluation has its own stage. It stays at the production default OBSERVE
        // here so the finalization-gate test cases keep checking exactly what they're meant to
        // check: the gate, not the rules.
        complianceStage: String = "OBSERVE",
        // Nullable so every existing finalization-gate test above stays unaffected (no B2B rule
        // set wired in unless a test passes one explicitly).
        b2bProfileRuleSet: de.littletrotter.application.invoice.B2bProfileRuleSet? = null,
    ): InvoiceLifecycleService = InvoiceLifecycleServiceTestBuilder(
        invoiceRepo = invoiceRepo,
        senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
        recipientRepo = recipientRepoWith(recipient(id = 1L)),
        invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
        domainEventBus = RecordingEventBus(),
        validationProfileRepo = FixedValidationProfileRepository(profileState),
        gateStageConfig = stage,
        complianceStageConfig = complianceStage,
    ).apply {
        generationJobRepo?.let { withGenerationJobRepo(it) }
        b2bProfileRuleSet?.let { withB2bProfileRuleSet(it) }
    }.build()

    private fun financializableInvoice(invoiceRepo: InMemoryInvoiceRepository, validationStatus: ValidationStatus): Invoice {
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        // BT-10 — recipient(id) carries no routingId, and the BT-10 gate (checked ahead of F4/
        // R5's own checks in transition()) would otherwise block every test using this fixture
        // before it ever reaches the scenario the test actually means to exercise. Tests that
        // specifically exercise the BT-10 gate itself override this back to null/blank.
        invoice.edit { references.buyerReference = "PROJ-TEST" }
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.edit { this.validationStatus = validationStatus }
        invoiceRepo.save(invoice)
        return invoice
    }

    @Test
    fun `F4-T01 - VALID validation status allows ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, result.status)
    }

    @Test
    fun `F4-T02 - INVALID blocks ISSUED and the message names a rule id`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.INVALID)
        val jobRepo = FixedGenerationJobRepository(
            listOf(completedJob(invoice, KositOutcome.INVALID, listOf(ValidationMessage("ERROR", "Bad", ruleId = "BR-DE-15"))))
        )
        val service = gateService(invoiceRepo, generationJobRepo = jobRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(GateBlockReason.VALIDATION_INVALID, ex.reason)
        assertTrue(ex.message?.contains("BR-DE-15") == true, "message should name the rule id: ${ex.message}")
    }

    @Test
    fun `F4-T03 - PENDING blocks ISSUED with a reason distinguishable from INVALID`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.PENDING)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(GateBlockReason.VALIDATION_PENDING, ex.reason)
    }

    // NOT_VALIDATED (no run has ever started) is
    // distinguishable from a genuinely running PENDING job, wired through the same
    // InvoiceLifecycleService path as the finalization-gate case above.
    @Test
    fun `NOT_VALIDATED blocks ISSUED with VALIDATION_NOT_RUN, distinguishable from PENDING`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.NOT_VALIDATED)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(GateBlockReason.VALIDATION_NOT_RUN, ex.reason)
    }

    @Test
    fun `F4-T04 - UNAVAILABLE without an override blocks ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.UNAVAILABLE)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(GateBlockReason.VALIDATION_UNAVAILABLE, ex.reason)
    }

    @Test
    fun `F4-T05 - UNAVAILABLE with a reasoned override allows ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.UNAVAILABLE)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED, overrideReason = "Validator war down, manuell geprüft.")

        assertEquals(InvoiceStatus.ISSUED, result.status)
    }

    @Test
    fun `F4-T06 - an override never applies to INVALID`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.INVALID)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED, overrideReason = "Trust me")
        }
        assertEquals(GateBlockReason.VALIDATION_INVALID, ex.reason)
    }

    @Test
    fun `F4-T07 - an override with a blank reason is rejected`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.UNAVAILABLE)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED, overrideReason = "   ")
        }
        assertEquals(GateBlockReason.VALIDATION_UNAVAILABLE, ex.reason)
    }

    @Test
    fun `F4-T08 - Stage OBSERVE never blocks, even for INVALID`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.INVALID)
        val service = gateService(invoiceRepo, stage = "OBSERVE")

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, result.status)
    }

    @Test
    fun `F4-T09 - the gate does not run for non-ISSUED targets`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.INVALID)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.VOIDED)

        assertEquals(InvoiceStatus.VOIDED, result.status)
    }

    @Test
    fun `leaving review is rejected while a SEND job is still in flight and nothing changes`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val jobs: GenerationJobRepository = org.mockito.kotlin.mock()
        org.mockito.kotlin.whenever(jobs.hasNonTerminalTriggerJob(invoice.id!!, de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)).thenReturn(true)
        val service = gateService(invoiceRepo, generationJobRepo = jobs)

        org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.VOIDED)
        }
        org.junit.jupiter.api.Assertions.assertThrows(de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.DRAFT)
        }

        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `the SEND job's own transition to ISSUED is not blocked by its in-flight job`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val jobs: GenerationJobRepository = org.mockito.kotlin.mock()
        org.mockito.kotlin.whenever(jobs.hasNonTerminalTriggerJob(invoice.id!!, de.littletrotter.domain.invoice.GenerationJobTrigger.SEND)).thenReturn(true)
        val service = gateService(invoiceRepo, generationJobRepo = jobs)

        assertEquals(InvoiceStatus.ISSUED, service.transition(invoice.id!!, InvoiceStatus.ISSUED).status)
    }

    @Test
    fun `F4-T10 - transitioning an already-ISSUED invoice fails via the normal status guard, not the gate`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val service = gateService(invoiceRepo)
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
    }

    // ── BT-10 — BuyerReference must never reach ISSUED blank (BR-DE-15/PEPPOL-EN16931-R008) ──

    @Test
    fun `BT-10 - blank buyerReference with no recipient routingId blocks ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.edit { references.buyerReference = null }
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        assertThrows(de.littletrotter.domain.invoice.BuyerReferenceMissingException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
    }

    @Test
    fun `free text with a character the PDF fonts lack blocks ISSUED and the invoice stays in review`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.texts.changeFreeText("Danke 💩")
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(de.littletrotter.domain.invoice.PdfUnrenderableTextException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(listOf("💩"), ex.fields["Freitext"])
        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `BT-10 - blank buyerReference is backfilled from recipient routingId and finalizes`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.edit { references.buyerReference = "   " }
        invoice.recipient?.edit { routingId = "04011000-1234512345-06" }
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, result.status)
        assertEquals("04011000-1234512345-06", result.references.buyerReference)
    }

    @Test
    fun `BT-10 - an already-set buyerReference finalizes untouched`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.edit { references.buyerReference = "PROJ-4711" }
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals("PROJ-4711", result.references.buyerReference)
    }

    // ── the tax case must match the recipient (AE ↔ S) ─────────────────────────
    // The gap that hasMixedReverseChargeAndStandard doesn't see: all line items uniformly S on
    // an invoice for which the resolver suggests Reverse Charge. No mix, no complaint — and
    // that's exactly what happened when a 19% article overrode the RC suggestion.

    private fun euBusinessRecipient(id: Long) = recipient(id).edit {
        customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
        vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
        vatId = VatId("FR12345678901")
        designateBillingAddress(
            Address.forRecipient(entity, Address.AddressType.BILLING, "Rue 1", "75001", "Paris", Country.FR, true),
        )
    }

    private fun finalizableInvoiceFor(
        invoiceRepo: InMemoryInvoiceRepository,
        customer: Recipient,
        itemCategory: String,
    ): Invoice {
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, customer.id!!, itemCategory == "AE")
        invoice.edit { references.buyerReference = "PROJ-TEST" }
        invoice.addItem(itemWithCategory(itemCategory, if (itemCategory == "AE") "0.00" else "19.00"))
        // reverseCharge is the invoice's own leading decision, no longer derived from the item
        // category by recalculateTotals; this fixture keeps decision and item consistent the
        // same way real usage (the header toggle) would.
        invoice.edit { reverseCharge = itemCategory == "AE" }
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.edit { validationStatus = ValidationStatus.VALID }
        invoiceRepo.save(invoice)
        return invoice
    }

    @Test
    fun `ARC-11 - decision OFF with S items finalizes even for an EU-business recipient (Reverse Charge is a decision, not automatic)`() {
        // A recipient-context-derived check forcing AE onto this invoice regardless of what the
        // user chose would block this. Instead, the header decision leads, and the recipient
        // context only gates it (assertReverseChargeRecipientEligibility) when the decision is
        // actually ON.
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val invoice = finalizableInvoiceFor(invoiceRepo, customer, "S")
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            validationProfileRepo = FixedValidationProfileRepository(ValidationProfileState.ACTIVE),
            gateStageConfig = "BLOCK",
        ).build()

        assertEquals(InvoiceStatus.ISSUED, service.transition(invoice.id!!, InvoiceStatus.ISSUED).status)
    }

    @Test
    fun `ARC-11 - decision ON with matching AE items and an eligible recipient finalizes`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val invoice = finalizableInvoiceFor(invoiceRepo, customer, "AE")
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            validationProfileRepo = FixedValidationProfileRepository(ValidationProfileState.ACTIVE),
            gateStageConfig = "BLOCK",
        ).build()

        assertEquals(InvoiceStatus.ISSUED, service.transition(invoice.id!!, InvoiceStatus.ISSUED).status)
    }

    @Test
    fun `ARC-11 - decision ON with a non-AE item among the positions blocks ISSUED naming the position`() {
        // Deliberately no AE item at all (a mix of AE+S would trip the older, still-valid
        // hasMixedReverseChargeAndStandard check first — this test targets the decision-vs-items
        // check instead, so it needs a case that check alone sees.
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.edit { references.buyerReference = "PROJ-TEST" }
        invoice.addItem(itemWithCategory("S", "19.00").also { it.edit { positionId = "1" } })
        invoice.addItem(itemWithCategory("S", "19.00").also { it.edit { positionId = "2" } })
        invoice.edit { reverseCharge = true }
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.edit { validationStatus = ValidationStatus.VALID }
        invoiceRepo.save(invoice)
        val gate = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            validationProfileRepo = FixedValidationProfileRepository(ValidationProfileState.ACTIVE),
            gateStageConfig = "BLOCK",
        ).build()

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            gate.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_ITEM_NOT_AE, ex.error)
        assertEquals(listOf("1, 2"), ex.args)
    }

    @Test
    fun `ARC-11 - decision OFF with an AE item blocks ISSUED naming the position`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.itemList.first().applyTaxRule(
            de.littletrotter.domain.reference.TaxRate().edit {
                code = "TEST_AE"; category = "AE"; rate = TaxRatePercent(java.math.BigDecimal.ZERO); exemptionReasonCode = "VATEX-EU-AE"
            },
        )
        invoice.recalculateTotals()
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_ITEM_UNEXPECTED_AE, ex.error)
        assertEquals(listOf(invoice.itemList.first().positionId), ex.args)
    }

    @Test
    fun `ARC-11 - decision ON blocks ISSUED once the recipient no longer qualifies for Reverse Charge`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID) // recipient(1L) — no customerType, no billing address
        invoice.itemList.first().applyTaxRule(
            de.littletrotter.domain.reference.TaxRate().edit {
                code = "TEST_AE"; category = "AE"; rate = TaxRatePercent(java.math.BigDecimal.ZERO); exemptionReasonCode = "VATEX-EU-AE"
            },
        )
        invoice.edit { reverseCharge = true }
        invoice.recalculateTotals()
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    // ── an explicit `true` at create() (exactly what an ALWAYS-preference recipient's
    // InvoiceCreateDialog auto-supplies) is blocked by the existing eligibility gate for every
    // reason that gate already covers, same as a manual "Yes" click always was ────────────────

    @Test
    fun `RC-2 - create with an explicit true is blocked for a domestic recipient (never eligible for Reverse Charge)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domestic = recipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.create(1L, domestic.id!!, true)
        }

        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    @Test
    fun `RC-2 - create with an explicit true is blocked when the seller already holds a matching registration`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val foreign = euBusinessRecipient(1L) // FR, verified
        val sellerWithFrRegistration = sender(1L, "RE-").apply {
            updateVatRegistrations(
                listOf(de.littletrotter.domain.masterdata.SellerVatRegistrationCommand(Country.FR, VatId("FR98765432100"))), today = FIXED_TODAY,
            )
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sellerWithFrRegistration), recipientRepoWith(foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.create(1L, foreign.id!!, true)
        }

        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    @Test
    fun `a domestic reduced-rate position never trips the context guard`() {
        // 7% is category S just like the DE_STANDARD_19 suggestion — a code comparison would
        // have blocked here, a category comparison correctly lets it through.
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true),
            )
        }
        val invoice = finalizableInvoiceFor(invoiceRepo, customer, "S")
        invoice.itemList.first().applyTaxRule(
            de.littletrotter.domain.reference.TaxRate().edit {
                code = "DE_REDUCED_7"; category = "S"; rate = TaxRatePercent(java.math.BigDecimal("7.00"))
            },
        )
        invoiceRepo.save(invoice)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            validationProfileRepo = FixedValidationProfileRepository(ValidationProfileState.ACTIVE),
            gateStageConfig = "BLOCK",
        ).build()

        assertEquals(InvoiceStatus.ISSUED, service.transition(invoice.id!!, InvoiceStatus.ISSUED).status)
    }

    // ── Reverse Charge and S positions can never coexist ──────────

    private fun itemWithCategory(category: String, rate: String = "19.00"): de.littletrotter.domain.invoice.InvoiceItem =
        de.littletrotter.domain.invoice.InvoiceItem().edit {
            positionId = "1"; description = "Beratung"
            quantity = java.math.BigDecimal("1")
            unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100"))
            netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100"))
            vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("0"))
            grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100"))
            applyTaxRule(de.littletrotter.domain.reference.TaxRate().edit {
                code = "TEST"; this.category = category; this.rate = TaxRatePercent(java.math.BigDecimal(rate))
                if (category == "AE") exemptionReasonCode = "VATEX-EU-AE"
            })
        }

    @Test
    fun `R5-T17 - a mix of AE and S positions blocks ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        // financializableInvoice's own populatedItem() carries no tax snapshot (taxCategory
        // == null), so it doesn't participate in the mix check — both categories below are
        // added explicitly to actually exercise the AE+S combination.
        invoice.addItem(itemWithCategory("AE", "0.00"))
        invoice.addItem(itemWithCategory("S"))
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        assertThrows(de.littletrotter.domain.compliance.MixedTaxCategoryException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
    }

    @Test
    fun `R5-T18 - a consistent all-S invoice finalizes normally`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        invoice.addItem(itemWithCategory("S"))
        invoiceRepo.save(invoice)
        val service = gateService(invoiceRepo)

        val result = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, result.status)
    }

    // ── setReverseChargeDecision: ON/OFF conversion mechanics ─────────

    private fun rcTaxRate(id: Long = 100L) = de.littletrotter.domain.reference.TaxRate().edit {
        this.id = id; code = "EU_B2B_RC_SERVICE"; category = "AE"; rate = TaxRatePercent(java.math.BigDecimal.ZERO)
        exemptionReasonCode = "VATEX-EU-AE"
        exemptionReasonText = "Reverse Charge — Steuerschuldnerschaft des Leistungsempfängers"
        name = "Reverse Charge"
    }

    private fun standardTaxRate(id: Long = 200L) = de.littletrotter.domain.reference.TaxRate().edit {
        this.id = id; code = "DE_STANDARD_19"; category = "S"; rate = TaxRatePercent(java.math.BigDecimal("19.00")); name = "Regelsteuersatz"
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision ON converts every item to the RC rule with zero VAT`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00").edit {
            category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
        })
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)

        assertTrue(result.reverseCharge)
        assertTrue(result.itemList.isNotEmpty())
        result.itemList.forEach { item ->
            assertEquals(de.littletrotter.shared.tax.VatCategory.REVERSE_CHARGE, item.taxCategory)
            assertEquals(de.littletrotter.shared.Money.ZERO, item.vatAmount)
            assertEquals("VATEX-EU-AE", item.exemptionReasonCode)
        }
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision OFF restores the German reference rule`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true),
            )
        }
        val reference = standardTaxRate().edit { isReference = true }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("AE", "0.00"))
        // A travel-like position resolves the same dated reference independently.
        invoice.addItem(itemWithCategory("AE", "0.00").edit { positionId = "2"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP })
        invoice.edit { reverseCharge = true }
        // A pending domain event (from this direct transitionTo, bypassing the repository) so the
        // method's own `pullDomainEvents().forEach` below has something to iterate — production
        // always calls save() right after every mutation, so events don't normally pile up like
        // this, but the loop body itself only ever runs when the pulled list is non-empty.
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)

        assertFalse(result.reverseCharge)
        assertEquals(de.littletrotter.shared.tax.VatCategory.STANDARD, result.itemList.first().taxCategory)
        assertEquals(0, java.math.BigDecimal("19.00").compareTo(result.itemList.first().taxRateSnapshot?.value))
        assertEquals(0, java.math.BigDecimal("19.00").compareTo(result.itemList[1].effectiveTaxRate()), "die Reiseposition verwendet die deutsche Referenz")
    }

    @Test
    fun `fixed trip follows reference country and invoice date when header changes`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val luReference = standardTaxRate(301L).edit {
            country = Country.LU; rate = TaxRatePercent(java.math.BigDecimal("17.00")); isReference = true
            validFrom = LocalDate.of(2026, 1, 1); code = "LU_STANDARD_17"
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(luReference),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(luReference),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoice.addItem(itemWithCategory("S", "19.00").edit {
            positionId = "2"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
            tripFixedNetPrice = de.littletrotter.shared.Money(java.math.BigDecimal("500"))
        })
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(
            updateHeaderCommand(invoice, "LU").copy(invoiceDate = LocalDate.of(2026, 9, 16)),
        )

        assertEquals(Country.LU, updated.sellerVatRegistrationCountry)
        assertEquals(luReference, updated.itemList.single { it.category == de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP }.taxRateRef)
    }

    @Test
    fun `turning reverse charge off restores the country reference on a fixed trip`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true))
        }
        val reference = standardTaxRate().edit { country = Country.DE; isReference = true }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).buildReverseChargeService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(itemWithCategory("AE", "0.00"))
        invoice.addItem(itemWithCategory("AE", "0.00").edit {
            positionId = "2"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
            tripFixedNetPrice = de.littletrotter.shared.Money(java.math.BigDecimal("500"))
        })
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)

        val updated = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)

        assertEquals(reference, updated.itemList.single { it.category == de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP }.taxRateRef)
    }

    // ── A context change (here: the invoice date) while Reverse Charge stays ON re-taxes the fixed
    // trip through the Reverse-Charge rule, never through a country reference ──────────────────

    private fun reverseChargeInvoiceWithFixedTrip(
        customer: Recipient,
        vararg taxRates: de.littletrotter.domain.reference.TaxRate,
    ): Triple<InvoiceHeaderUpdateService, InvoiceLifecycleService, Invoice> {
        val invoiceRepo = InMemoryInvoiceRepository()
        val builderArgs = {
            InvoiceLifecycleServiceTestBuilder(
                invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(customer),
                InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
                taxRateRepo = taxRateRepoWith(*taxRates),
            )
        }
        val service = builderArgs().build()
        val headerUpdateService = builderArgs().buildHeaderUpdateService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("AE", "0.00"))
        invoice.addItem(itemWithCategory("S", "19.00").edit {
            positionId = "2"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
            tripFixedNetPrice = de.littletrotter.shared.Money(java.math.BigDecimal("500"))
        })
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)
        return Triple(headerUpdateService, service, invoice)
    }

    private fun dateChangeKeepingReverseCharge(invoice: Invoice) =
        updateHeaderCommand(invoice, null).copy(invoiceDate = LocalDate.of(2026, 9, 16))

    @Test
    fun `a header change under an unchanged reverse charge decision re-taxes the fixed trip with the RC rule`() {
        val rcRate = rcTaxRate()
        val (headerUpdateService, _, invoice) = reverseChargeInvoiceWithFixedTrip(euBusinessRecipient(1L), rcRate, standardTaxRate())

        val updated = headerUpdateService.updateHeader(dateChangeKeepingReverseCharge(invoice))

        assertTrue(updated.reverseCharge)
        assertEquals(rcRate, updated.itemList.single { it.category == de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP }.taxRateRef)
    }

    @Test
    fun `a header change under reverse charge aborts when the RC rule is missing from master data`() {
        val (headerUpdateService, _, invoice) = reverseChargeInvoiceWithFixedTrip(euBusinessRecipient(1L), standardTaxRate())

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(dateChangeKeepingReverseCharge(invoice))
        }
        assertTrue(ex.message!!.contains("EU_B2B_RC_SERVICE"), ex.message)
    }

    @Test
    fun `a header change under reverse charge aborts once the recipient no longer qualifies`() {
        val (headerUpdateService, _, invoice) = reverseChargeInvoiceWithFixedTrip(recipient(1L), rcTaxRate(), standardTaxRate())

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            headerUpdateService.updateHeader(dateChangeKeepingReverseCharge(invoice))
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    @Test
    fun `header update aborts when a fixed trip has no country reference`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), taxRateRepo = taxRateRepoWith(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), taxRateRepo = taxRateRepoWith(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(itemWithCategory("S"))
        invoice.addItem(itemWithCategory("S").edit {
            positionId = "2"; category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
            tripFixedNetPrice = de.littletrotter.shared.Money(java.math.BigDecimal("500"))
        })
        invoiceRepo.save(invoice)

        val error = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))
        }
        assertTrue(error.message!!.contains("kein gültiger Referenzsteuersatz"))
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision OFF rejects a missing country reference`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            vatId = VatId("GB123456789")
            designateBillingAddress(Address.forRecipient(entity, Address.AddressType.BILLING, "Baker Street 1", "NW1", "London", Country.GB, true))
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("AE", "0.00"))
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)

        val error = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)
        }
        assertTrue(error.message!!.contains("kein gültiger Referenzsteuersatz"))
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision OFF falls back to the item's bound article default when the resolver's proposed code is missing from master data`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        // DE B2B: the resolver DOES propose "DE_STANDARD_19" (restoreProposedTaxRule's first
        // step succeeds), but this fake TaxRateRepository only stocks the RC rate — the
        // proposed code is not found in allTaxRates, so proposedRate stays null and the method
        // falls through to the item's own bound article's defaultTaxRateRef (the second
        // fallback step, distinct from the reference-data-default third step other tests here
        // already cover).
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true),
            )
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        val articleDefaultRate = de.littletrotter.domain.reference.TaxRate().edit {
            id = 300L; code = "ARTICLE_DEFAULT"; category = "S"; rate = TaxRatePercent(java.math.BigDecimal("7.00")); name = "Ermaessigt"
        }
        invoice.addItem(
            itemWithCategory("AE", "0.00").edit {
                articleRef = anArticle { defaultTaxRateRef = articleDefaultRate }
            },
        )
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)

        assertFalse(result.reverseCharge)
        assertEquals(VatCategory.STANDARD, result.itemList.single().taxCategory, "die Regel des gebundenen Artikels wurde uebernommen")
        assertEquals(0, java.math.BigDecimal("7.00").compareTo(result.itemList.single().taxRateSnapshot?.value))
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision OFF applies the reference to an unbound product`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true),
            )
        }
        val reference = standardTaxRate().edit { isReference = true }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("AE", "0.00").edit { category = de.littletrotter.shared.catalogue.InvoiceItemCategory.PRODUCT })
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)

        assertFalse(result.reverseCharge)
        assertEquals(VatCategory.STANDARD, result.itemList.single().taxCategory)
        assertEquals(reference, result.itemList.single().taxRateRef)
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision OFF resolves a trip without a main item`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Hauptstr. 1", "10115", "Berlin", Country.DE, true),
            )
        }
        val reference = standardTaxRate().edit { isReference = true }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), reference),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("AE", "0.00").edit { category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP })
        invoice.edit { reverseCharge = true }
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, false)

        assertFalse(result.reverseCharge)
        assertEquals(VatCategory.STANDARD, result.itemList.single().taxCategory)
        assertEquals(reference, result.itemList.single().taxRateRef)
    }

    @Test
    fun `setReverseChargeDecision skips the optimistic-lock check when the invoice itself carries no version`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoice.edit { version = null }
        invoiceRepo.save(invoice)

        // expectedVersion supplied AND the entity carries no version at all -- the check must be
        // skipped (nothing to compare against), not throw.
        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, 5L, true)

        assertTrue(result.reverseCharge)
    }

    @Test
    fun `ARC-11 - setReverseChargeDecision ON is rejected server-side when the recipient is not eligible`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L) // no customerType, no billing address -> ineligible
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    @Test
    fun `setReverseChargeDecision throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildReverseChargeService()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            reverseChargeService.setReverseChargeDecision(999L, null, true)
        }
    }

    @Test
    fun `setReverseChargeDecision refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val service = gateService(invoiceRepo)
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(invoiceRepo = invoiceRepo).buildReverseChargeService()
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            reverseChargeService.setReverseChargeDecision(invoice.id!!, null, true)
        }
    }

    @Test
    fun `setReverseChargeDecision throws OptimisticLockException when expectedVersion no longer matches`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoice.edit { version = 5L }
        invoiceRepo.save(invoice)

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            reverseChargeService.setReverseChargeDecision(invoice.id!!, 1L, true)
        }
    }

    @Test
    fun `setReverseChargeDecision skips the optimistic-lock check when expectedVersion is not supplied`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoice.edit { version = 5L }
        invoiceRepo.save(invoice)

        // expectedVersion null AND a stale version on the entity would throw if the check ran —
        // it must not, since the caller explicitly opted out of the check.
        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, null, true)

        assertTrue(result.reverseCharge)
    }

    @Test
    fun `setReverseChargeDecision ON throws when the resolver's proposed rule is missing from master data`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            // deliberately no rcTaxRate() -> the resolver proposes EU_B2B_RC_SERVICE, but it does
            // not exist as a TaxRate row.
            taxRateRepo = taxRateRepoWith(standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            // deliberately no rcTaxRate() -> the resolver proposes EU_B2B_RC_SERVICE, but it does
            // not exist as a TaxRate row.
            taxRateRepo = taxRateRepoWith(standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)
        }
        assertTrue(ex.message!!.contains("fehlt in den Stammdaten"))
    }

    @Test
    fun `an unregistered profile blocks ISSUED even when validation is VALID`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)
        val service = gateService(invoiceRepo, profileState = null)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(GateBlockReason.PROFILE_BLOCKED_OR_UNKNOWN, ex.reason)
    }

    private fun completedJob(invoice: Invoice, outcome: KositOutcome, details: List<ValidationMessage>): GenerationJob =
        aGenerationJob {
            id = 1L
            this.invoice = invoice
            status = GenerationJobStatus.COMPLETED
            createdAt = OffsetDateTime.now()
            kositReport = KositReportPayload.encode(KositValidationResult(status = outcome, details = details))
        }

    private class FixedValidationProfileRepository(private val state: ValidationProfileState?) : ValidationProfileRepository {
        override fun findCurrentByProfileId(profileId: String): ValidationProfile? =
            state?.let { ValidationProfile().edit { this.profileId = profileId; specVersion = "1.0"; this.state = it.name } }
        override fun findAll(): List<ValidationProfile> = emptyList()
        override fun save(profile: ValidationProfile): ValidationProfile = profile
    }

    private class FixedGenerationJobRepository(private val jobs: List<GenerationJob>) : GenerationJobRepository {
        override fun findByInvoiceId(invoiceId: Long): List<GenerationJob> = jobs.filter { it.invoice?.id == invoiceId }
        override fun findByInvoiceIdWithTaxRates(invoiceId: Long): List<GenerationJob> = findByInvoiceId(invoiceId)
        override fun findPending(): List<GenerationJob> = emptyList()
        override fun findPendingWithInvoice(): List<GenerationJob> = emptyList()
        override fun findById(id: Long): GenerationJob? = jobs.firstOrNull { it.id == id }
        override fun save(job: GenerationJob): GenerationJob = job
        override fun tryMarkDelegated(id: Long): Boolean = false
    }

    // ── updateHeader re-derives artikelgebundene positions on a seller-country switch ──

    private fun luArticle(): de.littletrotter.domain.masterdata.Article {
        val article = anArticle {
            id = 1L
            name = "Beratung"
            defaultTaxRateRef = standardTaxRate(2L)
        }
        val luRate = de.littletrotter.domain.reference.TaxRate().edit {
            id = 1L; code = "LU_STANDARD_17"; category = "S"; rate = TaxRatePercent(java.math.BigDecimal("17.00"))
            country = Country.LU
        }
        article.replaceTaxVariants(listOf(de.littletrotter.domain.masterdata.ArticleTaxVariant.of(article, Country.LU, luRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        return article
    }

    private fun senderWithLuRegistration(id: Long): SenderProfile {
        val s = sender(id, "RE-")
        s.updateVatRegistrations(listOf(de.littletrotter.domain.masterdata.SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        return s
    }

    private fun updateHeaderCommand(
        invoice: Invoice,
        sellerVatRegistrationCountry: String?,
        invoiceFormat: String? = null,
    ) =
        de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand(
            invoiceId = invoice.id!!,
            expectedVersion = null,
            invoiceDate = invoice.invoiceDate,
            servicePeriodFrom = null,
            servicePeriodTo = null,
            dueDate = null,
            freeText = null,
            buyerReference = null,
            orderReference = null,
            orderReferenceDate = null,
            currency = de.littletrotter.shared.Currency.EUR,
            recipientId = invoice.recipient?.id,
            senderProfileId = invoice.senderProfile?.id,
            paymentTermId = null,
            sellerVatRegistrationCountry = sellerVatRegistrationCountry,
            invoiceFormat = invoiceFormat,
            reverseChargeDecision = invoice.reverseCharge,
        )

    // ── invoiceFormat only takes effect while a seller VAT registration is active ──

    @Test
    fun `updateHeader applies INTERNATIONAL when a seller VAT registration is active`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU", "INTERNATIONAL"))

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, updated.invoiceFormat)
    }

    @Test
    fun `updateHeader preselects INTERNATIONAL when a non-German-language registration is activated with the old forced value`() {
        // PO refinement (2026-08-25, late evening): activating the LU registration while the
        // command still carries the previously forced GERMAN must flip the default to
        // INTERNATIONAL — the field only just became editable, so GERMAN here is never a choice.
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU", "GERMAN"))

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, updated.invoiceFormat, "activation preselects the English document for a non-DE/AT/CH registration")

        val backToGerman = headerUpdateService.updateHeader(updateHeaderCommand(updated, "LU", "GERMAN"))
        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, backToGerman.invoiceFormat, "after activation, an explicit GERMAN choice sticks — the default fires only on the null-to-set transition")
    }

    @Test
    fun `updateHeader keeps GERMAN when a German-business-language registration (AT) is activated`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val atReference = standardTaxRate(901L).edit { country = Country.AT; isReference = true }
        val senderRepo = senderRepoWith(
            sender(1L, "RE-").apply {
                updateVatRegistrations(
                    listOf(de.littletrotter.domain.masterdata.SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY,
                )
            },
        )
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(atReference),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(atReference),
        ).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        // registrationJustActivated (previousSellerCountry was null) with AT — a German-business-
        // language country per Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES — must NOT flip the
        // still-forced GERMAN default to INTERNATIONAL, unlike the LU case above.
        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "AT", "GERMAN"))

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, updated.invoiceFormat, "AT ist deutschsprachig — kein automatischer Wechsel auf INTERNATIONAL")
    }

    @Test
    fun `updateHeader forces GERMAN when no seller VAT registration is active, even if INTERNATIONAL was requested`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, null, "INTERNATIONAL"))

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, updated.invoiceFormat)
    }

    @Test
    fun `updateHeader falls back invoiceFormat to GERMAN when switching back to the German default`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)
        headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU", "INTERNATIONAL"))

        val backToGerman = headerUpdateService.updateHeader(updateHeaderCommand(invoice, null))

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, backToGerman.invoiceFormat)
    }

    @Test
    fun `updateHeader rejects an unknown invoiceFormat value`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU", "FRENCH"))
        }
    }

    // ── R-CB — a Reverse-Charge invoice is the other case where a
    // null sellerVatRegistrationCountry is expected, not just "German default" — DBG003-2026-08-
    // 00006 (id 373) had to stay GERMAN for a French recipient purely because of the V5 coupling
    // above, with no domain reason. ──

    @Test
    fun `updateHeader applies INTERNATIONAL for a reverse-charge invoice with no seller VAT registration`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildHeaderUpdateService()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)
        reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, null, "INTERNATIONAL"))

        assertTrue(updated.reverseCharge)
        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, updated.invoiceFormat)
        updated.itemList.forEach { item ->
            assertEquals(de.littletrotter.shared.tax.VatCategory.REVERSE_CHARGE, item.taxCategory)
        }
    }

    // ── RG3-F1 (PO decision 2026-08-30, option a) — setReverseChargeDecision no longer touches
    // invoice.invoiceFormat at all (see the method's own KDoc for why: the automatic
    // INTERNATIONAL default collided with chk_invoice_format_requires_seller_vat_registration for
    // every one of RC's own supported target countries, FR/BE/NL/LU). These tests replace the
    // former "preselects INTERNATIONAL" pin: a real RC decision for an FR buyer now simply
    // persists, invoiceFormat left exactly as it was. ──

    @Test
    fun `setReverseChargeDecision ON for an FR buyer persists and leaves invoiceFormat untouched (RG3-F1)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)
        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, invoice.invoiceFormat, "sanity: starts forced GERMAN")

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)

        assertTrue(result.reverseCharge)
        assertEquals(
            de.littletrotter.domain.shared.InvoiceFormat.GERMAN, result.invoiceFormat,
            "RG3-F1 — invoiceFormat is no longer auto-coupled to the RC decision",
        )
        assertEquals(1, result.itemList.size)
        assertEquals(
            de.littletrotter.shared.tax.VatCategory.REVERSE_CHARGE, result.itemList.single().taxCategory,
            "the real production rule (EU_B2B_RC_SERVICE) is still applied to every position",
        )
    }

    @Test
    fun `setReverseChargeDecision ON leaves invoiceFormat untouched for an Austrian recipient too`() {
        // Previously AT was the one recipient this coupling exempted (German stays the business
        // language default there); now that the coupling is gone entirely, AT behaves exactly
        // like every other eligible recipient — format simply never moves.
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = recipient(1L).edit {
            customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
            vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
            vatId = VatId("ATU12345675")
            designateBillingAddress(
                Address.forRecipient(entity, Address.AddressType.BILLING, "Ring 1", "1010", "Wien", Country.AT, true),
            )
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)

        assertTrue(result.reverseCharge)
        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, result.invoiceFormat)
    }

    @Test
    fun `setReverseChargeDecision ON never clobbers a manual GERMAN-or-INTERNATIONAL choice made via updateHeader`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val customer = euBusinessRecipient(1L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildHeaderUpdateService()
        val reverseChargeService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo = invoiceRepo,
            senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepo = recipientRepoWith(customer),
            invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
            domainEventBus = RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate(), standardTaxRate()),
        ).buildReverseChargeService()
        val invoice = service.create(1L, customer.id!!, false)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)
        reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)
        // The user explicitly picks INTERNATIONAL via updateHeader — isInvoiceFormatEditable
        // already allows this while reverseCharge is true (R-CB) — and re-toggling RC afterwards
        // must not touch that explicit choice either way.
        headerUpdateService.updateHeader(updateHeaderCommand(invoice, null, "INTERNATIONAL"))
        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, invoice.invoiceFormat, "sanity: manual choice applied")

        val result = reverseChargeService.setReverseChargeDecision(invoice.id!!, invoice.version, true)

        assertTrue(result.reverseCharge)
        assertEquals(
            de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, result.invoiceFormat,
            "a redundant ON toggle must not touch the user's explicit choice",
        )
    }

    @Test
    fun `updateHeader re-derives an artikelgebundene position's tax from the article's country variant`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))

        assertEquals(Country.LU, updated.sellerVatRegistrationCountry)
        val item = updated.itemList.single()
        assertEquals("LU_STANDARD_17", item.taxRuleCode)
        assertEquals(0, java.math.BigDecimal("17.00").compareTo(item.taxRateSnapshot?.value))
    }

    @Test
    fun `updateHeader blocks when an article has no variant for the new country, never falls back to a reference rate`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        // article WITHOUT any LU variant — updateHeader must block, never fall back to Germany
        val articleWithoutVariant = anArticle { id = 2L; name = "Ohne Variante" }
        invoice.addItem(populatedItem().edit { articleRef = articleWithoutVariant })
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.ITEM_ARTICLE_TAX_VARIANT_MISSING, ex.error)
    }

    @Test
    fun `updateHeader rejects a seller VAT registration the sender doesn't actually carry`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        // sender WITHOUT any additional VAT registration
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))
        }
    }

    @Test
    fun `updateHeader leaves the article's frozen LU tax snapshot untouched when switching back to Germany`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)
        headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))

        val backToGerman = headerUpdateService.updateHeader(updateHeaderCommand(invoice, null))

        // switching sellerVatRegistrationCountry back to null deliberately leaves the
        // already-frozen LU snapshot in place (see applySellerVatRegistration's own KDoc);
        // re-running the German tax-rate resolution path is out of this method's write scope.
        assertEquals(null, backToGerman.sellerVatRegistrationCountry)
        assertEquals("LU_STANDARD_17", backToGerman.itemList.single().taxRuleCode)
    }

    @Test
    fun `updateHeader does not re-derive when the seller country is saved unchanged`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        val article = luArticle()
        invoice.addItem(populatedItem().edit { articleRef = article })
        invoiceRepo.save(invoice)
        headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))

        // article loses its LU variant after the first save — a second save with the SAME
        // country must not re-derive (and therefore not throw), only an actual change does.
        article.replaceTaxVariants(emptyList(), today = FIXED_TODAY)
        val resaved = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))

        assertEquals("LU_STANDARD_17", resaved.itemList.single().taxRuleCode, "unchanged country must not re-trigger derivation")
    }

    @Test
    fun `updateHeader re-derives sellerVatRegistrationCountry from the new recipient VAT prefix`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val oldRecipient = recipient(id = 2L)
        val newRecipient = recipient(id = 3L).edit {
            customerNumber = CustomerNumber("KD-003")
            vatId = VatId("LU12345678")
            designateBillingAddress(Address.forRecipient(entity, Address.AddressType.BILLING, "Rue 1", "L-1234", "Luxembourg", Country.LU, true))
        }
        val recipientRepo = recipientRepoWith(oldRecipient, newRecipient)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        // command.sellerVatRegistrationCountry stays null on purpose — updateHeader ignores it
        // once the recipient itself changed, deriving straight from the new recipient's country.
        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, recipientId = 3L, sellerVatRegistrationCountry = null))

        assertEquals(Country.LU, updated.sellerVatRegistrationCountry, "der Laenderwechsel kommt vom VAT-Praefix, nicht vom (leeren) Kommandofeld")
        assertEquals("LU_STANDARD_17", updated.itemList.single().taxRuleCode, "die artikelgebundene Position folgt der neu abgeleiteten Registrierung")
    }

    @Test
    fun `updateHeader uses the VAT prefix even when only a delivery address exists`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(senderWithLuRegistration(1L))
        val oldRecipient = recipient(id = 2L)
        val newRecipient = recipient(id = 3L).edit {
            customerNumber = CustomerNumber("KD-003")
            vatId = VatId("LU12345678")
            addAddress(Address.forRecipient(entity, Address.AddressType.DELIVERY, "Rue 1", "L-1234", "Luxembourg", Country.LU, true))
        }
        val recipientRepo = recipientRepoWith(oldRecipient, newRecipient)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem().edit { articleRef = luArticle() })
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, recipientId = 3L, sellerVatRegistrationCountry = null))

        assertEquals(Country.LU, updated.sellerVatRegistrationCountry, "das VAT-Praefix bleibt unabhaengig von der Adressart")
    }

    // ── finding-fixes (2026-08-31, PO order) — party-change re-derivation: deliveryAddress/
    // buyerReference on a recipient change, billingAddress/paymentTerm on a sender change ───────

    @Test
    fun `updateHeader re-derives deliveryAddress and buyerReference from the NEW recipient on a recipient change`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val oldRecipient = recipient(id = 2L)
        val newRecipient = recipient(id = 3L).edit {
            customerNumber = CustomerNumber("KD-003")
            routingId = "991-01484-64"
            addAddress(Address.forRecipient(entity, Address.AddressType.DELIVERY, "Lieferstr. 9", "10119", "Berlin", Country.DE, true))
        }
        val recipientRepo = recipientRepoWith(oldRecipient, newRecipient)
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, recipientId = 3L))

        assertEquals("Lieferstr. 9", updated.deliveryAddress?.street, "die Lieferadresse kommt vom neuen Empfaenger, nicht vom alten")
        assertEquals("991-01484-64", updated.references.buyerReference, "die Kaeuferreferenz (BT-10) kommt vom neuen Empfaenger")
    }

    @Test
    fun `updateHeader leaves deliveryAddress alone and applies the submitted buyerReference when the recipient is unchanged`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipient(id = 2L).edit { routingId = "STANDING-2" })
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        val originalDeliveryAddress = invoice.deliveryAddress
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice, recipientId = 2L).copy(buyerReference = "Manuell ueberschrieben"),
        )

        assertEquals(originalDeliveryAddress?.street, updated.deliveryAddress?.street, "ein unveraenderter Empfaenger laesst die Lieferadresse unangetastet")
        assertEquals("Manuell ueberschrieben", updated.references.buyerReference, "der Bearbeiter darf die vorgeschlagene Referenz weiter bearbeiten")
    }

    @Test
    fun `updateHeader re-derives billingAddress and the default paymentTerm from the NEW sender on a sender change`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val net14 = paymentTerm(9L, "14 Tage netto")
        val oldSender = sender(id = 1L, prefix = "RE-")
        val newSender = sender(id = 5L, prefix = "NEU-").edit {
            defaultPaymentTerm = net14
            designateBillingAddress(Address.forSender(entity, Address.AddressType.BILLING, "Neue Anbieterstr. 3", "80331", "München", Country.DE, true))
        }
        val senderRepo = senderRepoWith(oldSender, newSender)
        val recipientRepo = recipientRepoWith(recipient(id = 2L))
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, senderProfileId = 5L))

        assertEquals("Neue Anbieterstr. 3", updated.billingAddress?.street, "die Rechnungsadresse kommt vom neuen Anbieter, nicht vom alten")
        assertEquals(net14, updated.paymentTerm, "das Zahlungsziel kommt vom Standard-Zahlungsziel des neuen Anbieters")
    }

    @Test
    fun `updateHeader leaves billingAddress alone and applies the submitted paymentTermId when the sender is unchanged`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val sofort = paymentTerm(1L, "Sofort fällig")
        val net14 = paymentTerm(2L, "14 Tage netto")
        val senderWithDefault = sender(id = 1L, prefix = "RE-").edit { defaultPaymentTerm = sofort }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithDefault), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net14),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithDefault), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            paymentTermRepo = paymentTermRepoWith(net14),
        ).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        assertEquals(sofort, invoice.paymentTerm, "Vorbedingung: Standard-Zahlungsziel beim Anlegen")
        val originalBillingAddress = invoice.billingAddress
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, senderProfileId = 1L, paymentTermId = 2L))

        assertEquals(originalBillingAddress?.street, updated.billingAddress?.street, "ein unveraenderter Anbieter laesst die Rechnungsadresse unangetastet")
        assertEquals(net14, updated.paymentTerm, "der Bearbeiter darf das vorgeschlagene Zahlungsziel weiter bearbeiten")
    }

    @Test
    fun `updateHeader treats assigning a recipient as a change when the invoice previously carried none`() {
        // Simulates a legacy/data-repair state (e.g. a prior clear via a since-rejected command,
        // see "rejects clearing the mandatory recipient..." above) where invoice.recipient is
        // already null going into this save — applyPartyChanges' own previousRecipientId reads
        // that via a safe call specifically to handle this receiver-null case, not just the
        // usual "some other recipient before" case every other party-change test here covers.
        val invoiceRepo = InMemoryInvoiceRepository()
        val newRecipient = recipient(id = 3L).edit {
            customerNumber = CustomerNumber("KD-003")
            addAddress(Address.forRecipient(entity, Address.AddressType.DELIVERY, "Rue 1", "L-1234", "Luxembourg", Country.LU, true))
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L), newRecipient),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L), newRecipient),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoice.edit { recipient = null }
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, recipientId = 3L))

        assertEquals(3L, updated.recipient?.id)
        assertEquals("Rue 1", updated.deliveryAddress?.street, "recipient ging von null auf 3 -> gilt als Wechsel, Lieferadresse wird neu abgeleitet")
    }

    @Test
    fun `updateHeader treats assigning a senderProfile as a change when the invoice previously carried none`() {
        // Same shape as the recipient counterpart above, sender side: previousSenderId's own
        // safe call covers invoice.senderProfile already being null going in.
        val invoiceRepo = InMemoryInvoiceRepository()
        val newSender = sender(id = 5L, prefix = "NEU-").apply {
            designateBillingAddress(Address.forSender(this, Address.AddressType.BILLING, "Neue Anbieterstr. 3", "80331", "München", Country.DE, true))
        }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-"), newSender), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-"), newSender), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoice.edit { senderProfile = null }
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, senderProfileId = 5L))

        assertEquals(5L, updated.senderProfile?.id)
        assertEquals("Neue Anbieterstr. 3", updated.billingAddress?.street, "senderProfile ging von null auf 5 -> gilt als Wechsel, Rechnungsadresse wird neu abgeleitet")
    }

    @Test
    fun `updateHeader validates a seller VAT registration against the invoice's selectedSeller override, not senderProfile`() {
        // effectiveSeller = invoice.selectedSeller ?: invoice.senderProfile: a
        // per-invoice seller override (multi-entity billing) decides the check, not the
        // invoice's own stored senderProfile — mirrors the same selectedSeller-wins precedence
        // documented on Invoice.effectiveSender()/TaxCategoryConsistency.
        val invoiceRepo = InMemoryInvoiceRepository()
        // senderProfile itself carries NO LU registration...
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        // ...but the invoice's selectedSeller override does.
        invoice.edit { selectedSeller = senderWithLuRegistration(9L) }
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(updateHeaderCommand(invoice, "LU"))

        assertEquals(Country.LU, updated.sellerVatRegistrationCountry, "die Pruefung folgt selectedSeller, nicht dem unveraenderten senderProfile")
    }

    // ── updateHeader — non-status fields: not found, lock, optimistic locking ──────

    @Test
    fun `INV-B create freezes seller note and untouched default follows sender change`() {
        val language = germanFallbackLanguage()
        val firstSender = sender(1L, "ALT-").apply { changeInvoiceNote(language, "Alter Standard") }
        val secondSender = sender(5L, "NEU-").apply { changeInvoiceNote(language, "Neuer Standard") }
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(firstSender, secondSender),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(firstSender, secondSender),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildHeaderUpdateService()

        val invoice = service.create(1L, 2L)
        assertEquals("Alter Standard", invoice.texts.invoiceNote)

        val updated = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice, senderProfileId = 5L).copy(
                invoiceNoteSubmitted = true,
                invoiceNote = "Alter Standard",
            ),
        )

        assertEquals("Neuer Standard", updated.texts.invoiceNote)
        assertEquals(de.littletrotter.domain.invoice.InvoiceNoteOrigin.SELLER_DEFAULT, updated.texts.invoiceNoteOrigin)
        assertEquals(5L, updated.texts.invoiceNoteSourceSenderProfileId)
    }

    @Test
    fun `INV-B deliberate empty note survives a later sender change`() {
        val language = germanFallbackLanguage()
        val firstSender = sender(1L, "ALT-").apply { changeInvoiceNote(language, "Alter Standard") }
        val secondSender = sender(5L, "NEU-").apply { changeInvoiceNote(language, "Neuer Standard") }
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(firstSender, secondSender),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(firstSender, secondSender),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)

        val cleared = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice).copy(invoiceNoteSubmitted = true, invoiceNote = null),
        )
        val senderChanged = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(cleared, senderProfileId = 5L).copy(invoiceNoteSubmitted = true, invoiceNote = null),
        )

        assertNull(senderChanged.texts.invoiceNote)
        assertEquals(de.littletrotter.domain.invoice.InvoiceNoteOrigin.MANUAL, senderChanged.texts.invoiceNoteOrigin)
    }

    // ── consumer note (craft-trade private customers) ─────────────────────────────

    private fun consumerRecipient(id: Long, language: String = "de") = recipient(id).edit {
        customerType = de.littletrotter.shared.tax.CustomerType.CONSUMER
        documentLanguage = language
    }

    private fun businessRecipient(id: Long) = recipient(id).edit {
        customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
    }

    private fun consumerFixture(
        vararg recipients: Recipient,
        sender: SenderProfile = sender(1L, "RE-"),
        profile: de.littletrotter.shared.wording.WordingProfile = de.littletrotter.shared.wording.WordingProfile.HANDWERK,
    ): InvoiceLifecycleServiceTestBuilder = InvoiceLifecycleServiceTestBuilder(
        InMemoryInvoiceRepository(),
        senderRepoWith(sender),
        recipientRepoWith(*recipients),
        InvoiceNumberService(freshNumberRangeService()),
        RecordingEventBus(),
    ).withWordingProfile(profile)

    private val germanDefault = "Bitte bewahren Sie diese Rechnung zwei Jahre auf. Die enthaltenen Arbeits-, Maschinen- und Fahrtkosten können Sie bei Ihrer Steuererklärung angeben."

    @Test
    fun `G4 consumer invoice under HANDWERK without own text gets the German default`() {
        val invoice = consumerFixture(consumerRecipient(2L)).build().create(1L, 2L)

        assertEquals(germanDefault, invoice.texts.consumerNote)
        assertEquals(de.littletrotter.domain.invoice.InvoiceNoteOrigin.SELLER_DEFAULT, invoice.texts.consumerNoteOrigin)
        assertEquals("CONSUMER", invoice.texts.consumerNoteSourceContext)
    }

    @Test
    fun `G4 own sender text wins over the default`() {
        val sender = sender(1L, "RE-").apply { changeConsumerNote(germanFallbackLanguage(), "Eigener Hinweis") }

        val invoice = consumerFixture(consumerRecipient(2L), sender = sender).build().create(1L, 2L)

        assertEquals("Eigener Hinweis", invoice.texts.consumerNote)
    }

    @Test
    fun `G7 English document gets the English default`() {
        val invoice = consumerFixture(consumerRecipient(2L, "en")).build().create(1L, 2L)

        assertEquals(
            "Please keep this invoice for two years. You can state the labour, machine and travel costs it contains in your tax return.",
            invoice.texts.consumerNote,
        )
    }

    @Test
    fun `G7 own English text wins on an English document`() {
        val english = de.littletrotter.domain.reference.Language().edit { id = 2; code = "en" }
        val sender = sender(1L, "RE-").apply { changeConsumerNote(english, "Own English notice") }

        val invoice = consumerFixture(consumerRecipient(2L, "en"), sender = sender).build().create(1L, 2L)

        assertEquals("Own English notice", invoice.texts.consumerNote)
    }

    @Test
    fun `G1 other wording profile creates no consumer note`() {
        val invoice = consumerFixture(
            consumerRecipient(2L),
            profile = de.littletrotter.shared.wording.WordingProfile.BERATUNG,
        ).build().create(1L, 2L)

        assertNull(invoice.texts.consumerNote)
    }

    @Test
    fun `G2 business recipient creates no consumer note`() {
        val invoice = consumerFixture(businessRecipient(2L)).build().create(1L, 2L)

        assertNull(invoice.texts.consumerNote)
        assertEquals("BUSINESS", invoice.texts.consumerNoteSourceContext)
    }

    @Test
    fun `G3 switched off sender creates no consumer note`() {
        val sender = sender(1L, "RE-").edit { consumerNoteEnabled = false }

        val invoice = consumerFixture(consumerRecipient(2L), sender = sender).build().create(1L, 2L)

        assertNull(invoice.texts.consumerNote)
    }

    @Test
    fun `G5 draft recipient switch to business and back removes and restores the untouched note`() {
        val builder = consumerFixture(consumerRecipient(2L), businessRecipient(3L))
        val invoice = builder.build().create(1L, 2L)
        val header = builder.buildHeaderUpdateService()

        val business = header.updateHeader(customUpdateHeaderCommand(invoice, recipientId = 3L))
        assertNull(business.texts.consumerNote)
        val consumerAgain = header.updateHeader(customUpdateHeaderCommand(business, recipientId = 2L))

        assertEquals(germanDefault, consumerAgain.texts.consumerNote)
    }

    @Test
    fun `G6 edited note survives the recipient switch to business`() {
        val builder = consumerFixture(consumerRecipient(2L), businessRecipient(3L))
        val invoice = builder.build().create(1L, 2L)
        val header = builder.buildHeaderUpdateService()

        val edited = header.updateHeader(
            customUpdateHeaderCommand(invoice).copy(consumerNoteSubmitted = true, consumerNote = "Mein Text"),
        )
        val switched = header.updateHeader(
            customUpdateHeaderCommand(edited, recipientId = 3L).copy(consumerNoteSubmitted = true, consumerNote = "Mein Text"),
        )

        assertEquals("Mein Text", switched.texts.consumerNote)
        assertEquals(de.littletrotter.domain.invoice.InvoiceNoteOrigin.MANUAL, switched.texts.consumerNoteOrigin)
    }

    @Test
    fun `R6 reset returns an edited note to the current default`() {
        val builder = consumerFixture(consumerRecipient(2L))
        val invoice = builder.build().create(1L, 2L)
        val header = builder.buildHeaderUpdateService()
        val edited = header.updateHeader(
            customUpdateHeaderCommand(invoice).copy(consumerNoteSubmitted = true, consumerNote = "Mein Text"),
        )

        val reset = header.updateHeader(
            customUpdateHeaderCommand(edited).copy(consumerNoteSubmitted = true, consumerNote = "Mein Text", resetConsumerNoteToSellerDefault = true),
        )

        assertEquals(germanDefault, reset.texts.consumerNote)
        assertEquals(de.littletrotter.domain.invoice.InvoiceNoteOrigin.SELLER_DEFAULT, reset.texts.consumerNoteOrigin)
    }

    @Test
    fun `G8 later master data edit leaves the invoice copy unchanged`() {
        val sender = sender(1L, "RE-")
        val invoice = consumerFixture(consumerRecipient(2L), sender = sender).build().create(1L, 2L)

        sender.changeConsumerNote(germanFallbackLanguage(), "Geaenderter Stammdatentext")
        sender.edit { consumerNoteEnabled = false }

        assertEquals(germanDefault, invoice.texts.consumerNote)
    }

    @Test
    fun `G12 profile switch after creation keeps the draft copy and gives new invoices none`() {
        val builder = consumerFixture(consumerRecipient(2L))
        val invoice = builder.build().create(1L, 2L)

        builder.withWordingProfile(de.littletrotter.shared.wording.WordingProfile.HANDEL)
        val saved = builder.buildHeaderUpdateService().updateHeader(customUpdateHeaderCommand(invoice))
        val fresh = builder.build().create(1L, 2L)

        assertEquals(germanDefault, saved.texts.consumerNote)
        assertNull(fresh.texts.consumerNote)
    }

    @Test
    fun `R7 sent invoice rejects a consumer note edit`() {
        val builder = consumerFixture(consumerRecipient(2L))
        val invoice = builder.build().create(1L, 2L)
        invoice.forceStatusForTest(InvoiceStatus.ISSUED)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            builder.buildHeaderUpdateService().updateHeader(
                customUpdateHeaderCommand(invoice).copy(consumerNoteSubmitted = true, consumerNote = "Neu"),
            )
        }
    }

    @Test
    fun `R6 header command delegates submitted B2B values inside lifecycle save`() {
        val b2bFieldValueService = mock<de.littletrotter.application.reference.B2bFieldValueService>()
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bFieldValueService(b2bFieldValueService).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(recipient(2L)),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bFieldValueService(b2bFieldValueService).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        val submitted = mapOf("BT-12" to "VERTRAG-1")

        headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice).copy(b2bFieldValues = submitted))

        verify(b2bFieldValueService).saveFieldValues(
            de.littletrotter.domain.reference.B2bFieldOwnerType.INVOICE,
            invoice.id!!,
            submitted,
        )
    }

    @Test
    fun `INV-J imports only free header terms once and preserves later free-text edits`() {
        val b2bService = mock<de.littletrotter.application.reference.B2bProfileFieldDefinitionService>()
        val customer = recipientWithB2bProfile(2L, 77L)
        val profile = customer.b2bProfile!!
        val fields = listOf(
            de.littletrotter.domain.reference.B2bProfileField().edit {
                term = de.littletrotter.shared.b2b.B2bTerm.BT_12
                businessDefinition = "Contract"
            },
            de.littletrotter.domain.reference.B2bProfileField().edit {
                term = de.littletrotter.shared.b2b.B2bTerm.BT_11
                businessDefinition = "Project reference"
            },
            de.littletrotter.domain.reference.B2bProfileField().edit {
                term = de.littletrotter.shared.b2b.B2bTerm.BT_155
                businessDefinition = "Line note"
            },
        )
        whenever(b2bService.findFields(77L)).thenReturn(fields)
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bProfileRepo(b2bProfileRepoWith(profile))
            .withB2bProfileFieldDefinitionService(b2bService)
            .build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bProfileRepo(b2bProfileRepoWith(profile))
            .withB2bProfileFieldDefinitionService(b2bService).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        val effectiveValues = mapOf("BT-12" to "C-1", "BT-11" to "P-1", "BT-155" to "Line")

        val imported = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice).copy(
                b2bProfileId = 77L,
                effectiveB2bHeaderValues = effectiveValues,
            ),
        )
        val initiallyImportedFreeText = imported.texts.freeText
        imported.texts.changeFreeText("Manager replacement")
        val repeated = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(imported).copy(
                freeText = imported.texts.freeText,
                b2bProfileId = 77L,
                effectiveB2bHeaderValues = effectiveValues,
            ),
        )

        assertEquals("C-1", initiallyImportedFreeText)
        assertEquals("Manager replacement", repeated.texts.freeText)
        assertEquals(setOf("BT-12"), repeated.texts.importedB2bFreeTextCodes())
    }

    // ═══ B3/B8 — a term the office manager already clicked as a free-text chip must not be
    //     appended a second time by the automatic silent merge, while an untouched term still
    //     imports exactly as before (the pre-existing mechanism stays additive, not replaced). ═══
    @Test
    fun `B3 - a chip-imported term is not re-added by the automatic merge, an untouched term still imports normally`() {
        val b2bService = mock<de.littletrotter.application.reference.B2bProfileFieldDefinitionService>()
        val customer = recipientWithB2bProfile(2L, 77L)
        val profile = customer.b2bProfile!!
        val fields = listOf(
            de.littletrotter.domain.reference.B2bProfileField().edit {
                term = de.littletrotter.shared.b2b.B2bTerm.BT_12
                businessDefinition = "Contract"
            },
            de.littletrotter.domain.reference.B2bProfileField().edit {
                term = de.littletrotter.shared.b2b.B2bTerm.BT_18
                businessDefinition = "Object id"
            },
        )
        whenever(b2bService.findFields(77L)).thenReturn(fields)
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bProfileRepo(b2bProfileRepoWith(profile))
            .withB2bProfileFieldDefinitionService(b2bService)
            .build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo,
            senderRepoWith(sender(1L, "RE-")),
            recipientRepoWith(customer),
            InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).withB2bProfileRepo(b2bProfileRepoWith(profile))
            .withB2bProfileFieldDefinitionService(b2bService).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        val effectiveValues = mapOf("BT-12" to "C-1", "BT-18" to "IA-1")

        val saved = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice).copy(
                b2bProfileId = 77L,
                effectiveB2bHeaderValues = effectiveValues,
                manuallyImportedB2bCodes = setOf("BT-12"),
            ),
        )

        assertEquals(
            "IA-1", saved.texts.freeText,
            "BT-12 was already chipped by the office manager, only the untouched BT-18 term is auto-merged",
        )
        assertEquals(
            setOf("BT-12", "BT-18"), saved.texts.importedB2bFreeTextCodes(),
            "the chipped code is recorded as imported too, so a later save never appends it either",
        )
    }

    private fun customUpdateHeaderCommand(
        invoice: Invoice,
        expectedVersion: Long? = null,
        recipientId: Long? = invoice.recipient?.id,
        senderProfileId: Long? = invoice.senderProfile?.id,
        paymentTermId: Long? = null,
        sellerVatRegistrationCountry: String? = null,
        reverseChargeDecision: Boolean? = invoice.reverseCharge,
    ) = de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand(
        invoiceId = invoice.id!!,
        expectedVersion = expectedVersion,
        invoiceDate = invoice.invoiceDate,
        servicePeriodFrom = null,
        servicePeriodTo = null,
        dueDate = null,
        freeText = null,
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = de.littletrotter.shared.Currency.EUR,
        recipientId = recipientId,
        senderProfileId = senderProfileId,
        paymentTermId = paymentTermId,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
        invoiceFormat = null,
        reverseChargeDecision = reverseChargeDecision,
    )

    @Test
    fun `header update requires an explicit reverse charge answer when VAT countries start differing`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domestic = recipient(1L)
        val foreign = euBusinessRecipient(2L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, domestic.id!!)

        val error = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(
                customUpdateHeaderCommand(invoice, recipientId = foreign.id, reverseChargeDecision = null),
            )
        }

        assertTrue(error.message!!.contains("ausdrücklich mit Ja oder Nein"))
    }

    @Test
    fun `header update applies explicit reverse charge yes atomically to every item`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domestic = recipient(1L)
        val foreign = euBusinessRecipient(2L)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate()),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rcTaxRate()),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, domestic.id!!)
        invoice.addItem(itemWithCategory("S", "19.00"))
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice, recipientId = foreign.id, reverseChargeDecision = true),
        )

        assertTrue(updated.reverseCharge)
        assertEquals(foreign, updated.recipient)
        assertTrue(updated.itemList.all { it.taxCategory == VatCategory.REVERSE_CHARGE })
    }

    @Test
    fun `header update applies explicit reverse charge no with German reference fallback`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val domestic = recipient(1L)
        val foreign = euBusinessRecipient(2L)
        val reference = standardTaxRate().edit { isReference = true }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(reference),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(1L, "RE-")), recipientRepoWith(domestic, foreign),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(reference),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, domestic.id!!)
        invoice.addItem(itemWithCategory("AE", "0.00"))
        invoiceRepo.save(invoice)

        val updated = headerUpdateService.updateHeader(
            customUpdateHeaderCommand(invoice, recipientId = foreign.id, reverseChargeDecision = false),
        )

        assertFalse(updated.reverseCharge)
        assertEquals(reference, updated.itemList.single().taxRateRef)
    }

    @Test
    fun `updateHeader throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(Invoice().edit { id = 999L }))
        }
    }

    @Test
    fun `updateHeader refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice))
        }
    }

    @Test
    fun `updateHeader accepts a matching expectedVersion`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        // A pending domain event (production always saves right after every mutation, so events
        // don't normally accumulate like this) so finalizeHeaderUpdate's own
        // `pullDomainEvents().forEach` below has something to iterate at least once.
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, expectedVersion = invoice.version))

        assertEquals(invoice.id, result.id)
    }

    // finding-fixes (2026-08-31) — the header-capture pane's very first save happens right after
    // create(), before any position exists; see the matching `save succeeds for a freshly created
    // draft with zero items` test above for the same fix at the plain save() entrypoint.
    @Test
    fun `updateHeader succeeds for a freshly created draft with zero items`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        assertTrue(invoice.itemList.isEmpty(), "Vorbedingung: ein frischer Entwurf traegt noch keine Positionen")
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice))

        assertEquals(invoice.id, result.id)
        assertTrue(result.itemList.isEmpty())
    }

    @Test
    fun `updateHeader throws OptimisticLockException when expectedVersion no longer matches`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, expectedVersion = (invoice.version ?: 0L) + 1))
        }
    }

    @Test
    fun `updateHeader skips the optimistic-lock check when expectedVersion is not supplied`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        // A version wildly different from the invoice's real one would fail the check above --
        // omitting expectedVersion entirely must skip it instead of failing.
        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, expectedVersion = null))

        assertEquals(invoice.id, result.id)
    }

    @Test
    fun `updateHeader skips the optimistic-lock check when the invoice itself carries no version`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoice.edit { version = null }
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, expectedVersion = 5L))

        assertEquals(invoice.id, result.id)
    }

    @Test
    fun `updateHeader rejects clearing the mandatory recipient and senderProfile via null ids`() {
        // Invoice.validate() requires both -- clearing them is caught there, not silently
        // accepted. The null-id branches themselves (recipientId/senderProfileId -> null) still
        // run before validate() throws.
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, recipientId = null, senderProfileId = null))
        }
    }

    @Test
    fun `updateHeader clears the paymentTerm when the command carries no paymentTermId`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val paymentTermRepo = paymentTermRepoWith(paymentTerm(1L, "Sofort fällig"))
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepo,
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepo,
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, paymentTermId = null))

        assertEquals(null, result.paymentTerm)
    }

    @Test
    fun `updateHeader applies a paymentTermId given in the command`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val net14 = paymentTerm(3L, "14 Tage netto")
        val paymentTermRepo = paymentTermRepoWith(net14)
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepo,
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepo,
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, paymentTermId = 3L))

        assertEquals(net14, result.paymentTerm)
    }

    // ── DATE: rules 2/4 (PO decision 2026-09-01) — the
    // header-edit auto-correction/derivation, wired in InvoiceLifecycleService.updateHeader via
    // applyInvoiceDateAutoCorrection. The pure comparison/derivation logic itself is pinned
    // exhaustively by InvoiceDateRulesTest; these four tests only pin the wiring: what actually
    // lands on the persisted Invoice after a real updateHeader() call. ──

    private fun dateRuleWiringCommand(
        invoice: Invoice,
        invoiceDate: LocalDate?,
        servicePeriodTo: LocalDate?,
        dueDate: LocalDate?,
        paymentTermId: Long?,
    ) = de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand(
        invoiceId = invoice.id!!,
        expectedVersion = null,
        invoiceDate = invoiceDate,
        servicePeriodFrom = null,
        servicePeriodTo = servicePeriodTo,
        dueDate = dueDate,
        freeText = null,
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = de.littletrotter.shared.Currency.EUR,
        recipientId = invoice.recipient?.id,
        senderProfileId = invoice.senderProfile?.id,
        paymentTermId = paymentTermId,
        sellerVatRegistrationCountry = null,
        invoiceFormat = null,
        reverseChargeDecision = invoice.reverseCharge,
    )

    @Test
    fun `updateHeader moves the invoice date forward to the service end and recomputes the due date from the chosen payment term`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val net14 = paymentTerm(3L, "14 Tage netto").edit { dueInDays = 14 }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(net14),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(net14),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(
            dateRuleWiringCommand(
                invoice,
                invoiceDate = LocalDate.of(2026, 8, 25),
                servicePeriodTo = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 9, 5),
                paymentTermId = 3L,
            ),
        )

        assertEquals(LocalDate.of(2026, 8, 31), result.invoiceDate, "rule 2 must move the invoice date forward to the service end")
        assertEquals(LocalDate.of(2026, 9, 14), result.dueDate, "rule 4 must recompute the due date from the CORRECTED invoice date + the chosen payment term's dueInDays")
    }

    @Test
    fun `updateHeader leaves the submitted due date alone when the invoice date already sits on or after the service end`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val net14 = paymentTerm(3L, "14 Tage netto").edit { dueInDays = 14 }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(net14),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(net14),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(
            dateRuleWiringCommand(
                invoice,
                invoiceDate = LocalDate.of(2026, 9, 5),
                servicePeriodTo = LocalDate.of(2026, 8, 31),
                dueDate = LocalDate.of(2026, 9, 10),
                paymentTermId = 3L,
            ),
        )

        assertEquals(LocalDate.of(2026, 9, 5), result.invoiceDate, "no correction needed - invoice date already on/after the service end")
        assertEquals(LocalDate.of(2026, 9, 10), result.dueDate, "rule 4 must not overwrite a due date the user entered independently when rule 2 never fired")
    }

    @Test
    fun `updateHeader falls back to paymentTermsDays for the rule 4 cascade when no payment term is chosen`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(null),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(), paymentTermRepo = paymentTermRepoWith(null),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        assertEquals(14, invoice.paymentTermsDays, "precondition: create()'s own default")
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(
            dateRuleWiringCommand(
                invoice,
                invoiceDate = LocalDate.of(2026, 8, 25),
                servicePeriodTo = LocalDate.of(2026, 8, 31),
                dueDate = null,
                paymentTermId = null,
            ),
        )

        assertEquals(LocalDate.of(2026, 8, 31), result.invoiceDate)
        assertEquals(LocalDate.of(2026, 9, 14), result.dueDate, "no payment term chosen - falls back to Invoice.paymentTermsDays (14)")
    }

    @Test
    fun `updateHeader leaves both dates untouched when no service end is resolvable at all`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(standardTaxRate().edit { isReference = true }),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(
            dateRuleWiringCommand(
                invoice,
                invoiceDate = LocalDate.of(2026, 9, 1),
                servicePeriodTo = null,
                dueDate = LocalDate.of(2026, 9, 15),
                paymentTermId = null,
            ),
        )

        assertEquals(LocalDate.of(2026, 9, 1), result.invoiceDate, "no service end resolvable - rule 2 is a no-op, nothing to correct against")
        assertEquals(LocalDate.of(2026, 9, 15), result.dueDate, "rule 2 never fired, so rule 4 never cascades")
    }

    @Test
    fun `updateHeader leaves invoiceDate untouched when the command carries none`() {
        // date invariants (PO 2026-09-02) — a null command.invoiceDate means "not part of this
        // update", not "clear it" (Invoice.invoiceDate is mandatory from creation onward); in
        // practice the entry pane's DatePicker is a required field so this never happens from the
        // UI, but applyHeaderMetadata's own guard is what this test exercises directly.
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        val originalInvoiceDate = invoice.invoiceDate
        invoiceRepo.save(invoice)

        val result = headerUpdateService.updateHeader(
            dateRuleWiringCommand(invoice, invoiceDate = null, servicePeriodTo = null, dueDate = null, paymentTermId = null),
        )

        assertEquals(originalInvoiceDate, result.invoiceDate, "kein invoiceDate im Kommando -> das bestehende Datum bleibt unangetastet")
    }

    @Test
    fun `updateHeader rejects an unrecognized sellerVatRegistrationCountry alpha2 code`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(senderWithLuRegistration(1L)), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, sellerVatRegistrationCountry = "XX"))
        }
        assertTrue(ex.message!!.contains("XX"), "die Meldung muss das unbekannte Kuerzel nennen: ${ex.message}")
    }

    @Test
    fun `updateHeader rejects a seller VAT registration when neither selectedSeller nor senderProfile is set at all`() {
        // effectiveSeller = invoice.selectedSeller ?: invoice.senderProfile can itself be null —
        // senderProfileId is a nullable, "editable afterwards" command field (same as
        // paymentTermId/b2bProfileId elsewhere), so a save that clears it to null while the
        // invoice also carries no selectedSeller override reaches hasRegistration with a null
        // effectiveSeller. Guards against a raw NPE on effectiveSeller?.name in the rejection
        // message.
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 2L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildHeaderUpdateService()
        val invoice = service.create(1L, 2L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            headerUpdateService.updateHeader(customUpdateHeaderCommand(invoice, senderProfileId = null, sellerVatRegistrationCountry = "LU"))
        }
        assertTrue(ex.message!!.contains("LU"), "die Meldung muss das angeforderte Laenderkuerzel nennen: ${ex.message}")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // COMPREHENSIVE LIFECYCLE TESTS — State Machine, Item Categories, Cancellation
    // ──────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────
    // State Machine Edge Matrix — All Legal and Illegal Transitions
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `RG1-StateMatrix-01 - DRAFT to IN_REVIEW is legal`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertEquals(InvoiceStatus.DRAFT, invoice.status)
        val result = service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)

        assertEquals(InvoiceStatus.IN_REVIEW, result.status)
    }

    @Test
    fun `RG1-StateMatrix-02 - IN_REVIEW to ISSUED is legal`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)

        val finalized = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, finalized.status)
    }

    @Test
    fun `RG1-StateMatrix-03 - DRAFT to ISSUED is illegal`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }
        assertEquals(InvoiceStatus.DRAFT, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `RG1-StateMatrix-04 - DRAFT to VOIDED is legal`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoiceRepo.save(invoice)

        val voided = service.transition(invoice.id!!, InvoiceStatus.VOIDED)

        assertEquals(InvoiceStatus.VOIDED, voided.status)
    }

    @Test
    fun `RG1-StateMatrix-05 - IN_REVIEW to VOIDED is legal`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)

        val voided = service.transition(invoice.id!!, InvoiceStatus.VOIDED)

        assertEquals(InvoiceStatus.VOIDED, voided.status)
    }

    @Test
    fun `RG1-StateMatrix-06 - IN_REVIEW to DRAFT is legal (back to editing)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)

        val backToDraft = service.transition(invoice.id!!, InvoiceStatus.DRAFT)

        assertEquals(InvoiceStatus.DRAFT, backToDraft.status)
    }

    @Test
    fun `RG1-StateMatrix-07 - ISSUED to IN_REVIEW is illegal (immutable)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        }
        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `RG1-StateMatrix-08 - ISSUED to DRAFT is illegal (immutable)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.DRAFT)
        }
    }

    @Test
    fun `RG1-StateMatrix-09 - ISSUED to VOIDED is illegal (immutable)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.VOIDED)
        }
    }

    @Test
    fun `RG1-StateMatrix-10 - VOIDED to any other state is illegal (immutable)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.VOIDED)

        assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.DRAFT)
        }
    }

    // ── Item Lifecycle and comprehensive cancellation tests ────

    @Test
    fun `RG1-ItemCategory-01 - SERVICE item lifecycle`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val taxRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), taxRateRepo = taxRateRepoWith(taxRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), taxRateRepo = taxRateRepoWith(taxRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)

        val command = de.littletrotter.application.invoice.InvoiceItemCommand(
            category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
            description = "Consulting services",
            quantity = java.math.BigDecimal("10"),
            unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
            unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100")),
            taxRateId = taxRate.id!!,
        )
        val result = itemMutationService.addItem(invoice.id!!, command)

        assertEquals(1, result.itemList.size)
        val item = result.itemList.single()
        assertEquals(de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE, item.category)
    }

    @Test
    fun `RG1-Cancellation-01 - ST-3 - createCreditNote creates the credit note directly in CANCELLED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val cancellationService = de.littletrotter.application.invoice.InvoiceCancellationService(
            invoiceRepo,
            invoiceRepo,
            DomainEventDispatcher(RecordingEventBus(), null),
            de.littletrotter.application.invoice.InvoiceNumberAssigner { it.edit { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("ST-2026-0001") } },
            TEST_TIME,
        )

        val original = service.create(1L, 1L)
        original.addItem(populatedItem())
        original.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(original)
        service.transition(original.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(original.id!!, InvoiceStatus.ISSUED)

        val creditNote = cancellationService.createCreditNote(original.id!!, reason = "Testgrund")

        assertEquals(InvoiceStatus.CANCELLED, creditNote.status)
        assertEquals(original.invoiceNumber.value, creditNote.originalInvoiceNumber?.value)
    }

    @Test
    fun `RG1-Cancellation-02 - a credit note is read-only from birth`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val cancellationService = de.littletrotter.application.invoice.InvoiceCancellationService(
            invoiceRepo,
            invoiceRepo,
            DomainEventDispatcher(RecordingEventBus(), null),
            de.littletrotter.application.invoice.InvoiceNumberAssigner { it.edit { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("ST-2026-0001") } },
            TEST_TIME,
        )

        val original = service.create(1L, 1L)
        original.addItem(populatedItem())
        original.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(original)
        service.transition(original.id!!, InvoiceStatus.IN_REVIEW)
        service.transition(original.id!!, InvoiceStatus.ISSUED)

        val creditNote = cancellationService.createCreditNote(original.id!!, reason = "Testgrund")

        // PO order 2026-08-31 — a credit note is the system's automatic 1:1 copy, not a draft
        // someone is meant to keep editing — ST-3 makes it terminal from birth as well.
        assertTrue(creditNote.isReadOnly(), "a credit note must be read-only regardless of its own status")
        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            service.save(creditNote)
        }
    }

    @Test
    fun `RG1-Immutability-01 - isReadOnly returns true after ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(), gateStageConfig = "OBSERVE",
        ).build()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.edit { references.buyerReference = "TEST-001" }
        invoiceRepo.save(invoice)
        service.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
        val finalized = service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertTrue(finalized.isReadOnly(), "isReadOnly must return true after ISSUED")
    }

    @Test
    fun `RG1-Deletion-01 - a discarded draft is voided, never deleted, and keeps its number`() {
        // N5 — InvoiceLifecycleService no longer offers a hard delete: discarding a draft goes
        // through VOIDED, which keeps the row and the number it was numbered with at create()
        // (see InvoiceStateMachine.isIssued's KDoc) instead of tearing a gap in the sequence.
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()),
            RecordingEventBus(),
        ).build()
        val invoice = service.create(1L, 1L)
        val id = invoice.id!!
        val numberAtCreation = invoice.invoiceNumber.value
        invoiceRepo.save(invoice)

        val voided = service.transition(id, InvoiceStatus.VOIDED)

        assertTrue(invoiceRepo.findById(id) != null, "voiding must not delete the row")
        assertEquals(InvoiceStatus.VOIDED, voided.status)
        assertEquals(numberAtCreation, voided.invoiceNumber.value)
        assertTrue(voided.invoiceNumber.isAssigned())
    }

    // ── updateItem — Feldaenderungen, Steuerreferenz, Reisekostenpositionen ────

    private fun taxRateRepoWith(vararg rates: de.littletrotter.domain.reference.TaxRate): TaxRateRepository =
        object : TaxRateRepository {
            private val data = rates.associateBy { it.id!! }
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String?, sortDir: SortDirection) = data.values.toList()
            override fun count(filterText: String?): Long = data.size.toLong()
            override fun countInactive(filterText: String?): Long = data.values.count { !it.isActive }.toLong()
            override fun findById(id: Long) = data[id]
            override fun findValidAt(date: LocalDate) = data.values.toList()
            override fun findDefault(): de.littletrotter.domain.reference.TaxRate? = null
            override fun findDefaults(): List<de.littletrotter.domain.reference.TaxRate> = emptyList()
            override fun save(rate: de.littletrotter.domain.reference.TaxRate) = rate
            override fun delete(id: Long) {}
            override fun isInUse(id: Long): Boolean = false
            override fun usageCounts(): Map<Long, Long> = emptyMap()
        }

    // perDiemRateRepoWith (the PerDiemRateRepository test fake) deleted: its only callers
    // were the PER_DIEM/TRIP addItem/updateItem tests moved to TripServiceTest (grep-proof: zero
    // remaining call sites in this file once those tests moved).

    @Test
    fun `updateItem throws InvoiceNotFoundException for an unknown invoice id`() {
        val service = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            InMemoryInvoiceRepository(), senderRepoWith(sender(id = 1L, prefix = "RE-")),
            recipientRepoWith(recipient(id = 1L)), InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()

        assertThrows(de.littletrotter.domain.exception.InvoiceNotFoundException::class.java) {
            itemMutationService.updateItem(999L, minimalItemCommand(itemId = 1L))
        }
    }

    @Test
    fun `updateItem refuses to touch a finalized invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId = invoice.itemList.single().id))
        }
    }

    @Test
    fun `updateItem throws when the given item carries no id`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertThrows(IllegalArgumentException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId = null))
        }
    }

    @Test
    fun `updateItem throws InvoiceItemNotFoundException when no item with that id exists on the invoice`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceItemNotFoundException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId = 987654L))
        }
    }

    @Test
    fun `updateItem applies field changes and re-derives amounts, clearing per-diem entries when none are supplied`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val newRate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("7")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(newRate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(newRate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        // InMemoryInvoiceRepository does not simulate item-level id generation (@GeneratedValue
        // has no persistence context here) -- assign one by hand, exactly as a real save would.
        invoice.itemList.single().edit { id = 501L }
        // A pending domain event so updateItem's own `pullDomainEvents().forEach` below has
        // something to iterate at least once (production always saves right after every mutation).
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoiceRepo.save(invoice)
        val existingId = invoice.itemList.single().id

        val updated = itemMutationService.updateItem(
            invoice.id!!,
            de.littletrotter.application.invoice.InvoiceItemCommand(
                itemId = existingId,
                description = "Geaenderte Beratung",
                deliveryNoteReference = "LS-42",
                quantity = java.math.BigDecimal("3"),
                unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
                unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("50")),
                // R7 — category is fixed by the selected catalogue editor; this update keeps the
                // existing SERVICE category and changes only the editable values.
                category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
                taxRateId = 9L,
            ),
        )

        val item = updated.itemList.single()
        assertEquals("Geaenderte Beratung", item.description)
        assertEquals("LS-42", item.deliveryNoteReference)
        assertEquals(0, java.math.BigDecimal("3").compareTo(item.quantity))
        assertEquals(de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR, item.unit)
        assertEquals(de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE, item.category)
        assertEquals(newRate, item.taxRateRef)
        assertTrue(item.perDiemEntryList.isEmpty(), "keine mitgegebenen Reisekosten -> die Liste bleibt leer")
        // net = 3 x 50.00 = 150.00, vat 7% = 10.50, gross = 160.50
        assertEquals(0, java.math.BigDecimal("160.50").compareTo(updated.totals.grossAmount.amount))
    }

    @Test
    fun `updateItem overwrites and can clear BT-154 itemDescription, mirroring description's plain assignment`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem().edit { itemDescription = "Alte Beschreibung" })
        invoice.itemList.single().edit { id = 501L }
        invoiceRepo.save(invoice)
        val existingId = invoice.itemList.single().id

        val updated = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(existingId).copy(itemDescription = "Neue Beschreibung", taxRateId = rate.id))
        assertEquals("Neue Beschreibung", updated.itemList.single().itemDescription)

        val cleared = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(existingId).copy(taxRateId = rate.id))
        org.junit.jupiter.api.Assertions.assertNull(cleared.itemList.single().itemDescription, "ein weggelassenes itemDescription im Command leert das Feld, wie description es auch tut")
    }

    // ── Monkey finding (2026-08-30) — "updateItem taxRateId silently ineffective": a bare
    // `target.taxRateRef = ...` assignment never re-ran InvoiceItem.snapshotTaxRule, so once an
    // item had gone through the real addItem path (which DOES snapshot), a caller passing a
    // different taxRateId to updateItem changed the live reference but effectiveTaxRate() kept
    // returning the old frozen rate — the computed VAT never moved. The tests above use
    // populatedItem()/invoice.addItem() directly, which never snapshots in the first place and so
    // never exercised the bug; the tests below go through the real service.addItem() to actually
    // snapshot first. ──

    @Test
    fun `updateItem re-applies and re-snapshots the tax rule when a caller passes a different taxRateId`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rateA = de.littletrotter.domain.reference.TaxRate().edit { id = 1L; code = "A19"; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val rateB = de.littletrotter.domain.reference.TaxRate().edit { id = 2L; code = "B07"; rate = TaxRatePercent(java.math.BigDecimal("7")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA, rateB),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA, rateB),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        // Real addItem — unlike populatedItem()/invoice.addItem() above, this DOES snapshot the
        // tax rule (InvoiceItem.applyTaxRule), the exact precondition the bug needed to manifest.
        val withItem = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRateId = 1L))
        // InMemoryInvoiceRepository does not simulate item-level id generation — assign one by
        // hand, exactly as a real save would (see the other updateItem tests above).
        withItem.itemList.single().edit { id = 501L }
        val itemId = withItem.itemList.single().id
        assertEquals(0, java.math.BigDecimal("19").compareTo(withItem.itemList.single().effectiveTaxRate()), "sanity: snapshotted at 19%")

        val updated = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId).copy(taxRateId = 2L))

        val item = updated.itemList.single()
        assertEquals(rateB, item.taxRateRef)
        assertEquals(
            0, java.math.BigDecimal("7").compareTo(item.effectiveTaxRate()),
            "a different taxRateId must actually move the frozen snapshot, not just the live pointer",
        )
        // net = 1 x 100.00 = 100.00, vat 7% = 7.00, gross = 107.00
        assertEquals(0, java.math.BigDecimal("107.00").compareTo(updated.totals.grossAmount.amount))
    }

    @Test
    fun `updateItem passing null taxRateId is a no-op that preserves the existing taxRateRef and frozen snapshot`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rateA = de.littletrotter.domain.reference.TaxRate().edit { id = 1L; code = "A19"; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val withItem = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRateId = 1L))
        withItem.itemList.single().edit { id = 501L }
        val itemId = withItem.itemList.single().id

        val updated = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId))

        val item = updated.itemList.single()
        assertEquals(rateA, item.taxRateRef, "a missing taxRateId means \"no change\": the existing live reference stays exactly as it was")
        assertEquals(0, java.math.BigDecimal("19").compareTo(item.effectiveTaxRate()), "the frozen snapshot must survive a null taxRateId untouched")
        // net = 1 x 100.00 = 100.00, vat 19% (frozen) = 19.00, gross = 119.00
        assertEquals(0, java.math.BigDecimal("119.00").compareTo(updated.totals.grossAmount.amount))
    }

    @Test
    fun `updateItem passing null taxRateId leaves an already-null taxRateRef null`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rate = de.littletrotter.domain.reference.TaxRate().edit { id = 9L; code = "A19"; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rate),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val withItem = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRateId = 9L))
        withItem.itemList.single().edit { id = 501L }
        val itemId = withItem.itemList.single().id
        // Simulates a live reference that is already gone (e.g. the master-data row was deleted)
        // while the frozen taxRuleCode/taxRateSnapshot -- taken at addItem time -- survives, so
        // effectiveTaxRate() still resolves without needing taxRateRef.
        withItem.itemList.single().edit { taxRateRef = null }

        val updated = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId))

        org.junit.jupiter.api.Assertions.assertNull(
            updated.itemList.single().taxRateRef,
            "a missing taxRateId is a no-op; an item that already had no live reference still has none",
        )
    }

    @Test
    fun `updateItem throws for an unknown taxRateId, mirroring addItem's own required-rate check`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rateA = de.littletrotter.domain.reference.TaxRate().edit { id = 1L; code = "A19"; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val withItem = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRateId = 1L))
        withItem.itemList.single().edit { id = 501L }
        val itemId = withItem.itemList.single().id

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId).copy(taxRateId = 999L))
        }
        assertTrue(ex.message!!.contains("Unknown tax rate id"))
    }

    @Test
    fun `updateItem passing the same taxRateId keeps the frozen tax snapshot unchanged (existing contract)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val rateA = de.littletrotter.domain.reference.TaxRate().edit { id = 1L; code = "A19"; rate = TaxRatePercent(java.math.BigDecimal("19")) }
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            taxRateRepo = taxRateRepoWith(rateA),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val withItem = itemMutationService.addItem(invoice.id!!, populatedCommand(taxRateId = 1L))
        withItem.itemList.single().edit { id = 501L }
        val itemId = withItem.itemList.single().id

        val updated = itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId).copy(taxRateId = 1L))

        val item = updated.itemList.single()
        assertEquals(rateA, item.taxRateRef)
        assertEquals(0, java.math.BigDecimal("19").compareTo(item.effectiveTaxRate()))
    }

    @Test
    fun `updateItem rejects TRIP outright — a business trip goes through TripService now`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        invoice.addItem(itemWithCategory("S"))
        invoice.itemList.single().edit { id = 701L }
        invoiceRepo.save(invoice)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId = 701L).copy(category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP))
        }
        assertTrue(ex.message!!.contains("TripService"))
    }

    @Test
    fun `updateItem rejects switching an existing TRIP item away from TRIP — same TripService-only rule, the other direction`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).build()
        val itemMutationService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepoWith(sender(id = 1L, prefix = "RE-")), recipientRepoWith(recipient(id = 1L)),
            InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
        ).buildItemMutationService()
        val invoice = service.create(1L, 1L)
        val trip = de.littletrotter.domain.invoice.InvoiceItem().edit {
            positionId = "1"; description = "Workshop Hamburg"
            category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
            tripFixedNetPrice = de.littletrotter.shared.Money(java.math.BigDecimal("500"))
        }
        invoice.addItem(trip)
        invoice.itemList.single().edit { id = 702L }
        invoiceRepo.save(invoice)

        assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            itemMutationService.updateItem(invoice.id!!, minimalItemCommand(itemId = 702L))
        }
    }

    // The PER_DIEM/TRIP-specific updateItem cases this block used to hold (wholesale
    // entry rebuild, unknown per-diem rate id, re-deriving quantity/unit/price/tax, positive
    // total without another main-service item) moved to TripServiceTest, exercising TripService
    // .updateTrip directly: updateItem itself now only ever sees SERVICE/PRODUCT and rejects a
    // TRIP target outright (see the test right above).

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun populatedItem() = de.littletrotter.domain.invoice.InvoiceItem().edit {
        positionId = "1"; description = "Beratung"
        quantity = java.math.BigDecimal("1"); unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100"))
        taxRateRef = de.littletrotter.domain.reference.TaxRate().edit { rate = TaxRatePercent(java.math.BigDecimal("19")) }
        netAmount = de.littletrotter.shared.Money(java.math.BigDecimal("100"))
        vatAmount = de.littletrotter.shared.Money(java.math.BigDecimal("19")); grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("119"))
    }

    // W4 — the command-facing counterparts of [populatedItem]/a bare item id, for
    // InvoiceLifecycleService.addItem/updateItem's InvoiceItemCommand-based signature.
    // [populatedCommand] needs a real, repository-resolvable [taxRateId] (addItem now resolves it
    // itself, mirroring updateHeader's id-resolution — there is no live TaxRate reference to smuggle
    // through any more); [minimalItemCommand] is for the error-path tests that never reach tax
    // resolution (not-found/finalized/no-id checks all run before any command field is read).
    private fun populatedCommand(taxRateId: Long) = de.littletrotter.application.invoice.InvoiceItemCommand(
        category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
        description = "Beratung",
        quantity = java.math.BigDecimal("1"),
        unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
        unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100")),
        taxRateId = taxRateId,
    )

    private fun minimalItemCommand(itemId: Long? = null) = de.littletrotter.application.invoice.InvoiceItemCommand(
        itemId = itemId,
        category = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE,
        description = "Beratung",
        quantity = java.math.BigDecimal("1"),
        unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
        unitPriceNet = de.littletrotter.shared.Money(java.math.BigDecimal("100")),
    )

    private fun sender(id: Long, prefix: String) = aSenderProfile {
        this.id = id; name = "Acme GmbH"; invoicePrefix = prefix
        email = de.littletrotter.domain.value.Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
        phone = "+49 30 1234567" // N9 — BT-42, required unconditionally by FinalizationPolicy.enforceSellerTelephoneNumber at ISSUED
        designateBillingAddress(
            de.littletrotter.domain.masterdata.Address.forSender(
                entity,
                de.littletrotter.domain.masterdata.Address.AddressType.BILLING,
                "Musterstr. 1",
                "10115",
                "Berlin",
                de.littletrotter.shared.country.Country.DE,
                true,
            )
        )
    }

    private fun recipient(id: Long) = aRecipient {
        this.id = id; name = "Kunde GmbH"; customerNumber = CustomerNumber("KD-001")
        email = de.littletrotter.domain.value.Email("kunde@kunde-gmbh.example")
        vatId = VatId("DE987654321")
        iban = de.littletrotter.domain.value.Iban("DE02120300000000202051")
    }

    private fun senderRepoWith(
        vararg senders: SenderProfile,
        onFindAll: ((pageIndex: Int, pageSize: Int, filterText: String?) -> Unit)? = null,
    ): SenderProfileRepository =
        object : SenderProfileRepository {
            override fun forceVersionIncrement(profile: SenderProfile) { profile.edit { version = (profile.version ?: 0) + 1 } }
            private val data = senders.associateBy { it.id!! }.toMutableMap()
            override fun findById(id: Long) = data[id]
            override fun findByIdForEdit(id: Long) = data[id]
            override fun findDefault() = data.values.firstOrNull { it.isDefault }
            override fun findDefaults() = data.values.filter { it.isDefault }
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> {
                onFindAll?.invoke(pageIndex, pageSize, filterText)
                return data.values.toList()
            }
            override fun count(filterText: String?): Long = data.size.toLong()
            override fun save(profile: SenderProfile) = profile.also { data[it.id!!] = it }
            override fun delete(id: Long) { data.remove(id) }
        }

    private fun recipientRepoWith(
        vararg recipients: Recipient,
        onFindAll: ((pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection) -> Unit)? = null,
    ): RecipientRepository =
        object : RecipientRepository {
            private val data = recipients.associateBy { it.id!! }.toMutableMap()
            override fun findById(id: Long) = data[id]
            override fun findByCustomerNumber(number: String) = data.values.firstOrNull { it.customerNumber?.value == number }
            override fun findAll(
                pageIndex: Int,
                pageSize: Int,
                filterText: String?,
                sortField: String,
                sortDir: SortDirection,
            ): List<Recipient> {
                onFindAll?.invoke(pageIndex, pageSize, filterText, sortField, sortDir)
                return data.values.toList()
            }
            override fun count(filterText: String?): Long = data.size.toLong()
            override fun save(recipient: Recipient) = recipient.also { data[it.id!!] = it }
            override fun forceVersionIncrement(recipient: Recipient) { recipient.edit { version = (recipient.version ?: 0) + 1 } }
            override fun delete(id: Long) { data.remove(id) }
            override fun findNamesByB2bProfileId(b2bProfileId: Long): List<String> =
                data.values.filter { it.b2bProfile?.id == b2bProfileId }.map { it.name }
            override fun countGroupedByB2bProfile(): Map<Long, Long> =
                data.values.mapNotNull { it.b2bProfile?.id }.groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }

    private class InMemoryInvoiceRepository : InvoiceRepository, de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository, de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository {
        private val data = linkedMapOf<Long, Invoice>()
        private var seq = 0L
        override fun findById(id: Long) = data[id]
        override fun findInvoiceByItemId(itemId: Long): de.littletrotter.domain.invoice.Invoice? = null
        override fun findByIdWithItems(id: Long) = data[id]
        override fun findByInvoiceNumber(number: String) = data.values.firstOrNull { it.invoiceNumber.value == number }
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun findAll(pageIndex: Int, pageSize: Int) = data.values.toList()
        override fun count() = data.size.toLong()
        override fun countByStatus(status: InvoiceStatus) = data.values.count { it.status == status }.toLong()
        override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int) = data.values.filter { it.recipient?.id == recipientId }
        override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int) = data.values.filter { it.senderProfile?.id == senderProfileId }
        override fun existsByBillingAddressId(addressId: Long): Boolean = data.values.any { it.billingAddress?.id == addressId }
        override fun existsByDeliveryAddressId(addressId: Long): Boolean = data.values.any { it.deliveryAddress?.id == addressId }
        override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = count()
        override fun findAllForRecalculation(): List<Invoice> = data.values.toList()
        override fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: de.littletrotter.shared.country.Country): Long = 0
        override fun existsByB2bProfileId(b2bProfileId: Long): Boolean = false
        override fun findAllIdsForRecalculation(): List<Long> = findAllForRecalculation().mapNotNull { it.id }
        override fun findByOriginalInvoiceNumber(number: String): List<de.littletrotter.domain.invoice.Invoice> =
            findAll(0, Int.MAX_VALUE).filter { it.originalInvoiceNumber?.value == number }
        override fun findFinalizedInvoiceNumberAssignments(): List<de.littletrotter.domain.repository.InvoiceNumberAssignment> = emptyList()
        override fun findItemPositions(itemIds: Collection<Long>): Map<Long, String> = emptyMap()
        override fun save(invoice: Invoice): Invoice {
            val id = invoice.id ?: ++seq
            invoice.edit { this.id = id }
            data[id] = invoice
            return invoice
        }
        override fun forceVersionIncrement(invoice: Invoice) { invoice.edit { version = (invoice.version ?: 0L) + 1L } }
    }

    private class RecordingEventBus : Event<DomainEvent> {
        val fired = mutableListOf<DomainEvent>()
        override fun fire(event: DomainEvent) { fired.add(event) }
        override fun <U : DomainEvent> fireAsync(event: U): CompletionStage<U> {
            fired.add(event)
            return CompletableFuture.completedFuture(event)
        }
        override fun <U : DomainEvent> fireAsync(event: U, options: NotificationOptions): CompletionStage<U> {
            fired.add(event)
            return CompletableFuture.completedFuture(event)
        }
        override fun select(vararg qualifiers: kotlin.Annotation): Event<DomainEvent> = this
        override fun <U : DomainEvent> select(subtype: Class<U>, vararg qualifiers: kotlin.Annotation): Event<U> = throw UnsupportedOperationException()
        override fun <U : DomainEvent> select(subtype: TypeLiteral<U>, vararg qualifiers: kotlin.Annotation): Event<U> = throw UnsupportedOperationException()
    }

    // ── Rule evaluation at the status transition ───────────────────────────────────
    // Two statements that belong together: the default does **not** block (otherwise no
    // legacy document could be finalized anymore), and the BLOCK stage actually does block
    // (otherwise rule evaluation would be for show only). Without the second test,
    // "everything passes" would look identical to "the rules are never evaluated".

    @Test
    fun `T5a - OBSERVE laesst eine Rechnung mit blockierenden Befunden durch`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)

        gateService(invoiceRepo, stage = "OBSERVE", complianceStage = "OBSERVE")
            .transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `T5a - BLOCK haelt dieselbe Rechnung auf und nennt die Regel`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val invoice = financializableInvoice(invoiceRepo, ValidationStatus.VALID)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            gateService(invoiceRepo, stage = "OBSERVE", complianceStage = "BLOCK")
                .transition(invoice.id!!, InvoiceStatus.ISSUED)
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertTrue(ex.findings.isNotEmpty(), "der Wurf muss die Befunde mitführen, nicht nur den Grund")
        assertTrue(
            ex.findings.all { it.validator == "compliance-rules" },
            "die Befunde stammen aus der Regelauswertung, nicht aus einem KoSIT-Lauf",
        )
        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status, "ein blockierter Versuch aendert nichts")
    }

    // ── B2B mandatory fields as a rule set ─────────────────────────────────────────
    // Same split as above (OBSERVE lets through, BLOCK stops), plus the two definition-of-done
    // points not needed above: a draft stays savable despite a missing mandatory field, and with
    // no profile assigned nothing changes from the previous behavior (no B2B profile assigned).

    private class FixedB2bProfileRepository(
        private val fields: List<de.littletrotter.domain.reference.B2bProfileField>,
        private val profiles: List<de.littletrotter.domain.reference.B2bProfile> = emptyList(),
    ) : de.littletrotter.domain.repository.B2bProfileRepository {
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortDir: SortDirection) = profiles
        override fun count(filterText: String?): Long = profiles.size.toLong()
        override fun countInactive(filterText: String?): Long = profiles.count { !it.active }.toLong()
        override fun findById(id: Long) = profiles.firstOrNull { it.id == id }
        override fun findAllActive() = profiles.filter { it.active }
        override fun findByName(name: String) = profiles.firstOrNull { it.name == name }
        override fun save(profile: de.littletrotter.domain.reference.B2bProfile) = profile
        override fun delete(id: Long) {}
        override fun findFieldById(fieldId: Long) = fields.firstOrNull { it.id == fieldId }
        override fun findByFieldId(fieldId: Long): de.littletrotter.domain.reference.B2bProfile? = null
        override fun findFieldsByProfileIds(profileIds: Collection<Long>) = profileIds.associateWith { fields }
    }

    private class FixedB2bFieldValueRepository(
        values: List<de.littletrotter.domain.reference.B2bFieldValue> = emptyList(),
    ) : de.littletrotter.application.reference.port.outbound.B2bFieldValueStore {
        private val data = values.toMutableList()
        override fun findByOwner(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerId: Long) =
            data.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override fun findByOwnerAndBtCode(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerId: Long, term: de.littletrotter.shared.b2b.B2bTerm) =
            data.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.term == term }
        override fun findByOwners(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerIds: List<Long>) =
            data.filter { it.ownerType == ownerType && it.ownerId in ownerIds }
        override fun save(value: de.littletrotter.domain.reference.B2bFieldValue) = value.also { data.add(it) }
        override fun delete(id: Long) { data.removeAll { it.id == id } }
    }

    /** One HEADER-level, FREE, MANDATORY term (`BT-19`-shaped, but the rule set never branches on
     *  that code — [de.littletrotter.domain.compliance.B2bProfileRulesTest] is where the "kein BT
     *  literal" claim is actually proven; this is just realistic fixture data). */
    private fun b2bMandatoryHeaderRuleSet(
        values: List<de.littletrotter.domain.reference.B2bFieldValue> = emptyList(),
    ): de.littletrotter.application.invoice.B2bProfileRuleSet {
        val field = de.littletrotter.domain.reference.B2bProfileField().edit {
            term = de.littletrotter.shared.b2b.B2bTerm.BT_19
            requirement = de.littletrotter.domain.reference.B2bFieldRequirement.MANDATORY
            placement = de.littletrotter.domain.reference.B2bPlacement.INVOICE
            businessDefinition = "Buchungsreferenz des Kaeufers"
        }
        return de.littletrotter.application.invoice.B2bProfileRuleSet(
            FixedB2bProfileRepository(listOf(field)),
            FixedB2bFieldValueRepository(values),
            de.littletrotter.application.reference.B2bPartyFieldValues(
                FixedB2bFieldValueRepository(values),
                mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            ),
        )
    }

    /** [recipient] plus a complete billing address — the plain fixture has none, which would
     *  otherwise block ISSUED on `CORE.BUYER_ADDRESS` regardless of B2B, masking exactly the
     *  outcome these tests mean to isolate. */
    private fun recipientWithAddress(id: Long) = recipient(id).apply {
        designateBillingAddress(
            de.littletrotter.domain.masterdata.Address.forRecipient(
                this, de.littletrotter.domain.masterdata.Address.AddressType.BILLING,
                "Kundenweg 2", "50668", "Koeln", de.littletrotter.shared.country.Country.DE, true,
            ),
        )
    }

    /** The B2B profile assignment anchors on the recipient (standing default), not the
     *  seller (decision 12); [InvoiceLifecycleService.create] derives it onto the invoice itself. */
    private fun recipientWithB2bProfile(id: Long, profileId: Long = 77L) =
        recipientWithAddress(id).edit { b2bProfile = de.littletrotter.domain.reference.B2bProfile().edit { this.id = profileId; name = "Standard" } }

    private fun b2bServiceWith(
        invoiceRepo: InMemoryInvoiceRepository,
        senderRepo: SenderProfileRepository,
        complianceStage: String,
        b2bProfileRuleSet: de.littletrotter.application.invoice.B2bProfileRuleSet?,
        recipientRepo: RecipientRepository = recipientRepoWith(recipientWithAddress(id = 1L)),
        b2bFieldValueService: de.littletrotter.application.reference.B2bFieldValueService? = null,
        b2bProfileFieldDefinitionService: de.littletrotter.application.reference.B2bProfileFieldDefinitionService? = null,
        b2bProfileRepo: de.littletrotter.domain.repository.B2bProfileRepository? = null,
    ): InvoiceLifecycleService = InvoiceLifecycleServiceTestBuilder(
        invoiceRepo = invoiceRepo,
        senderRepo = senderRepo,
        recipientRepo = recipientRepo,
        invoiceNumberService = InvoiceNumberService(freshNumberRangeService()),
        domainEventBus = RecordingEventBus(),
        validationProfileRepo = FixedValidationProfileRepository(ValidationProfileState.ACTIVE),
        gateStageConfig = "OBSERVE",
        complianceStageConfig = complianceStage,
    ).apply {
        b2bProfileRuleSet?.let { withB2bProfileRuleSet(it) }
        b2bProfileRepo?.let { withB2bProfileRepo(it) }
        b2bFieldValueService?.let { withB2bFieldValueService(it) }
        b2bProfileFieldDefinitionService?.let { withB2bProfileFieldDefinitionService(it) }
    }.build()

    /** A minimal [de.littletrotter.domain.repository.B2bProfileRepository] fake keyed by
     *  id, for override resolution (`InvoiceLifecycleService.updateHeader`'s `b2bProfileId` path)
     *  and for wiring a real [de.littletrotter.application.reference.B2bProfileFieldDefinitionService]. */
    private fun b2bProfileRepoWith(vararg profiles: de.littletrotter.domain.reference.B2bProfile): de.littletrotter.domain.repository.B2bProfileRepository =
        object : de.littletrotter.domain.repository.B2bProfileRepository {
            private val data = profiles.associateBy { it.id!! }
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortDir: SortDirection) = data.values.toList()
            override fun count(filterText: String?): Long = data.size.toLong()
            override fun countInactive(filterText: String?): Long = data.values.count { !it.active }.toLong()
            override fun findById(id: Long) = data[id]
            override fun findAllActive() = data.values.filter { it.active }
            override fun findByName(name: String) = data.values.firstOrNull { it.name == name }
            override fun save(profile: de.littletrotter.domain.reference.B2bProfile) = profile
            override fun delete(id: Long) {}
            override fun findFieldById(fieldId: Long) = data.values.flatMap { it.fieldList }.firstOrNull { it.id == fieldId }
            override fun findByFieldId(fieldId: Long) = data.values.firstOrNull { p -> p.fieldList.any { it.id == fieldId } }
            override fun findFieldsByProfileIds(profileIds: Collection<Long>) =
                profileIds.mapNotNull { id -> data[id]?.fieldList?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
        }

    private fun financializableB2bInvoice(invoiceRepo: InMemoryInvoiceRepository, service: InvoiceLifecycleService): Invoice {
        val invoice = service.create(1L, 1L)
        invoice.edit { references.buyerReference = "PROJ-TEST" }
        // D-R1 (CORE.SERVICE_PERIOD_XOR): a BLOCK-stage compliance run needs one of the three
        // service-date alternatives; this fixture is about B2B mandatory-field wiring, so the
        // cheapest satisfier (BT-72) keeps it out of the way of that concern.
        invoice.edit { deliveryDate = java.time.LocalDate.of(2026, 1, 15) }
        invoice.addItem(populatedItem())
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.edit { validationStatus = ValidationStatus.VALID }
        invoiceRepo.save(invoice)
        return invoice
    }

    @Test
    fun `T5b - OBSERVE laesst eine Rechnung mit fehlendem B2B-Pflichtfeld durch`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithB2bProfile(id = 1L))
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "OBSERVE", b2bProfileRuleSet = b2bMandatoryHeaderRuleSet(), recipientRepo = recipientRepo)
        val invoice = financializableB2bInvoice(invoiceRepo, service)

        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `T5b - BLOCK haelt eine Rechnung mit fehlendem B2B-Pflichtfeld auf`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithB2bProfile(id = 1L))
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "BLOCK", b2bProfileRuleSet = b2bMandatoryHeaderRuleSet(), recipientRepo = recipientRepo)
        val invoice = financializableB2bInvoice(invoiceRepo, service)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertTrue(ex.findings.any { it.ruleId == de.littletrotter.domain.compliance.B2bProfileRules.MANDATORY_FIELD })
        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `T5b - a filled mandatory B2B field does not block ISSUED`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithB2bProfile(id = 1L))
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "BLOCK", b2bProfileRuleSet = b2bMandatoryHeaderRuleSet(), recipientRepo = recipientRepo)
        val invoice = financializableB2bInvoice(invoiceRepo, service)
        // Fill the mandatory value after the invoice got its id (owner_type INVOICE, owner_id = invoice.id).
        val filledRuleSet = b2bMandatoryHeaderRuleSet(
            values = listOf(
                de.littletrotter.domain.reference.B2bFieldValue().edit {
                    ownerType = de.littletrotter.domain.reference.B2bFieldOwnerType.INVOICE
                    ownerId = invoice.id!!
                    term = de.littletrotter.shared.b2b.B2bTerm.BT_19
                    value = "K-4711"
                },
            ),
        )
        val filledService = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "BLOCK", b2bProfileRuleSet = filledRuleSet, recipientRepo = recipientRepo)

        filledService.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `T5b - Community-Build ohne zugeordnetes B2B-Profil bleibt unveraendert finalisierbar`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        // recipientWithAddress(...) (the b2bServiceWith default) carries no B2bProfile —
        // exactly the "no profile present" case.
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "BLOCK", b2bProfileRuleSet = b2bMandatoryHeaderRuleSet())
        val invoice = financializableB2bInvoice(invoiceRepo, service)

        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    /** DoD: "A draft stays savable even with empty mandatory fields — a test saves an
     *  incomplete invoice and explicitly expects no exception, while the same call still
     *  delivers and persists the expected findings." [InvoiceLifecycleService.save]
     *  never evaluates B2B compliance at all (T5, point 10 — "saving always goes through"); this
     *  test proves both halves: saving an invoice with a missing mandatory B2B field never throws,
     *  and evaluating compliance against the very same (now persisted) invoice still reports it. */
    @Test
    fun `T5b - ein Entwurf mit leerem B2B-Pflichtfeld bleibt speicherbar, der Befund bleibt sichtbar`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(id = 1L, prefix = "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithB2bProfile(id = 1L))
        val ruleSet = b2bMandatoryHeaderRuleSet()
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "BLOCK", b2bProfileRuleSet = ruleSet, recipientRepo = recipientRepo)

        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())

        val saved = service.save(invoice) // must not throw despite the missing BT-19 value

        val findings = de.littletrotter.domain.compliance.ComplianceEvaluator.evaluate(
            saved, de.littletrotter.test.FIXED_TODAY, de.littletrotter.domain.compliance.ComplianceRuleSets.DEFAULT + ruleSet,
        )
        assertTrue(
            findings.any { it.ruleId == de.littletrotter.domain.compliance.B2bProfileRules.MANDATORY_FIELD },
            "der Befund muss trotz erfolgreichem Speichern sichtbar sein: $findings",
        )
    }

    // ── B2B profile at the customer, override at the invoice (decision 12) ──────────

    private fun b2bProfile(id: Long, name: String = "Profil-$id") =
        de.littletrotter.domain.reference.B2bProfile().edit { this.id = id; this.name = name }

    @Test
    fun `AP-9 - create derives the invoice's B2B profile from the recipient's standing default`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientRepo = recipientRepoWith(recipient(1L).edit { b2bProfile = b2bProfile(10L) })
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 1L)

        assertEquals(10L, invoice.b2bProfile?.id, "die Rechnung übernimmt den Standing-Default des Kunden bei der Erstzuordnung")
    }

    @Test
    fun `AP-9 - create leaves the invoice's B2B profile null when the recipient has none (Community behavior)`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientRepo = recipientRepoWith(recipient(1L)) // kein b2bProfile
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()

        val invoice = service.create(1L, 1L)

        assertNull(invoice.b2bProfile)
    }

    /**
     * The three-step story of rule 1 in one test — deliberately not split across three tests,
     * because the point is the *sequence*: derive, override, then a recipient change that
     * overwrites the override. Splitting them would let each half pass against an accidental
     * "always re-derive" or "never re-derive" implementation; only the sequence proves the
     * actual rule.
     */
    @Test
    fun `AP-9 - a manual override survives while the recipient is unchanged, and is overwritten by a recipient change`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientA = recipient(1L).edit { b2bProfile = b2bProfile(10L, "A") }
        val recipientB = recipient(2L).edit { customerNumber = CustomerNumber("KD-002"); b2bProfile = b2bProfile(30L, "B") }
        val recipientRepo = recipientRepoWith(recipientA, recipientB)
        val b2bProfileRepo = b2bProfileRepoWith(b2bProfile(10L, "A"), b2bProfile(20L, "Manuell"), b2bProfile(30L, "B"))
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            b2bProfileRepo = b2bProfileRepo,
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            b2bProfileRepo = b2bProfileRepo,
        ).buildHeaderUpdateService()

        // 1. Derivation on first assignment.
        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        assertEquals(10L, invoice.b2bProfile?.id)

        // 2. Manual override while the recipient stays the same — the "editable afterwards, any
        //    time while draft" half of rule 1.
        val overridden = headerUpdateService.updateHeader(
            updateHeaderCommand(invoice, sellerVatRegistrationCountry = null).copy(recipientId = 1L, b2bProfileId = 20L),
        )
        assertEquals(20L, overridden.b2bProfile?.id, "der manuelle Override muss greifen, solange der Kunde unverändert ist")

        // 3. A recipient change re-derives and overwrites the override — the "bigger event" half.
        val changed = headerUpdateService.updateHeader(
            updateHeaderCommand(overridden, sellerVatRegistrationCountry = null).copy(recipientId = 2L, b2bProfileId = 20L),
        )
        assertEquals(
            30L, changed.b2bProfile?.id,
            "ein Kundenwechsel überschreibt auch einen soeben gesetzten manuellen Override — sonst bliebe stillschweigend der Katalog des alten Kunden stehen",
        )
    }

    @Test
    fun `AP-9 - updateHeader clears the B2B profile back to null when the override is cleared`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientRepo = recipientRepoWith(recipient(1L).edit { b2bProfile = b2bProfile(10L) })
        val service = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus()).buildHeaderUpdateService()

        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        assertEquals(10L, invoice.b2bProfile?.id)

        val cleared = headerUpdateService.updateHeader(
            updateHeaderCommand(invoice, sellerVatRegistrationCountry = null).copy(recipientId = 1L, b2bProfileId = null),
        )

        assertNull(cleared.b2bProfile, "b2bProfileId = null räumt einen Override zurück auf kein Profil")
    }

    @Test
    fun `AP-9 - the B2B profile is frozen once ISSUED, unmoved by a later change to the recipient's standing default`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipient = recipientWithAddress(id = 1L).edit { b2bProfile = b2bProfile(10L) }
        val recipientRepo = recipientRepoWith(recipient)
        val service = b2bServiceWith(invoiceRepo, senderRepo, complianceStage = "OBSERVE", b2bProfileRuleSet = null, recipientRepo = recipientRepo)
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(invoiceRepo = invoiceRepo).buildHeaderUpdateService()

        val invoice = financializableB2bInvoice(invoiceRepo, service)
        assertEquals(10L, invoice.b2bProfile?.id)
        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        // "Later" — the customer's master data changes (in place, the same instance the fake
        // repository returns, just like a real UPDATE on recipient.b2b_profile_id).
        recipient.edit { b2bProfile = b2bProfile(99L, "Nachträglich") }

        assertEquals(
            10L, invoiceRepo.findById(invoice.id!!)!!.b2bProfile?.id,
            "eine spätere Stammdaten-Änderung am Kunden darf das eingefrorene Profil einer finalisierten Rechnung nicht verschieben",
        )
        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            headerUpdateService.updateHeader(updateHeaderCommand(invoice, sellerVatRegistrationCountry = null).copy(recipientId = 1L, b2bProfileId = 99L))
        }
    }

    @Test
    fun `AP-9 - missingMandatorySellerFields blocks finalization when a mandatory SELLER field of the invoice's own profile is empty`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithAddress(id = 1L).edit { b2bProfile = b2bProfile(10L) })
        val field = de.littletrotter.domain.reference.B2bProfileField().edit {
            term = de.littletrotter.shared.b2b.B2bTerm.BT_56
            requirement = de.littletrotter.domain.reference.B2bFieldRequirement.MANDATORY
            placement = de.littletrotter.domain.reference.B2bPlacement.SELLER
            businessDefinition = "Kontaktstelle des Käufers"
        }
        val b2bProfileFieldDefinitionService = de.littletrotter.application.reference.B2bProfileFieldDefinitionService(
            FixedB2bProfileRepository(listOf(field), listOf(b2bProfile(10L))),
            recipientRepo,
            invoiceRepo,
        )
        val b2bFieldValueService = de.littletrotter.application.reference.B2bFieldValueService(
            FixedB2bFieldValueRepository(emptyList()), // kein SENDER_PROFILE-Wert erfasst
            mock<de.littletrotter.domain.repository.RecipientRepository>(),
            mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            invoiceRepo,
            b2bProfileFieldDefinitionService,
            de.littletrotter.application.reference.B2bPartyFieldValues(
                FixedB2bFieldValueRepository(emptyList()),
                mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            ),
        )
        val service = b2bServiceWith(
            invoiceRepo, senderRepo, complianceStage = "OBSERVE", b2bProfileRuleSet = null,
            recipientRepo = recipientRepo, b2bFieldValueService = b2bFieldValueService,
            b2bProfileFieldDefinitionService = b2bProfileFieldDefinitionService,
        )
        val invoice = financializableB2bInvoice(invoiceRepo, service)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            service.transition(invoice.id!!, InvoiceStatus.ISSUED)
        }

        assertTrue(ex.message!!.contains("Kontaktstelle des Käufers"), "die Meldung muss die anwenderdefinierte Bezeichnung nennen: ${ex.message}")
        assertEquals(InvoiceStatus.IN_REVIEW, invoiceRepo.findById(invoice.id!!)!!.status, "die Finalisierung darf nicht stattgefunden haben")
    }

    @Test
    fun `AP-9 - missingMandatorySellerFields lets finalization through once the seller's own value is captured`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientRepo = recipientRepoWith(recipientWithAddress(id = 1L).edit { b2bProfile = b2bProfile(10L) })
        val field = de.littletrotter.domain.reference.B2bProfileField().edit {
            term = de.littletrotter.shared.b2b.B2bTerm.BT_56
            requirement = de.littletrotter.domain.reference.B2bFieldRequirement.MANDATORY
            placement = de.littletrotter.domain.reference.B2bPlacement.SELLER
            businessDefinition = "Kontaktstelle des Käufers"
        }
        val filledValue = de.littletrotter.domain.reference.B2bFieldValue().edit {
            ownerType = de.littletrotter.domain.reference.B2bFieldOwnerType.SENDER_PROFILE
            ownerId = 1L; term = de.littletrotter.shared.b2b.B2bTerm.BT_56; value = "Einkauf, Frau Meier"
        }
        val b2bProfileFieldDefinitionService = de.littletrotter.application.reference.B2bProfileFieldDefinitionService(
            FixedB2bProfileRepository(listOf(field), listOf(b2bProfile(10L))),
            recipientRepo,
            invoiceRepo,
        )
        val b2bFieldValueService = de.littletrotter.application.reference.B2bFieldValueService(
            FixedB2bFieldValueRepository(listOf(filledValue)),
            mock<de.littletrotter.domain.repository.RecipientRepository>(),
            mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            invoiceRepo,
            b2bProfileFieldDefinitionService,
            de.littletrotter.application.reference.B2bPartyFieldValues(
                FixedB2bFieldValueRepository(listOf(filledValue)),
                mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            ),
        )
        val service = b2bServiceWith(
            invoiceRepo, senderRepo, complianceStage = "OBSERVE", b2bProfileRuleSet = null,
            recipientRepo = recipientRepo, b2bFieldValueService = b2bFieldValueService,
            b2bProfileFieldDefinitionService = b2bProfileFieldDefinitionService,
        )
        val invoice = financializableB2bInvoice(invoiceRepo, service)

        service.transition(invoice.id!!, InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoiceRepo.findById(invoice.id!!)!!.status)
    }

    @Test
    fun `AP-9 - switching the invoice's B2B profile via updateHeader never touches b2b_field_value rows`() {
        // A fake repository that counts every call: the proof isn't "it still works anyway",
        // but "updateHeader never touches this table at all", no matter which profile hangs
        // off the invoice before or after.
        var saveCalls = 0
        var deleteCalls = 0
        val fieldValueRepository = object : de.littletrotter.application.reference.port.outbound.B2bFieldValueStore {
            override fun findByOwner(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerId: Long) = emptyList<de.littletrotter.domain.reference.B2bFieldValue>()
            override fun findByOwnerAndBtCode(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerId: Long, term: de.littletrotter.shared.b2b.B2bTerm) = null
            override fun findByOwners(ownerType: de.littletrotter.domain.reference.B2bFieldOwnerType, ownerIds: List<Long>) = emptyList<de.littletrotter.domain.reference.B2bFieldValue>()
            override fun save(value: de.littletrotter.domain.reference.B2bFieldValue): de.littletrotter.domain.reference.B2bFieldValue { saveCalls++; return value }
            override fun delete(id: Long) { deleteCalls++ }
        }
        val invoiceRepo = InMemoryInvoiceRepository()
        val senderRepo = senderRepoWith(sender(1L, "RE-"))
        val recipientA = recipient(1L).edit { b2bProfile = b2bProfile(10L, "A") }
        val recipientB = recipient(2L).edit { customerNumber = CustomerNumber("KD-002"); b2bProfile = b2bProfile(20L, "B") }
        val recipientRepo = recipientRepoWith(recipientA, recipientB)
        val b2bProfileFieldDefinitionService = de.littletrotter.application.reference.B2bProfileFieldDefinitionService(
            b2bProfileRepoWith(b2bProfile(10L, "A"), b2bProfile(20L, "B")),
            recipientRepo,
            invoiceRepo,
        )
        val b2bFieldValueService = de.littletrotter.application.reference.B2bFieldValueService(
            fieldValueRepository,
            mock<de.littletrotter.domain.repository.RecipientRepository>(),
            mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            invoiceRepo,
            b2bProfileFieldDefinitionService,
            de.littletrotter.application.reference.B2bPartyFieldValues(
                fieldValueRepository,
                mock<de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository>(),
            ),
        )
        val service = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            b2bFieldValueService = b2bFieldValueService, b2bProfileFieldDefinitionService = b2bProfileFieldDefinitionService,
        ).build()
        val headerUpdateService = InvoiceLifecycleServiceTestBuilder(
            invoiceRepo, senderRepo, recipientRepo, InvoiceNumberService(freshNumberRangeService()), RecordingEventBus(),
            b2bFieldValueService = b2bFieldValueService, b2bProfileFieldDefinitionService = b2bProfileFieldDefinitionService,
        ).buildHeaderUpdateService()

        val invoice = service.create(1L, 1L)
        invoice.addItem(populatedItem())
        invoiceRepo.save(invoice)
        assertEquals(10L, invoice.b2bProfile?.id)

        // A customer switch also switches the profile (invoice 1 -> B).
        headerUpdateService.updateHeader(updateHeaderCommand(invoice, sellerVatRegistrationCountry = null).copy(recipientId = 2L))

        assertEquals(0, saveCalls, "die Profil-Zuordnung ist keine Wert-Operation — ein Wechsel darf b2b_field_value nie schreiben")
        assertEquals(0, deleteCalls, "und erst recht nicht löschen — die Werte des alten Profils bleiben unangetastet liegen")
    }

    // ─── fakes for a real NumberRangeService in unit tests ────────────────
    // (the concurrency/rollback guarantees themselves are already covered by Postgres IT
    // tests: NumberCounterIT, N1-18..21 — these fakes only mimic the plain counting semantics.)

    // IMP-3 — factory for tests that only need "some real number assignment" (replacing the
    // by-now-deleted legacy allocator fake), without checking a specific number or the journal
    // itself — the test above already does that ("N2 - transition to ISSUED assigns a number
    // from the real NumberRangeService"). Each call builds fresh fakes so tests don't
    // influence each other's counter state.
    private fun freshNumberRangeService(): de.littletrotter.application.reference.NumberRangeService =
        de.littletrotter.application.reference.NumberRangeService(
            InMemoryNumberRangeRepository(invoiceGaplessRange()),
            InMemoryNumberCounterRepository(),
            de.littletrotter.application.reference.RecordingNumberJournalRepository(),
            TEST_TIME,
        )

    private fun invoiceGaplessRange(): de.littletrotter.domain.reference.NumberRange =
        de.littletrotter.domain.reference.NumberRange().edit {
            code = "INVOICE_GAPLESS"
            displayName = "Rechnungsnummer"
            subject = de.littletrotter.domain.reference.NumberRangeSubject.INVOICE
            startValue = 1
            prefixSource = de.littletrotter.domain.reference.PrefixSource.SELLER_PROFILE
            datePart = de.littletrotter.shared.numbering.DatePart.YEAR_MONTH_DAY
            counterWidth = 5
            resetCycle = de.littletrotter.shared.numbering.ResetCycle.YEARLY
            scope = de.littletrotter.domain.reference.CounterScope.GLOBAL
            continuity = de.littletrotter.domain.reference.Continuity.GAPLESS
            isActive = true
            isDefault = true
        }

    private class InMemoryNumberRangeRepository(
        private val active: de.littletrotter.domain.reference.NumberRange,
    ) : de.littletrotter.domain.repository.NumberRangeRepository {
        override fun findActive(subject: de.littletrotter.domain.reference.NumberRangeSubject) =
            active.takeIf { it.subject == subject && it.isActive }
        override fun findBySubject(subject: de.littletrotter.domain.reference.NumberRangeSubject) = listOf(active).filter { it.subject == subject }
        override fun findById(id: Long) = active.takeIf { it.id == id }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = listOf(active)
        override fun count(filterText: String?) = 1L
        override fun countInactive(filterText: String?) = if (active.isActive) 0L else 1L
        override fun save(range: de.littletrotter.domain.reference.NumberRange) = range
        override fun delete(id: Long) {}
    }

    /** Mimics last_value — counter semantics like `PostgresNumberCounterRepository`, without
     *  transaction/locking behavior (that's `NumberCounterIT`'s job). */
    private class InMemoryNumberCounterRepository : de.littletrotter.domain.repository.NumberCounterRepository {
        private val counters = mutableMapOf<String, Long>()
        private fun key(range: de.littletrotter.domain.reference.NumberRange, scopeKey: String, periodKey: String) =
            "${range.id}/$scopeKey/$periodKey"

        override fun peekNext(range: de.littletrotter.domain.reference.NumberRange, scopeKey: String, periodKey: String): Long =
            counters[key(range, scopeKey, periodKey)]?.let { it + 1 } ?: range.startValue

        private fun allocate(range: de.littletrotter.domain.reference.NumberRange, scopeKey: String, periodKey: String): Long {
            val next = peekNext(range, scopeKey, periodKey)
            counters[key(range, scopeKey, periodKey)] = next
            return next
        }

        override fun allocateUniqueOnly(range: de.littletrotter.domain.reference.NumberRange, scopeKey: String, periodKey: String) =
            allocate(range, scopeKey, periodKey)
        override fun allocateGapless(range: de.littletrotter.domain.reference.NumberRange, scopeKey: String, periodKey: String) =
            allocate(range, scopeKey, periodKey)
        override fun currentValue(subject: de.littletrotter.domain.reference.NumberRangeSubject): Long? = null
    }
}
