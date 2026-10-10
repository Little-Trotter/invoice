package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.applyConsumerNoteSnapshot
import de.littletrotter.domain.invoice.consumerNoteSnapshot

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceStornoNotAllowedException
import de.littletrotter.domain.exception.StornoRejection
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.invoice.snapshotAsBuyer
import de.littletrotter.domain.invoice.snapshotAsSeller
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import jakarta.enterprise.event.Event
import jakarta.enterprise.event.NotificationOptions
import jakarta.enterprise.util.TypeLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import de.littletrotter.shared.time.ClockTimeSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

private val TEST_TIME = ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z"))

/**
 * Unit-level mapping of the storno process from an active invoice through cancellation: a
 * finalized invoice → [InvoiceStornoService.createCreditNote] produces a position-accurate
 * credit note that mirrors the same original data.
 *
 * The business rule (see the KDoc on [InvoiceStornoService]) is protected here:
 * - The original stays [InvoiceStatus.SENT], only a second document is created.
 * - The storno document carries [InvoiceTypeCode.CORRECTED_INVOICE] (UNTDID 1001 = `384`, PO
 *   decision 1 (2026-09-02); previously `381`) and the exact negative of the original's amounts,
 *   via a negated `unitPriceNet` per line (quantity stays positive) — see [InvoiceStornoService]'s
 *   own KDoc for the full sign-logic reasoning and the BR-CO-25 finding behind it.
 * - `originalInvoiceNumber`/`originalInvoiceDate` (BT-25/BT-26) point to the original.
 * - No payment terms travel to the storno (PO decision 2, 2026-09-02): no due date, no
 *   payment-term snapshot, `paymentTermsDays == 0`.
 */
class InvoiceStornoServiceTest {

    private val events = RecordingEventBus()

    // ── happy path: SENT → storno credit note ─────────────────────────────

