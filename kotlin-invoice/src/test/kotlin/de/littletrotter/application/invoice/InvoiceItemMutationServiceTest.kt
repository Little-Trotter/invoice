package de.littletrotter.application.invoice

import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceItemNotFoundException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoicePositionNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import jakarta.enterprise.event.Event
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * Dedicated unit tests for [InvoiceItemMutationService], covering its own responsibility separate
 * from `InvoiceLifecycleService`. Constructs the service directly (not through the facade); tests
 * cover happy path and boundaries with comprehensive validation logic.
 */
class InvoiceItemMutationServiceTest {

    private fun support(
        taxRateRepo: TaxRateRepository = mock(),
        articleRepo: ArticleRepository = mock(),
    ) = InvoiceLifecycleSupport(
        addressManagementService = AddressManagementService(mock<AddressRepository>(), mock(), mock()),
        paymentTermRepo = mock(),
        taxRateRepo = taxRateRepo,
        b2bProfileRepo = mock(),
        invoiceB2bFieldsQueryPort = mock<InvoiceB2bFieldsQueryPort>(),
        articleRepo = articleRepo,
        numberAssigner = mock(),
    )

    private fun service(
        invoiceRepo: InvoiceRepository = mock(),
        taxRateRepo: TaxRateRepository = mock(),
        articleRepo: ArticleRepository = mock(),
        auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
        domainEventBus: Event<DomainEvent> = mock(),
    ) = InvoiceItemMutationService(
        invoiceRepo = invoiceRepo,
        support = support(taxRateRepo, articleRepo),
        auditActorProvider = auditActorProvider,
        domainEventBus = domainEventBus,
        overviewCache = de.littletrotter.application.cache.NoopInvoiceCachePort(),
        transactionSynchronizationRegistry = org.mockito.Mockito.mock(jakarta.transaction.TransactionSynchronizationRegistry::class.java),
    )

    private fun serviceCommand(taxRateId: Long? = 7L, unit: InvoiceItemUnit = InvoiceItemUnit.HOUR) = InvoiceItemCommand(
        category = InvoiceItemCategory.SERVICE,
        description = "Beratung",
        quantity = BigDecimal.ONE,
        unit = unit,
        unitPriceNet = Money(BigDecimal.TEN),
        taxRateId = taxRateId,
    )

    private fun taxRate(id: Long = 7L, code: String = "DE_STANDARD_19") = TaxRate().apply {
        this.id = id
        this.code = code
        this.rate = TaxRatePercent(BigDecimal("19"))
    }

    // ── addItem ──────────────────────────────────────────────────────────

