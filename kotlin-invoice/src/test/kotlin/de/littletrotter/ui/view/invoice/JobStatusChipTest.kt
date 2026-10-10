package de.littletrotter.ui.view.invoice

import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.ui.component.ChipTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every status enum gets its chip tone from exactly one mapping function; each is pinned over all values. */
class JobStatusChipTest {

    @Test
    fun `invoiceStatusTone maps every InvoiceStatus`() {
        val expected = mapOf(
            InvoiceStatus.DRAFT to ChipTone.NEUTRAL,
            InvoiceStatus.IN_REVIEW to ChipTone.REVIEW,
            InvoiceStatus.SENT to ChipTone.ACCENT,
            InvoiceStatus.VOIDED to ChipTone.ERROR,
            InvoiceStatus.CANCELLED to ChipTone.STORNO,
        )
        assertEquals(InvoiceStatus.entries.toSet(), expected.keys, "keep this table in sync with InvoiceStatus.entries")
        InvoiceStatus.entries.forEach { assertEquals(expected.getValue(it), invoiceStatusTone(it), "status=$it") }
    }

    @Test
    fun `generationJobStatusTone maps every GenerationJobStatus`() {
        val expected = mapOf(
            GenerationJobStatus.PENDING to ChipTone.NEUTRAL,
            GenerationJobStatus.DELEGATED to ChipTone.ACCENT,
            GenerationJobStatus.RUNNING to ChipTone.ACCENT,
            GenerationJobStatus.COMPLETED to ChipTone.OK,
            GenerationJobStatus.FAILED to ChipTone.ERROR,
        )
        assertEquals(GenerationJobStatus.entries.toSet(), expected.keys)
        GenerationJobStatus.entries.forEach { assertEquals(expected.getValue(it), generationJobStatusTone(it), "status=$it") }
    }

    @Test
    fun `validationStatusTone maps every ValidationStatus and keeps unavailable apart from invalid`() {
        val expected = mapOf(
            ValidationStatus.VALID to ChipTone.OK,
            ValidationStatus.INVALID to ChipTone.ERROR,
            ValidationStatus.UNAVAILABLE to ChipTone.WARN,
            ValidationStatus.NOT_VALIDATED to ChipTone.NEUTRAL,
            ValidationStatus.PENDING to ChipTone.ACCENT,
        )
        assertEquals(ValidationStatus.entries.toSet(), expected.keys)
        ValidationStatus.entries.forEach { assertEquals(expected.getValue(it), validationStatusTone(it), "status=$it") }
        assertNotEquals(validationStatusTone(ValidationStatus.UNAVAILABLE), validationStatusTone(ValidationStatus.INVALID))
    }

    @Test
    fun `validationOutcomeTone is binary plus pending`() {
        val valid = GenerationJob().apply {
            status = GenerationJobStatus.COMPLETED
            kositReport = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        }
        val missingReport = GenerationJob().apply { status = GenerationJobStatus.COMPLETED }
        val failed = GenerationJob().apply { status = GenerationJobStatus.FAILED }
        val pending = GenerationJob().apply { status = GenerationJobStatus.PENDING }

        assertEquals(ChipTone.OK, validationOutcomeTone(valid))
        assertEquals(ChipTone.ERROR, validationOutcomeTone(missingReport))
        assertEquals(ChipTone.ERROR, validationOutcomeTone(failed))
        assertEquals(ChipTone.ACCENT, validationOutcomeTone(pending))
    }

    @Test
    fun `invoiceStatusGlyphIcon gives every InvoiceStatus its own shape`() {
        val icons = InvoiceStatus.entries.map { invoiceStatusGlyphIcon(it) }
        assertEquals(InvoiceStatus.entries.size, icons.toSet().size)
        assertTrue(invoiceStatusGlyphIcon(InvoiceStatus.VOIDED) != invoiceStatusGlyphIcon(InvoiceStatus.CANCELLED))
    }
}
