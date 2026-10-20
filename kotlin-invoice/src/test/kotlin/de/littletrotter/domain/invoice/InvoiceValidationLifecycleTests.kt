package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.event.InvoiceStatusChanged
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceItemRemoved
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.PersistenceEmulation

class InvoiceValidationLifecycleTests {

    private fun basicItem(positionId: String = ""): InvoiceItem = anInvoiceItem(
        positionId = positionId,
        quantity = BigDecimal.ONE,
        unitPriceNet = Money.of("10.00"),
        taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
    )

    private fun validInvoice(
        invoiceNumber: InvoiceNumber = InvoiceNumber("TEST-2026-0001"),
        typeCode: InvoiceTypeCode = InvoiceTypeCode.COMMERCIAL_INVOICE,
        id: Long? = null,
    ): Invoice = anInvoice(
        id = id,
        typeCode = typeCode,
        invoiceNumber = invoiceNumber,
        senderProfile = aSenderProfile { name = "S" },
        recipient = aRecipient { name = "R"; customerNumber = CustomerNumber("C1") },
    )

    /** Unlike [validInvoice], a sender/recipient pair that actually passes its own validate() —
     *  needed for every assertDoesNotThrow case below, since Invoice.validate() reaches the
     *  invoice-level checks only after both master records pass their own. */
    private fun completeInvoice(): Invoice = anInvoice(
        invoiceNumber = InvoiceNumber("TEST-2026-0005"),
        senderProfile = aSenderProfile {
            name = "Sender GmbH"
            invoicePrefix = "TEST-"
            email = Email.ofNullable("billing@sender.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        },
        recipient = aRecipient {
            customerNumber = CustomerNumber("C-100")
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
            customerType = CustomerType.CONSUMER
        },
    )

    /**
     * ST-3 — the invoice-number rule covers every ISSUED status, not just ISSUED. Both statuses that
     * count as issued are pinned here, and so is the counter-case: a status that is terminal and
     * read-only but was never issued (VOIDED) must still validate without a number, because it
     * never draws one.
     */
    @Test
    fun `ST-3 - an issued invoice without a number is rejected, a voided one is not`() {
        val sent = validInvoice(invoiceNumber = InvoiceNumber.UNASSIGNED).apply {
            forceStatusForTest(InvoiceStatus.ISSUED)
        }
        assertEquals(
            InvoiceValidationError.INVOICE_NUMBER_MISSING,
            assertThrows(InvoiceValidationException::class.java) { sent.validate(requireItems = false) }.error,
        )

        val cancelled = validInvoice(invoiceNumber = InvoiceNumber.UNASSIGNED).apply {
            forceStatusForTest(InvoiceStatus.CANCELLED)
        }
        assertEquals(
            InvoiceValidationError.INVOICE_NUMBER_MISSING,
            assertThrows(InvoiceValidationException::class.java) { cancelled.validate(requireItems = false) }.error,
        )

        // VOIDED is terminal and read-only, but never issued: it draws no number and must not be
        // rejected for the lack of one. It fails later, on the sender, which is a different error.
        val voided = validInvoice(invoiceNumber = InvoiceNumber.UNASSIGNED).apply {
            forceStatusForTest(InvoiceStatus.VOIDED)
        }
        assertNotEquals(
            InvoiceValidationError.INVOICE_NUMBER_MISSING,
            assertThrows(InvoiceValidationException::class.java) { voided.validate(requireItems = false) }.error,
        )
    }

    /**
     * ST-3 — `isReadOnly()` has two independent reasons, and this pins the second one on its own.
     * No service path produces this combination any more: since the cancellation document is born
     * CANCELLED, every cancellation document's *status* already makes it read-only and the type-code
     * term never decides the outcome in practice. The term stays because the invariant is the
     * entity's, not the service's — a cancellation document is never editable, whatever status it is put
     * into — and an invariant that only holds as long as one caller behaves is not an invariant.
     *
     * PO decision 1 (2026-09-02) — moved from CREDIT_NOTE (`381`) to CORRECTED_INVOICE (`384`)
     * along with the cancellation flow itself.
     */
    @Test
    fun `ST-3 - the type code alone makes a cancellation document read-only`() {
        val creditNote = validInvoice(typeCode = InvoiceTypeCode.CORRECTED_INVOICE).apply {
            forceStatusForTest(InvoiceStatus.DRAFT)
        }
        assertTrue(creditNote.isReadOnly(), "a storno document is read-only regardless of its status")

        val draft = validInvoice(typeCode = InvoiceTypeCode.COMMERCIAL_INVOICE).apply {
            forceStatusForTest(InvoiceStatus.DRAFT)
        }
        assertFalse(draft.isReadOnly(), "an ordinary draft stays editable")
    }

    @Test
    fun `PO decision 1 (2026-09-02) - CREDIT_NOTE no longer makes an invoice read-only by type code`() {
        // CREDIT_NOTE (381) is reserved for a genuine credit note that no code path produces today
        // -- unlike CORRECTED_INVOICE above, it must carry no read-only invariant of its own.
        val draft = validInvoice(typeCode = InvoiceTypeCode.CREDIT_NOTE).apply {
            forceStatusForTest(InvoiceStatus.DRAFT)
        }
        assertFalse(draft.isReadOnly(), "CREDIT_NOTE alone must not make a DRAFT invoice read-only any more")
    }

    @Test
    fun `validate missing sender throws`() {
        val inv = anInvoice()
        inv.edit {
            invoiceNumber = InvoiceNumber("TEST-2026-0001")
            recipient = aRecipient { name = "R"; customerNumber = CustomerNumber("C1") }
        }
        inv.addItem(basicItem())
        inv.recalculateTotals()

        assertThrows(InvoiceValidationException::class.java) { inv.validate() }
    }

    @Test
    fun `totals mismatch throws`() {
        val inv = validInvoice()
        inv.addItem(basicItem())
        inv.recalculateTotals()
        // tamper totals
        PersistenceEmulation.assignTotals(inv, InvoiceTotals(Money.of("0.01"), inv.totals.vatAmount, inv.totals.grossAmount))
        assertThrows(InvoiceValidationException::class.java) { inv.validate() }
    }

    @Test
    fun `service period invalid throws`() {
        val inv = validInvoice()
        inv.addItem(basicItem())
        inv.edit {
            servicePeriodFrom = LocalDate.of(2026, 4, 10)
            servicePeriodTo = LocalDate.of(2026, 4, 1)
        }
        inv.recalculateTotals()
        assertThrows(InvoiceValidationException::class.java) { inv.validate() }
    }

    @Test
    fun `a service period end on or after its start does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            servicePeriodFrom = LocalDate.of(2026, 4, 1)
            servicePeriodTo = LocalDate.of(2026, 4, 5)
        }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `a service period start without an end does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            servicePeriodFrom = LocalDate.of(2026, 4, 1)
            servicePeriodTo = null
        }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `an invoice-level delivery address that fails its own validation is rejected too`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        // effectiveRecipientDeliveryAddress() prefers the invoice's own deliveryAddress over the
        // recipient's — an incomplete one set directly here must still block validate().
        inv.edit { deliveryAddress = Address.forRecipient(inv.recipient as Recipient, Address.AddressType.DELIVERY, "", "50667", "Koeln", Country.DE, false) }
        inv.recalculateTotals()

        assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) { inv.validate() }
    }

    @Test
    fun `a complete invoice-level delivery address does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit { deliveryAddress = Address.forRecipient(inv.recipient as Recipient, Address.AddressType.DELIVERY, "Lieferweg 1", "50667", "Koeln", Country.DE, false) }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `an invalid position is reported by its 1-based index and inner error, wrapped as ITEM_POSITION`() {
        val inv = completeInvoice()
        inv.addItem(basicItem(positionId = "1"))
        val second = basicItem(positionId = "2")
        inv.addItem(second)
        // addItem() auto-fills a blank description, so it has to be cleared afterward to force
        // item.validate() to actually fail on it.
        second.edit { description = "" }
        inv.recalculateTotals()

        val ex = assertThrows(InvoiceValidationException::class.java) { inv.validate() }

        assertEquals(InvoiceValidationError.ITEM_POSITION, ex.error)
        assertEquals(listOf(2, InvoiceValidationError.ITEM_DESCRIPTION_BLANK.name), ex.args)
    }

    // date invariants (PO 2026-09-02) — dueDate >= invoiceDate is now a hard rejection at every
    // save/edit, not only the WARNING-severity finalization-checklist entry (DueDateRules).

    @Test
    fun `due date before invoice date throws DUE_DATE_BEFORE_INVOICE_DATE`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
            dueDate = LocalDate.of(2026, 4, 1)
        }
        inv.recalculateTotals()

        val ex = assertThrows(InvoiceValidationException::class.java) { inv.validate() }
        assertEquals(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE, ex.error)
    }

    @Test
    fun `due date equal to invoice date does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
            dueDate = LocalDate.of(2026, 4, 10)
        }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `due date after invoice date does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
            dueDate = LocalDate.of(2026, 4, 20)
        }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `no due date set does not throw`() {
        val inv = completeInvoice()
        inv.addItem(basicItem())
        inv.edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
            dueDate = null
        }
        inv.recalculateTotals()

        assertDoesNotThrow { inv.validate() }
    }

    @Test
    fun `status transitions and domain events`() {
        val inv = validInvoice(id = 42L)
        // start DRAFT
        assertEquals(InvoiceStatus.DRAFT, inv.status)
        inv.transitionTo(InvoiceStatus.IN_REVIEW)
        val events1 = inv.pullDomainEvents()
        assertTrue(events1.any { it is InvoiceStatusChanged })

        // go to finalized
        inv.transitionTo(InvoiceStatus.ISSUED)
        val events2 = inv.pullDomainEvents()
        assertTrue(events2.any { it is InvoiceStatusChanged })
        assertTrue(events2.any { it is InvoiceFinalized })

        // cannot transition further
        assertFalse(inv.canTransitionTo(InvoiceStatus.DRAFT))
        assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) { inv.transitionTo(InvoiceStatus.DRAFT) }
    }

    @Test
    fun `add and remove item emit domain events when persisted and numbered`() {
        val inv = validInvoice(id = 7L)

        val item = basicItem()
        inv.addItem(item)
        val events = inv.pullDomainEvents()
        assertTrue(events.any { it is InvoiceItemAdded })

        // remove
        inv.removeItem(item)
        val events2 = inv.pullDomainEvents()
        assertTrue(events2.any { it is InvoiceItemRemoved })
    }

    @Test
    fun `removing an item from an unpersisted invoice emits no event`() {
        val inv = validInvoice() // id == null
        val item = basicItem()
        inv.addItem(item)
        inv.pullDomainEvents()

        inv.removeItem(item)

        assertTrue(inv.pullDomainEvents().none { it is InvoiceItemRemoved })
    }

    @Test
    fun `removing an item from a persisted but still-unnumbered draft emits no event`() {
        val inv = validInvoice(id = 9L, invoiceNumber = InvoiceNumber.UNASSIGNED)
        val item = basicItem()
        inv.addItem(item)
        inv.pullDomainEvents()

        inv.removeItem(item)

        assertTrue(inv.pullDomainEvents().none { it is InvoiceItemRemoved })
    }

    @Test
    fun `removing an item whose positionId was cleared beforehand emits no event`() {
        val inv = validInvoice(id = 10L, invoiceNumber = InvoiceNumber("TEST-2026-0002"))
        val item = basicItem()
        inv.addItem(item)
        inv.pullDomainEvents()
        item.edit { positionId = "" }

        inv.removeItem(item)

        assertTrue(inv.pullDomainEvents().none { it is InvoiceItemRemoved })
    }
}
