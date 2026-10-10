package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.reference.PerDiemService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.InMemoryFeatureSettingRepository
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.application.travel.PerDiemCalculator
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.Money
import jakarta.enterprise.event.Event
import jakarta.enterprise.event.NotificationOptions
import jakarta.enterprise.util.TypeLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [TripService.addTrip]/[TripService.updateTrip] at
 * unit speed against an in-memory [InvoiceRepository] fake, mirroring
 * [de.littletrotter.application.InvoiceLifecycleServiceTest]'s own idiom. The Postgres-backed
 * counterpart (freeze guard, journal, cascade delete) is TripExpenseIT — unchanged here,
 * since TripService reuses the same InvoiceItem/TripExpense entities and trigger.
 */
class TripServiceTest {

    private class InMemoryInvoiceRepository : InvoiceRepository {
        private val data = linkedMapOf<Long, Invoice>()
        private var seq = 0L
        override fun findById(id: Long) = data[id]
        override fun findInvoiceByItemId(itemId: Long): Invoice? = null
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
        override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?): List<Invoice> = findAll(pageIndex, pageSize)
        override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = count()
        override fun findAllForRecalculation(): List<Invoice> = data.values.toList()
        override fun save(invoice: Invoice): Invoice {
            val id = invoice.id ?: ++seq
            invoice.id = id
            data[id] = invoice
            return invoice
        }
        override fun forceVersionIncrement(invoice: Invoice) { invoice.version = (invoice.version ?: 0L) + 1L }
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

