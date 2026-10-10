package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

        val snapshot = InvoicePaymentTermSnapshot().apply {
            name = "Netto 14 Tage"
            dueInDays = 14
        }
        invoice.paymentTermSnapshot = snapshot
        assertSame(snapshot, invoice.paymentTermSnapshot)
    }

    @Test
    fun `references round-trips and defaults to an empty value object, never null`() {
        val invoice = Invoice()
        assertNotNull(invoice.references)

        val refs = InvoiceReferences().apply { buyerReference = "BR-1" }
        invoice.references = refs
        assertSame(refs, invoice.references)
    }

    @Test
    fun `totals round-trips independently of recalculateTotals`() {
        val invoice = Invoice()
        assertNotNull(invoice.totals)

        val totals = InvoiceTotals()
        invoice.totals = totals
        assertSame(totals, invoice.totals)
    }

    @Test
    fun `needsRecalculation starts true and can be cleared and reset directly`() {
        val invoice = Invoice()
        assertTrue(invoice.needsRecalculation)

        invoice.needsRecalculation = false
        assertEquals(false, invoice.needsRecalculation)

        invoice.needsRecalculation = true
        assertTrue(invoice.needsRecalculation)
    }

    @Test
    fun `llmAnalyses round-trips and defaults to an empty mutable list`() {
        val invoice = Invoice()
        assertTrue(invoice.llmAnalyses.isEmpty())

        val analysis = InvoiceLlmAnalysis()
        val list = mutableListOf(analysis)
        invoice.llmAnalyses = list
        assertSame(list, invoice.llmAnalyses)
    }

    /**
     * [Invoice.documents] is read-only from outside the class; the only way in is the
     * same runtime-mutable-list cast the class's own domain methods use, exercised here through
     * the test-only [asMutableForTest] helper rather than a public setter.
     */
    @Test
    fun `documents defaults to an empty list and is reachable via the test-support cast`() {
        val invoice = Invoice()
        assertTrue(invoice.documents.isEmpty())

        val document = InvoiceDocument()
        invoice.documents.asMutableForTest().add(document)
        assertSame(document, invoice.documents.single())
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

        val seller = InvoicePartySnapshot().apply { partyRole = PartyRole.SELLER }
        val buyer = InvoicePartySnapshot().apply { partyRole = PartyRole.BUYER }
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
        val backingField = Invoice::class.java.getDeclaredField("referenceFields")
        backingField.isAccessible = true

        // Hibernate-style hydration of an all-null composite.
        backingField.set(invoice, null)
        assertNull(backingField.get(invoice), "reading the raw field must not be what materialises it")

        // The accessor hands out a usable value and remembers it, so the ~90 call sites that
        // write through it (`invoice.references.buyerReference = …`) reach the column.
        val materialised = invoice.references
        assertNotNull(materialised)
        assertSame(materialised, backingField.get(invoice), "the accessor must assign what it hands out")
        assertSame(materialised, invoice.references, "a second read must not hand out a different instance")

        invoice.references.buyerReference = "BR-1"
        assertEquals("BR-1", (backingField.get(invoice) as InvoiceReferences).buyerReference)
    }

    /** [Invoice.totals] needs no such handling — its three columns are NOT NULL, so Hibernate
     *  never hydrates it as null. Pinned so a future nullable amount column does not quietly
     *  reintroduce the same defect on the other composite. */
    @Test
    fun `totals is a plain non-null field`() {
        assertNotNull(Invoice().totals)
        val totals = InvoiceTotals()
        val invoice = Invoice()
        invoice.totals = totals
        assertSame(totals, invoice.totals)
    }

    /** (calculation-core-100 gate closure) — [Invoice.importedB2bFreeTextCodes] never
     *  ran with a never-set (null) backing field in any existing test; the null branch of its
     *  safe-call chain (`b2bFreeTextImportedCodes?.split(...)`) was structurally untested. */
    @Test
    fun `importedB2bFreeTextCodes is empty when nothing was ever imported`() {
        assertEquals(emptySet<String>(), Invoice().importedB2bFreeTextCodes())
    }

    /** A blank entry (whitespace only, or produced by a trailing/leading comma) must not survive
     *  as an empty-string "code" — [String.isNotEmpty]'s `false` branch, never exercised by the
     *  single-valid-code fixture in `InvoiceLifecycleServiceTest`. */
    @Test
    fun `importedB2bFreeTextCodes drops blank entries after trimming`() {
        val invoice = Invoice()
        invoice.replaceImportedB2bFreeTextCodes(setOf("BT-12", "   ", "BT-19"))
        assertEquals(setOf("BT-12", "BT-19"), invoice.importedB2bFreeTextCodes())
    }

    /** [replaceImportedB2bFreeTextCodes] clearing back to an empty set must reset the backing
     *  field to `null` (`ifEmpty { null }`'s `true` branch), not persist an empty string. */
    @Test
    fun `replaceImportedB2bFreeTextCodes with an empty set clears the field back to null`() {
        val invoice = Invoice()
        invoice.replaceImportedB2bFreeTextCodes(setOf("BT-12"))
        invoice.replaceImportedB2bFreeTextCodes(emptySet())
        assertEquals(emptySet<String>(), invoice.importedB2bFreeTextCodes())
    }
}
