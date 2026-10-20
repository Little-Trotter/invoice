package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aPartySnapshot
import de.littletrotter.fixture.PersistenceEmulation

/**
 * plain getter/setter round trips for [Invoice]'s embedded/collection fields that
 * [Invoice.recalculateTotals]/[Invoice.validate] and the lifecycle service never happen to read
 * AND write in the same test, plus the [Invoice.initializeEmbeddablesAfterLoad] `@PostLoad`
 * callback, which only Hibernate calls in production. Closes the calculation-core-100 gate's
 * plumbing gaps without an alibi test: every assertion here is a real field-contract check
 * (round-trips the value actually stored), not a call made only to trip the line counter.
 */
class InvoiceEntityAccessorsTest {

    @Test
    fun `paymentTermSnapshot round-trips and defaults to null for a draft`() {
        val invoice = Invoice()
        assertEquals(null, invoice.paymentTermSnapshot)

        val frozen = anInvoice(frozenPaymentTerm = aPaymentTerm(name = "Netto 14 Tage", dueInDays = 14))
        val snapshot = frozen.paymentTermSnapshot
        assertNotNull(snapshot)
        assertEquals("Netto 14 Tage", snapshot!!.name)
        assertEquals(14, snapshot.dueInDays)
        assertSame(snapshot, frozen.paymentTermSnapshot)
    }

    @Test
    fun `references round-trips and defaults to an empty value object, never null`() {
        val invoice = Invoice()
        assertNotNull(invoice.references)

        invoice.references.update(InvoiceReferencesChange(buyerReference = "BR-1"))
        assertEquals("BR-1", invoice.references.buyerReference)
        assertSame(invoice.references, invoice.references)
    }

    @Test
    fun `totals round-trips independently of recalculateTotals`() {
        val invoice = Invoice()
        assertNotNull(invoice.totals)

        val totals = InvoiceTotals()
        PersistenceEmulation.assignTotals(invoice, totals)
        assertSame(totals, invoice.totals)
    }

    @Test
    fun `needsRecalculation starts true and can be cleared and reset directly`() {
        val invoice = Invoice()
        assertTrue(invoice.needsRecalculation)

        invoice.recalculateTotals()
        assertEquals(false, invoice.needsRecalculation)

        PersistenceEmulation.flagNeedsRecalculation(invoice)
        assertTrue(invoice.needsRecalculation)
    }

    @Test
    fun `llmAnalyses starts empty and recordLlmAnalysis queues one run per model`() {
        val invoice = Invoice()
        assertTrue(invoice.llmAnalysisList.isEmpty())

        val analysis = invoice.recordLlmAnalysis("model-a")
        assertSame(analysis, invoice.llmAnalysisList.single())
        assertSame(analysis, invoice.recordLlmAnalysis("model-a"), "a second request for the same model reuses the record")
        assertEquals(2, invoice.apply { recordLlmAnalysis("model-b") }.llmAnalysisList.size)
    }

    /** [Invoice.documentList] is read-only from outside the class; [Invoice.attachDocument] is the way in. */
    @Test
    fun `documents starts empty and attachDocument registers one row per object key`() {
        val invoice = Invoice()
        assertTrue(invoice.documentList.isEmpty())

        val document = invoice.attachDocument("PDF", "invoices/a.pdf", "application/pdf")
        assertSame(document, invoice.documentList.single())
        assertSame(document, invoice.attachDocument("PDF", "invoices/a.pdf", "application/pdf"))
        assertSame(document, invoice.documentByObjectKey("invoices/a.pdf"))
    }

    /**
     * [Invoice.partySnapshots] is read-only from outside the class;
     * [Invoice.applyPartySnapshots] is the sole mutation path (see
     * [PartySnapshotRefreshTest] for its own coverage). No round-trip setter remains to test.
     */
    @Test
    fun `partySnapshots defaults to an empty list and is populated only through applyPartySnapshots`() {
        val invoice = Invoice()
        assertTrue(invoice.partySnapshotList.isEmpty())

        val seller = aPartySnapshot(PartyRole.SELLER)
        val buyer = aPartySnapshot(PartyRole.BUYER)
        invoice.applyPartySnapshots(seller, buyer)

        assertEquals(2, invoice.partySnapshotList.size)
        assertSame(seller, invoice.sellerSnapshot())
        assertSame(buyer, invoice.buyerSnapshot())
    }

