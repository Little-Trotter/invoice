package de.littletrotter.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceStornoService
import de.littletrotter.domain.exception.InvoiceStornoNotAllowedException
import de.littletrotter.domain.exception.StornoRejection
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.InvoiceDto
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.impl.Headers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * End-to-end test for the storno process, from an active invoice through cancellation, across
 * the full persistence stack: real PostgreSQL, CDI-wired [InvoiceLifecycleService] and
 * [InvoiceStornoService], down to the payload that `kotlin-xpdf` actually receives.
 *
 * Path: draft → IN_REVIEW → SENT → storno/corrected invoice (384) → IN_REVIEW → SENT → wire.
 *
 * Core statement, per the GoBD-driven decision: **the original is never touched.** It stays
 * SENT, keeps type code 380 and its amounts; the cancellation is a second, independent document.
 * A test that only checks the storno document would let an implementation pass that quietly
 * corrupts the original in the process — so the integrity of the original is asserted here on
 * equal footing with the storno document itself.
 *
 * PO decision 1 (2026-09-02) — the storno document's own type code is
 * [InvoiceTypeCode.CORRECTED_INVOICE] (`384`), not [InvoiceTypeCode.CREDIT_NOTE] (`381`); this
 * class was written before that decision and still calls it "the credit note"/"Gutschrift" in
 * plenty of prose below — read that as "the storno document" throughout.
 *
 * **Not covered here:** whether the document carries XML BT-3=384 and BT-25. That ends at the
 * module boundary — kotlin-invoice does not know kotlin-xpdf. This test only carries things
 * through to the `InvoiceDto` on the wire; from there `En16931TermsExportTest` in kotlin-xpdf
 * takes over.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceStornoE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var stornoService: InvoiceStornoService

    private val mapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `an active invoice runs through finalization, storno and export handover`() {
        val originalId = QuarkusTransaction.requiringNew().call { persistScenario("STE") }

        // ── active invoice → finalized ────────────────────────────────
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.SENT) }

        val originalBefore = QuarkusTransaction.requiringNew().call {
            val inv = em.find(Invoice::class.java, originalId)!!
            assertEquals(InvoiceStatus.SENT, inv.status)
            Snapshot.of(inv)
        }

        // ── storno ───────────────────────────────────────────────────────────
        val creditNoteId = stornoService.createCreditNote(originalId, "Leistung nicht erbracht").id!!

        QuarkusTransaction.requiringNew().run {
            val creditNote = em.find(Invoice::class.java, creditNoteId)!!

            assertEquals(InvoiceTypeCode.CORRECTED_INVOICE, creditNote.typeCode, "BT-3 der Stornorechnung")
            assertEquals(originalBefore.number, creditNote.originalInvoiceNumber, "BT-25 zeigt auf das Original")
            assertEquals(originalBefore.invoiceDate, creditNote.originalInvoiceDate, "BT-26 ist das Datum des Vorbelegs")
            // ST-3 — the credit note is created terminal and draws its number at that same
            // moment: it never reaches the SENT transition where it would otherwise draw one.
            assertTrue(creditNote.invoiceNumber.isAssigned(), "die Stornorechnung ist sofort nummeriert")
            assertNotEquals(originalBefore.number, creditNote.invoiceNumber.value, "sie hat eine eigene Nummer")
            assertEquals(InvoiceStatus.CANCELLED, creditNote.status, "sie entsteht terminal, nicht als Entwurf")
            // freeText ist nicht mehr der rohe Grund, sondern Bezugssatz + Grund. Beide
            // Haelften einzeln zugesichert: der Bezug nennt die stornierte Rechnung, und der
            // getippte Grund bleibt wortgleich erhalten (er wird nie uebersetzt oder gekuerzt).
            // Dieser Beleg ist deutsch (document_language = 'de'), also die deutsche Fassung.
            val freeText = creditNote.freeText!!
            assertTrue(freeText.contains(originalBefore.number), "der Bezugssatz nennt die stornierte Rechnung")
            assertTrue(freeText.endsWith("Grund: Leistung nicht erbracht"), "der getippte Grund bleibt wortgleich erhalten")

            // PO decision 2 (2026-09-02) — no payment terms on a storno.
            assertNull(creditNote.dueDate, "eine Stornorechnung traegt kein Zahlungsziel")
            assertEquals(0, creditNote.paymentTermsDays, "paymentTermsDays ist 0, nicht der Invoice-Default 14")
            assertNull(creditNote.paymentTermSnapshot, "eine Stornorechnung friert keinen Zahlungsbedingungs-Snapshot ein")

            // PO decision 2 (2026-09-02, sign logic) — the storno's total is the exact NEGATIVE of
            // the original's, not a positive copy: BR-CO-25 forces this once payment terms are
            // gone (see InvoiceStornoService's own class KDoc).
            assertEquals(0, originalBefore.gross.negate().compareTo(creditNote.totals.grossAmount.amount), "Bruttobetrag der Stornorechnung")
            assertTrue(creditNote.totals.grossAmount.amount.signum() < 0, "eine Stornorechnung traegt negative Betraege")

            // The service reference is carried over: the credit note reverses exactly this service.
            assertEquals(originalBefore.periodFrom, creditNote.servicePeriodFrom, "BT-73")
            assertEquals(originalBefore.periodTo, creditNote.servicePeriodTo, "BT-74")
            assertEquals(originalBefore.deliveryDate, creditNote.deliveryDate, "BT-72")
            assertEquals(LocalDate.now(), creditNote.invoiceDate, "BT-2 ist das Ausstellungsdatum der Gutschrift, nicht des Originals")

            // Line items including the new terms and the frozen tax rate.
            assertEquals(2, creditNote.itemList.size)
            val service = creditNote.itemList.first { it.positionId == "1" }
            assertEquals("Ausführliche Leistungsbeschreibung", service.itemDescription, "BT-154")
            assertEquals(LocalDate.of(2026, 1, 5), service.servicePeriodFrom, "BT-134")
            assertEquals(LocalDate.of(2026, 1, 20), service.servicePeriodTo, "BT-135")
            assertEquals(InvoiceItemUnit.HOUR, service.unit, "BT-130")

            // The rebate line carries over too, price negated like every other line (sign logic) —
            // a storno that dropped the rebate reversal would credit back MORE than was ever
            // billed. The original's price was negative (-50.00); negated once more here it comes
            // back positive, exactly reversing the discount.
            val rebate = creditNote.itemList.first { it.positionId == "2" }
            assertTrue(rebate.unitPriceNet.amount.signum() > 0, "die negierte Rabattposition wird positiv")
            assertTrue(rebate.quantity.signum() > 0, "das Vorzeichen sitzt im Preis, nie in der Menge")
        }

        // ── the original is cancelled, otherwise untouched ───────────────────
        QuarkusTransaction.requiringNew().run {
            val original = em.find(Invoice::class.java, originalId)!!
            // ST-3 — status is the ONLY field the storno changes on the original.
            assertEquals(InvoiceStatus.CANCELLED, original.status, "das Original ist storniert")
            assertEquals(originalBefore.number, original.invoiceNumber.value, "es behält seine Nummer")
            assertEquals(InvoiceTypeCode.COMMERCIAL_INVOICE, original.typeCode, "das Original bleibt eine Rechnung")
            assertNull(original.originalInvoiceNumber, "das Original verweist auf nichts")
            assertEquals(0, originalBefore.gross.compareTo(original.totals.grossAmount.amount), "die Beträge des Originals ändern sich nicht")
        }

        // ── a second storno is rejected ────────────────────────────────
        val second = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            stornoService.createCreditNote(originalId, "Testgrund")
        }
        // ST-3, reason precedence corrected: a CANCELLED original always answers the truthful
        // ALREADY_CANCELLED — sequentially and in the concurrent race alike; NOT_SENT stays the
        // answer for DRAFT/IN_REVIEW/VOIDED (pinned below).
        assertEquals(StornoRejection.ALREADY_CANCELLED, second.reason)

        // ── a credit note cannot itself be cancelled ───────────
        val recursive = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            stornoService.createCreditNote(creditNoteId, "Testgrund")
        }
        assertEquals(StornoRejection.ALREADY_A_CORRECTED_INVOICE, recursive.reason)

        // ── the credit note is terminal: no path leads out of it ───
        QuarkusTransaction.requiringNew().run {
            val terminal = em.find(Invoice::class.java, creditNoteId)!!
            InvoiceStatus.entries.forEach { target ->
                assertTrue(!terminal.canTransitionTo(target), "CANCELLED hat keine Kante nach $target")
            }
        }

        // ── handover to kotlin-xpdf ──────────────────────────────────────────
        val dto = QuarkusTransaction.requiringNew().call {
            publishAndCapture(em.find(Invoice::class.java, creditNoteId)!!)
        }
        assertEquals("384", dto.typeCode, "BT-3 auf der Leitung")
        assertNull(dto.dates.dueDate, "kein Zahlungsziel auf der Leitung")
        assertEquals(originalBefore.number, dto.references.originalInvoiceNumber, "BT-25 auf der Leitung")
        assertEquals(originalBefore.invoiceDate.toString(), dto.references.originalInvoiceDate, "BT-26 auf der Leitung")
        assertEquals(originalBefore.deliveryDate.toString(), dto.dates.deliveryDate, "BT-72 auf der Leitung")
        assertEquals(originalBefore.periodFrom.toString(), dto.dates.servicePeriodFrom, "BT-73 auf der Leitung")
        assertEquals(originalBefore.periodTo.toString(), dto.dates.servicePeriodTo, "BT-74 auf der Leitung")

        val serviceLine = dto.items.first { it.positionId == "1" }
        assertEquals("Ausführliche Leistungsbeschreibung", serviceLine.itemDescription, "BT-154 auf der Leitung")
        assertEquals("2026-01-05", serviceLine.servicePeriodFrom, "BT-134 auf der Leitung")
        assertEquals("2026-01-20", serviceLine.servicePeriodTo, "BT-135 auf der Leitung")
        assertEquals("HUR", serviceLine.unitCode, "BT-130 auf der Leitung")
    }

    /**
     * ST-3 — the credit note draws its number inside the transaction of `createCreditNote`. If
     * that transaction aborts, the number must never have been consumed: the sequence stays
     * gapless. This can only be shown here — a unit test has no transaction that could roll back.
     */
    @Test
    fun `ST-3 - ein zurueckgerollter Storno verbraucht keine Rechnungsnummer`() {
        val originalId = QuarkusTransaction.requiringNew().call { persistScenario("RBK") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.SENT) }

        // What's measured is the number sequence itself, not the invoice: the test invoice has
        // its number set directly in the fixture and therefore has no sequence value that could
        // show consumption. number_counter.last_value is the last-assigned counter state —
        // exactly the value a consumed number draw increments.
        val counterBefore = counterTotal()

        // A storno inside a transaction that is deliberately made to fail afterward.
        // createCreditNote is @Transactional(REQUIRED) and joins this transaction, so the throw
        // rolls back exactly what it wrote — status change, credit note, AND number assignment.
        assertThrows(IllegalStateException::class.java) {
            QuarkusTransaction.requiringNew().run {
                stornoService.createCreditNote(originalId, "Testgrund")
                error("erzwungener Rollback, nachdem die Nummer gezogen wurde")
            }
        }

        QuarkusTransaction.requiringNew().run {
            val original = em.find(Invoice::class.java, originalId)!!
            assertEquals(InvoiceStatus.SENT, original.status, "das Original ist nicht storniert worden")
            assertEquals(
                0L,
                (
                    em.createNativeQuery("SELECT COUNT(*) FROM invoice WHERE original_invoice_number = :num")
                        .setParameter("num", original.invoiceNumber.value).singleResult as Number
                    ).toLong(),
                "es ist keine Gutschrift zurueckgeblieben",
            )
        }

        assertEquals(counterBefore, counterTotal(), "der Fehlversuch hat keine Nummer verbraucht")

        // And the sequence isn't blocked afterward either: the next real storno draws exactly
        // one number, not two.
        val creditNote = stornoService.createCreditNote(originalId, "Testgrund")
        assertTrue(creditNote.invoiceNumber.isAssigned(), "die Stornorechnung ist nummeriert")
        assertEquals(counterBefore + 1, counterTotal(), "genau ein Nummernzug, luckenlos")
    }

    /** Sum of all counter states — increases by exactly 1 per number actually consumed. */
    private fun counterTotal(): Long = QuarkusTransaction.requiringNew().call {
        (
            em.createNativeQuery("SELECT COALESCE(SUM(last_value), 0) FROM number_counter")
                .singleResult as Number
            ).toLong()
    }

    /**
     * The upfront check `findByOriginalInvoiceNumber(...).isNotEmpty()` is a
     * check-then-act: two concurrent calls both pass it before either one writes. Two browser
     * tabs are enough. Only the index `uq_invoice_single_credit_note` can decide the
     * race — and the loser must get the same business-level answer as someone who simply
     * clicked too late.
     */
    @Test
    fun `IMP-6 - zwei gleichzeitige Stornos erzeugen genau eine Gutschrift`() {
        val originalId = QuarkusTransaction.requiringNew().call { persistScenario("RACE") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(originalId, InvoiceStatus.SENT) }

        val ready = java.util.concurrent.CountDownLatch(2)
        val go = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                pool.submit<Throwable?> {
                    ready.countDown()
                    go.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    // The result is the error, not the credit note: what matters is WHO
                    // loses and with what reason.
                    runCatching { stornoService.createCreditNote(originalId, "Testgrund") }.exceptionOrNull()
                }
            }
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
            go.countDown()
            val outcomes = attempts.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }

            val erfolge = outcomes.count { it == null }
            assertEquals(1, erfolge, "genau ein Versuch darf durchkommen, Ergebnisse: $outcomes")

            val verlierer = outcomes.filterNotNull().single()
            assertTrue(
                verlierer is InvoiceStornoNotAllowedException && verlierer.reason == StornoRejection.ALREADY_CANCELLED,
                "der Verlierer muss die fachliche Ablehnung bekommen, nicht einen nackten DB-Fehler: $verlierer",
            )
        } finally {
            pool.shutdown()
        }

        // And the database really does hold only one credit note.
        // PO decision 1 (2026-09-02) — type_code 2 (CORRECTED_INVOICE), not 1 (CREDIT_NOTE).
        val gutschriften = QuarkusTransaction.requiringNew().call {
            val originalNumber = em.find(Invoice::class.java, originalId)!!.invoiceNumber.value
            em.createNativeQuery(
                "SELECT COUNT(*) FROM invoice WHERE original_invoice_number = :num AND type_code = 2",
            ).setParameter("num", originalNumber).singleResult as Number
        }
        assertEquals(1L, gutschriften.toLong(), "je Originalrechnung genau eine gueltige Gutschrift")
    }

    /**
     * The race test above checks the *behavior*, but cannot guarantee the index was actually
     * involved — under unfavorable timing the upfront check alone already catches the second
     * attempt, and the test would pass green even without the index. So here, additionally, the
     * assertion itself: the index must create a **unique, partial** index on
     * `original_invoice_number`, restricted to storno documents (`type_code = 2`, PO decision 1
     * 2026-09-02; previously `type_code = 1`).
     *
     * ST-2 — the former status clause (`status <> 3`, "excluding voided credit notes") has been
     * removed. It was meant to allow a second attempt after a voided credit note, and stopped
     * doing so once a voided credit note carried `VOIDED` instead of `CANCELLED` — though that
     * was never actually observable, because the upfront check in `stornoRejection` works
     * without any status filter and catches the second attempt before the index anyway. As of
     * ST-3, a credit note is created directly terminal and can no longer be voided at all, so
     * there is nothing left to exclude: the index covers every credit note.
     */
    @Test
    fun `IMP-6 - der eindeutige partielle Index erfasst jede Gutschrift, ohne Statusausnahme`() {
        val definition = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
                .setParameter("name", "uq_invoice_single_credit_note")
                .resultList.firstOrNull() as String?
        }

        assertTrue(definition != null, "der Index fehlt — die Migration ist nicht gelaufen")
        assertTrue(definition!!.contains("UNIQUE"), "der Index muss eindeutig sein, sonst haelt er nichts auf: $definition")
        assertTrue(definition.contains("original_invoice_number"), "er muss auf der Originalnummer liegen: $definition")
        assertTrue(
            // PO decision 1 (2026-09-02) — type_code 2 (CORRECTED_INVOICE), not 1 (CREDIT_NOTE):
            // the storno flow's own type code moved (see V1__baseline_schema.sql's own comment).
            definition.contains("type_code = 2"),
            "er darf nur Stornorechnungen erfassen, keine gewoehnlichen Rechnungen: $definition",
        )
        assertTrue(
            !definition.contains("status"),
            "ST-2 hat die Statusklausel entfernt — der Index darf keine Statusbedingung mehr " +
                "enthalten, sonst kehrt der alte Bug zurueck: $definition",
        )
    }

    @Test
    fun `a draft invoice cannot be cancelled`() {
        val draftId = QuarkusTransaction.requiringNew().call { persistScenario("STD") }

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            stornoService.createCreditNote(draftId, "Testgrund")
        }
        assertEquals(StornoRejection.NOT_SENT, ex.reason)

        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.DRAFT, em.find(Invoice::class.java, draftId)!!.status, "ein abgelehntes Storno ändert nichts")
        }
    }

    // Same NOT_SENT rejection, but hardened specifically for IN_REVIEW: so far IN_REVIEW only
    // appeared in this class as an intermediate step in the happy path, never as a rejected
    // storno target — DRAFT and IN_REVIEW share the same `status != SENT` condition
    // (InvoiceStornoService.stornoRejection), but untested is untested.
    @Test
    fun `an invoice in review cannot be cancelled`() {
        val reviewId = QuarkusTransaction.requiringNew().call { persistScenario("STR") }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(reviewId, InvoiceStatus.IN_REVIEW) }

        val ex = assertThrows(InvoiceStornoNotAllowedException::class.java) {
            stornoService.createCreditNote(reviewId, "Testgrund")
        }
        assertEquals(StornoRejection.NOT_SENT, ex.reason)

        QuarkusTransaction.requiringNew().run {
            assertEquals(InvoiceStatus.IN_REVIEW, em.find(Invoice::class.java, reviewId)!!.status, "ein abgelehntes Storno ändert nichts")
        }
    }

    // ─── setup ──────────────────────────────────────────────────────────────

    /**
     * A billable invoice with exactly the fields this suite cares about: header and line period
     * (BG-14/BG-26), delivery date (BT-72), line description (BT-154), and a negative rebate
     * line item against a positive total.
     *
     * [tag] keeps the unique columns (prefix, customer number, tax rate code, invoice number)
     * apart between tests — the tables are not empty between test methods.
     */
    private fun persistScenario(tag: String): Long {
        val sender = SenderProfile().apply {
            phone = "+49 30 1234567"
            name = "Storno GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@storno.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        // VAT ID and IBAN here are not decorative filler: `Recipient.validate()` requires both,
        // as long as no `customerType` is set, and `createCreditNote` validates the credit note
        // before saving. `transition()` does not do this (there the F4 guard applies instead),
        // which is why an invoice created via `em.persist` would otherwise be finalizable but
        // not cancelable — a state that does not exist via `InvoiceLifecycleService.save`.
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
            vatId = VatId("DE987654321")
            iban = Iban.ofNullable("DE02500105170137075030")
        }
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"
            category = "S"
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            senderProfile = sender
            this.recipient = recipient
            invoiceNumber = InvoiceNumber("$tag-2026-0001")
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            // Must equal the (only) line period's own bounds exactly: item 1 below
            // carries BT-134/135 (Jan 5-20), and Invoice.validateHeaderLinePeriodConsistency now
            // blocks SENT when the header period contradicts it. Item 2 (the Nachlass line)
            // carries no period of its own, so it doesn't participate in that check.
            servicePeriodFrom = LocalDate.of(2026, 1, 5)
            servicePeriodTo = LocalDate.of(2026, 1, 20)
            deliveryDate = LocalDate.of(2026, 1, 20)
            references.buyerReference = "PO-$tag-1"
        }

        invoice.addItem(
            InvoiceItem().apply {
                positionId = "1"
                category = InvoiceItemCategory.SERVICE
                description = "Beratung"
                itemDescription = "Ausführliche Leistungsbeschreibung"
                servicePeriodFrom = LocalDate.of(2026, 1, 5)
                servicePeriodTo = LocalDate.of(2026, 1, 20)
                quantity = BigDecimal("8")
                unit = InvoiceItemUnit.HOUR
                unitPriceNet = Money.of("100.00")
                taxRateRef = taxRate
                sortOrder = 0
            },
        )
        // A negative line item, positive invoice total. Quantity 1 with unit EURO, because
        // the amount here is itself the unit price (UnitConsistencyRules LT-UNIT-02).
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "2"
                category = InvoiceItemCategory.SERVICE
                description = "Nachlass"
                quantity = BigDecimal.ONE
                unit = InvoiceItemUnit.EURO
                unitPriceNet = Money.of("-50.00")
                taxRateRef = taxRate
                sortOrder = 1
            },
        )

        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        check(invoice.totals.grossAmount.amount.signum() > 0) { "die Rechnungssumme muss trotz der negativen Position positiv bleiben" }
        return invoice.id!!
    }

    /** The payload `kotlin-xpdf` reads off the wire — not a reconstruction, but the actual
     *  result of [DocumentJobPublisher.publish] itself. */
    private fun publishAndCapture(invoice: Invoice): InvoiceDto {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        DocumentJobPublisher(jetStreamProvider = { jetStream }).publish(invoice, 1L)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        return mapper.readValue(root.path("payload").path("invoice").toString())
    }

    /** State of the original **before** the storno — captured outside the transaction so
     *  the later comparison doesn't read the same entity instance it's meant to check. */
    private data class Snapshot(
        val number: String,
        val invoiceDate: LocalDate,
        val gross: BigDecimal,
        val periodFrom: LocalDate,
        val periodTo: LocalDate,
        val deliveryDate: LocalDate,
    ) {
        companion object {
            fun of(inv: Invoice) = Snapshot(
                number = inv.invoiceNumber.value,
                invoiceDate = inv.invoiceDate!!,
                gross = inv.totals.grossAmount.amount,
                periodFrom = inv.servicePeriodFrom!!,
                periodTo = inv.servicePeriodTo!!,
                deliveryDate = inv.deliveryDate!!,
            )
        }
    }
}