    @Test
    fun `addItem happy path resolves the tax rate, appends the item and saves`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(7L)).thenReturn(taxRate())

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo).addItem(1L, serviceCommand())

        assertEquals(1, saved.itemList.size)
        assertEquals("Beratung", saved.itemList.first().description)
    }

    @Test
    fun `addItem rejects category TRIP`() {
        assertThrows(InvalidParameterException::class.java) {
            service().addItem(1L, serviceCommand().copy(category = InvoiceItemCategory.TRIP))
        }
    }

    @Test
    fun `addItem throws InvoiceNotFoundException for an unknown invoice id`() {
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(99L)).thenReturn(null)

        assertThrows(InvoiceNotFoundException::class.java) {
            service(invoiceRepo = invoiceRepo).addItem(99L, serviceCommand())
        }
    }

    @Test
    fun `addItem throws InvoiceImmutableException on a read-only invoice`() {
        val invoice = Invoice().apply { id = 1L }
        invoice.forceStatusForTest(InvoiceStatus.SENT)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).addItem(1L, serviceCommand())
        }
    }

    @Test
    fun `a voided invoice rejects adding and deleting items even though a reproduction may render it`() {
        val invoice = Invoice().apply { id = 1L }
        invoice.forceStatusForTest(InvoiceStatus.VOIDED)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).addItem(1L, serviceCommand())
        }
        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "1")
        }
    }

    @Test
    fun `addItem without a tax rate id and no article throws InvalidParameterException`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo).addItem(1L, serviceCommand(taxRateId = null))
        }
    }

    @Test
    fun `addItem with an unknown article id throws InvalidParameterException`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        val articleRepo = mock<ArticleRepository>()
        whenever(articleRepo.findById(42L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo, articleRepo = articleRepo).addItem(1L, serviceCommand().copy(articleId = 42L))
        }
    }

    @Test
    fun `addItem with an article whose category disagrees throws InvalidParameterException`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        val articleRepo = mock<ArticleRepository>()
        whenever(articleRepo.findById(42L)).thenReturn(Article().apply { id = 42L; category = InvoiceItemCategory.PRODUCT })

        assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo, articleRepo = articleRepo).addItem(1L, serviceCommand().copy(articleId = 42L))
        }
    }

    @Test
    fun `addItem wraps an invalid unit for the category as InvalidParameterException with the original cause`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        val ex = assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo).addItem(1L, serviceCommand(unit = InvoiceItemUnit.METER))
        }
        // detekt's SwallowedException flagged this call site for dropping the original
        // LeistungsverzeichnisRules.requireAllowed IllegalArgumentException instead of chaining it.
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
        assertEquals("Invalid unit for catalogue category.", ex.cause?.message)
    }

    // ── WP1 (0.9.10) — expectedVersion / lost-update guard ────────────────

    @Test
    fun `addItem throws OptimisticLockException on a stale expectedVersion, without persisting`() {
        val invoice = Invoice().apply { id = 1L; version = 5L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(7L)).thenReturn(taxRate())

        assertThrows(OptimisticLockException::class.java) {
            service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
                .addItem(1L, serviceCommand().copy(expectedVersion = 4L))
        }
        verify(invoiceRepo, never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `addItem with a matching expectedVersion succeeds`() {
        val invoice = Invoice().apply { id = 1L; version = 5L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(7L)).thenReturn(taxRate())

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
            .addItem(1L, serviceCommand().copy(expectedVersion = 5L))

        assertEquals(1, saved.itemList.size)
    }

    @Test
    fun `addItem with a null expectedVersion skips the check (internal - non-UI callers)`() {
        val invoice = Invoice().apply { id = 1L; version = 5L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(7L)).thenReturn(taxRate())

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
            .addItem(1L, serviceCommand().copy(expectedVersion = null))

        assertEquals(1, saved.itemList.size)
    }

    @Test
    fun `updateItem throws OptimisticLockException on a stale expectedVersion, without persisting`() {
        val (invoice, _) = invoiceWithItem()
        invoice.version = 5L
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(OptimisticLockException::class.java) {
            service(invoiceRepo = invoiceRepo).updateItem(1L, serviceCommand().copy(itemId = 5L, expectedVersion = 4L))
        }
        verify(invoiceRepo, never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `updateItem with a matching expectedVersion succeeds`() {
        val (invoice, _) = invoiceWithItem()
        invoice.version = 5L
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(7L)).thenReturn(taxRate())

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
            .updateItem(1L, serviceCommand().copy(itemId = 5L, description = "Beratung neu", expectedVersion = 5L))

        assertEquals("Beratung neu", saved.itemList.first().description)
    }

    @Test
    fun `deleteItemByPosition throws OptimisticLockException on a stale expectedVersion, without persisting`() {
        val invoice = Invoice().apply { id = 1L; version = 5L }
        val item = InvoiceItem().apply { positionId = "1"; category = InvoiceItemCategory.SERVICE; unit = InvoiceItemUnit.HOUR; quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal.TEN) }
        invoice.addItem(item)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(OptimisticLockException::class.java) {
            service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "1", expectedVersion = 4L)
        }
        verify(invoiceRepo, never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `deleteItemByPosition with a matching expectedVersion succeeds`() {
        val invoice = Invoice().apply { id = 1L; version = 5L }
        val item = InvoiceItem().apply { positionId = "1"; category = InvoiceItemCategory.SERVICE; unit = InvoiceItemUnit.HOUR; quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal.TEN) }
        invoice.addItem(item)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)

        val saved = service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "1", expectedVersion = 5L)

        assertEquals(0, saved.itemList.size)
    }

    // ── deleteItemByPosition ─────────────────────────────────────────────

    @Test
    fun `deleteItemByPosition happy path removes the position and saves`() {
        val invoice = Invoice().apply { id = 1L }
        val item = InvoiceItem().apply { positionId = "1"; category = InvoiceItemCategory.SERVICE; unit = InvoiceItemUnit.HOUR; quantity = BigDecimal.ONE; unitPriceNet = Money(BigDecimal.TEN) }
        invoice.addItem(item)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)

        val saved = service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "1")

        assertEquals(0, saved.itemList.size)
    }

    @Test
    fun `deleteItemByPosition throws InvoicePositionNotFoundException for an unknown position`() {
        val invoice = Invoice().apply { id = 1L }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoicePositionNotFoundException::class.java) {
            service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "does-not-exist")
        }
    }

    @Test
    fun `deleteItemByPosition throws InvoiceImmutableException on a read-only invoice`() {
        val invoice = Invoice().apply { id = 1L }
        invoice.forceStatusForTest(InvoiceStatus.SENT)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).deleteItemByPosition(1L, "1")
        }
    }

    // ── updateItem ───────────────────────────────────────────────────────

    private fun invoiceWithItem(itemId: Long = 5L): Pair<Invoice, InvoiceItem> {
        val item = InvoiceItem().apply {
            id = itemId
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = Money(BigDecimal.TEN)
            unit = InvoiceItemUnit.HOUR
        }
        val invoice = Invoice().apply { id = 1L }
        invoice.addItem(item)
        return invoice to item
    }

    @Test
    fun `updateItem happy path applies the command fields and re-tags an actually changed tax rate`() {
        val (invoice, item) = invoiceWithItem()
        item.applyTaxRule(taxRate(id = 7L, code = "DE_STANDARD_19"))
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findById(9L)).thenReturn(taxRate(id = 9L, code = "DE_REDUCED_7"))

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
            .updateItem(1L, serviceCommand(taxRateId = 9L).copy(itemId = 5L, description = "Beratung neu"))

        assertEquals("Beratung neu", saved.itemList.first().description)
        assertEquals("DE_REDUCED_7", saved.itemList.first().taxRuleCode)
    }

    @Test
    fun `updateItem rejects category TRIP`() {
        assertThrows(InvalidParameterException::class.java) {
            service().updateItem(1L, serviceCommand().copy(itemId = 5L, category = InvoiceItemCategory.TRIP))
        }
    }

    @Test
    fun `updateItem throws InvoiceItemNotFoundException for an unknown item id`() {
        val (invoice, _) = invoiceWithItem()
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceItemNotFoundException::class.java) {
            service(invoiceRepo = invoiceRepo).updateItem(1L, serviceCommand().copy(itemId = 999L))
        }
    }

    @Test
    fun `updateItem rejects a category change on the managed item`() {
        val (invoice, _) = invoiceWithItem()
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo).updateItem(1L, serviceCommand().copy(itemId = 5L, category = InvoiceItemCategory.PRODUCT))
        }
    }

    @Test
    fun `updateItem with a null taxRateId leaves the existing tax rate untouched`() {
        val (invoice, item) = invoiceWithItem()
        item.applyTaxRule(taxRate(id = 7L, code = "DE_STANDARD_19"))
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)

        val saved = service(invoiceRepo = invoiceRepo).updateItem(1L, serviceCommand(taxRateId = null).copy(itemId = 5L))

        assertEquals("DE_STANDARD_19", saved.itemList.first().taxRuleCode)
    }

    // Same SwallowedException fix as addItem's own cause-preservation test above.
    @Test
    fun `updateItem wraps an invalid unit for the category as InvalidParameterException with the original cause`() {
        val (invoice, _) = invoiceWithItem()
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        val ex = assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo).updateItem(1L, serviceCommand(unit = InvoiceItemUnit.METER).copy(itemId = 5L))
        }
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
        assertEquals("Invalid unit for catalogue category.", ex.cause?.message)
    }
}