    /**
     * L2 — replaces the former `@PostLoad`-repair test, because the repair itself is gone and was the defect.
     *
     * Hibernate hydrates [Invoice.references] with `null` whenever all eight of its columns are
     * null (every plain draft), assigning the backing field directly and bypassing Kotlin's
     * non-null type. Repairing that in a `@PostLoad` made the entity dirty against a snapshot
     * that still held `null`, so every read wrote the row back and bumped `@Version`. The
     * persisted field is nullable now and the accessor materialises on demand.
     *
     * Reflection reproduces Hibernate's field-level bypass exactly, so both halves are pinned
     * without a Testcontainers round trip: the field STAYS null while nobody asks (that is what
     * keeps a pure read clean —
     * [de.littletrotter.infrastructure.persistence.InvoiceCleanReadIT] proves the database side),
     * and asking materialises a value that subsequent writes go into.
     */
    @Test
    fun `a hydrated-null references composite stays null until something asks for it`() {
        val invoice = Invoice()

        // Hibernate-style hydration of an all-null composite.
        PersistenceEmulation.hydrateNullReferences(invoice)
        assertNull(PersistenceEmulation.storedReferences(invoice), "reading the raw field must not be what materialises it")

        // A read hands out a usable empty value but must not store it: storing on read made every
        // loaded plain draft dirty, so Hibernate rewrote the row and bumped the version on each
        // read, and the next edit from the UI failed its version check.
        assertNotNull(invoice.references)
        assertNull(invoice.references.buyerReference)
        assertNull(PersistenceEmulation.storedReferences(invoice), "a read must not materialise the column group")

        // The first write stores the instance; from then on reads and writes see that one.
        invoice.references.assignBuyerReference("BR-1")
        val stored = PersistenceEmulation.storedReferences(invoice)
        assertEquals("BR-1", stored!!.buyerReference)
        assertSame(stored, invoice.references, "once written, reads must hand out the stored instance")
        invoice.references.update(InvoiceReferencesChange(buyerReference = "BR-2", costCenter = "CC-1"))
        assertSame(stored, PersistenceEmulation.storedReferences(invoice), "a later write must not replace the stored instance")
        assertEquals("CC-1", stored.costCenter)
    }

    /** [Invoice.totals] needs no such handling — its three columns are NOT NULL, so Hibernate
     *  never hydrates it as null. Pinned so a future nullable amount column does not quietly
     *  reintroduce the same defect on the other composite. */
    @Test
    fun `totals is a plain non-null field`() {
        assertNotNull(Invoice().totals)
        val totals = InvoiceTotals()
        val invoice = Invoice()
        PersistenceEmulation.assignTotals(invoice, totals)
        assertSame(totals, invoice.totals)
    }

    /** (calculation-core-100 gate closure) — [Invoice.importedB2bFreeTextCodes] never
     *  ran with a never-set (null) backing field in any existing test; the null branch of its
     *  safe-call chain (`b2bFreeTextImportedCodes?.split(...)`) was structurally untested. */
    @Test
    fun `importedB2bFreeTextCodes is empty when nothing was ever imported`() {
        assertEquals(emptySet<String>(), Invoice().texts.importedB2bFreeTextCodes())
    }

    /** A blank entry (whitespace only, or produced by a trailing/leading comma) must not survive
     *  as an empty-string "code" — [String.isNotEmpty]'s `false` branch, never exercised by the
     *  single-valid-code fixture in `InvoiceLifecycleServiceTest`. */
    @Test
    fun `importedB2bFreeTextCodes drops blank entries after trimming`() {
        val invoice = Invoice()
        invoice.texts.replaceImportedB2bFreeTextCodes(setOf("BT-12", "   ", "BT-19"))
        assertEquals(setOf("BT-12", "BT-19"), invoice.texts.importedB2bFreeTextCodes())
    }

    /** [replaceImportedB2bFreeTextCodes] clearing back to an empty set must reset the backing
     *  field to `null` (`ifEmpty { null }`'s `true` branch), not persist an empty string. */
    @Test
    fun `replaceImportedB2bFreeTextCodes with an empty set clears the field back to null`() {
        val invoice = Invoice()
        invoice.texts.replaceImportedB2bFreeTextCodes(setOf("BT-12"))
        invoice.texts.replaceImportedB2bFreeTextCodes(emptySet())
        assertEquals(emptySet<String>(), invoice.texts.importedB2bFreeTextCodes())
    }
}