    @Test
    fun `createCreditNote builds a positions-genaue positive Gutschrift referencing the original`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo, items = listOf(item(1, "Beratung", "29.00"), item(2, "Material", "11.50")))
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Kunde hat storniert.")

        assertNotNull(creditNote.id)
        assertNotEquals(original.invoiceNumber.value, creditNote.invoiceNumber.value)
        assertEquals(InvoiceStatus.CANCELLED, creditNote.status)
        assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, creditNote.typeCode)
        assertEquals(original.invoiceNumber.value, creditNote.originalInvoiceNumber)
        // BT-26 — the original is available at storno time, its date is copied and not
        // guessed. Explicitly not the credit note's own date.
        assertEquals(original.invoiceDate, creditNote.originalInvoiceDate)
        assertTrue(
            creditNote.originalInvoiceDate != creditNote.invoiceDate,
            "BT-26 ist das Datum des Vorbelegs, nicht das der Gutschrift",
        )
        // freeText (BT-22) is now the composed reference sentence + the reason, not the
        // bare typed reason. original.invoiceDate is 2026-08-01, documentLanguage defaults "de".
        assertEquals(
            "Stornierung der Rechnung RE-2026-08-00001 vom 01.08.2026. Grund: Kunde hat storniert.",
            creditNote.freeText,
        )

        // BT-10 and BT-13 are two separate terms and both are copied over — the recipient books
        // the credit note under the same transaction and the same order.
        assertEquals("KUNDE-REF", creditNote.references.buyerReference, "BT-10")
        assertEquals("BST-2026-0042", creditNote.references.purchaseOrderReference, "BT-13")

        // PO decision 2 (2026-09-02, sign logic) — the storno's totals are the exact negative of
        // the original's, not a positive copy: BR-CO-25 forces this once payment terms are gone.
        assertEquals(original.totals.netAmount.amount.negate(), creditNote.totals.netAmount.amount)
        assertEquals(original.totals.grossAmount.amount.negate(), creditNote.totals.grossAmount.amount)
        assertTrue(creditNote.totals.grossAmount.isNegative(), "a storno document's total is negative")

        // Compare line item by line item: same ID, description, quantity (unchanged, positive);
        // the price is negated (sign logic), so compare it explicitly instead of via assertEquals.
        assertEquals(original.itemList.size, creditNote.itemList.size)
        original.itemList.forEachIndexed { index, origItem ->
            val copy = creditNote.itemList[index]
            assertEquals(origItem.positionId, copy.positionId)
            assertEquals(origItem.description, copy.description)
            assertEquals(origItem.quantity, copy.quantity, "quantity is never negative, sign logic or not (B1)")
            assertEquals(origItem.unitPriceNet.amount.negate(), copy.unitPriceNet.amount, "unitPriceNet is negated")
            assertEquals(origItem.unit, copy.unit)
        }

        // ST-3 — the event names BOTH numbers. It could not before: the credit note was a draft
        // and drew its number only at its own finalization. It is now numbered by the time this
        // event is fired, and a message that names only one of the two documents does not say
        // what happened.
        val event = events.fired.filterIsInstance<InvoiceCancelled>().single()
        assertEquals(creditNote.id, event.creditNoteId)
        assertEquals(creditNote.invoiceNumber.value, event.creditNoteNumber)
        assertEquals(original.invoiceNumber.value, event.originalInvoiceNumber)
        assertTrue(creditNote.invoiceNumber.isAssigned(), "die Stornorechnung ist sofort nummeriert")
    }

    @Test
    fun `createCreditNote carries no payment terms at all, even when the original has one`() {
        // PO decision 2 (2026-09-02) — a storno has nothing to pay and nothing falls due: no
        // paymentTerm reference, no frozen paymentTermSnapshot, no dueDate, and paymentTermsDays is
        // stamped 0 rather than copied from the original. Deliberately built on an original that
        // DOES carry a payment term, so this pins that it is dropped, not merely never set.
        val repo = InMemoryInvoiceRepository()
        val term = PaymentTerm().apply {
            id = 77L
            setName(germanFallbackLanguage(), "30 Tage netto")
            dueInDays = 30
        }
        val original = finalizedInvoice(repo).apply { paymentTerm = term }
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Testgrund")

        assertNull(creditNote.paymentTerm, "the storno must not adopt the original's payment-term reference")
        assertNull(creditNote.paymentTermSnapshot, "the storno must freeze no payment-term snapshot")
        assertNull(creditNote.dueDate, "the storno must carry no due date")
        assertEquals(0, creditNote.paymentTermsDays, "paymentTermsDays must be 0, not the Invoice-entity default of 14")
    }

    // ── The storno reference (BT-22) follows the original's document_language ──────────

    @Test
    fun `createCreditNote composes the English storno reference and reason for an English original`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo).apply { documentLanguage = "en" }
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Customer cancelled the order.")

        // original.invoiceDate is 2026-08-01 (invoiceFixture) — "d MMM yyyy" never zero-pads the day.
        assertEquals(
            "Reversal of invoice RE-2026-08-00001 dated 1 Aug 2026. Reason: Customer cancelled the order.",
            creditNote.freeText,
        )
    }

    @Test
    fun `createCreditNote picks the English reason over the German one on an English original when both are given`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo).apply { documentLanguage = "en" }
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Kunde storniert.", reasonEn = "Customer cancelled.")

        assertTrue(creditNote.freeText!!.endsWith("Reason: Customer cancelled."), creditNote.freeText!!)
    }

    @Test
    fun `createCreditNote falls back to the German reason verbatim on an English original when reasonEn is blank`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo).apply { documentLanguage = "en" }
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Kunde storniert.", reasonEn = "   ")

        // testmatrix.md's third column: "English, text missing" -> German, verbatim, in the
        // English reference sentence's own "Reason:" wrapper (reasonEn never reaches this call).
        assertTrue(creditNote.freeText!!.endsWith("Reason: Kunde storniert."), creditNote.freeText!!)
    }

    @Test
    fun `createCreditNote ignores reasonEn entirely on a German original`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo) // documentLanguage defaults "de"
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Kunde storniert.", reasonEn = "Customer cancelled.")

        assertTrue(creditNote.freeText!!.startsWith("Stornierung der Rechnung"), creditNote.freeText!!)
        assertTrue(creditNote.freeText!!.endsWith("Grund: Kunde storniert."), creditNote.freeText!!)
    }

    @Test
    fun `composeStornoFreeText uses the no-date variant instead of printing null or a bare date gap`() {
        // Unreachable through createCreditNote's public API today: Invoice.invoiceDate has been
        // mandatory (non-nullable) since the 2026-09-02 date invariants, so `original.invoiceDate`
        // itself can no longer be null at that one call site — see composeStornoFreeText's own
        // KDoc. Tested directly here so the no-date bundle key (storno.reference.noDate) stays
        // covered and correct regardless.
        assertEquals(
            "Stornierung der Rechnung RE-2026-08-00001. Grund: Kunde storniert.",
            composeStornoFreeText("RE-2026-08-00001", null, "de", "Kunde storniert."),
        )
        assertEquals(
            "Reversal of invoice RE-2026-08-00001. Reason: Customer cancelled.",
            composeStornoFreeText("RE-2026-08-00001", null, "en", "Customer cancelled."),
        )
    }

    @Test
    fun `G9 - createCreditNote copies the consumer note snapshot of the original`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo, items = listOf(item(1, "Beratung", "29.00"))).apply {
            applyConsumerNoteSnapshot(
                de.littletrotter.domain.invoice.InvoiceNoteSnapshot(
                    value = "Bitte aufbewahren.",
                    origin = de.littletrotter.domain.invoice.InvoiceNoteOrigin.MANUAL,
                    sourceSenderProfileId = 3L,
                    sourceLanguage = "de",
                    sourceDefaultValue = "Standard",
                    sourceContext = "CONSUMER",
                ),
            )
        }
        repo.save(original)

        val creditNote = service(repo).createCreditNote(original.id!!, reason = "Kunde hat storniert.")

        assertEquals(original.consumerNoteSnapshot(), creditNote.consumerNoteSnapshot())
    }

    @Test
    fun `ARC-11 - createCreditNote uebernimmt die Reverse-Charge-Entscheidung des Originals`() {
        // reverseCharge is the invoice's own leading decision now, no longer derived by
        // recalculateTotals() from the items — a credit note copying "the entire tax context"
        // from the original must copy this explicitly, or it would default back to
        // false and disagree with the (also copied) AE items at the credit note's own
        // finalization (validateReverseChargeItemConsistency).
        val repo = InMemoryInvoiceRepository()
        val rcItem = item(1, "Beratung", "100.00").apply {
            applyTaxRule(TaxRate().apply { code = "EU_B2B_RC_SERVICE"; category = "AE"; rate = TaxRatePercent(BigDecimal.ZERO); exemptionReasonCode = "VATEX-EU-AE" })
        }
        val original = invoiceFixture(repo, status = InvoiceStatus.DRAFT, items = listOf(rcItem)).apply {
            reverseCharge = true
            itemList.forEach { it.snapshotTaxRule() }
            forceStatusForTest(InvoiceStatus.SENT)
        }
        repo.save(original)
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Kunde hat storniert.")

        assertTrue(creditNote.reverseCharge)
    }

    @Test
    fun `createCreditNote lehnt einen leeren Storno-Grund ab`() {
        // The storno reason is mandatory, no longer an optional default text.
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        assertThrows(IllegalArgumentException::class.java) {
            service.createCreditNote(original.id!!, reason = "")
        }
    }

    @Test
    fun `createCreditNote rejects an emoji or CJK reason and persists nothing`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        val ex = assertThrows(PdfUnrenderableTextException::class.java) {
            service.createCreditNote(original.id!!, reason = "🐴 🔥 日 本 語")
        }

        assertTrue(ex.fields.containsKey("Storno-Grund"))
        assertEquals(InvoiceStatus.SENT, original.status)
    }

    @Test
    fun `createCreditNote rejects an unrenderable English reason even on a German original`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        val ex = assertThrows(PdfUnrenderableTextException::class.java) {
            service.createCreditNote(original.id!!, reason = "Kunde storniert", reasonEn = "cancelled 🔥")
        }

        assertTrue(ex.fields.containsKey("Storno-Grund (EN)"))
    }

    @Test
    fun `createCreditNote accepts umlauts and the euro sign in the reason`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Rückerstattung über 100 € wegen Größenfehler")

        assertTrue(creditNote.freeText!!.contains("Rückerstattung über 100 € wegen Größenfehler"))
    }

    @Test
    fun `createCreditNote uebernimmt den Abrechnungszeitraum, das Lieferdatum und die Positionsperioden`() {
        val repo = InMemoryInvoiceRepository()
        val itemWithPeriod = item(1, "Wartung", "40.00").apply {
            servicePeriodFrom = LocalDate.of(2026, 2, 1)
            servicePeriodTo = LocalDate.of(2026, 2, 28)
        }
        val original = finalizedInvoice(repo, items = listOf(itemWithPeriod)).apply {
            servicePeriodFrom = LocalDate.of(2026, 1, 1)
            servicePeriodTo = LocalDate.of(2026, 1, 31)
            deliveryDate = LocalDate.of(2026, 1, 20)
        }
        repo.save(original)
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Testgrund")

        // BT-72/73/74 — the credit note reverses exactly this service.
        assertEquals(LocalDate.of(2026, 1, 1), creditNote.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 1, 31), creditNote.servicePeriodTo)
        assertEquals(LocalDate.of(2026, 1, 20), creditNote.deliveryDate)
        // BG-26 (BT-134/BT-135) on the line item.
        assertEquals(LocalDate.of(2026, 2, 1), creditNote.itemList.single().servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 2, 28), creditNote.itemList.single().servicePeriodTo)
    }

    @Test
    fun `createCreditNote rechnet die Summen der Gutschrift aus den Kopien neu`() {
        val repo = InMemoryInvoiceRepository()
        val service = service(repo)
        val original = finalizedInvoice(repo, items = listOf(item(1, "A", "10.00"), item(2, "B", "20.00")))

        val creditNote = service.createCreditNote(original.id!!, reason = "Testgrund")

        // PO decision 2 (2026-09-02, sign logic) — negative, not positive: see the class KDoc.
        assertEquals(BigDecimal("-30.00").setScale(2), creditNote.totals.netAmount.amount)
        assertEquals(BigDecimal("-5.70").setScale(2), creditNote.totals.vatAmount.amount)
        assertEquals(BigDecimal("-35.70").setScale(2), creditNote.totals.grossAmount.amount)
    }

    // ── full copy: receipts, per-diem allowances ──────

    @Test
    fun `createCreditNote dupliziert Beleg-Zuordnungen auf Rechnungs- und Positionsebene`() {
        val repo = InMemoryInvoiceRepository()
        val invoiceLevelReceipt = Receipt().apply { id = 501L; fileName = "vertrag.pdf" }
        val itemLevelReceipt = Receipt().apply { id = 502L; fileName = "stundenzettel.pdf" }
        val untouchedItem = item(2, "Material", "5.00")
        val itemWithReceipt = item(1, "Beratung", "100.00").apply { receipts.add(itemLevelReceipt) }
        val original = finalizedInvoice(repo, items = listOf(itemWithReceipt, untouchedItem)).apply {
            receipts.add(invoiceLevelReceipt)
        }
        repo.save(original)
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Testgrund")

        // Invoice level: the same receipt row, an additional association on the credit note —
        // the original keeps its own unchanged.
        assertEquals(listOf(invoiceLevelReceipt), creditNote.receipts)
        assertEquals(listOf(invoiceLevelReceipt), original.receipts, "das Original bleibt unangetastet")

        // Line-item level: the copied line item at index 0 carries the same receipt row as the
        // original at index 0; the receipt-free line item stays receipt-free.
        assertEquals(listOf(itemLevelReceipt), creditNote.itemList[0].receipts)
        assertTrue(creditNote.itemList[1].receipts.isEmpty())
        assertEquals(listOf(itemLevelReceipt), itemWithReceipt.receipts, "das Original bleibt unangetastet")
    }

    @Test
    fun `createCreditNote kopiert die Verpflegungspauschalen-Eintraege einer PER_DIEM-Position`() {
        val repo = InMemoryInvoiceRepository()
        val entry = TravelExpenseEntry().apply {
            id = 901L
            country = Country.DE
            description = "Team-Reise Berlin"
            participant = "Kundentermin"
            travelDate = LocalDate.of(2026, 3, 3)
            fullDayRateSnapshot = Money(BigDecimal("28.00"))
            calculatedAmount = Money(BigDecimal("28.00"))
        }
        val perDiemItem = item(1, "Verpflegungspauschale", "28.00").apply {
            category = InvoiceItemCategory.TRIP
            perDiemEntries.add(entry.also { it.invoiceItem = this })
        }
        val original = finalizedInvoice(repo, items = listOf(perDiemItem))
        val service = service(repo)

        val creditNote = service.createCreditNote(original.id!!, reason = "Testgrund")

        val copiedEntries = creditNote.itemList.single().perDiemEntries
        assertEquals(1, copiedEntries.size)
        val copiedEntry = copiedEntries.single()
        assertTrue(copiedEntry !== entry, "muss eine eigene Zeile sein, keine geteilte Referenz")
        assertEquals(entry.country, copiedEntry.country)
        assertEquals(entry.description, copiedEntry.description)
        assertEquals(entry.participant, copiedEntry.participant)
        assertEquals(entry.travelDate, copiedEntry.travelDate)
        assertEquals(entry.calculatedAmount.amount, copiedEntry.calculatedAmount.amount)
        assertEquals(creditNote.itemList.single(), copiedEntry.invoiceItem, "muss der Kopie zugeordnet sein")

        // The original keeps its own entry unchanged.
        assertEquals(1, perDiemItem.perDiemEntries.size)
        assertEquals(entry, perDiemItem.perDiemEntries.single())
    }

    // ── rejection reasons ───────────────────────────────────────────────────────

    /**
     * ST-3 — the storno document draws its number when it is created, so every test needs an
     * assigner. This one allocates a deterministic, gapless sequence and, like the real
     * implementation, never renumbers a document that already carries a number — which is what
     * makes it usable for the "a failed storno consumes no number" assertion: [allocated] counts
     * only the numbers this instance actually handed out.
     */
    private class RecordingNumberAssigner : InvoiceNumberAssigner {
        val allocated = mutableListOf<String>()

        override fun assignIfNeeded(invoice: Invoice) {
            if (invoice.invoiceNumber.isAssigned()) return
            val next = "ST-2026-%04d".format(allocated.size + 1)
            invoice.invoiceNumber = InvoiceNumber(next)
            invoice.invoiceNumberPrefix = "ST-2026"
            invoice.invoiceNumberSequence = (allocated.size + 1).toLong()
            allocated += next
        }
    }

    private val numberAssigner = RecordingNumberAssigner()

    private fun service(repo: InvoiceRepository): InvoiceStornoService =
        InvoiceStornoService(repo, events, numberAssigner, TEST_TIME)

    @Test
    fun `canCreateCreditNote is true for a finalized invoice`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        assertTrue(service.canCreateCreditNote(original))
    }

    @Test
    fun `Storno einer nicht versandten Rechnung wird abgelehnt`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceFixture(repo, status = InvoiceStatus.IN_REVIEW)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(invoice.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.NOT_SENT, ex.reason)
        assertFalse(service.canCreateCreditNote(invoice))
    }

    // ST-9 — the storno-rejections list names DRAFT as its own case alongside IN_REVIEW and
    // VOIDED, even though all three share the same NOT_SENT branch: a draft was never sent, so
    // there is nothing to storno.
    @Test
    fun `Storno eines Entwurfs wird abgelehnt`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceFixture(repo, status = InvoiceStatus.DRAFT)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(invoice.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.NOT_SENT, ex.reason)
        assertFalse(service.canCreateCreditNote(invoice))
    }

    // ST-8 rule table — VOIDED gets the exact same NOT_SENT rejection as any other non-SENT
    // status (a voided invoice was discarded before it was ever sent, so there is nothing to
    // storno), but the table names it as its own column and this pins it explicitly rather than
    // leaving it to only be exercised incidentally through the generic IN_REVIEW case above.
    @Test
    fun `Storno einer annullierten Rechnung wird abgelehnt`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceFixture(repo, status = InvoiceStatus.VOIDED)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(invoice.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.NOT_SENT, ex.reason)
        assertFalse(service.canCreateCreditNote(invoice))
    }

    // A CANCELLED original gets the truthful ALREADY_CANCELLED, never the generic NOT_SENT —
    // the loser of two concurrent storno attempts reads the winner's CANCELLED state, and before
    // this precedence its rejection reason depended purely on interleaving (found by
    // InvoiceStornoE2EIT's IMP-6 race under machine load).
    @Test
    fun `Storno einer bereits stornierten Rechnung nennt ALREADY_CANCELLED, nicht NOT_SENT`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceFixture(repo, status = InvoiceStatus.CANCELLED)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(invoice.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.ALREADY_CANCELLED, ex.reason)
        assertFalse(service.canCreateCreditNote(invoice))
    }

    // PO wave G (2026-08-30), Auftrag 2 — "pin it explicitly with a readable German error":
    // the NOT_SENT case gets its own exact-message assertion, not just the typed `.reason`.
    @Test
    fun `Storno einer nicht versandten Rechnung traegt eine lesbare deutsche Fehlermeldung`() {
        val repo = InMemoryInvoiceRepository()
        val invoice = invoiceFixture(repo, status = InvoiceStatus.IN_REVIEW)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(invoice.id!!, reason = "Testgrund")
        }
        assertEquals(
            "Nur versandte Rechnungen können storniert werden — Rechnung '${invoice.invoiceNumber.value}' hat einen anderen Status.",
            ex.message,
        )
    }

    @Test
    fun `Storno einer bereits erstellten Stornorechnung wird abgelehnt`() {
        // PO decision 1 (2026-09-02) — the storno flow stamps CORRECTED_INVOICE (`384`), not
        // CREDIT_NOTE (`381`); this guard, and its rejection reason, moved with it.
        val repo = InMemoryInvoiceRepository()
        val correctedInvoiceOriginal = finalizedInvoice(repo).apply { typeCode = InvoiceTypeCode.CORRECTED_INVOICE }
        repo.save(correctedInvoiceOriginal)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(correctedInvoiceOriginal.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.ALREADY_A_CORRECTED_INVOICE, ex.reason)
    }

    @Test
    fun `ein zweites Storno derselben Rechnung wird abgelehnt`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)
        service.createCreditNote(original.id!!, reason = "Testgrund")

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        // ST-3, corrected after IMP-6's race surfaced the asymmetry: a CANCELLED original now
        // always answers ALREADY_CANCELLED — the truthful reason — instead of whichever guard
        // happened to fire first. The generic NOT_SENT stays for DRAFT/IN_REVIEW/VOIDED, pinned
        // by the status tests above.
        assertEquals(StornoRejection.ALREADY_CANCELLED, ex.reason)
        assertFalse(service.canCreateCreditNote(original))
    }

    // ST-9 — ALREADY_CANCELLED has two genuinely different branches: this one is the pre-check
    // in stornoRejection() itself (a credit note referencing this original's number already
    // exists, while the original's own status field still reads SENT — a data state a real
    // second call never produces, since the first storno flips the original's status to
    // CANCELLED before anything else could see it as SENT again; reachable here only by seeding
    // the repository directly, e.g. demo data or a manual DB edit). The other branch is the
    // PersistenceException translation from the unique-index race, covered by the
    // `wraps a single-credit-note constraint violation` test below — genuinely unreachable from
    // this pre-check, since by the time two concurrent calls could race on the index, both
    // already passed this same check.
    @Test
    fun `Storno wird ueber den Vorab-Check abgelehnt, wenn bereits eine Stornorechnung auf die Nummer verweist`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val staleCreditNote = Invoice().apply {
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE
            forceStatusForTest(InvoiceStatus.CANCELLED)
            invoiceNumber = InvoiceNumber("RE-2026-08-99999")
            originalInvoiceNumber = original.invoiceNumber.value
        }
        repo.save(staleCreditNote)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.ALREADY_CANCELLED, ex.reason)
        assertFalse(service.canCreateCreditNote(original))
    }

    @Test
    fun `Storno einer fehlenden Rechnung wirft InvoiceNotFoundException`() {
        val service = service(InMemoryInvoiceRepository())
        assertThrows(InvoiceNotFoundException::class.java) {
            service.createCreditNote(999L, reason = "Testgrund")
        }
    }

    // ── violatesSingleCreditNoteIndex (unique-index race) ───────────────────────────
    // Deterministic coverage for the uq_invoice_single_credit_note race-condition
    // classifier: InvoiceStornoE2EIT's concurrent-storno test is the only other exerciser of this
    // path, and whether it actually produces a ConstraintViolationException (rather than one
    // thread simply losing a check-then-act race before either writes) depends on real DB/thread
    // timing — not reliable coverage to depend on alone. Called via reflection since it's
    // `private fun` (same idiom InvoiceListViewKaribuTest already uses for private members).

    private fun violatesSingleCreditNoteIndex(service: InvoiceStornoService, ex: Throwable?): Boolean {
        val method = InvoiceStornoService::class.java.getDeclaredMethod("violatesSingleCreditNoteIndex", Throwable::class.java)
        method.isAccessible = true
        return method.invoke(service, ex) as Boolean
    }

    @Test
    fun `violatesSingleCreditNoteIndex is true for a direct constraint violation on the single-credit-note index`() {
        val service = service(InMemoryInvoiceRepository())
        val violation = org.hibernate.exception.ConstraintViolationException(
            "duplicate key value violates unique constraint",
            java.sql.SQLException("simulated unique-index violation"),
            InvoiceStornoService.UQ_SINGLE_CREDIT_NOTE,
        )

        assertTrue(violatesSingleCreditNoteIndex(service, violation))
    }

    @Test
    fun `violatesSingleCreditNoteIndex is true when the constraint violation is nested deeper in the cause chain`() {
        val service = service(InMemoryInvoiceRepository())
        val violation = org.hibernate.exception.ConstraintViolationException(
            "duplicate key value violates unique constraint",
            java.sql.SQLException("simulated unique-index violation"),
            InvoiceStornoService.UQ_SINGLE_CREDIT_NOTE,
        )
        // Hibernate wraps the JDBC-level exception at varying depth depending on the call path
        // (see the method's own KDoc) — a PersistenceException-style wrapper is the realistic case.
        val wrapped = RuntimeException("flush failed", violation)

        assertTrue(violatesSingleCreditNoteIndex(service, wrapped))
    }

    @Test
    fun `violatesSingleCreditNoteIndex is false for an unrelated constraint violation`() {
        val service = service(InMemoryInvoiceRepository())
        val otherViolation = org.hibernate.exception.ConstraintViolationException(
            "duplicate key value violates unique constraint",
            java.sql.SQLException("simulated unique-index violation"),
            "some_other_constraint",
        )

        assertFalse(violatesSingleCreditNoteIndex(service, otherViolation))
    }

    @Test
    fun `violatesSingleCreditNoteIndex is false for an unrelated exception and for null`() {
        val service = service(InMemoryInvoiceRepository())

        assertFalse(violatesSingleCreditNoteIndex(service, IllegalStateException("something else entirely")))
        assertFalse(violatesSingleCreditNoteIndex(service, null))
    }

    // ── branch-coverage completion ───────────────────────────────────────────────

    @Test
    fun `createCreditNote throws InvoiceWithoutSenderProfileException for a SENT invoice missing its sender profile`() {
        // IMP-6's own KDoc: only reachable if the row was written around the domain model
        // (demo data, a DB-only edit) — reproduced here by clearing senderProfile directly.
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo).apply { senderProfile = null }
        repo.save(original)
        val service = service(repo)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertTrue(ex.message!!.contains(original.id.toString()))
    }

    @Test
    fun `createCreditNote lehnt einen Storno-Grund ab, der nur aus Leerzeichen besteht`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        val service = service(repo)

        assertThrows(IllegalArgumentException::class.java) {
            service.createCreditNote(original.id!!, reason = "   ")
        }
    }

    @Test
    fun `createCreditNote skips the party-snapshot refresh when the copied credit note has no recipient`() {
        // A recipient-less credit note is never actually persistable (Invoice.validate() rejects
        // RECIPIENT_MISSING like any other invoice) — this exercises the null-recipient branch
        // right before that validation runs, proving the snapshot refresh is skipped rather than
        // NPE-ing on a null recipient.
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo)
        original.recipient = null
        repo.save(original)
        val service = service(repo)

        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceValidationException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.RECIPIENT_MISSING, ex.error)
    }

    @Test
    fun `createCreditNote wraps a single-credit-note constraint violation into InvoiceStornoNotAllowedException`() {
        val inner = InMemoryInvoiceRepository()
        val original = finalizedInvoice(inner)
        val violation = org.hibernate.exception.ConstraintViolationException(
            "duplicate key value violates unique constraint",
            java.sql.SQLException("simulated unique-index violation"),
            InvoiceStornoService.UQ_SINGLE_CREDIT_NOTE,
        )
        // AP-EXC-1: InvoiceStornoService's catch is now narrowed to jakarta.persistence
        // .PersistenceException, matching what em.flush() (inside the real saveAndFlush) actually
        // throws — Hibernate's own ConstraintViolationException is always wrapped inside one by the
        // JPA integration layer before it reaches a caller of flush(). Wrapping it here too so this
        // fake repository reproduces that real shape instead of throwing the unwrapped Hibernate
        // exception directly (which the narrowed catch would no longer intercept).
        val wrapped = jakarta.persistence.PersistenceException("could not execute statement", violation)
        val repo = object : InvoiceRepository by inner {
            override fun saveAndFlush(invoice: Invoice): Invoice = throw wrapped
        }
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.ALREADY_CANCELLED, ex.reason)
    }

    @Test
    fun `createCreditNote rethrows an unrelated saveAndFlush failure unchanged`() {
        val inner = InMemoryInvoiceRepository()
        val original = finalizedInvoice(inner)
        val boom = RuntimeException("db down")
        val repo = object : InvoiceRepository by inner {
            override fun saveAndFlush(invoice: Invoice): Invoice = throw boom
        }
        val service = service(repo)

        val ex = assertThrows(RuntimeException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertEquals("db down", ex.message)
    }

    // ST-9 — distinct from the plain-RuntimeException case above: a RuntimeException never
    // enters the narrowed `catch (ex: PersistenceException)` clause at all, so it does not
    // exercise that clause's own false branch (violatesSingleCreditNoteIndex(ex) == false ->
    // `throw ex`). This one is a genuine PersistenceException whose cause chain does not name
    // uq_invoice_single_credit_note, so it must fall through the same catch clause and be
    // rethrown unchanged rather than translated.
    @Test
    fun `createCreditNote rethrows a PersistenceException unrelated to the single-credit-note index unchanged`() {
        val inner = InMemoryInvoiceRepository()
        val original = finalizedInvoice(inner)
        val unrelated = jakarta.persistence.PersistenceException(
            "could not execute statement",
            RuntimeException("some other failure"),
        )
        val repo = object : InvoiceRepository by inner {
            override fun saveAndFlush(invoice: Invoice): Invoice = throw unrelated
        }
        val service = service(repo)

        val ex = assertThrows(jakarta.persistence.PersistenceException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertSame(unrelated, ex, "an unrelated PersistenceException must be rethrown unchanged, not translated")
    }

    @Test
    fun `Storno einer versandten Rechnung ohne zugewiesene Rechnungsnummer wird abgelehnt`() {
        val repo = InMemoryInvoiceRepository()
        val original = finalizedInvoice(repo).apply { invoiceNumber = InvoiceNumber.UNASSIGNED }
        repo.save(original)
        val service = service(repo)

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            service.createCreditNote(original.id!!, reason = "Testgrund")
        }
        assertEquals(StornoRejection.ORIGINAL_NUMBER_MISSING, ex.reason)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun finalizedInvoice(repo: InMemoryInvoiceRepository, items: List<InvoiceItem> = listOf(item(1, "Beratung", "100.00"))): Invoice {
        // Create as a draft first, then freeze the tax snapshot, then finalize — in the same
        // order as the real lifecycle. snapshotTaxRule() rejects line items on a finalized
        // invoice (assertSnapshotMutable).
        val invoice = invoiceFixture(repo, status = InvoiceStatus.DRAFT, items = items)
        invoice.itemList.forEach { it.snapshotTaxRule() }
        invoice.forceStatusForTest(InvoiceStatus.SENT)
        return repo.save(invoice)
    }

    private fun invoiceFixture(
        repo: InMemoryInvoiceRepository,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        items: List<InvoiceItem> = listOf(item(1, "Beratung", "100.00")),
    ): Invoice {
        val sender = sender(id = 1L, prefix = "RE-")
        val customer = recipient(id = 2L)
        val invoice = Invoice().apply {
            this.senderProfile = sender
            this.recipient = customer
            invoiceNumber = InvoiceNumber("RE-2026-08-00001")
            invoiceDate = LocalDate.of(2026, 8, 1)
            dueDate = LocalDate.of(2026, 8, 15)
            paymentTermsDays = 14
            validationStatus = ValidationStatus.VALID
            references.buyerReference = "KUNDE-REF"
            references.purchaseOrderReference = "BST-2026-0042"
            applyPartySnapshots(sender.snapshotAsSeller(), customer.snapshotAsBuyer())
            items.forEach { addItem(it) }
            this.forceStatusForTest(status)
            recalculateTotals()
        }
        return repo.save(invoice)
    }

    private fun item(positionId: Int, description: String, price: String) = InvoiceItem().apply {
        this.positionId = positionId.toString()
        this.description = description
        quantity = BigDecimal.ONE
        unitPriceNet = Money(BigDecimal(price))
        applyTaxRule(stubTaxRate())
    }

    private fun stubTaxRate() = TaxRate().apply {
        code = "DE_STANDARD_19"
        category = "S"
        rate = TaxRatePercent(BigDecimal("19.00"))
        country = Country.DE
    }

    private fun sender(id: Long, prefix: String) = SenderProfile().apply {
        this.id = id
        name = "Acme GmbH"
        invoicePrefix = prefix
        email = Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        setBillingAddress(
            Address.forSender(this, Address.AddressType.BILLING, "Musterstr. 1", "10115", "Berlin", Country.DE, true),
        )
    }

    private fun recipient(id: Long) = Recipient().apply {
        this.id = id
        name = "Kunde GmbH"
        customerNumber = CustomerNumber("KD-001")
        email = Email("kunde@kunde-gmbh.example")
        vatId = VatId("DE987654321")
        iban = Iban("DE02120300000000202051")
        setBillingAddress(
            Address.forRecipient(this, Address.AddressType.BILLING, "Kundenstr. 2", "10117", "Berlin", Country.DE, true),
        )
    }

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
        override fun findByOriginalInvoiceNumber(number: String): List<Invoice> = data.values.filter { it.originalInvoiceNumber == number }
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

    private fun assertNotEquals(a: String, b: String) = org.junit.jupiter.api.Assertions.assertNotEquals(a, b)
}