    private fun perDiemRateRepoWith(vararg rates: PerDiemRate): PerDiemRateRepository =
        object : PerDiemRateRepository {
            private val data = rates.associateBy { it.id!! }
            override fun findByCountryAndRegionAndDate(country: Country, region: String?, date: LocalDate) = null
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection) = data.values.toList()
            override fun count(filterText: String?): Long = data.size.toLong()
            override fun countInactive(filterText: String?): Long = 0L
            override fun findById(id: Long) = data[id]
            override fun findDefault(): PerDiemRate? = null
            override fun clearAllDefaults() {}
            override fun save(rate: PerDiemRate) = rate
            override fun delete(id: Long) {}
        }

    private fun service(
        invoiceRepo: InvoiceRepository = InMemoryInvoiceRepository(),
        perDiemRates: List<PerDiemRate> = emptyList(),
        featureSettingsGate: FeatureSettingsGate? = null,
        auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
        domainEventBus: Event<DomainEvent> = RecordingEventBus(),
        taxRateRepo: de.littletrotter.domain.repository.TaxRateRepository =
            org.mockito.kotlin.mock<de.littletrotter.domain.repository.TaxRateRepository>().also { repository ->
                org.mockito.kotlin.whenever(repository.findReferences(Country.DE)).thenReturn(listOf(TaxRate().apply {
                    name = "Reference DE"
                    country = Country.DE
                    rate = TaxRatePercent(BigDecimal("19"))
                    category = "S"
                    isReference = true
                }))
            },
    ): TripService {
        val perDiemRepo = perDiemRateRepoWith(*perDiemRates.toTypedArray())
        return TripService(
            invoiceRepo = invoiceRepo,
            domainEventBus = domainEventBus,
            auditActorProvider = auditActorProvider,
            perDiemCalculator = PerDiemCalculator(perDiemRepo, PerDiemService(perDiemRepo)),
            featureSettingsGate = featureSettingsGate,
            taxRateRepo = taxRateRepo, time = FIXED_TIME_SOURCE,
        )
    }

    private fun invoiceWithMainService(repo: InvoiceRepository, taxRate: BigDecimal = BigDecimal("19.00")): Invoice {
        val invoice = Invoice().apply { id = 1L }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        invoice.addItem(
            InvoiceItem().apply {
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                taxRateRef = TaxRate().apply { rate = TaxRatePercent(taxRate) }
            }.also { it.applyTaxRule(it.taxRateRef!!) },
        )
        repo.save(invoice)
        return invoice
    }

    // ── addTrip ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `addTrip uses the country reference and prices from expenses plus per-diem days`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val svc = service(repo, perDiemRates = listOf(perDiemRate))

        val updated = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise Hamburg",
                purpose = "Kundentermin",
                periodFrom = LocalDate.of(2026, 2, 8),
                periodTo = LocalDate.of(2026, 2, 9),
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.MILEAGE, description = "120 km", quantity = BigDecimal("120"),
                        unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30"), sortOrder = 1,
                    ),
                ),
                perDiemDays = listOf(
                    PerDiemEntryCommand(
                        date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false,
                        breakfast = false, lunch = false, dinner = false,
                        description = "Team-Reise Hamburg", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = 4L,
                    ),
                ),
            ),
        )

        val trip = updated.itemList.single { it.category == InvoiceItemCategory.TRIP }
        assertEquals("Dienstreise Hamburg", trip.description)
        assertEquals("Kundentermin", trip.itemDescription)
        assertEquals(LocalDate.of(2026, 2, 8), trip.servicePeriodFrom)
        assertEquals(BigDecimal.ONE, trip.quantity)
        assertEquals(InvoiceItemUnit.FLAT_RATE, trip.unit)
        assertEquals(1, trip.tripExpenses.size)
        assertEquals(Money.of("36.00"), trip.tripExpenses.single().netAmount)
        assertEquals(1, trip.perDiemEntries.size)
        // net = 36.00 (mileage) + 28.00 (per-diem) = 64.00
        assertEquals(Money.of("64.00"), trip.netAmount)
        assertEquals(0, BigDecimal("19.00").compareTo(trip.effectiveTaxRate()), "a trip uses the dated German reference")
    }

    @Test
    fun `addTrip prices a fixed-price trip from the command, ignoring the calculated sum`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        val updated = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Workshop Pauschale",
                fixedNetPrice = Money.of("500.00"),
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.OTHER, description = "Material", quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("50.00"),
                    ),
                ),
            ),
        )

        val trip = updated.itemList.single { it.category == InvoiceItemCategory.TRIP }
        assertEquals(Money.of("500.00"), trip.netAmount)
        assertEquals(Money.of("50.00"), trip.calculatedTripNet(), "the calculated sum stays available for the Excel delta (RK-5)")
    }

    @Test
    fun `addTrip without a main-service item uses the country reference`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = Invoice().apply { id = 1L }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        repo.save(invoice)
        val svc = service(repo)

        val updated = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))

        assertEquals(0, BigDecimal("19.00").compareTo(updated.itemList.single().effectiveTaxRate()))
    }

    // ── WP1 (0.9.10) — expectedVersion / lost-update guard ────────────────────────────────────

    @Test
    fun `addTrip throws OptimisticLockException on a stale expectedVersion, without persisting`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        invoice.version = 5L
        repo.save(invoice)
        val svc = service(repo)

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise", expectedVersion = 4L))
        }
        assertEquals(1, invoice.itemList.size, "no TRIP item must have been appended")
    }

    @Test
    fun `addTrip with a matching expectedVersion succeeds`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        invoice.version = 5L
        repo.save(invoice)
        val svc = service(repo)

        val updated = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise", expectedVersion = 5L))

        assertEquals(2, updated.itemList.size)
    }

    @Test
    fun `addTrip throws InvoiceNotFoundException for an unknown invoice id`() {
        val svc = service()
        assertThrows(InvoiceNotFoundException::class.java) { svc.addTrip(404L, TripCommand(title = "Dienstreise")) }
    }

    @Test
    fun `addTrip throws InvoiceImmutableException once the invoice is SENT`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        invoice.forceStatusForTest(InvoiceStatus.SENT)
        repo.save(invoice)
        val svc = service(repo)

        assertThrows(InvoiceImmutableException::class.java) { svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise")) }
    }

    @Test
    fun `addTrip rejects per-diem days while the PER_DIEM_UI flag is off`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val gate = FeatureSettingsGate(InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE)
        val svc = service(repo, perDiemRates = listOf(perDiemRate), featureSettingsGate = gate)

        val ex = assertThrows(InvoiceValidationException::class.java) {
            svc.addTrip(
                invoice.id!!,
                TripCommand(
                    title = "Dienstreise",
                    perDiemDays = listOf(
                        PerDiemEntryCommand(
                            date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false,
                            breakfast = false, lunch = false, dinner = false,
                            description = "Team-Reise Hamburg", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = 4L,
                        ),
                    ),
                ),
            )
        }
        assertEquals(InvoiceValidationError.PER_DIEM_FEATURE_DISABLED, ex.error)
    }

    @Test
    fun `addTrip rejects an expense referencing a receipt - a brand-new item has none attached yet`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        val ex = assertThrows(InvoiceValidationException::class.java) {
            svc.addTrip(
                invoice.id!!,
                TripCommand(
                    title = "Dienstreise",
                    expenses = listOf(
                        TripExpenseCommand(
                            kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal.ONE,
                            unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"), receiptId = 42L,
                        ),
                    ),
                ),
            )
        }
        assertEquals(InvoiceValidationError.TRIP_EXPENSE_RECEIPT_NOT_ATTACHED, ex.error)
    }

    // ── updateTrip ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateTrip replaces expenses and per-diem days wholesale (orphan removal)`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val svc = service(repo, perDiemRates = listOf(perDiemRate))
        val afterAdd = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                expenses = listOf(
                    TripExpenseCommand(TripExpenseKind.OTHER, "Taxi 1", null, BigDecimal.ONE, InvoiceItemUnit.FLAT_RATE, Money.of("20.00")),
                    TripExpenseCommand(TripExpenseKind.OTHER, "Taxi 2", null, BigDecimal.ONE, InvoiceItemUnit.FLAT_RATE, Money.of("15.00")),
                ),
                perDiemDays = listOf(
                    PerDiemEntryCommand(LocalDate.of(2026, 2, 8), false, false, false, false, "Team-Reise Hamburg", "Kundentermin", Money.ZERO, 4L),
                ),
            ),
        )
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId

        val afterUpdate = svc.updateTrip(
            invoice.id!!,
            positionId,
            TripCommand(
                title = "Dienstreise umbenannt",
                expenses = listOf(
                    TripExpenseCommand(TripExpenseKind.OTHER, "Taxi neu", null, BigDecimal.ONE, InvoiceItemUnit.FLAT_RATE, Money.of("10.00")),
                ),
                // per-diem days dropped entirely on this update
            ),
        )

        val trip = afterUpdate.itemList.single { it.category == InvoiceItemCategory.TRIP }
        assertEquals("Dienstreise umbenannt", trip.description)
        assertEquals(1, trip.tripExpenses.size, "the two old expenses are gone, orphan-removed")
        assertEquals("Taxi neu", trip.tripExpenses.single().description)
        assertTrue(trip.perDiemEntries.isEmpty(), "the per-diem day is gone too")
        assertEquals(Money.of("10.00"), trip.netAmount)
    }

    @Test
    fun `updateTrip throws OptimisticLockException on a stale expectedVersion, without persisting`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        afterAdd.version = 9L
        repo.save(afterAdd)

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt", expectedVersion = 8L))
        }
        assertEquals("Dienstreise", afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.description)
    }

    @Test
    fun `updateTrip with a matching expectedVersion succeeds`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        afterAdd.version = 9L
        repo.save(afterAdd)

        val updated = svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt", expectedVersion = 9L))

        assertEquals("Dienstreise umbenannt", updated.itemList.single { it.category == InvoiceItemCategory.TRIP }.description)
    }

    @Test
    fun `updateTrip rejects a target position whose category is not TRIP`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val serviceItemPositionId = invoice.itemList.single().positionId

        val ex = assertThrows(InvoiceValidationException::class.java) {
            svc.updateTrip(invoice.id!!, serviceItemPositionId, TripCommand(title = "Dienstreise"))
        }
        assertEquals(InvoiceValidationError.TRIP_CATEGORY_SWITCH_NOT_SUPPORTED, ex.error)
    }

    @Test
    fun `boundary - updateTrip attaches an already-linked receipt to its referencing expense`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val tripItem = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }
        val receipt = de.littletrotter.domain.masterdata.Receipt().apply {
            id = 77L
            fileName = "hotel.pdf"
            objectKey = "receipt-pool/hotel.pdf"
            sha256Checksum = "checksum-77"
            sizeBytes = 1024L
            description = "Hotel Beleg"
            documentDate = LocalDate.of(2026, 3, 1)
        }
        tripItem.receipts.add(receipt)

        val updated = svc.updateTrip(
            invoice.id!!,
            tripItem.positionId,
            TripCommand(
                title = "Dienstreise",
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"), receiptId = 77L,
                    ),
                ),
            ),
        )

        val expense = updated.itemList.single { it.category == InvoiceItemCategory.TRIP }.tripExpenses.single()
        assertEquals(receipt, expense.receipt)
    }

    @Test
    fun `boundary - updateTrip throws InvoiceNotFoundException for an unknown invoice id`() {
        val svc = service(InMemoryInvoiceRepository())

        assertThrows(InvoiceNotFoundException::class.java) {
            svc.updateTrip(999L, "does-not-exist", TripCommand(title = "Dienstreise"))
        }
    }

    @Test
    fun `updateTrip throws InvoicePositionNotFoundException for an unknown position id`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        assertThrows(InvoicePositionNotFoundException::class.java) {
            svc.updateTrip(invoice.id!!, "does-not-exist", TripCommand(title = "Dienstreise"))
        }
    }

    @Test
    fun `updateTrip throws InvoiceImmutableException once the invoice is SENT`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        afterAdd.forceStatusForTest(InvoiceStatus.SENT)
        repo.save(afterAdd)

        assertThrows(InvoiceImmutableException::class.java) {
            svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt"))
        }
    }

    @Test
    fun `updateTrip lets a trip that already has per-diem days stay updatable while the flag is off`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val svcOn = service(repo, perDiemRates = listOf(perDiemRate))
        val afterAdd = svcOn.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                perDiemDays = listOf(PerDiemEntryCommand(LocalDate.of(2026, 2, 8), false, false, false, false, "Team-Reise Hamburg", "Kundentermin", Money.ZERO, 4L)),
            ),
        )
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId

        val gateOff = FeatureSettingsGate(InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE)
        val svcOff = service(repo, perDiemRates = listOf(perDiemRate), featureSettingsGate = gateOff)

        val afterUpdate = svcOff.updateTrip(
            invoice.id!!,
            positionId,
            TripCommand(
                title = "Dienstreise umbenannt",
                perDiemDays = listOf(PerDiemEntryCommand(LocalDate.of(2026, 2, 9), true, false, false, false, "Team-Reise Hamburg", "Kundentermin", Money.ZERO, 4L)),
            ),
        )

        assertEquals("Dienstreise umbenannt", afterUpdate.itemList.single { it.category == InvoiceItemCategory.TRIP }.description)
    }

    @Test
    fun `README border case 2 - an update without days while the flag is off keeps the stored per-diem days`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val afterAdd = service(repo, perDiemRates = listOf(perDiemRate)).addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                perDiemDays = listOf(PerDiemEntryCommand(LocalDate.of(2026, 2, 8), false, false, false, false, "Team-Reise Hamburg", "Kundentermin", Money.ZERO, 4L)),
            ),
        )
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        val gateOff = FeatureSettingsGate(InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE)

        val afterUpdate = service(repo, perDiemRates = listOf(perDiemRate), featureSettingsGate = gateOff)
            .updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt"))

        val trip = afterUpdate.itemList.single { it.category == InvoiceItemCategory.TRIP }
        assertEquals("Dienstreise umbenannt", trip.description)
        assertEquals(1, trip.perDiemEntries.size)
        assertEquals(Money.of("28.00"), trip.netAmount)
    }

    @Test
    fun `with the flag on an update without days still removes the stored per-diem days`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val perDiemRate = PerDiemRate().apply { id = 4L; country = Country.DE; fullDayRate = Money.of("28.00") }
        val svc = service(repo, perDiemRates = listOf(perDiemRate))
        val afterAdd = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                perDiemDays = listOf(PerDiemEntryCommand(LocalDate.of(2026, 2, 8), false, false, false, false, "Team-Reise Hamburg", "Kundentermin", Money.ZERO, 4L)),
            ),
        )
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId

        val afterUpdate = svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise ohne Tage"))

        assertEquals(0, afterUpdate.itemList.single { it.category == InvoiceItemCategory.TRIP }.perDiemEntries.size)
    }

    @Test
    fun `boundary - updateTrip with the flag off submitting no days and no stored days leaves an empty per-diem list`() {
        // keepStoredPerDiemDays's third conjunct (target.perDiemEntries.isNotEmpty())
        // false: nothing was ever stored, so there is nothing to preserve either way.
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        val gateOff = FeatureSettingsGate(InMemoryFeatureSettingRepository(mapOf(FeatureFlag.PER_DIEM_UI to false)), time = FIXED_TIME_SOURCE)

        val afterUpdate = service(repo, featureSettingsGate = gateOff)
            .updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt"))

        assertTrue(afterUpdate.itemList.single { it.category == InvoiceItemCategory.TRIP }.perDiemEntries.isEmpty())
    }

    // ── Travel-time Kulanz text and reverse-charge tax rate resolution ──────────────────

    @Test
    fun `boundary - a travel-time expense with a provided rate and blank text keeps the blank text, not the Kulanz fallback`() {
        // rateProvided=true short-circuits the Kulanz condition's `!rateProvided`
        // conjunct, independent of the description being blank too.
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        val afterAdd = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.TRAVEL_TIME, description = "", quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("50.00"), rateProvided = true,
                    ),
                ),
            ),
        )

        val expense = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.tripExpenses.single()
        assertEquals("", expense.description)
    }

    // A "receiptId present but item.receipts.find{} returns null" case was attempted
    // here and deleted: TripCommandValidator.validateReceipts runs first and already rejects any
    // non-null receiptId absent from `target.receipts.mapNotNull{it.id}.toSet()` — the exact same
    // set applyTripFields's own `item.receipts.find{it.id==id}` searches, so that outcome of
    // `receiptId?.let{...find...}` is unreachable through the public addTrip/updateTrip API
    // (proven by this test itself throwing InvoiceValidationException(TRIP_EXPENSE_RECEIPT_NOT_ATTACHED)
    // instead of ever reaching applyTripFields).

    @Test
    fun `boundary - a travel-time expense without a rate and without its own text falls back to the Kulanz wording`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        val afterAdd = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.TRAVEL_TIME, description = "", quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.ZERO, rateProvided = false,
                    ),
                ),
            ),
        )

        val expense = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.tripExpenses.single()
        assertEquals(
            de.littletrotter.domain.invoice.TripExpense.TRAVEL_TIME_KULANZ_TEXT,
            expense.description,
        )
    }

    @Test
    fun `boundary - a travel-time expense with its own description keeps it instead of the Kulanz fallback`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)

        val afterAdd = svc.addTrip(
            invoice.id!!,
            TripCommand(
                title = "Dienstreise",
                expenses = listOf(
                    TripExpenseCommand(
                        kind = TripExpenseKind.TRAVEL_TIME, description = "Fahrt zum Kunden", quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.ZERO, rateProvided = false,
                    ),
                ),
            ),
        )

        val expense = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.tripExpenses.single()
        assertEquals("Fahrt zum Kunden", expense.description)
    }

    @Test
    fun `boundary - updateTrip on a reverse-charge invoice without an eligible recipient rejects the update`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        // Reverse Charge switched on only after the position exists -- an eligible recipient
        // never was, and never is here (no recipient at all), the same as the header toggle
        // being set without master data ever having supported it.
        invoice.reverseCharge = true

        val ex = assertThrows(InvoiceValidationException::class.java) {
            svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise umbenannt"))
        }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, ex.error)
    }

    private fun euBusinessRecipient() = de.littletrotter.domain.masterdata.Recipient().apply {
        customerNumber = CustomerNumber("K-2")
        name = "Client SARL"
        customerType = de.littletrotter.shared.tax.CustomerType.BUSINESS
        vatId = VatId("FR12345678901")
        vatIdVerificationStatus = de.littletrotter.shared.tax.VatIdVerificationStatus.VERIFIED
    }

    @Test
    fun `boundary - updateTrip on a reverse-charge invoice with an eligible recipient applies the RC tax rule`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val rcRate = TaxRate().apply { name = "RC EU B2B"; code = "EU_B2B_RC_SERVICE"; rate = TaxRatePercent(BigDecimal.ZERO); category = "AE" }
        val taxRateRepo = org.mockito.kotlin.mock<de.littletrotter.domain.repository.TaxRateRepository>().also { repository ->
            org.mockito.kotlin.whenever(repository.findReferences(Country.DE)).thenReturn(
                listOf(TaxRate().apply { name = "Reference DE"; country = Country.DE; rate = TaxRatePercent(BigDecimal("19")); category = "S"; isReference = true }),
            )
            org.mockito.kotlin.whenever(repository.findByCode("EU_B2B_RC_SERVICE")).thenReturn(rcRate)
        }
        val svc = service(repo, taxRateRepo = taxRateRepo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        invoice.senderProfile = de.littletrotter.domain.masterdata.SenderProfile().apply { vatId = VatId("DE123456789") }
        invoice.recipient = euBusinessRecipient()
        invoice.reverseCharge = true

        val updated = svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise RC"))

        val tripItem = updated.itemList.single { it.category == InvoiceItemCategory.TRIP }
        assertEquals(rcRate, tripItem.taxRateRef)
    }

    @Test
    fun `boundary - updateTrip on a reverse-charge invoice throws InvalidParameterException when the RC tax rule is missing in master data`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceWithMainService(repo)
        val svc = service(repo)
        val afterAdd = svc.addTrip(invoice.id!!, TripCommand(title = "Dienstreise"))
        val positionId = afterAdd.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        invoice.senderProfile = de.littletrotter.domain.masterdata.SenderProfile().apply { vatId = VatId("DE123456789") }
        invoice.recipient = euBusinessRecipient()
        invoice.reverseCharge = true

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            svc.updateTrip(invoice.id!!, positionId, TripCommand(title = "Dienstreise RC"))
        }
        assertTrue(ex.message!!.contains("EU_B2B_RC_SERVICE"), "message names the missing rule code: ${ex.message}")
    }
}
